package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.az.services.search.indexer.MappingFunctions;
import io.floci.az.services.search.indexer.MappingFunctions.MappingException;
import io.floci.az.services.search.model.SearchJson;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The indexer mapping functions an output field mapping can apply, by their documented names (compared ignoring case):
 * {@code base64Encode}, {@code base64Decode}, {@code extractTokenAtPosition}, {@code jsonArrayToStringCollection},
 * {@code urlEncode}, {@code urlDecode}, {@code fixedLengthEncode} and {@code toJson}. A function applies to each
 * element when the mapped value is an array.
 *
 * <p>The encodings are those of an indexer's field mappings ({@link MappingFunctions}), so a function gives the same
 * value in {@code fieldMappings} and {@code outputFieldMappings}.</p>
 */
final class OutputMappingFunctions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private OutputMappingFunctions() {
    }

    /**
     * Applies the mapping function {@code function} ({@code {"name": ..., "parameters": {...}}}) to {@code value}.
     *
     * @throws IllegalArgumentException when the function is unknown or cannot apply to the value
     */
    static JsonNode apply(JsonNode function, JsonNode value) {
        String name = SkillDefinition.text(function, "name");
        if (name == null) {
            throw new IllegalArgumentException("the mapping function has no name");
        }
        JsonNode parameters = function.get("parameters");
        String normalized = name.toLowerCase(Locale.ROOT);
        if (value.isArray() && !normalized.equals("jsonarraytostringcollection")) {
            ArrayNode mapped = NODES.arrayNode();
            for (JsonNode element : value) {
                mapped.add(element.isNull() ? element : applyOne(normalized, name, parameters, element));
            }
            return mapped;
        }
        return applyOne(normalized, name, parameters, value);
    }

    private static JsonNode applyOne(String normalized, String name, JsonNode parameters, JsonNode value) {
        return switch (normalized) {
            case "base64encode" -> NODES.textNode(MappingFunctions.base64Encode(text(value),
                flag(parameters, "useHttpServerUtilityUrlTokenEncode", true)));
            case "base64decode" -> NODES.textNode(base64Decode(text(value), flag(parameters,
                "useHttpServerUtilityUrlTokenDecode", flag(parameters, "useHttpServerUtilityUrlTokenEncode", true))));
            case "extracttokenatposition" -> NODES.textNode(extractToken(text(value), parameters));
            case "jsonarraytostringcollection" -> stringCollection(value);
            case "urlencode" -> NODES.textNode(MappingFunctions.urlEncode(text(value)));
            case "urldecode" -> NODES.textNode(MappingFunctions.urlDecode(text(value)));
            case "fixedlengthencode" -> NODES.textNode(MappingFunctions.fixedLengthEncode(text(value)));
            case "tojson" -> toJson(value);
            default -> throw new IllegalArgumentException("'" + name + "' is not a mapping function");
        };
    }

    private static String text(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        throw new IllegalArgumentException("the value is not a string");
    }

    private static boolean flag(JsonNode parameters, String name, boolean defaultValue) {
        JsonNode flag = parameters == null ? null : parameters.get(name);
        return flag != null && flag.isBoolean() ? flag.booleanValue() : defaultValue;
    }

    private static String base64Decode(String text, boolean urlToken) {
        try {
            return MappingFunctions.base64Decode(text, urlToken);
        } catch (MappingException e) {
            // OutputFieldMappings ends the error with its own period.
            String message = e.getMessage();
            throw new IllegalArgumentException(message.endsWith(".")
                ? message.substring(0, message.length() - 1) : message, e);
        }
    }

    private static String extractToken(String text, JsonNode parameters) {
        String delimiter = parameters == null ? null : SkillDefinition.text(parameters, "delimiter");
        JsonNode positionNode = parameters == null ? null : parameters.get("position");
        if (delimiter == null || delimiter.isEmpty() || positionNode == null || !positionNode.canConvertToInt()) {
            throw new IllegalArgumentException("extractTokenAtPosition needs a 'delimiter' and a 'position'");
        }
        String[] tokens = text.split(Pattern.quote(delimiter), -1);
        int position = positionNode.intValue();
        if (position < 0 || position >= tokens.length) {
            throw new IllegalArgumentException("there is no token at position " + position);
        }
        return tokens[position];
    }

    private static JsonNode stringCollection(JsonNode value) {
        JsonNode parsed = value;
        if (value.isTextual()) {
            try {
                parsed = SearchJson.MAPPER.readTree(value.textValue());
            } catch (IOException e) {
                throw new IllegalArgumentException("the value is not a JSON array of strings");
            }
        }
        if (!(parsed instanceof ArrayNode array)) {
            throw new IllegalArgumentException("the value is not a JSON array of strings");
        }
        for (JsonNode element : array) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("the value is not a JSON array of strings");
            }
        }
        return array;
    }

    private static JsonNode toJson(JsonNode value) {
        if (!value.isTextual()) {
            return value;
        }
        try {
            JsonNode parsed = SearchJson.MAPPER.readTree(value.textValue());
            if (parsed == null || parsed.isMissingNode()) {
                throw new IllegalArgumentException("the value is not valid JSON");
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalArgumentException("the value is not valid JSON");
        }
    }
}
