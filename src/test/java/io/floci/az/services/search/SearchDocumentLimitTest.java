package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchDocumentLimitTest.TwoDocuments.class)
@DisplayName("Search data plane: the max-documents-per-index guard")
class SearchDocumentLimitTest {

    public static class TwoDocuments implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.search.max-documents-per-index", "2");
        }
    }

    private static final String API = "?api-version=2024-07-01";

    @Test
    void documentsBeyondTheLimitFailWith429WhileUpdatesStillWork() {
        given().post("/_admin/reset").then().statusCode(204);
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json")
            .body("{\"name\":\"small\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"n\",\"type\":\"Edm.Int32\"}]}")
            .post("/limit-search/indexes" + API).then().statusCode(201);
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json")
            .body("{\"value\":[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"},{\"id\":\"1\",\"n\":5}]}")
            .post("/limit-search/indexes('small')/docs/search.index" + API).then().statusCode(207)
            .body("value.statusCode", contains(201, 201, 429, 200))
            .body("value[2].errorMessage", containsString("max-documents-per-index"));
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY)
            .get("/limit-search/indexes('small')/docs/$count" + API).then().body(equalTo("2"));
    }
}
