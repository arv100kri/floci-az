package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.indexer.MappingFunctions.MappingException;
import io.floci.az.services.search.model.SearchJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingFunctionsTest {

    private static JsonNode apply(String name, String parameters, JsonNode value) throws MappingException {
        ObjectNode parameterNode = parameters == null ? null
            : (ObjectNode) SearchJson.parse(parameters.getBytes(StandardCharsets.UTF_8));
        return MappingFunctions.apply(new MappingFunction(name, parameterNode), value);
    }

    private static JsonNode text(String value) {
        return SearchJson.NODES.textNode(value);
    }

    /** Learn's comparison table: {@code 00>00?00} in the two encodings Azure AI Search supports. */
    @Test
    void base64EncodeFollowsTheDocumentedEncodings() throws MappingException {
        assertEquals("MDA-MDA_MDA1", apply("base64Encode", null, text("00>00?00")).textValue(),
            "HttpServerUtility.UrlTokenEncode by default: URL-safe, the padding count appended");
        assertEquals("MDA-MDA_MDA", apply("BASE64ENCODE", "{\"useHttpServerUtilityUrlTokenEncode\": false}",
            text("00>00?00")).textValue(), "URL-safe without padding otherwise");
        assertEquals("00>00?00", apply("base64Decode", null, text("MDA-MDA_MDA1")).textValue());
        assertEquals("00>00?00", apply("base64Decode", "{\"useHttpServerUtilityUrlTokenDecode\": false}",
            text("MDA-MDA_MDA")).textValue());
        assertThrows(MappingException.class, () -> apply("base64Decode", null, text("MDA-MDA_MDA")));
    }

    @Test
    void urlFunctionsBehaveLikeHttpUtility() throws MappingException {
        assertEquals("%3chello%3e", apply("urlEncode", null, text("<hello>")).textValue());
        assertEquals("a+b%c3%a9", apply("urlEncode", null, text("a bé")).textValue());
        assertEquals("<hello> a bé", apply("urlDecode", null, text("%3chello%3e+a+b%c3%a9")).textValue());
    }

    @Test
    void extractTokenAtPositionSplitsOnTheDelimiter() throws MappingException {
        String first = "{\"delimiter\": \" \", \"position\": 0}";
        String second = "{\"delimiter\": \" \", \"position\": 1}";
        assertEquals("Jane", apply("extractTokenAtPosition", first, text("Jane Doe")).textValue());
        assertEquals("Doe", apply("extractTokenAtPosition", second, text("Jane Doe")).textValue());
        assertThrows(MappingException.class, () -> apply("extractTokenAtPosition", second, text("Jane")));
    }

    @Test
    void jsonFunctionsParseTheirInput() throws MappingException {
        assertEquals(SearchJson.parse("[\"red\",\"white\",\"blue\"]".getBytes(StandardCharsets.UTF_8)),
            apply("jsonArrayToStringCollection", null, text("[\"red\", \"white\", \"blue\"]")));
        assertThrows(MappingException.class, () -> apply("jsonArrayToStringCollection", null, text("red")));
        assertEquals("Jane", apply("toJson", null, text("{\"info\": {\"name\": \"Jane\"}}"))
            .path("info").path("name").textValue());
        assertThrows(MappingException.class, () -> apply("toJson", null, text("not json")));
    }

    @Test
    void fixedLengthEncodeGivesAKeySafeValueOfOneLength() throws MappingException {
        String shortValue = apply("fixedLengthEncode", null, text("a")).textValue();
        String longValue = apply("fixedLengthEncode", null, text("x".repeat(5000))).textValue();
        assertEquals(shortValue.length(), longValue.length());
        assertTrue(longValue.matches("[A-Za-z0-9_-]+"), longValue);
    }
}
