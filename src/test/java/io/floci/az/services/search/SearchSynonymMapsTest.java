package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: synonym maps")
class SearchSynonymMapsTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/syn-search";
    private static final String MAP = """
        {"name": "geo", "format": "solr",
         "synonyms": "USA, United States, United States of America\\nWashington, Wash., WA => WA\\ndog, canine"}
        """;
    private static final String INDEX = """
        {"name": "places", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "text", "type": "Edm.String", "searchable": true, "synonymMaps": ["geo"]}]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse createMap() {
        return admin().body(MAP).post(BASE + "/synonymmaps" + API).then().statusCode(201);
    }

    private static ValidatableResponse search(String text) {
        return admin().body("{\"search\": \"" + text + "\", \"orderby\": \"id\"}")
            .post(BASE + "/indexes('places')/docs/search.post.search" + API).then();
    }

    private static void createPlaces() {
        admin().body(INDEX).post(BASE + "/indexes" + API).then().statusCode(201);
        admin().body("""
            {"value": [{"@search.action": "upload", "id": "1", "text": "touring the USA"},
                       {"@search.action": "upload", "id": "2", "text": "the United States of America"},
                       {"@search.action": "upload", "id": "3", "text": "hiking in WA"},
                       {"@search.action": "upload", "id": "4", "text": "the Washington monument"},
                       {"@search.action": "upload", "id": "5", "text": "a friendly dog"}]}
            """).post(BASE + "/indexes('places')/docs/search.index" + API).then().statusCode(200);
    }

    @Test
    void createGetListAndDeleteFollowTheRestContract() {
        String etag = createMap()
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/syn-search/synonymmaps('geo')?api-version=2024-07-01"))
            .body("'@odata.context'", endsWith("/syn-search/$metadata#synonymmaps/$entity"))
            .body("'@odata.etag'", startsWith("\"0x"))
            .body("name", equalTo("geo"))
            .body("format", equalTo("solr"))
            .body("synonyms", startsWith("USA, United States"))
            .body("encryptionKey", nullValue())
            .extract().header("ETag");
        admin().get(BASE + "/synonymmaps('geo')" + API).then().statusCode(200)
            .header("ETag", equalTo(etag))
            .body("name", equalTo("geo"));
        admin().get(BASE + "/synonymmaps/geo" + API).then().statusCode(200);
        admin().get(BASE + "/synonymmaps" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#synonymmaps"))
            .body("value.name", contains("geo"))
            .body("value[0].synonyms", startsWith("USA"));
        admin().get(BASE + "/synonymmaps?api-version=2024-07-01&$select=name").then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#synonymmaps(name)"))
            .body("value.name", contains("geo"))
            .body("value[0]", not(hasKey("synonyms")));
        admin().body(MAP).post(BASE + "/synonymmaps" + API).then().statusCode(409);
        admin().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.synonymMaps.usage", equalTo(1));
        admin().delete(BASE + "/synonymmaps('geo')" + API).then().statusCode(204);
        admin().get(BASE + "/synonymmaps('geo')" + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        admin().delete(BASE + "/synonymmaps('geo')" + API).then().statusCode(404);
    }

    @Test
    void putCreatesThenUpdatesHonouringEtagsAndPrefer() {
        String etag = admin().body(MAP).put(BASE + "/synonymmaps('geo')" + API).then().statusCode(201)
            .extract().header("ETag");
        String updated = MAP.replace("dog, canine", "dog, hound");
        admin().header("If-Match", "\"0x1\"").body(updated).put(BASE + "/synonymmaps('geo')" + API).then()
            .statusCode(412).body("error.code", equalTo("PreconditionFailed"));
        String next = admin().header("If-Match", etag).header("Prefer", "return=representation").body(updated)
            .put(BASE + "/synonymmaps('geo')" + API).then().statusCode(200)
            .body("synonyms", containsString("dog, hound"))
            .extract().header("ETag");
        admin().body(updated).put(BASE + "/synonymmaps('geo')" + API).then().statusCode(204);
        admin().header("If-Match", etag).delete(BASE + "/synonymmaps('geo')" + API).then().statusCode(412);
        admin().header("If-None-Match", "*").body(updated).put(BASE + "/synonymmaps('geo')" + API).then()
            .statusCode(412);
        admin().body(updated.replace("\"geo\"", "\"other\"")).put(BASE + "/synonymmaps('geo')" + API).then()
            .statusCode(400);
        assertThat(next, startsWith("\"0x"));
    }

    @Test
    void invalidSynonymMapsAre400() {
        String[] invalid = {
            "{\"name\": \"Bad_Name\", \"format\": \"solr\", \"synonyms\": \"a, b\"}",
            "{\"name\": \"geo\", \"format\": \"wordnet\", \"synonyms\": \"a, b\"}",
            "{\"name\": \"geo\", \"format\": \"solr\"}",
            "{\"name\": \"geo\", \"format\": \"solr\", \"synonyms\": \"a => b => c\"}",
            "{\"name\": \"geo\", \"format\": \"solr\", \"synonyms\": \"a, , b\"}",
            "{\"format\": \"solr\", \"synonyms\": \"a, b\"}",
            "{\"name\": \"geo\", \"format\": \"solr\", \"synonyms\": \"" + "x, ".repeat(21) + "y\"}"
        };
        for (String body : invalid) {
            admin().body(body).post(BASE + "/synonymmaps" + API).then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestParameter"));
        }
    }

    @Test
    void assignedMapsExpandQueriesAndChangesApplyToTheNextQuery() {
        createMap();
        createPlaces();
        search("USA").statusCode(200).body("value.id", contains("1", "2"));
        search("Washington").statusCode(200).body("value.id", contains("3"));
        search("canine").statusCode(200).body("value.id", contains("5"));
        search("puppy").statusCode(200).body("value", empty());

        admin().body(MAP.replace("dog, canine", "dog, canine, puppy")).put(BASE + "/synonymmaps('geo')" + API)
            .then().statusCode(204);
        search("puppy").statusCode(200).body("value.id", contains("5"));

        admin().delete(BASE + "/synonymmaps('geo')" + API).then().statusCode(204);
        search("dog").statusCode(404).body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("geo"));
        search("*").statusCode(200).body("value.id", containsInAnyOrder("1", "2", "3", "4", "5"));
    }
}
