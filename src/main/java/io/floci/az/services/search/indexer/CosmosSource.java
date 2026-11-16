package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.cosmos.CosmosReader;
import io.floci.az.services.search.indexer.SourceItem.CrackedItem;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The items of a {@code cosmosdb} data source ("Index data from Azure Cosmos DB for NoSQL"): the documents the
 * container query returns, or every document without one. With a {@code HighWaterMarkChangeDetectionPolicy} on
 * {@code _ts}, the default query is the documented {@code SELECT * FROM c WHERE c._ts >= @HighWaterMark ORDER BY c._ts};
 * a custom query that names {@code @HighWaterMark} gets the parameter, and documents at or below the mark already
 * indexed are left out. Without a policy every run reads every document. Each document keeps its system properties,
 * and gains {@code rid}, its {@code _rid} Base64-encoded, which the indexer maps to an index key field named
 * {@code rid} ("Azure AI Search automatically renames [_rid] to rid ... the _rid values are Base64 encoded").
 */
final class CosmosSource implements SourceReader {

    static final String HIGH_WATER_MARK_PARAMETER = "@HighWaterMark";
    private static final String TIMESTAMP_COLUMN = "_ts";
    private static final String DEFAULT_QUERY = "SELECT * FROM c";
    private static final String HIGH_WATER_MARK_QUERY =
        "SELECT * FROM c WHERE c._ts >= @HighWaterMark ORDER BY c._ts";

    private final CosmosReader cosmos;
    private final String account;
    private final String database;
    private final String container;
    private final String query;
    private final String highWaterMarkColumn;

    CosmosSource(CosmosReader cosmos, String account, String database, String container, String query,
                 String highWaterMarkColumn) {
        this.cosmos = cosmos;
        this.account = account;
        this.database = database;
        this.container = container;
        this.query = query;
        this.highWaterMarkColumn = highWaterMarkColumn;
    }

    @Override
    public List<SourceItem> items(ChangeTracking tracking, boolean everything) throws IndexerRunException {
        boolean tracked = highWaterMarkColumn != null;
        String sql = query != null ? query
            : tracked && TIMESTAMP_COLUMN.equals(highWaterMarkColumn) ? HIGH_WATER_MARK_QUERY : DEFAULT_QUERY;
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (sql.toLowerCase(Locale.ROOT).contains(HIGH_WATER_MARK_PARAMETER.toLowerCase(Locale.ROOT))) {
            parameters.put(HIGH_WATER_MARK_PARAMETER,
                everything || tracking.highWaterMark() == null ? 0L : tracking.highWaterMark());
        }
        Optional<List<Object>> results;
        try {
            results = cosmos.query(account, database, container, sql, parameters);
        } catch (IllegalArgumentException e) {
            throw new IndexerRunException("Error with data source: The query '" + sql + "' failed: "
                + e.getMessage());
        }
        if (results.isEmpty()) {
            throw new IndexerRunException("Error with data source: The container '" + container + "' does not exist "
                + "in the database '" + database + "' of the Azure Cosmos DB account '" + account + "'.");
        }
        List<SourceItem> items = new ArrayList<>();
        for (Object result : results.get()) {
            if (!(result instanceof Map<?, ?>)) {
                throw new IndexerRunException("Error with data source: The query '" + sql + "' returned a value that "
                    + "is not a JSON object; the indexer needs one JSON object per document.");
            }
            ObjectNode document = SearchJson.MAPPER.valueToTree(result);
            String rid = SearchJson.text(document, "_rid");
            String id = SearchJson.text(document, "id");
            if (rid != null && !document.has("rid")) {
                document.put("rid", MappingFunctions.base64Encode(rid, true));
            }
            Long value = tracked ? number(document.get(highWaterMarkColumn)) : null;
            String identity = rid != null ? rid : id != null ? id : SearchJson.write(document);
            SourceItem item = new SourceItem(identity, SearchJson.text(document, "_etag"), value, id, id, false,
                () -> CrackedItem.of(List.of(document), false, List.of()));
            if (everything || !tracked || tracking.admits(item.trackingValue(), item.trackingIdentity())) {
                items.add(item);
            }
        }
        return items;
    }

    @Override
    public boolean timestampTracking() {
        return false;
    }

    private static Long number(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.longValue();
    }
}
