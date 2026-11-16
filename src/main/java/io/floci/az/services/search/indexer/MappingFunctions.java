package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The field mapping functions of Learn's "Field mappings and transformations": {@code base64Encode},
 * {@code base64Decode}, {@code extractTokenAtPosition}, {@code jsonArrayToStringCollection}, {@code urlEncode},
 * {@code urlDecode}, {@code fixedLengthEncode} and {@code toJson}. Names match ignoring case.
 *
 * <ul>
 *   <li>{@code base64Encode} with {@code useHttpServerUtilityUrlTokenEncode} true (the default) behaves like .NET's
 *       {@code HttpServerUtility.UrlTokenEncode}: URL-safe characters, the padding replaced by a trailing digit that
 *       counts it. With false it is URL-safe Base64 without padding ({@code 00>00?00} gives {@code MDA-MDA_MDA}).
 *       {@code base64Decode} reverses either form, per {@code useHttpServerUtilityUrlTokenDecode}.</li>
 *   <li>{@code urlEncode} and {@code urlDecode} follow .NET's {@code HttpUtility}: UTF-8, lowercase hex, a space as
 *       {@code +} ({@code <hello>} gives {@code %3chello%3e}).</li>
 *   <li>{@code fixedLengthEncode} has no published algorithm; floci-az returns the URL-safe Base64 SHA-256 of the
 *       UTF-8 value: 43 characters, all valid in a document key.</li>
 * </ul>
 */
public final class MappingFunctions {

    static final String BASE64_ENCODE = "base64encode";
    static final String BASE64_DECODE = "base64decode";
    static final String EXTRACT_TOKEN = "extracttokenatposition";
    static final String JSON_ARRAY = "jsonarraytostringcollection";
    static final String URL_ENCODE = "urlencode";
    static final String URL_DECODE = "urldecode";
    static final String FIXED_LENGTH = "fixedlengthencode";
    static final String TO_JSON = "tojson";

    private static final Set<String> NAMES = Set.of(BASE64_ENCODE, BASE64_DECODE, EXTRACT_TOKEN, JSON_ARRAY,
        URL_ENCODE, URL_DECODE, FIXED_LENGTH, TO_JSON);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MappingFunctions() {
    }

    /** A value a mapping function cannot transform. */
    public static final class MappingException extends Exception {

        public MappingException(String message) {
            super(message);
        }
    }

    /** Checks a function at create time: a 400 for an unknown name or invalid parameters. */
    static void validate(MappingFunction function, String owner) {
        String name = function.name() == null ? "" : function.name().toLowerCase(Locale.ROOT);
        if (!NAMES.contains(name)) {
            throw IndexerDefinitionParser.invalid(owner + " uses the mapping function '" + function.name()
                + "', which is not supported. Supported functions are base64Encode, base64Decode, "
                + "extractTokenAtPosition, jsonArrayToStringCollection, urlEncode, urlDecode, fixedLengthEncode and "
                + "toJson.");
        }
        if (EXTRACT_TOKEN.equals(name)) {
            ObjectNode parameters = function.parameters();
            String delimiter = parameters == null ? null : SearchJson.text(parameters, "delimiter");
            JsonNode position = parameters == null ? null : parameters.get("position");
            if (delimiter == null || delimiter.isEmpty()) {
                throw IndexerDefinitionParser.invalid(owner + ": extractTokenAtPosition requires the parameter "
                    + "'delimiter'.");
            }
            if (position == null || !(position.isIntegralNumber() || isIntegerText(position)) || position.asInt() < 0) {
                throw IndexerDefinitionParser.invalid(owner + ": extractTokenAtPosition requires the parameter "
                    + "'position', a zero-based integer.");
            }
        }
    }

