package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The analyzers and normalizers one index uses: the ones it defines, compiled once, then the built-in ones. Immutable;
 * a new instance is built whenever the index definition changes.
 */
public final class IndexAnalysis {

    private final AnalyzerRegistry registry;
    private final Map<String, Analyzer> customAnalyzers;
    private final Map<String, LexicalNormalizer> customNormalizers;

    private IndexAnalysis(AnalyzerRegistry registry, Map<String, Analyzer> customAnalyzers,
                          Map<String, LexicalNormalizer> customNormalizers) {
        this.registry = registry;
        this.customAnalyzers = Map.copyOf(customAnalyzers);
        this.customNormalizers = Map.copyOf(customNormalizers);
    }

    public static IndexAnalysis of(SearchIndexDefinition definition, AnalyzerRegistry registry) {
        Map<String, Analyzer> analyzers = new HashMap<>();
        Map<String, LexicalNormalizer> normalizers = new HashMap<>();
        JsonNode json = definition.json();
        for (JsonNode analyzer : json.path("analyzers")) {
            analyzers.put(SearchJson.text(analyzer, "name"), AnalysisComponents.compileAnalyzer(analyzer, json));
        }
        for (JsonNode normalizer : json.path("normalizers")) {
            normalizers.put(SearchJson.text(normalizer, "name"),
                AnalysisComponents.compileNormalizer(normalizer, json));
        }
        return new IndexAnalysis(registry, analyzers, normalizers);
    }

    /** The analyzer named {@code name}: the index's own first, then a built-in one, else the standard analyzer. */
    public Analyzer analyzer(String name) {
        Analyzer custom = customAnalyzers.get(name);
        if (custom != null) {
            return custom;
        }
        return registry.analyzer(name).orElseGet(registry::standard);
    }

    /** The analyzer that turns values of {@code field} into indexed terms. */
    public Analyzer indexAnalyzer(SearchFieldDefinition field) {
        return analyzer(field.effectiveIndexAnalyzer());
    }

    /** The analyzer that turns query text into terms for {@code field}. */
    public Analyzer searchAnalyzer(SearchFieldDefinition field) {
        return analyzer(field.effectiveSearchAnalyzer());
    }

    /** The normalizer {@code field} names, if any. */
    public Optional<LexicalNormalizer> normalizer(SearchFieldDefinition field) {
        if (field.normalizer() == null) {
            return Optional.empty();
        }
        LexicalNormalizer custom = customNormalizers.get(field.normalizer());
        return custom != null ? Optional.of(custom) : registry.normalizer(field.normalizer());
    }
}
