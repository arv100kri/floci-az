package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.SearchErrors;
import io.floci.az.services.search.indexer.IndexerDefinition;
import io.floci.az.services.search.indexer.IndexerService;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Indexers: Create (POST), Create or Update (PUT), Get, List (with {@code $select}) and Delete Indexer, with
 * {@code @odata.etag} concurrency; Run (202, or 409 while a run is in progress: "Another indexer invocation is
 * currently in progress; concurrent invocations are not allowed"), Reset (204), Reset Documents (204, with
 * {@code overwrite}), Resync (204) and Get Indexer Status. Preview versions' {@code ignoreResetRequirements} and
 * {@code disableCacheReprocessingChangeDetection} are accepted; floci-az has no enrichment cache for them to affect.
 */
@ApplicationScoped
public class IndexersController implements SearchResourceCounter {

    private static final String STATUS_TYPE = "Microsoft.Azure.Search.V2019_05_06.IndexerExecutionInfo";

    private final IndexerService indexers;

    @Inject
    public IndexersController(IndexerService indexers) {
        this.indexers = indexers;
    }

    public Response list(SearchCall call) {
        List<String> select = SearchRequestParser.csv(call.query("$select"));
        boolean all = select.isEmpty() || select.contains("*");
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (IndexerDefinition indexer : indexers.list(call.serviceName())) {
            ObjectNode representation = indexer.representation();
            if (all) {
                value.add(representation);
            } else {
                ObjectNode projected = SearchJson.object();
                for (String property : select) {
                    if (representation.has(property)) {
                        projected.set(property, representation.get(property));
                    }
                }
                value.add(projected);
            }
        }
        ObjectNode body = SearchJson.object();
        body.set("value", value);
        String fragment = all ? "indexers" : "indexers(" + String.join(",", select) + ")";
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, fragment, body));
    }

    public Response create(SearchCall call) {
        return created(call, indexers.create(call.serviceName(), call.json()));
    }

    public Response createOrUpdate(SearchCall call) {
        IndexerService.Write write = indexers.createOrUpdate(call.serviceName(), call.path("name"), call.json(),
            etag -> IndexesController.checkPreconditions(call, etag));
        if (write.created()) {
            return created(call, write.definition());
        }
        if (!Boolean.TRUE.equals(call.preferRepresentation())) {
            return Response.noContent().header("ETag", write.definition().etag()).build();
        }
        return SearchResponses.jsonBuilder(call, 200, entity(call, write.definition()))
            .header("ETag", write.definition().etag())
            .build();
    }

    public Response get(SearchCall call) {
        IndexerDefinition indexer = indexers.get(call.serviceName(), call.path("name"));
        return SearchResponses.jsonBuilder(call, 200, entity(call, indexer))
            .header("ETag", indexer.etag())
            .build();
    }

    public Response delete(SearchCall call) {
        indexers.delete(call.serviceName(), call.path("name"), etag -> IndexesController.checkPreconditions(call, etag));
        return Response.noContent().build();
    }

    public Response status(SearchCall call) {
        ObjectNode status = indexers.status(call.service(), call.serviceName(), call.path("name"),
            ApiVersions.isPreview(call.apiVersion()));
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, STATUS_TYPE, status));
    }

    public Response run(SearchCall call) {
        if (!indexers.run(call.serviceName(), call.path("name"))) {
            return Response.status(409)
                .entity(SearchJson.write(SearchErrors.envelope("Conflict", "Failed to run indexer '"
                    + call.path("name") + "' error: Another indexer invocation is currently in progress; concurrent "
                    + "invocations are not allowed.")))
                .header("Content-Type", SearchErrors.ERROR_CONTENT_TYPE)
                .build();
        }
        return Response.accepted().build();
    }

    public Response reset(SearchCall call) {
        indexers.reset(call.serviceName(), call.path("name"));
        return Response.noContent().build();
    }

    public Response resetDocuments(SearchCall call) {
        indexers.resetDocuments(call.serviceName(), call.path("name"), call.json(),
            "true".equalsIgnoreCase(call.query("overwrite")));
        return Response.noContent().build();
    }

    public Response resync(SearchCall call) {
        indexers.resync(call.serviceName(), call.path("name"), call.json());
        return Response.noContent().build();
    }

    private static Response created(SearchCall call, IndexerDefinition indexer) {
        Response.ResponseBuilder builder = Boolean.FALSE.equals(call.preferRepresentation())
            ? Response.noContent() : SearchResponses.jsonBuilder(call, 201, entity(call, indexer));
        return builder.header("ETag", indexer.etag())
            .header("Location", call.serviceUrl() + "/indexers('" + indexer.name() + "')?api-version="
                + call.apiVersion())
            .build();
    }

    private static ObjectNode entity(SearchCall call, IndexerDefinition indexer) {
        return SearchResponses.withContext(call, "indexers/$entity", indexer.representation());
    }

    @Override
    public String counterName() {
        return "indexersCount";
    }

    @Override
    public long usage(String service) {
        return indexers.count(service);
    }
}
