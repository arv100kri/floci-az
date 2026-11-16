package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the documents of an indexing batch to the index schema: every property must be a field of the index, and
 * every value must convert to the field's EDM type. Values are normalised the way the service stores them:
 * {@code Edm.DateTimeOffset} in UTC with millisecond precision, {@code Edm.Double} specials as {@code "NaN"},
 * {@code "INF"} and {@code "-INF"}, geography points as GeoJSON with the WGS 84 CRS. A document that does not convert
 * is a 400 for the whole request.
 *
 * <p>Conversions are lenient where the service is: {@code "true"} and {@code "false"} strings convert to
 * {@code Edm.Boolean}, numeric strings to numbers, and numbers or booleans to {@code Edm.String}.</p>
 */
public final class DocumentCoercer {

    /** The property of a batch action that names its action. */
    public static final String ACTION_PROPERTY = "@search.action";

    private static final JsonNodeFactory NODES = SearchJson.NODES;
    private static final DateTimeFormatter WHOLE_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FRACTIONAL_SECONDS =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private DocumentCoercer() {
    }

    /**
     * The properties of {@code input} that are fields of the index, converted to their types. Annotations
     * ({@code @search.action}, {@code @odata.*}) are dropped.
     */
    public static ObjectNode coerce(ObjectNode input, SearchIndexDefinition definition) {
        return coerceObject(input, definition.fields(), "");
    }

    /**
     * {@code provided} merged over {@code existing}: provided top-level fields replace existing ones, except that a
     * provided complex object is merged into the existing one subfield by subfield. Collections, complex collections
     * included, are replaced whole, and an explicit null clears a field.
     */
    public static ObjectNode merge(ObjectNode existing, ObjectNode provided) {
        ObjectNode merged = existing.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> entries = provided.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode previous = merged.get(entry.getKey());
            if (previous instanceof ObjectNode previousObject && entry.getValue() instanceof ObjectNode next
                    && !isGeoPoint(next)) {
                merged.set(entry.getKey(), merge(previousObject, next));
            } else {
                merged.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return merged;
    }

    /** Formats an instant the way the service returns {@code Edm.DateTimeOffset} values. */
    public static String formatDateTime(Instant instant) {
        Instant millis = instant.truncatedTo(ChronoUnit.MILLIS);
        return millis.getNano() == 0 ? WHOLE_SECONDS.format(millis) : FRACTIONAL_SECONDS.format(millis);
    }

    /** Parses an {@code Edm.DateTimeOffset} value; null when it is not one. */
    public static Instant parseDateTime(String text) {
        try {
            return OffsetDateTime.parse(text.trim()).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isGeoPoint(ObjectNode node) {
        return node.has("coordinates") && "Point".equalsIgnoreCase(SearchJson.text(node, "type"));
    }

    private static ObjectNode coerceObject(ObjectNode input, List<SearchFieldDefinition> fields, String parentPath) {
        ObjectNode output = NODES.objectNode();
        Iterator<Map.Entry<String, JsonNode>> entries = input.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String name = entry.getKey();
            if (name.startsWith("@")) {
                continue;
            }
            SearchFieldDefinition field = fields.stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow(() -> SearchException.badRequest("The request is invalid. Details: The "
                    + "property '" + parentPath + name + "' does not exist on type 'search.documentFields'. Make sure "
                    + "to only use property names that are defined by the type."));
            output.set(name, coerceField(entry.getValue(), field));
        }
        return output;
    }

    private static JsonNode coerceField(JsonNode value, SearchFieldDefinition field) {
        if (value == null || value.isNull()) {
            return field.type().collection() ? NODES.arrayNode() : NullNode.getInstance();
        }
        if (field.isVector()) {
            return coerceVector(value, field);
        }
        if (!field.type().collection()) {
            return coerceScalar(value, field);
        }
        if (!value.isArray()) {
            throw cannotConvert(value, field);
        }
        ArrayNode output = NODES.arrayNode();
        for (JsonNode element : value) {
            if (element.isNull()) {
                throw SearchException.badRequest("The request is invalid. Details: The collection field '"
                    + field.path() + "' contains a null element; collections cannot hold null values.");
            }
            output.add(coerceScalar(element, field));
        }
        return output;
    }

    private static JsonNode coerceScalar(JsonNode value, SearchFieldDefinition field) {
        EdmType element = field.type().element();
        return switch (element) {
            case STRING -> {
                if (value.isTextual()) {
                    yield value;
                }
                if (value.isNumber() || value.isBoolean()) {
                    yield NODES.textNode(value.asText());
                }
                throw cannotConvert(value, field);
            }
            case INT32, INT64 -> coerceInteger(value, field, element == EdmType.INT32);
            case DOUBLE -> coerceDouble(value, field);
            case BOOLEAN -> {
                if (value.isBoolean()) {
                    yield value;
                }
                if (value.isTextual() && ("true".equalsIgnoreCase(value.textValue())
                        || "false".equalsIgnoreCase(value.textValue()))) {
                    yield NODES.booleanNode(Boolean.parseBoolean(value.textValue().toLowerCase(Locale.ROOT)));
                }
                throw cannotConvert(value, field);
            }
            case DATE_TIME_OFFSET -> {
                Instant instant = value.isTextual() ? parseDateTime(value.textValue()) : null;
                if (instant == null) {
                    throw cannotConvert(value, field);
                }
                yield NODES.textNode(formatDateTime(instant));
            }
            case GEOGRAPHY_POINT -> coerceGeoPoint(value, field);
            case COMPLEX -> {
                if (!(value instanceof ObjectNode object)) {
                    throw cannotConvert(value, field);
                }
                yield coerceObject(object, field.fields(), field.path() + "/");
            }
            default -> throw cannotConvert(value, field);
        };
    }

    private static JsonNode coerceInteger(JsonNode value, SearchFieldDefinition field, boolean int32) {
        long number;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            number = value.longValue();
        } else if (value.isTextual()) {
            try {
                number = Long.parseLong(value.textValue().trim());
            } catch (NumberFormatException e) {
                throw cannotConvert(value, field);
            }
        } else if (value.isFloatingPointNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0
                && value.canConvertToLong()) {
            number = value.longValue();
        } else {
            throw cannotConvert(value, field);
        }
        if (int32 && (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)) {
            throw cannotConvert(value, field);
        }
        return int32 ? NODES.numberNode((int) number) : NODES.numberNode(number);
    }

    private static JsonNode coerceDouble(JsonNode value, SearchFieldDefinition field) {
        if (value.isNumber()) {
            return NODES.numberNode(value.doubleValue());
        }
        if (value.isTextual()) {
            String text = value.textValue().trim();
            switch (text) {
                case "NaN", "INF", "-INF" -> {
                    return NODES.textNode(text);
                }
                default -> {
                    try {
                        return NODES.numberNode(new BigDecimal(text).doubleValue());
                    } catch (NumberFormatException e) {
                        throw cannotConvert(value, field);
                    }
                }
            }
        }
        throw cannotConvert(value, field);
    }

    private static JsonNode coerceGeoPoint(JsonNode value, SearchFieldDefinition field) {
        if (!(value instanceof ObjectNode object) || !isGeoPoint(object)) {
            throw SearchException.badRequest("The request is invalid. Details: The value of the field '"
                + field.path() + "' must be a GeoJSON point: {\"type\": \"Point\", \"coordinates\": [longitude, "
                + "latitude]}.");
        }
        JsonNode coordinates = object.get("coordinates");
        if (!coordinates.isArray() || coordinates.size() != 2 || !coordinates.get(0).isNumber()
                || !coordinates.get(1).isNumber()) {
            throw cannotConvert(value, field);
        }
        double longitude = coordinates.get(0).doubleValue();
        double latitude = coordinates.get(1).doubleValue();
        if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw SearchException.badRequest("The request is invalid. Details: The geography point of the field '"
                + field.path() + "' is out of range: longitude must be between -180 and 180 and latitude between -90 "
                + "and 90.");
        }
        return geoPointJson(longitude, latitude);
    }

