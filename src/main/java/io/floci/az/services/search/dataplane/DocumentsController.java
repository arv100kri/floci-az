package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.SearchErrors;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.IndexAction;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataSelect;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.model.IndexingResult;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import io.floci.az.services.search.security.DocumentPermissions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The documents collection of an index: Search Documents (GET and POST), Lookup Document, Count Documents and Index
 * Documents (the {@code upload}, {@code merge}, {@code mergeOrUpload} and {@code delete} batch, answered with 200
 * when every action succeeded and 207 otherwise).
 */
@ApplicationScoped
public class DocumentsController {

    private final SearchEngine engine;
    private final DocumentIndexResolver indexes;

    @Inject
    public DocumentsController(SearchEngine engine, DocumentIndexResolver indexes) {
        this.engine = engine;
        this.indexes = indexes;
    }

    public Response searchGet(SearchCall call) {
        return search(call, SearchRequestParser.fromQuery(call), true);
    }

    public Response searchPost(SearchCall call) {
        return search(call, SearchRequestParser.fromBody(call.jsonObject("The search request")), false);
    }

    private Response search(SearchCall call, SearchRequest request, boolean get) {
        LiveIndex index = indexes.resolve(call);
        SearchOutcome outcome = engine.search(index, request);
        Response.ResponseBuilder response = SearchResponses.jsonBuilder(call, 200,
            SearchResponseWriter.write(call, index.name(), outcome, get));
        outcome.headers().forEach(response::header);
        return response.build();
    }

    public Response lookup(SearchCall call) {
        LiveIndex index = indexes.resolve(call);
        String key = call.path("key");
        List<String> select = SearchRequestParser.csv(String.join(",", call.queryValues("$select")));
        ObjectNode document = index.read(live -> {
            ODataSelect projection = ODataSelect.parse(select, live.definition(), "$select");
            IndexedDocument stored = DocumentPermissions.visibleDocument(live, key);
            return stored == null ? null : projection.project(stored.source());
        });
        if (document == null) {
            throw SearchException.notFound("Document not found.");
        }
        return SearchResponses.json(call, 200,
            SearchResponses.withIndexContext(call, index.name(), "docs(*)/$entity", document));
    }

    public Response count(SearchCall call) {
        LiveIndex index = indexes.resolve(call);
        return Response.ok(String.valueOf(DocumentPermissions.count(index)).getBytes(StandardCharsets.UTF_8))
            .header("Content-Type", "text/plain; charset=utf-8")
            .build();
    }

    public Response index(SearchCall call) {
        LiveIndex index = indexes.resolve(call);
        List<IndexAction> actions = IndexBatchParser.parse(call.json(), index.definition());
        List<IndexingResult> results = engine.index(call.serviceName(), index, actions);
        ArrayNode value = SearchJson.NODES.arrayNode();
        boolean allSucceeded = true;
        for (IndexingResult result : results) {
            ObjectNode item = value.addObject();
            item.put("key", result.key());
            item.put("status", result.status());
            if (result.errorMessage() == null) {
                item.putNull("errorMessage");
            } else {
                item.put("errorMessage", result.errorMessage());
            }
            item.put("statusCode", result.statusCode());
            allSucceeded &= result.status();
        }
        ObjectNode body = SearchJson.object();
        body.set("value", value);
        return SearchResponses.json(call, allSucceeded ? 200 : 207, SearchResponses.withIndexContext(call,
            index.name(), "Collection(Microsoft.Azure.Search.V2019_05_06.IndexResult)", body));
    }

    /** Graph queries ({@code docs/search.graph}, 2026-11-01-preview) are not emulated. */
    public Response graph(SearchCall call) {
        indexes.resolve(call);
        return SearchErrors.notImplemented("Graph queries (docs/search.graph)");
    }
}
