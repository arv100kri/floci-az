package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.LexicalNormalizer;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The typed values of a document, which filters compare, {@code $orderby} sorts and facets count. A document's values
 * are a map from top-level field name to:
 * <ul>
 *   <li>{@code String} for {@code Edm.String}, after the field's normalizer, if it names one;</li>
 *   <li>{@code Long} for {@code Edm.Int32} and {@code Edm.Int64}; {@code Double} for {@code Edm.Double};</li>
 *   <li>{@code Boolean}, {@code Instant} ({@code Edm.DateTimeOffset}) and {@link GeoPoint};</li>
 *   <li>{@code Map<String, Object>} of the same shape for {@code Edm.ComplexType};</li>
 *   <li>an unmodifiable {@code List} of element values for collections (empty, never null);</li>
 *   <li>{@code float[]} for vector fields;</li>
 *   <li>{@code null} for a null or missing single-valued field.</li>
 * </ul>
 */
public final class DocumentValues {

    private DocumentValues() {
    }

    /** The typed values of {@code source}, a document already converted to the schema. */
    public static Map<String, Object> extract(ObjectNode source, List<SearchFieldDefinition> fields,
                                              IndexAnalysis analysis) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (SearchFieldDefinition field : fields) {
            values.put(field.name(), typed(source.get(field.name()), field, analysis));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * The value at {@code path} below {@code values}, flattening collections on the way: a single value (or null)
     * when no collection lies on the path, otherwise the list of every value reached.
     */
    public static Object valueAt(Map<String, Object> values, List<String> path) {
        Object current = values;
        boolean flattened = false;
        for (String segment : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                flattened = true;
                List<Object> next = new ArrayList<>();
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        addFlattened(next, map.get(segment));
                    }
                }
                current = next;
            } else {
                return null;
            }
        }
        if (flattened && !(current instanceof List<?>)) {
            return current == null ? List.of() : List.of(current);
        }
        return current;
    }

    private static void addFlattened(List<Object> target, Object value) {
        if (value instanceof List<?> list) {
            target.addAll(list);
        } else if (value != null) {
            target.add(value);
        }
    }

    private static Object typed(JsonNode node, SearchFieldDefinition field, IndexAnalysis analysis) {
        if (field.isVector()) {
            return vector(node);
        }
        if (field.type().collection()) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Object> elements = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                Object value = scalar(element, field, analysis);
                if (value != null) {
                    elements.add(value);
                }
            }
            return Collections.unmodifiableList(elements);
        }
        return scalar(node, field, analysis);
    }

    private static Object scalar(JsonNode node, SearchFieldDefinition field, IndexAnalysis analysis) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (field.type().element()) {
            case STRING -> {
                Optional<LexicalNormalizer> normalizer = analysis.normalizer(field);
                yield normalizer.isPresent() ? normalizer.get().normalize(node.asText()) : node.asText();
            }
            case INT32, INT64 -> node.asLong();
            case DOUBLE -> doubleValue(node);
            case BOOLEAN -> node.asBoolean();
            case DATE_TIME_OFFSET -> {
                Instant instant = DocumentCoercer.parseDateTime(node.asText());
                yield instant;
            }
            case GEOGRAPHY_POINT -> {
                JsonNode coordinates = node.get("coordinates");
                yield coordinates == null ? null
                    : new GeoPoint(coordinates.get(0).doubleValue(), coordinates.get(1).doubleValue());
            }
            case COMPLEX -> node instanceof ObjectNode object
                ? extract(object, field.fields(), analysis) : null;
            default -> null;
        };
    }

    /** The {@code Edm.Double} a stored value holds, the specials {@code NaN}, {@code INF} and {@code -INF} included. */
    public static Double doubleValue(JsonNode node) {
        if (node.isTextual()) {
            return switch (node.textValue()) {
                case "NaN" -> Double.NaN;
                case "INF" -> Double.POSITIVE_INFINITY;
                case "-INF" -> Double.NEGATIVE_INFINITY;
                default -> Double.valueOf(node.textValue());
            };
        }
        return node.doubleValue();
    }

    private static float[] vector(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        float[] vector = new float[node.size()];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) node.get(index).doubleValue();
        }
        return vector;
    }

    /** Every text value at {@code field}'s path in {@code source}, in document order, flattening collections. */
    public static List<String> textValues(ObjectNode source, SearchFieldDefinition field) {
        List<String> values = new ArrayList<>();
        collectText(source, field.path().split("/"), 0, values);
        return values;
    }

    private static void collectText(JsonNode node, String[] segments, int depth, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectText(element, segments, depth, values);
            }
            return;
        }
        if (depth == segments.length) {
            if (node.isTextual()) {
                values.add(node.textValue());
            }
            return;
        }
        collectText(node.get(segments[depth]), segments, depth + 1, values);
    }
}
