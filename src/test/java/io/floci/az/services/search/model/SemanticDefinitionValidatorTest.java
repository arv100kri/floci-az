package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticDefinitionValidatorTest {

    private static final String FIELDS = """
        "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "title", "type": "Edm.String"},
          {"name": "body", "type": "Edm.String"},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "code", "type": "Edm.String", "searchable": false},
          {"name": "secret", "type": "Edm.String", "retrievable": false},
          {"name": "rating", "type": "Edm.Int32"},
          {"name": "address", "type": "Edm.ComplexType", "fields": [{"name": "city", "type": "Edm.String"}]}
        ]""";

    private static SearchIndexDefinition index(String semantic) {
        return EngineFixtures.definition("{\"name\": \"docs\", " + FIELDS + ", \"semantic\": " + semantic + "}");
    }

    private static String config(String name, String prioritizedFields) {
        return "{\"name\": \"" + name + "\", \"prioritizedFields\": " + prioritizedFields + "}";
    }

    private static String rejected(String semantic) {
        SearchException exception = assertThrows(SearchException.class, () -> index(semantic));
        assertEquals(400, exception.status());
        assertEquals(SearchErrorCode.INVALID_REQUEST, exception.errorCode());
        return exception.getMessage();
    }

    @Test
    void aValidSectionRoundTripsWithTheSpecSpellingOfRankingOrder() {
        SearchIndexDefinition definition = index("{\"defaultConfiguration\": \"main\", \"configurations\": ["
            + "{\"name\": \"main\", \"rankingOrder\": \"boostedRerankerScore\", \"flightingOptIn\": true, "
            + "\"prioritizedFields\": {\"titleField\": {\"fieldName\": \"title\"}, "
            + "\"prioritizedContentFields\": [{\"fieldName\": \"body\"}, {\"fieldName\": \"address/city\"}], "
            + "\"prioritizedKeywordsFields\": [{\"fieldName\": \"tags\"}]}},"
            + config("keywords", "{\"prioritizedKeywordsFields\": [{\"fieldName\": \"tags\"}]}") + "]}");
        JsonNode semantic = definition.json().get("semantic");
        assertEquals("main", semantic.get("defaultConfiguration").asText());
        assertEquals("BoostedRerankerScore", semantic.get("configurations").get(0).get("rankingOrder").asText());
        assertTrue(semantic.get("configurations").get(0).get("flightingOptIn").asBoolean());
        assertEquals("address/city", semantic.get("configurations").get(0).get("prioritizedFields")
            .get("prioritizedContentFields").get(1).get("fieldName").asText());
        assertTrue(index("null").json().get("semantic").isNull());
    }

    @Test
    void fieldsMustBeSearchableRetrievableStrings() {
        assertThat(rejected("{\"configurations\": [" + config("c", "{\"titleField\": {\"fieldName\": \"nope\"}}")
            + "]}"), containsString("'nope'"));
        assertThat(rejected("{\"configurations\": [" + config("c", "{\"titleField\": {\"fieldName\": \"rating\"}}")
            + "]}"), containsString("Edm.String"));
        assertThat(rejected("{\"configurations\": [" + config("c",
            "{\"prioritizedContentFields\": [{\"fieldName\": \"code\"}]}") + "]}"), containsString("searchable"));
        assertThat(rejected("{\"configurations\": [" + config("c",
            "{\"prioritizedKeywordsFields\": [{\"fieldName\": \"secret\"}]}") + "]}"), containsString("retrievable"));
        assertThat(rejected("{\"configurations\": [" + config("c",
            "{\"prioritizedContentFields\": [{\"fieldName\": \"address\"}]}") + "]}"), containsString("Edm.String"));
        assertThat(rejected("{\"configurations\": [" + config("c", "{\"prioritizedContentFields\": [\"body\"]}")
            + "]}"), containsString("'fieldName'"));
    }

    @Test
    void configurationsNeedANameAndAPrioritizedField() {
        assertThat(rejected("{\"configurations\": [" + config("c", "{}") + "]}"), containsString("at least one"));
        assertThat(rejected("{\"configurations\": [" + config("c", "{\"prioritizedContentFields\": []}") + "]}"),
            containsString("at least one"));
        assertThat(rejected("{\"configurations\": [{\"name\": \"c\"}]}"), containsString("prioritizedFields"));
        assertThat(rejected("{\"configurations\": [{\"prioritizedFields\": {\"titleField\": {\"fieldName\": "
            + "\"title\"}}}]}"), containsString("name"));
        String title = "{\"titleField\": {\"fieldName\": \"title\"}}";
        assertThat(rejected("{\"configurations\": [" + config("c", title) + "," + config("c", title) + "]}"),
            containsString("more than once"));
    }

    @Test
    void theDefaultMustNameAConfiguration() {
        String title = "{\"titleField\": {\"fieldName\": \"title\"}}";
        assertThat(rejected("{\"defaultConfiguration\": \"other\", \"configurations\": [" + config("c", title)
            + "]}"), containsString("'other'"));
        assertThat(rejected("{\"defaultConfiguration\": \"c\"}"), containsString("'c'"));
    }

    @Test
    void rankingOrderAndFlightingOptInAreChecked() {
        String title = "\"prioritizedFields\": {\"titleField\": {\"fieldName\": \"title\"}}";
        assertThat(rejected("{\"configurations\": [{\"name\": \"c\", \"rankingOrder\": \"byDate\", " + title + "}]}"),
            containsString("rankingOrder"));
        assertThat(rejected("{\"configurations\": [{\"name\": \"c\", \"flightingOptIn\": \"yes\", " + title + "}]}"),
            containsString("flightingOptIn"));
        assertEquals("RerankerScore", index("{\"configurations\": [{\"name\": \"c\", \"rankingOrder\": "
            + "\"reRankerScore\", " + title + "}]}").json().get("semantic").get("configurations").get(0)
            .get("rankingOrder").asText());
    }

    @Test
    void anIndexHoldsAtMostOneHundredConfigurations() {
        List<String> configurations = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            configurations.add(config("c" + index, "{\"titleField\": {\"fieldName\": \"title\"}}"));
        }
        index("{\"configurations\": [" + String.join(",", configurations) + "]}");
        configurations.add(config("c100", "{\"titleField\": {\"fieldName\": \"title\"}}"));
        assertThat(rejected("{\"configurations\": [" + String.join(",", configurations) + "]}"),
            containsString("maximum is 100"));
    }
}
