package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.az.services.search.model.SearchJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The indexer mapping functions an output field mapping can apply, by their documented names (compared ignoring case):
 * {@code base64Encode}, {@code base64Decode}, {@code extractTokenAtPosition}, {@code jsonArrayToStringCollection},
 * {@code urlEncode}, {@code urlDecode}, {@code fixedLengthEncode} and {@code toJson}. A function applies to each
 * element when the mapped value is an array.
 *
 * <p>{@code base64Encode} is URL-safe; with {@code useHttpServerUtilityUrlTokenEncode} (the default) it behaves like
 * .NET's {@code HttpServerUtility.UrlTokenEncode}, which ends with the number of padding characters it removed, and
 * otherwise drops the padding. {@code urlEncode} follows .NET's {@code HttpUtility.UrlEncode} ({@code <hello>} is
 * {@code %3chello%3e}). {@code fixedLengthEncode}, whose algorithm is not documented, is the lowercase hexadecimal
 * SHA-256 of the UTF-8 text: 64 characters that are valid in a document key.</p>
 */
final class MappingFunctions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MappingFunctions() {
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
            case "base64encode" -> NODES.textNode(base64Encode(text(value),
                flag(parameters, "useHttpServerUtilityUrlTokenEncode", true)));
            case "base64decode" -> NODES.textNode(base64Decode(text(value), flag(parameters,
                "useHttpServerUtilityUrlTokenDecode", flag(parameters, "useHttpServerUtilityUrlTokenEncode", true))));
            case "extracttokenatposition" -> NODES.textNode(extractToken(text(value), parameters));
            case "jsonarraytostringcollection" -> stringCollection(value);
            case "urlencode" -> NODES.textNode(urlEncode(text(value)));
            case "urldecode" -> NODES.textNode(urlDecode(text(value)));
            case "fixedlengthencode" -> NODES.textNode(fixedLengthEncode(text(value)));
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

    static String base64Encode(String text, boolean urlToken) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return "";
        }
        String encoded = Base64.getUrlEncoder().encodeToString(bytes);
        int padding = 0;
        while (encoded.endsWith("=")) {
            encoded = encoded.substring(0, encoded.length() - 1);
            padding++;
        }
        return urlToken ? encoded + padding : encoded;
    }

    static String base64Decode(String text, boolean urlToken) {
        if (text.isEmpty()) {
            return "";
        }
        String body = text.replace('+', '-').replace('/', '_');
        if (urlToken) {
            char last = body.charAt(body.length() - 1);
            if (last < '0' || last > '2') {
                throw new IllegalArgumentException("the value is not URL token encoded");
            }
            body = body.substring(0, body.length() - 1) + "=".repeat(last - '0');
        } else {
            body = body.replace("=", "");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the value is not valid base64: " + e.getMessage());
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

    /** .NET {@code HttpUtility.UrlEncode}: letters, digits and {@code -_.!*()} kept, space as {@code +}. */
    static String urlEncode(String text) {
        StringBuilder encoded = new StringBuilder(text.length());
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            char character = (char) (value & 0xFF);
            if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || "-_.!*()".indexOf(character) >= 0) {
                encoded.append(character);
            } else if (character == ' ') {
                encoded.append('+');
            } else {
                encoded.append('%').append(HexFormat.of().toHexDigits((byte) character));
            }
        }
        return encoded.toString();
    }

    /** .NET {@code HttpUtility.UrlDecode}: {@code +} as space, {@code %xx} as UTF-8 bytes. */
    static String urlDecode(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '+') {
                bytes.write(' ');
                index++;
            } else if (character == '%' && isHex(text, index + 1)) {
                bytes.write(Integer.parseInt(text.substring(index + 1, index + 3), 16));
                index += 3;
            } else {
                byte[] encoded = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                index++;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(String text, int from) {
        return from + 1 < text.length() && Character.digit(text.charAt(from), 16) >= 0
            && Character.digit(text.charAt(from + 1), 16) >= 0;
    }

    static String fixedLengthEncode(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
