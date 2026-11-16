package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.security.DocumentPermissions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Suggest Documents ({@code docs/search.suggest} by GET, {@code docs/search.post.suggest} by POST). The parameters
 * of either form are handed to the engine's {@code SuggestStage} under their POST names.
 */
@ApplicationScoped
public class SuggestController {

    /** GET query parameter name to POST body name. */
    static final Map<String, String> GET_PARAMETERS = Map.ofEntries(
        Map.entry("search", "search"), Map.entry("suggesterName", "suggesterName"), Map.entry("$filter", "filter"),
        Map.entry("fuzzy", "fuzzy"), Map.entry("highlightPreTag", "highlightPreTag"),
        Map.entry("highlightPostTag", "highlightPostTag"), Map.entry("minimumCoverage", "minimumCoverage"),
        Map.entry("$orderby", "orderby"), Map.entry("searchFields", "searchFields"), Map.entry("$select", "select"),
        Map.entry("$top", "top"));

    private final SearchEngine engine;
    private final DocumentIndexResolver indexes;

    @Inject
    public SuggestController(SearchEngine engine, DocumentIndexResolver indexes) {
        this.engine = engine;
        this.indexes = indexes;
    }

    public Response suggestGet(SearchCall call) {
        return suggest(call, fromQuery(call, GET_PARAMETERS));
    }

    public Response suggestPost(SearchCall call) {
        return suggest(call, call.jsonObject("The suggest request"));
    }

    private Response suggest(SearchCall call, ObjectNode parameters) {
        LiveIndex index = indexes.resolve(call);
        ObjectNode body = index.read(live -> engine.pipeline().suggestStage().suggest(live,
            DocumentPermissions.restrict(live, parameters)));
        return SearchResponses.json(call, 200, SearchResponses.withIndexContext(call, index.name(), "docs(*)", body));
    }

    /** The GET parameters named in {@code names}, as strings under their POST names. */
    static ObjectNode fromQuery(SearchCall call, Map<String, String> names) {
        ObjectNode parameters = SearchJson.object();
        names.forEach((queryName, bodyName) -> {
            String value = call.query(queryName);
            if (value != null) {
                parameters.put(bodyName, value);
            }
        });
        return parameters;
    }
}
