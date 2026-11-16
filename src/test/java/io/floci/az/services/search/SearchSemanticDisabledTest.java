package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchSemanticDisabledTest.Disabled.class)
@DisplayName("Search data plane: semantic-ranker=disabled refuses semantic queries like a service without them")
class SearchSemanticDisabledTest {

    private static final String API = "?api-version=2025-09-01";
    private static final String SERVICE = "/semdisabled-search";

    public static class Disabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.search.semantic-ranker", "disabled");
        }
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    @Test
    void semanticQueriesAreRefusedAndOthersAnswered() {
        given().post("/_admin/reset").then().statusCode(204);
        admin().body("""
            {"name": "docs", "fields": [{"name": "id", "type": "Edm.String", "key": true},
              {"name": "body", "type": "Edm.String"}],
             "semantic": {"configurations": [{"name": "c",
               "prioritizedFields": {"prioritizedContentFields": [{"fieldName": "body"}]}}]}}
            """).post(SERVICE + "/indexes" + API).then().statusCode(201);
        admin().body("{\"value\": [{\"id\": \"1\", \"body\": \"A rooftop pool.\"}]}")
            .post(SERVICE + "/indexes('docs')/docs/search.index" + API).then().statusCode(200);

        admin().body("{\"search\": \"pool\", \"queryType\": \"semantic\", \"semanticConfiguration\": \"c\"}")
            .post(SERVICE + "/indexes('docs')/docs/search.post.search" + API).then()
            .statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"))
            .body("error.message", equalTo("Semantic search is not enabled for this service.\r\nParameter name: "
                + "queryType"));
        admin().body("{\"search\": \"pool\"}").post(SERVICE + "/indexes('docs')/docs/search.post.search" + API)
            .then().statusCode(200)
            .body("value[0].id", equalTo("1"));
    }
}
