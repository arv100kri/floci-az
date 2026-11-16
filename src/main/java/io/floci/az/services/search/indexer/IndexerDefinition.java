package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.model.SearchJson;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A validated indexer: its normalized definition JSON, its ETag, and a typed view of what a run needs.
 *
 * @param name                the indexer name
 * @param dataSourceName      the data source it reads
 * @param targetIndexName     the index it writes
 * @param skillsetName        the skillset it runs, or null
 * @param disabled            whether it is disabled
 * @param schedule            its schedule, or null
 * @param parameters          its parameters, defaults applied
 * @param fieldMappings       its field mappings
 * @param outputFieldMappings its output field mappings, as defined
 * @param json                the normalized definition as stored; never mutate it
 * @param etag                the ETag, quoted
 */
public record IndexerDefinition(String name, String dataSourceName, String targetIndexName, String skillsetName,
                                boolean disabled, IndexerSchedule schedule, IndexerParameters parameters,
                                List<FieldMapping> fieldMappings, ArrayNode outputFieldMappings, ObjectNode json,
                                String etag) {

    public IndexerDefinition {
        fieldMappings = List.copyOf(fieldMappings);
    }

    /** The typed view of a normalized (already validated) definition. */
    public static IndexerDefinition fromJson(ObjectNode json, String etag) {
        String skillset = SearchJson.text(json, "skillsetName");
        return new IndexerDefinition(SearchJson.text(json, "name"), SearchJson.text(json, "dataSourceName"),
            SearchJson.text(json, "targetIndexName"), skillset == null || skillset.isBlank() ? null : skillset,
            json.path("disabled").asBoolean(false), schedule(json.get("schedule")),
            IndexerParameters.from(json.get("parameters")), fieldMappings(json.get("fieldMappings")),
            json.get("outputFieldMappings") instanceof ArrayNode output ? output : SearchJson.NODES.arrayNode(),
            json, etag);
    }

    /** The same definition with another ETag. */
    public IndexerDefinition withEtag(String newEtag) {
        return new IndexerDefinition(name, dataSourceName, targetIndexName, skillsetName, disabled, schedule,
            parameters, fieldMappings, outputFieldMappings, json, newEtag);
    }

    /** The definition as GET returns it: {@code @odata.etag} first, an encryption key's secret as null. */
    public ObjectNode representation() {
        ObjectNode body = SearchJson.object();
        body.put("@odata.etag", etag);
        ObjectNode copy = json.deepCopy();
        DataSourceDefinition.redactEncryptionKey(copy);
        body.setAll(copy);
        return body;
    }

    private static IndexerSchedule schedule(JsonNode node) {
        if (!(node instanceof ObjectNode schedule)) {
            return null;
        }
        String interval = SearchJson.text(schedule, "interval");
        if (interval == null) {
            return null;
        }
        Duration parsed = IndexerDefinitionParser.parseInterval(interval);
        if (parsed == null) {
            return null;
        }
        String start = SearchJson.text(schedule, "startTime");
        Instant startTime = start == null ? null : DocumentCoercer.parseDateTime(start);
        return new IndexerSchedule(parsed, startTime == null ? Instant.EPOCH : startTime);
    }

    private static List<FieldMapping> fieldMappings(JsonNode node) {
        List<FieldMapping> mappings = new ArrayList<>();
        if (!(node instanceof ArrayNode array)) {
            return mappings;
        }
        for (JsonNode item : array) {
            String source = SearchJson.text(item, "sourceFieldName");
            if (source == null) {
                continue;
            }
            MappingFunction function = null;
            if (item.get("mappingFunction") instanceof ObjectNode functionNode) {
                function = new MappingFunction(SearchJson.text(functionNode, "name"),
                    functionNode.get("parameters") instanceof ObjectNode parameters ? parameters : null);
            }
            mappings.add(new FieldMapping(source, SearchJson.text(item, "targetFieldName"), function));
        }
        return mappings;
    }
}
