package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.security.DocumentPermissions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Autocomplete ({@code docs/search.autocomplete} by GET, {@code docs/search.post.autocomplete} by POST). The
 * parameters of either form are handed to the engine's {@code SuggestStage} under their POST names; the stage is a
 * stub that answers 501 until autocomplete lands.
 */
@ApplicationScoped
public class AutocompleteController {

    /** GET query parameter name to POST body name. */
    static final Map<String, String> GET_PARAMETERS = Map.ofEntries(
        Map.entry("search", "search"), Map.entry("suggesterName", "suggesterName"),
        Map.entry("autocompleteMode", "autocompleteMode"), Map.entry("$filter", "filter"), Map.entry("fuzzy", "fuzzy"),
        Map.entry("highlightPreTag", "highlightPreTag"), Map.entry("highlightPostTag", "highlightPostTag"),
        Map.entry("minimumCoverage", "minimumCoverage"), Map.entry("searchFields", "searchFields"),
        Map.entry("$top", "top"));

    private final SearchEngine engine;
    private final DocumentIndexResolver indexes;

    @Inject
    public AutocompleteController(SearchEngine engine, DocumentIndexResolver indexes) {
        this.engine = engine;
        this.indexes = indexes;
    }

    public Response autocompleteGet(SearchCall call) {
        return autocomplete(call, SuggestController.fromQuery(call, GET_PARAMETERS));
    }

    public Response autocompletePost(SearchCall call) {
        return autocomplete(call, call.jsonObject("The autocomplete request"));
    }

    private Response autocomplete(SearchCall call, ObjectNode parameters) {
        LiveIndex index = indexes.resolve(call);
        ObjectNode body = index.read(live -> engine.pipeline().suggestStage().autocomplete(live,
            DocumentPermissions.restrict(live, parameters)));
        return SearchResponses.json(call, 200, SearchResponses.withIndexContext(call, index.name(),
            "Microsoft.Azure.Search.V2019_05_06.AutocompleteResult", body));
    }
}
