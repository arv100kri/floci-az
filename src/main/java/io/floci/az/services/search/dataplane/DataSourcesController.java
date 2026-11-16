package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.indexer.DataSourceDefinition;
import io.floci.az.services.search.indexer.DataSourceService;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Data sources: Create (POST), Create or Update (PUT), Get, List (with {@code $select}) and Delete Data Source, with
 * {@code @odata.etag} concurrency through {@code If-Match} and {@code If-None-Match}. Reads never return the
 * connection string: {@code credentials.connectionString} is {@code null}, and an update that sends it back as
 * {@code <unchanged>} (or leaves it out) keeps the stored one. Preview versions' {@code ignoreResetRequirements} is
 * accepted; floci-az has no enrichment cache for it to bypass.
 */
@ApplicationScoped
public class DataSourcesController implements SearchResourceCounter {

    private final DataSourceService dataSources;

    @Inject
    public DataSourcesController(DataSourceService dataSources) {
        this.dataSources = dataSources;
    }

    public Response list(SearchCall call) {
        List<String> select = SearchRequestParser.csv(call.query("$select"));
        boolean all = select.isEmpty() || select.contains("*");
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (DataSourceDefinition dataSource : dataSources.list(call.serviceName())) {
            ObjectNode representation = dataSource.representation();
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
        String fragment = all ? "datasources" : "datasources(" + String.join(",", select) + ")";
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, fragment, body));
    }

    public Response create(SearchCall call) {
        return created(call, dataSources.create(call.serviceName(), call.json()));
    }

    public Response createOrUpdate(SearchCall call) {
        DataSourceService.Write write = dataSources.createOrUpdate(call.serviceName(), call.path("name"), call.json(),
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
        DataSourceDefinition dataSource = dataSources.get(call.serviceName(), call.path("name"));
        return SearchResponses.jsonBuilder(call, 200, entity(call, dataSource))
            .header("ETag", dataSource.etag())
            .build();
    }

    public Response delete(SearchCall call) {
        dataSources.delete(call.serviceName(), call.path("name"),
            etag -> IndexesController.checkPreconditions(call, etag));
        return Response.noContent().build();
    }

    private static Response created(SearchCall call, DataSourceDefinition dataSource) {
        Response.ResponseBuilder builder = Boolean.FALSE.equals(call.preferRepresentation())
            ? Response.noContent() : SearchResponses.jsonBuilder(call, 201, entity(call, dataSource));
        return builder.header("ETag", dataSource.etag())
            .header("Location", call.serviceUrl() + "/datasources('" + dataSource.name() + "')?api-version="
                + call.apiVersion())
            .build();
    }

    private static ObjectNode entity(SearchCall call, DataSourceDefinition dataSource) {
        return SearchResponses.withContext(call, "datasources/$entity", dataSource.representation());
    }

    @Override
    public String counterName() {
        return "dataSourcesCount";
    }

    @Override
    public long usage(String service) {
        return dataSources.count(service);
    }
}
