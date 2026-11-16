package io.floci.az.services.search;

import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@DisplayName("Search data plane: API keys")
class SearchAuthTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/auth-search";
    private static final String INDEX = "{\"name\":\"docs\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\","
        + "\"key\":true},{\"name\":\"text\",\"type\":\"Edm.String\"}]}";
    private static final String BATCH = "{\"value\":[{\"@search.action\":\"upload\",\"id\":\"1\",\"text\":\"hi\"}]}";

    @Inject
    SearchServiceRegistry registry;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json")
            .body(INDEX).post(BASE + "/indexes" + API).then().statusCode(201);
    }

    @Test
    void bothAdminKeysMayDoEverything() {
        for (String key : List.of(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY,
                SearchServiceRegistry.DEV_SECONDARY_ADMIN_KEY)) {
            given().header("api-key", key).contentType("application/json").body(BATCH)
                .post(BASE + "/indexes('docs')/docs/search.index" + API).then().statusCode(200);
            given().header("api-key", key).get(BASE + "/indexes('docs')" + API).then().statusCode(200);
        }
    }

    @Test
    void aQueryKeyReadsDocumentsOnly() {
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json")
            .body(BATCH).post(BASE + "/indexes('docs')/docs/search.index" + API).then().statusCode(200);
        String query = SearchServiceRegistry.DEV_QUERY_KEY;
        given().header("api-key", query).contentType("application/json").body("{\"search\":\"hi\"}")
            .post(BASE + "/indexes('docs')/docs/search.post.search" + API).then().statusCode(200);
        given().header("api-key", query).get(BASE + "/indexes('docs')/docs?search=*&api-version=2024-07-01")
            .then().statusCode(200);
        given().header("api-key", query).get(BASE + "/indexes('docs')/docs('1')" + API).then().statusCode(200);
        given().header("api-key", query).get(BASE + "/indexes('docs')/docs/$count" + API).then().statusCode(200);

        given().header("api-key", query).contentType("application/json").body(BATCH)
            .post(BASE + "/indexes('docs')/docs/search.index" + API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"));
        given().header("api-key", query).get(BASE + "/indexes('docs')" + API).then().statusCode(403);
        given().header("api-key", query).get(BASE + "/indexes" + API).then().statusCode(403);
        given().header("api-key", query).get(BASE + "/servicestats" + API).then().statusCode(403);
        given().header("api-key", query).delete(BASE + "/indexes('docs')" + API).then().statusCode(403);
    }

    @Test
    void anUnknownKeyActsAsAnAdminKeyOnAServiceADataPlaneRequestProvisioned() {
        given().header("api-key", "not-a-key-of-this-service").get(BASE + "/indexes" + API).then().statusCode(200);
    }

    @Test
    void aRequestWithoutCredentialsIs401() {
        given().get(BASE + "/indexes" + API).then().statusCode(401)
            .body("error.code", equalTo("Unauthorized"));
    }

    @Test
    void aBearerTokenIsAccepted() {
        given().header("Authorization", "Bearer eyJ0eXAiOiJKV1QifQ.e30.c2ln").get(BASE + "/indexes" + API)
            .then().statusCode(200);
    }

    @Test
    void anApiKeyWinsOverABearerToken() {
        given().header("Authorization", "Bearer eyJ0eXAiOiJKV1QifQ.e30.c2ln")
            .header("api-key", SearchServiceRegistry.DEV_QUERY_KEY)
            .get(BASE + "/indexes" + API).then().statusCode(403);
    }

    @Test
    void theKeysOfARegisteredServiceAreItsOwn() {
        registry.put(new SearchServiceInfo("armsvc", "/subscriptions/s/resourceGroups/r/providers/Microsoft.Search/"
            + "searchServices/armsvc", "primary-admin", "secondary-admin", List.of(new SearchQueryKey("q", "query-1")),
            false, null, "enabled", null, null, "standard"));
        given().header("api-key", "primary-admin").contentType("application/json").body(INDEX)
            .post("/armsvc-search/indexes" + API).then().statusCode(201);
        given().header("api-key", "query-1").get("/armsvc-search/indexes" + API).then().statusCode(403);
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).get("/armsvc-search/indexes" + API)
            .then().statusCode(403).body("error.code", equalTo("Forbidden"));
        given().header("api-key", "not-a-key-of-this-service").contentType("application/json").body("{}")
            .post("/armsvc-search/indexes('docs')/docs/search.post.search" + API).then().statusCode(403);
    }

    @Test
    void apiKeysAreRefusedWhenLocalAuthIsDisabled() {
        registry.put(new SearchServiceInfo("aadonly", null, "primary-admin", "secondary-admin", List.of(), true, null,
            "enabled", null, null, "standard"));
        given().header("api-key", "primary-admin").get("/aadonly-search/indexes" + API).then().statusCode(403);
        given().header("Authorization", "Bearer eyJ0eXAiOiJKV1QifQ.e30.c2ln").get("/aadonly-search/indexes" + API)
            .then().statusCode(200);
    }
}
