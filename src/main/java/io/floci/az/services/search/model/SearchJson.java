package io.floci.az.services.search.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.JacksonConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The JSON reader and writer every part of the search service shares. */
public final class SearchJson {

    public static final ObjectMapper MAPPER = createMapper();

    public static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SearchJson() {
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JacksonConfig.applyLargePayloadConstraints(mapper);
        return mapper;
    }

    /** Parses a request body; an empty body is {@code null}, malformed JSON a 400. */
    public static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw SearchException.badRequest("The request is invalid. Details: the request body is not valid JSON: "
                + e.getOriginalMessage());
        } catch (IOException e) {
            throw SearchException.badRequest("The request is invalid. Details: the request body could not be read.");
        }
    }

    /** Parses a body that must be a JSON object; {@code what} names it in the 400 otherwise. */
    public static ObjectNode parseObject(byte[] body, String what) {
        JsonNode node = parse(body);
        if (node instanceof ObjectNode object) {
            return object;
        }
        throw SearchException.badRequest("The request is invalid. Details: " + what + " must be a JSON object.");
    }

    public static ObjectNode object() {
        return NODES.objectNode();
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A JSON tree could not be serialised", e);
        }
    }

    public static byte[] writeBytes(JsonNode node) {
        return write(node).getBytes(StandardCharsets.UTF_8);
    }

    /** The text of {@code node[field]}, or null when it is absent, null or not text. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
