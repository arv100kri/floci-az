package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Get Service Statistics ({@code /servicestats}: usage counters and limits), Get Index Statistics
 * ({@code indexes('x')/search.stats}) and the index statistics summary ({@code /indexstats}). Counters have no quota:
 * the emulator enforces no service quotas, only its {@code max-documents-per-index} guard. The limits are the
 * documented per-index ones floci-az validates.
 */
@ApplicationScoped
public class ServiceStatisticsController {

    private static final String STATISTICS_TYPE = "Microsoft.Azure.Search.V2019_05_06.";
    private static final List<String> COUNTER_ORDER = List.of(
        "documentCount", "indexesCount", "indexersCount", "dataSourcesCount", "storageSize", "synonymMaps",
        "skillsetCount", "aliasesCount", "vectorIndexSize");

    private final SearchEngine engine;
    private final Instance<SearchResourceCounter> counters;

    @Inject
    public ServiceStatisticsController(SearchEngine engine, Instance<SearchResourceCounter> counters) {
        this.engine = engine;
        this.counters = counters;
    }

    public Response serviceStatistics(SearchCall call) {
        List<LiveIndex> all = engine.service(call.serviceName()).list();
        ObjectNode usage = SearchJson.object();
        usage.put("documentCount", all.stream().mapToLong(LiveIndex::documentCount).sum());
        usage.put("indexesCount", all.size());
        usage.put("storageSize", all.stream().mapToLong(LiveIndex::storageSize).sum());
        usage.put("vectorIndexSize", all.stream().mapToLong(LiveIndex::vectorIndexSize).sum());
        for (SearchResourceCounter counter : counters) {
            usage.put(counter.counterName(), counter.usage(call.serviceName()));
        }
        ObjectNode counterJson = SearchJson.object();
        for (String name : COUNTER_ORDER) {
            ObjectNode counter = counterJson.putObject(name);
            counter.put("usage", usage.path(name).asLong(0));
            counter.putNull("quota");
        }
        ObjectNode limits = SearchJson.object();
        limits.putNull("maxStoragePerIndex");
        limits.put("maxFieldsPerIndex", 1000);
        limits.put("maxFieldNestingDepthPerIndex", 10);
        limits.put("maxComplexCollectionFieldsPerIndex", 40);
        limits.put("maxComplexObjectsInCollectionsPerDocument", 3000);
        ObjectNode body = SearchJson.object();
        body.set("counters", counterJson);
        body.set("limits", limits);
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, STATISTICS_TYPE + "ServiceStatistics",
            body));
    }

    public Response indexStatistics(SearchCall call) {
        LiveIndex index = engine.service(call.serviceName()).find(call.path("index"))
            .orElseThrow(() -> IndexesController.notFound(call, call.path("index")));
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, STATISTICS_TYPE + "IndexStatistics",
            statistics(index, false)));
    }

    public Response indexStatisticsSummary(SearchCall call) {
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (LiveIndex index : engine.service(call.serviceName()).list()) {
            value.add(statistics(index, true));
        }
        ObjectNode body = SearchJson.object();
        body.set("value", value);
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, "Collection(" + STATISTICS_TYPE
            + "IndexStatisticsSummary)", body));
    }

    private static ObjectNode statistics(LiveIndex index, boolean named) {
        ObjectNode statistics = SearchJson.object();
        if (named) {
            statistics.put("name", index.name());
        }
        statistics.put("documentCount", index.documentCount());
        statistics.put("storageSize", index.storageSize());
        statistics.put("vectorIndexSize", index.vectorIndexSize());
        return statistics;
    }
}