    private static boolean isIntegerText(JsonNode node) {
        if (!node.isTextual()) {
            return false;
        }
        try {
            Integer.parseInt(node.textValue().trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Whether the function encodes its input into a document-key-safe string. */
    static boolean producesKeySafeValue(MappingFunction function) {
        String name = function.name().toLowerCase(Locale.ROOT);
        return BASE64_ENCODE.equals(name) || FIXED_LENGTH.equals(name);
    }

    /**
     * Applies {@code function} to {@code value}.
     *
     * @throws MappingException when the value cannot be transformed
     */
    public static JsonNode apply(MappingFunction function, JsonNode value) throws MappingException {
        String name = function.name().toLowerCase(Locale.ROOT);
        ObjectNode parameters = function.parameters();
        return switch (name) {
            case BASE64_ENCODE -> SearchJson.NODES.textNode(base64Encode(text(value, function),
                flag(parameters, "useHttpServerUtilityUrlTokenEncode", "useHttpServerUtilityUrlTokenDecode")));
            case BASE64_DECODE -> SearchJson.NODES.textNode(base64Decode(text(value, function),
                flag(parameters, "useHttpServerUtilityUrlTokenDecode", "useHttpServerUtilityUrlTokenEncode")));
            case EXTRACT_TOKEN -> SearchJson.NODES.textNode(extractToken(text(value, function), parameters));
            case JSON_ARRAY -> jsonArrayToStringCollection(value, function);
            case URL_ENCODE -> SearchJson.NODES.textNode(urlEncode(text(value, function)));
            case URL_DECODE -> SearchJson.NODES.textNode(urlDecode(text(value, function)));
            case FIXED_LENGTH -> SearchJson.NODES.textNode(fixedLengthEncode(text(value, function)));
            case TO_JSON -> toJson(value);
            default -> throw new MappingException("The mapping function '" + function.name() + "' is not supported.");
        };
    }

    /** {@code value} encoded as {@code base64Encode} encodes it, with the HttpServerUtility form when asked. */
    public static String base64Encode(String value, boolean urlToken) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (!urlToken) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        if (bytes.length == 0) {
            return "";
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        int padding = 0;
        while (encoded.endsWith("=")) {
            encoded = encoded.substring(0, encoded.length() - 1);
            padding++;
        }
        return encoded.replace('+', '-').replace('/', '_') + padding;
    }

    /** {@code value} decoded as {@code base64Decode} decodes it. */
    public static String base64Decode(String value, boolean urlToken) throws MappingException {
        String text = value.trim();
        if (text.isEmpty()) {
            return "";
        }
        String standard;
        if (urlToken) {
            char last = text.charAt(text.length() - 1);
            if (last < '0' || last > '2') {
                throw new MappingException("The input is not a valid UrlTokenEncode value: it must end with the "
                    + "padding count 0, 1 or 2.");
            }
            standard = text.substring(0, text.length() - 1).replace('-', '+').replace('_', '/')
                + "=".repeat(last - '0');
        } else {
            String unpadded = text.replace('-', '+').replace('_', '/');
            while (unpadded.endsWith("=")) {
                unpadded = unpadded.substring(0, unpadded.length() - 1);
            }
            standard = unpadded + "=".repeat((4 - unpadded.length() % 4) % 4);
        }
        try {
            return new String(Base64.getDecoder().decode(standard), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new MappingException("The input is not a valid Base64 string: " + e.getMessage());
        }
    }

    /** {@code value} encoded as .NET's {@code HttpUtility.UrlEncode} encodes it. */
    public static String urlEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            char c = (char) b;
            if (b < 0x80 && isUrlSafe(c)) {
                encoded.append(c);
            } else if (c == ' ') {
                encoded.append('+');
            } else {
                encoded.append('%').append(HEX[b >> 4]).append(HEX[b & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUrlSafe(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '-' || c == '_' || c == '.' || c == '!' || c == '*' || c == '(' || c == ')';
    }

    /** {@code value} decoded as .NET's {@code HttpUtility.UrlDecode} decodes it; malformed escapes stay as they are. */
    public static String urlDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '+') {
                bytes.write(' ');
                index++;
            } else if (c == '%' && index + 2 < value.length()
                    && hexValue(value.charAt(index + 1)) >= 0 && hexValue(value.charAt(index + 2)) >= 0) {
                bytes.write(hexValue(value.charAt(index + 1)) * 16 + hexValue(value.charAt(index + 2)));
                index += 3;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                index++;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        return Character.digit(c, 16);
    }

    /** {@code value} reduced to a fixed-length, document-key-safe string. */
    public static String fixedLengthEncode(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String extractToken(String value, ObjectNode parameters) throws MappingException {
        String delimiter = SearchJson.text(parameters, "delimiter");
        int position = parameters.get("position").asInt();
        String[] tokens = value.split(Pattern.quote(delimiter), -1);
        if (position >= tokens.length) {
            throw new MappingException("The value has " + tokens.length + " tokens separated by '" + delimiter
                + "'; there is no token at position " + position + ".");
        }
        return tokens[position];
    }

    private static JsonNode jsonArrayToStringCollection(JsonNode value, MappingFunction function)
            throws MappingException {
        JsonNode parsed = value;
        if (value.isTextual()) {
            try {
                parsed = SearchJson.MAPPER.readTree(value.textValue());
            } catch (JsonProcessingException e) {
                throw new MappingException("The value is not a JSON array of strings: " + e.getOriginalMessage());
            }
        }
        if (!(parsed instanceof ArrayNode array)) {
            throw new MappingException("The value of type '" + value.getNodeType() + "' is not a JSON array of "
                + "strings; " + function.name() + " needs one.");
        }
        ArrayNode strings = SearchJson.NODES.arrayNode();
        for (JsonNode element : array) {
            if (element.isContainerNode() || element.isNull()) {
                throw new MappingException("The JSON array contains a value that is not a string.");
            }
            strings.add(element.asText());
        }
        return strings;
    }

    private static JsonNode toJson(JsonNode value) throws MappingException {
        if (value.isContainerNode()) {
            return value;
        }
        if (!value.isTextual()) {
            throw new MappingException("The value is not a string holding JSON.");
        }
        try {
            JsonNode parsed = SearchJson.MAPPER.readTree(value.textValue());
            if (parsed == null || !parsed.isContainerNode()) {
                throw new MappingException("The value is not a JSON object or array.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new MappingException("The value is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static String text(JsonNode value, MappingFunction function) throws MappingException {
        if (value.isContainerNode()) {
            throw new MappingException("The value of type '" + value.getNodeType() + "' is not a string; "
                + function.name() + " needs a string.");
        }
        return value.asText();
    }

    private static boolean flag(ObjectNode parameters, String name, String alternative) {
        JsonNode value = parameters == null ? null : parameters.get(name);
        if (IndexerJson.isNull(value) && parameters != null) {
            value = parameters.get(alternative);
        }
        if (IndexerJson.isNull(value)) {
            return true;
        }
        return value.isBoolean() ? value.booleanValue() : !"false".equalsIgnoreCase(value.asText());
    }
}
