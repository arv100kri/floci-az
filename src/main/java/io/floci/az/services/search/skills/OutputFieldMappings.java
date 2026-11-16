package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.skills.EnrichedDocument.PathValue;

/**
 * Applies an indexer's {@code outputFieldMappings} to an enriched document: each {@code sourceFieldName} is a path
 * into the enrichment tree (a {@code *} collects an array), {@code targetFieldName} names the index field (the last
 * name in the path by default), and an optional {@code mappingFunction} transforms the value. A path that selects
 * nothing or {@code null} is the documented warning and leaves the field out; a mapping function that cannot apply
 * is the documented error.
 */
final class OutputFieldMappings {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private OutputFieldMappings() {
    }

    /** The index fields {@code mappings} produce from {@code document}. */
    static ObjectNode apply(EnrichedDocument document, ArrayNode mappings, SkillMessages messages) {
        ObjectNode fields = NODES.objectNode();
        if (mappings == null) {
            return fields;
        }
        for (JsonNode mapping : mappings) {
            String source = SkillDefinition.text(mapping, "sourceFieldName");
            String target = SkillDefinition.text(mapping, "targetFieldName");
            EnrichmentPath path;
            try {
                path = EnrichmentPath.parse(source);
            } catch (IllegalArgumentException e) {
                String field = target == null ? String.valueOf(source) : target;
                messages.warning(operation(field), "Could not map output field '" + field + "' to search index. "
                    + "The source field name '" + source + "' is not a valid path: " + e.getMessage() + ".");
                continue;
            }
            if (target == null || target.isEmpty()) {
                target = defaultTarget(path);
            }
            PathValue value = document.evaluate(path);
            if (value.isMissingOrNull()) {
                messages.warning(operation(target), "Could not map output field '" + target + "' to search index. "
                    + (value.problem() == null ? "The value of " + path + " is null." : value.problem()));
                continue;
            }
            JsonNode mapped = value.value().deepCopy();
            JsonNode function = mapping.get("mappingFunction");
            if (function != null && function.isObject()) {
                try {
                    mapped = MappingFunctions.apply(function, mapped);
                } catch (IllegalArgumentException e) {
                    messages.error(operation(target), "Could not map output field '" + target + "' to search index "
                        + "due to deserialization problem while applying mapping function '"
                        + SkillDefinition.text(function, "name") + "'. " + e.getMessage() + ".");
                    continue;
                }
            }
            fields.set(target, mapped);
        }
        return fields;
    }

    private static String operation(String field) {
        return "Projection.SearchIndex.OutputFieldMapping." + field;
    }

    /** The last name in {@code path} that is not an array index: {@code description} for {@code /document/x/*}{@code /description}. */
    static String defaultTarget(EnrichmentPath path) {
        for (int index = path.length() - 1; index >= 0; index--) {
            EnrichmentPath.Segment segment = path.segment(index);
            if (segment.kind() == EnrichmentPath.Kind.NAME && !segment.isIndex()) {
                return segment.name();
            }
        }
        return "document";
    }
}
