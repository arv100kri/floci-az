package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.analysis.Analyzer;
import io.floci.az.services.search.engine.analysis.AnalysisComponents;
import io.floci.az.services.search.engine.analysis.ComposedAnalyzer;
import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.LexicalNormalizer;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * The Analyze API ({@code POST indexes('x')/search.analyze}, "Indexes - Analyze"): breaks {@code text} into tokens with
 * an {@code analyzer}, or with a {@code tokenizer} and optional {@code tokenFilters} and {@code charFilters}, or
 * normalizes it with a {@code normalizer}; each may be built in or defined by the index. Returns {@code tokens}, each
 * with its {@code token}, {@code startOffset}, {@code endOffset} and {@code position}. An analyzer and a tokenizer are
 * mutually exclusive, the filters go only with a tokenizer, and a normalizer stands alone. Aliases are not accepted.
 */
@ApplicationScoped
public class AnalyzeController {

    private static final String RESULT_TYPE = "Microsoft.Azure.Search.V2019_05_06.AnalyzeResult";

    private final SearchEngine engine;

    @Inject
    public AnalyzeController(SearchEngine engine) {
        this.engine = engine;
    }

    public Response analyze(SearchCall call) {
        String indexName = call.path("index");
        LiveIndex index = engine.service(call.serviceName()).find(indexName)
            .orElseThrow(() -> IndexesController.notFound(call, indexName));
        ObjectNode request = call.jsonObject("The analyze request");
        JsonNode textNode = request.get("text");
        if (textNode == null || !textNode.isTextual()) {
            throw SearchException.badRequest("The request is invalid. Details: request : 'text' is required: the "
                + "text to break into tokens.");
        }
        String text = textNode.textValue();
        String analyzer = optionalText(request, "analyzer");
        String tokenizer = optionalText(request, "tokenizer");
        String normalizer = optionalText(request, "normalizer");
        List<String> tokenFilters = names(request, "tokenFilters");
        List<String> charFilters = names(request, "charFilters");
        validate(analyzer, tokenizer, normalizer, tokenFilters, charFilters);
        IndexAnalysis analysis = index.analysis();
        List<Token> tokens;
        if (normalizer != null) {
            LexicalNormalizer found = analysis.findNormalizer(normalizer).orElseThrow(() -> invalid("the normalizer '"
                + normalizer + "' is neither built in nor defined by the index '" + indexName + "'."));
            tokens = text.isEmpty() ? List.of() : List.of(new Token(found.normalize(text), 0, 0, text.length()));
        } else if (analyzer != null) {
            Analyzer found = analysis.findAnalyzer(analyzer).orElseThrow(() -> invalid("the analyzer '" + analyzer
                + "' is neither built in nor defined by the index '" + indexName + "'."));
            tokens = found.analyze(text);
        } else {
            tokens = chain(analysis.components(), tokenizer, tokenFilters, charFilters).analyze(text);
        }
        ArrayNode tokenArray = SearchJson.NODES.arrayNode();
        for (Token token : tokens) {
            ObjectNode info = tokenArray.addObject();
            info.put("token", token.term());
            info.put("startOffset", token.startOffset());
            info.put("endOffset", token.endOffset());
            info.put("position", token.position());
        }
        ObjectNode body = SearchJson.object();
        body.set("tokens", tokenArray);
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, RESULT_TYPE, body));
    }

    private static void validate(String analyzer, String tokenizer, String normalizer, List<String> tokenFilters,
                                 List<String> charFilters) {
        if (analyzer != null && tokenizer != null) {
            throw invalid("the analyzer and tokenizer parameters are mutually exclusive; set one of them.");
        }
        if (normalizer != null && (analyzer != null || tokenizer != null)) {
            throw invalid("a normalizer cannot be combined with an analyzer or a tokenizer.");
        }
        if (analyzer == null && tokenizer == null && normalizer == null) {
            throw invalid("specify an analyzer, a tokenizer or a normalizer to break the text into tokens.");
        }
        if (tokenizer == null && (!tokenFilters.isEmpty() || !charFilters.isEmpty())) {
            throw invalid("tokenFilters and charFilters can only be set together with a tokenizer.");
        }
    }

    private static Analyzer chain(AnalysisComponents components, String tokenizer, List<String> tokenFilters,
                                  List<String> charFilters) {
        try {
            return new ComposedAnalyzer(tokenizer, components.charFilters(charFilters),
                components.tokenizer(tokenizer), components.tokenFilters(tokenFilters));
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static String optionalText(ObjectNode request, String property) {
        JsonNode value = request.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("'" + property + "' must be a string.");
        }
        return value.textValue();
    }

    private static List<String> names(ObjectNode request, String property) {
        JsonNode value = request.get(property);
        List<String> names = new ArrayList<>();
        if (value == null || value.isNull()) {
            return names;
        }
        if (!value.isArray()) {
            throw invalid("'" + property + "' must be an array of names.");
        }
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalid("'" + property + "' must be an array of names.");
            }
            names.add(element.textValue());
        }
        return names;
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: request : " + detail);
    }
}
