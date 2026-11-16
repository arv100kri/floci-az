package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Search data plane: semantic ranking through the stand-in")
class SearchSemanticTest {

    private static final String API = "?api-version=2025-09-01";
    private static final String SERVICE = "/semantic-search";
    private static final String HOTELS = """
        {"name": "hotels", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "name", "type": "Edm.String"},
          {"name": "description", "type": "Edm.String"},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "rating", "type": "Edm.Int32", "sortable": true}
        ],
        "semantic": {"defaultConfiguration": "hotel-config", "configurations": [
          {"name": "hotel-config", "prioritizedFields": {"titleField": {"fieldName": "name"},
            "prioritizedContentFields": [{"fieldName": "description"}],
            "prioritizedKeywordsFields": [{"fieldName": "tags"}]}}]}}
        """;
    private static final String DOCUMENTS = """
        {"value": [
          {"id": "1", "name": "Ocean View Resort", "description": "Luxury resort with a pool and an ocean view.",
           "tags": ["pool", "view", "wifi"], "rating": 5},
          {"id": "2", "name": "Budget Inn", "description": "Cheap motel near the airport with free parking.",
           "tags": ["parking", "wifi"], "rating": 2},
          {"id": "3", "name": "City Center Hotel",
           "description": "Modern hotel in the city center with a rooftop pool. The rooftop pool is open all year.",
           "tags": ["pool", "gym"], "rating": 4},
          {"id": "4", "name": "Harbor Boutique Hotel", "description": "Small boutique hotel on the harbor.",
           "tags": ["boutique", "view"], "rating": 4}
        ]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body(HOTELS).post(SERVICE + "/indexes" + API).then()
            .statusCode(201);
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body(DOCUMENTS)
            .post(SERVICE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
    }

    private static RequestSpecification admin(String key) {
        return given().header("api-key", key).contentType("application/json");
    }

    private static ValidatableResponse search(String body) {
        return admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body(body)
            .post(SERVICE + "/indexes('hotels')/docs/search.post.search" + API).then();
    }

    @Test
    void aSemanticQueryAnswersWithRerankerScoresCaptionsAndAnswers() {
        search("""
            {"search": "which hotel has a rooftop pool?", "queryType": "semantic", "count": true,
             "semanticConfiguration": "hotel-config", "captions": "extractive|highlight-true",
             "answers": "extractive|count-3", "select": "id,name"}
            """).statusCode(200)
            .header("x-floci-az-semantic-ranker", "stand-in")
            .body("'@odata.count'", equalTo(3))
            .body("value[0].id", equalTo("3"))
            .body("value[0].'@search.rerankerScore'", greaterThan(2.0f))
            .body("value.'@search.rerankerScore'", everyItem(lessThanOrEqualTo(4.0f)))
            .body("value.'@search.rerankerScore'", everyItem(greaterThanOrEqualTo(0.0f)))
            .body("value[0]", not(hasKey("@search.rerankerBoostedScore")))
            .body("value[0].'@search.captions'", hasSize(1))
            .body("value[0].'@search.captions'[0].text", equalTo("Modern hotel in the city center with a rooftop "
                + "pool. The rooftop pool is open all year."))
            .body("value[0].'@search.captions'[0].highlights", containsString("<em>rooftop pool</em>"))
            .body("value[0]", not(hasKey("description")))
            .body("'@search.answers'", hasSize(1))
            .body("'@search.answers'[0].key", equalTo("3"))
            .body("'@search.answers'[0].highlights", containsString("<em>hotel</em>"))
            .body("'@search.answers'[0].score", greaterThanOrEqualTo(0.7f));
    }

    @Test
    void theDefaultConfigurationAndTheGetFormApply() {
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY)
            .queryParam("api-version", "2025-09-01").queryParam("search", "rooftop pool")
            .queryParam("queryType", "semantic").queryParam("captions", "extractive|highlight-false")
            .get(SERVICE + "/indexes('hotels')/docs")
            .then().statusCode(200)
            .header("x-floci-az-semantic-ranker", "stand-in")
            .body("value[0].id", equalTo("3"))
            .body("value[0].'@search.captions'[0].highlights", nullValue());
    }

    @Test
    void plainQueriesStayUnmarked() {
        search("{\"search\": \"pool\"}").statusCode(200)
            .header("x-floci-az-semantic-ranker", nullValue())
            .body("value[0]", not(hasKey("@search.rerankerScore")))
            .body("$", not(hasKey("@search.answers")));
    }

    @Test
    void resultsBeyondTheTopFiftyFollowWithoutRerankerScores() {
        List<String> documents = new ArrayList<>();
        for (int index = 100; index < 160; index++) {
            documents.add("{\"id\":\"" + index + "\",\"name\":\"Paged Hotel " + index + "\",\"description\":\"A paged "
                + "hotel.\"}");
        }
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body("{\"value\":[" + String.join(",", documents) + "]}")
            .post(SERVICE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);

        JsonPath first = search("{\"search\": \"paged\", \"queryType\": \"semantic\", \"captions\": \"extractive\"}")
            .statusCode(200).extract().jsonPath();
        assertEquals(50, first.getList("value").size());
        assertTrue(first.getList("value.'@search.rerankerScore'").stream().allMatch(score -> score != null));
        Map<String, Object> next = first.getMap("'@search.nextPageParameters'");
        assertEquals("semantic", next.get("queryType"));
        assertEquals("extractive", next.get("captions"));
        assertEquals(50, next.get("skip"));

        JsonPath second = admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body(next)
            .post(SERVICE + "/indexes('hotels')/docs/search.post.search" + API).then().statusCode(200)
            .extract().jsonPath();
        assertEquals(10, second.getList("value").size());
        assertTrue(second.getList("value.'@search.rerankerScore'").stream().allMatch(score -> score == null));
        assertNull(second.get("'@search.nextPageParameters'"));
    }

    @Test
    void invalidSemanticRequestsAre400() {
        search("{\"search\": \"pool\", \"queryType\": \"semantic\", \"orderby\": \"rating desc\"}").statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"));
        search("{\"search\": \"pool\", \"queryType\": \"semantic\", \"semanticConfiguration\": \"nope\"}")
            .statusCode(400).body("error.message", containsString("'nope'"));
        search("{\"search\": \"pool\", \"answers\": \"extractive\"}").statusCode(400);
        search("{\"search\": \"pool\", \"queryType\": \"semantic\", \"semanticMaxWaitInMilliseconds\": 100}")
            .statusCode(400);
        search("{\"search\": \"pool\", \"queryType\": \"semantic\", \"queryRewrites\": \"generative\"}")
            .statusCode(400).body("error.message", containsString("queryLanguage"));
    }

    @Test
    void queryRewritesAnswerWithTheOriginalQueryOnly() {
        search("""
            {"search": "rooftop pool", "queryType": "semantic", "queryRewrites": "generative|count-3",
             "queryLanguage": "en-us", "debug": "semantic|queryRewrites"}
            """).statusCode(200)
            .body("'@search.semanticQueryRewritesResultType'", equalTo("originalQueryOnly"))
            .body("'@search.debug'.queryRewrites.text.rewrites", hasSize(0))
            .body("'@search.debug'.queryRewrites.vectors", hasSize(0))
            .body("value[0].'@search.documentDebugInfo'.semantic.titleField.name", equalTo("name"))
            .body("value[0].'@search.documentDebugInfo'.semantic.rerankerInput.title", equalTo("City Center Hotel"));
    }

    @Test
    void semanticConfigurationsAreValidatedWithTheIndex() {
        String invalid = HOTELS.replace("\"name\": \"hotels\"", "\"name\": \"broken\"")
            .replace("{\"fieldName\": \"tags\"}", "{\"fieldName\": \"rating\"}");
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).body(invalid).post(SERVICE + "/indexes" + API).then()
            .statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"))
            .body("error.message", containsString("'rating'"));
        admin(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).get(SERVICE + "/indexes('hotels')" + API).then()
            .statusCode(200)
            .body("semantic.defaultConfiguration", equalTo("hotel-config"))
            .body("semantic.configurations[0].prioritizedFields.titleField.fieldName", equalTo("name"));
    }

    @Test
    void aServiceWhoseSemanticPlanIsDisabledAnswersSemanticQueriesAsTheFreePlan() {
        // Learn, "Enable or disable semantic ranker billing": "Existing services with semanticSearch set to
        // disabled are automatically treated as free."
        String arm = "/subscriptions/sub-semantic/resourceGroups/rg-semantic/providers/Microsoft.Search/"
            + "searchServices/semoff";
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"properties\":{\"semanticSearch\":"
                + "\"disabled\"}}")
            .put(arm + "?api-version=2025-05-01").then().statusCode(201);
        String key = given().post(arm + "/listAdminKeys?api-version=2025-05-01").then().statusCode(200)
            .extract().path("primaryKey");
        admin(key).body(HOTELS).post("/semoff-search/indexes" + API).then().statusCode(201);
        admin(key).body(DOCUMENTS).post("/semoff-search/indexes('hotels')/docs/search.index" + API).then()
            .statusCode(200);

        String semantic = "{\"search\": \"rooftop pool\", \"queryType\": \"semantic\"}";
        admin(key).body(semantic).post("/semoff-search/indexes('hotels')/docs/search.post.search" + API).then()
            .statusCode(200)
            .header("x-floci-az-semantic-ranker", equalTo("stand-in"))
            .body("value[0].id", equalTo("3"));
    }
}
