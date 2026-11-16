package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.indexer.SourceItem.CrackedItem;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.table.TableReader;
import io.floci.az.services.table.TableReader.TableEntity;
import io.floci.az.services.table.TableReader.TableProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The items of an {@code azuretable} data source ("Index data from Azure Table Storage"): the entities of the table
 * the container names that match the container query (an OData filter), oldest {@code Timestamp} first. Each document
 * holds {@code PartitionKey}, {@code RowKey}, {@code Timestamp}, the entity's properties, and {@code Key}, the
 * partition key and row key concatenated, which the indexer maps to an index key field named {@code Key}. Change
 * detection is built in, through the documented {@code Timestamp >= HighWaterMark} filter.
 */
final class TableSource implements SourceReader {

    private final TableReader tables;
    private final String account;
    private final String table;
    private final String filter;

    TableSource(TableReader tables, String account, String table, String filter) {
        this.tables = tables;
        this.account = account;
        this.table = table;
        this.filter = filter;
    }

    @Override
    public List<SourceItem> items(ChangeTracking tracking, boolean everything) throws IndexerRunException {
        Instant since = everything || tracking.highWaterMark() == null ? null
            : Instant.ofEpochSecond(0, tracking.highWaterMark());
        Optional<List<TableEntity>> entities;
        try {
            entities = tables.query(account, table, filter, since);
        } catch (RuntimeException e) {
            throw new IndexerRunException("Error with data source: The container query '" + filter + "' is not a "
                + "valid table filter: " + e.getMessage());
        }
        if (entities.isEmpty()) {
            throw new IndexerRunException("Error with data source: The table '" + table + "' does not exist in the "
                + "storage account '" + account + "'.");
        }
        List<SourceItem> items = new ArrayList<>();
        for (TableEntity entity : entities.get()) {
            ObjectNode document = document(entity);
            String key = entity.partitionKey() + entity.rowKey();
            SourceItem item = new SourceItem(entity.partitionKey().length() + ":" + key, entity.etag(),
                ChangeTracking.nanos(entity.timestamp()), key, key, false,
                () -> CrackedItem.of(List.of(document), false, List.of()));
            if (everything || tracking.admits(item.trackingValue(), item.trackingIdentity())) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(SourceItem::trackingValue).thenComparing(SourceItem::identity));
        return items;
    }

    @Override
    public boolean timestampTracking() {
        return true;
    }

    static ObjectNode document(TableEntity entity) {
        ObjectNode document = SearchJson.object();
        for (TableProperty property : entity.properties().values()) {
            document.set(property.name(), value(property));
        }
        document.put("Key", entity.partitionKey() + entity.rowKey());
        return document;
    }

    private static JsonNode value(TableProperty property) {
        Object value = property.value();
        return switch (property.type()) {
            case BOOLEAN -> SearchJson.NODES.booleanNode((Boolean) value);
            case INT32 -> SearchJson.NODES.numberNode((Integer) value);
            case INT64 -> SearchJson.NODES.numberNode((Long) value);
            case DOUBLE -> {
                double number = (Double) value;
                if (Double.isNaN(number)) {
                    yield SearchJson.NODES.textNode("NaN");
                }
                if (Double.isInfinite(number)) {
                    yield SearchJson.NODES.textNode(number > 0 ? "INF" : "-INF");
                }
                yield SearchJson.NODES.numberNode(number);
            }
            case DATE_TIME -> SearchJson.NODES.textNode(DocumentCoercer.formatDateTime((Instant) value));
            case BINARY -> SearchJson.NODES.textNode(Base64.getEncoder().encodeToString((byte[]) value));
            case STRING, GUID -> SearchJson.NODES.textNode(String.valueOf(value));
        };
    }
}