    /** A geography point as the service stores and returns it. */
    public static ObjectNode geoPointJson(double longitude, double latitude) {
        ObjectNode point = NODES.objectNode();
        point.put("type", "Point");
        ArrayNode coordinates = point.putArray("coordinates");
        coordinates.add(longitude);
        coordinates.add(latitude);
        ObjectNode crs = point.putObject("crs");
        crs.put("type", "name");
        crs.putObject("properties").put("name", "EPSG:4326");
        return point;
    }

    private static JsonNode coerceVector(JsonNode value, SearchFieldDefinition field) {
        if (!value.isArray()) {
            throw cannotConvert(value, field);
        }
        boolean packedBits = "packedBit".equals(field.vectorEncoding());
        int expected = packedBits ? (field.dimensions() + 7) / 8 : field.dimensions();
        if (value.size() != expected) {
            throw SearchException.badRequest("The request is invalid. Details: The vector field '" + field.path()
                + "' dimensionality must match the field definition's 'dimensions' property. Expected: " + expected
                + ". Actual: " + value.size() + ".");
        }
        ArrayNode output = NODES.arrayNode();
        for (JsonNode element : value) {
            if (!element.isNumber()) {
                throw cannotConvert(element, field);
            }
            double number = element.doubleValue();
            boolean inRange = switch (field.type().element()) {
                case SBYTE -> element.isIntegralNumber() && number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
                case BYTE -> element.isIntegralNumber() && number >= 0 && number <= 255;
                case INT16 -> element.isIntegralNumber() && number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
                default -> Double.isFinite(number);
            };
            if (!inRange) {
                throw cannotConvert(element, field);
            }
            output.add(element.isIntegralNumber() ? NODES.numberNode(element.longValue())
                : NODES.numberNode((float) number));
        }
        return output;
    }

    private static SearchException cannotConvert(JsonNode value, SearchFieldDefinition field) {
        String literal = value.isValueNode() ? value.asText() : SearchJson.write(value);
        return SearchException.badRequest("The request is invalid. Details: Cannot convert the literal '" + literal
            + "' of the field '" + field.path() + "' to the expected type '" + field.type().edmName() + "'.");
    }
}
