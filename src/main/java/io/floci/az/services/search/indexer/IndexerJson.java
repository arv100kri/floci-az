package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** JSON helpers shared by the data source and indexer code. */
public final class IndexerJson {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    private IndexerJson() {
    }

    /** An instant as the service writes execution times: UTC with millisecond precision. */
    public static String formatTime(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /** The property {@code name} of {@code node}, matched exactly first and then ignoring case; null when absent. */
    public static JsonNode property(JsonNode node, String name) {
        if (!(node instanceof ObjectNode object) || name == null) {
            return null;
        }
        JsonNode exact = object.get(name);
        if (exact != null) {
            return exact;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(name)) {
                return field.getValue();
            }
        }
        return null;
    }

    /** The text of {@code node[name]}, or null when it is absent, null or not text. */
    public static String text(JsonNode node, String name) {
        return SearchJson.text(node, name);
    }

    /** Whether {@code node} is absent or JSON null. */
    public static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /**
     * Resolves a JSON Pointer ({@code /article/tags/0}) against {@code root}: property names match exactly first and
     * then ignoring case, as field mappings resolve names; an array index selects an element. Null when the path does
     * not exist.
     */
    public static JsonNode pointer(JsonNode root, String pointer) {
        JsonNode current = root;
        if (pointer.isEmpty() || pointer.equals("/")) {
            return root;
        }
        for (String rawSegment : pointer.substring(pointer.startsWith("/") ? 1 : 0).split("/", -1)) {
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            if (current instanceof ArrayNode array) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return null;
                }
                current = index >= 0 && index < array.size() ? array.get(index) : null;
            } else {
                current = property(current, segment);
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** {@code value} lowercased with the root locale. */
    public static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
