package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.az.services.search.SearchIdentityFixtures.ACL_DOCUMENTS;
import static io.floci.az.services.search.SearchIdentityFixtures.ACL_INDEX;
import static io.floci.az.services.search.SearchIdentityFixtures.ALICE;
import static io.floci.az.services.search.SearchIdentityFixtures.API;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_SCOPE;
import static io.floci.az.services.search.SearchIdentityFixtures.appToken;
import static io.floci.az.services.search.SearchIdentityFixtures.unsignedToken;
import static io.floci.az.services.search.SearchIdentityFixtures.userToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(SearchStrictTokensTest.StrictTokens.class)
@DisplayName("Search data plane: parity-overrides.strict-tokens validates bearer tokens")
class SearchStrictTokensTest {

    public static class StrictTokens implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-tokens", "true");
        }
    }

    private static final String ADMIN = SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY;

    @Inject
    SearchServiceRegistry registry;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aTokenEntraIssuedForSearchIsServed() {
        given().auth().oauth2(appToken(SEARCH_SCOPE)).get("/signed-search/indexes" + API).then().statusCode(200);
        for (String audience : List.of("https://search.azure.us", "https://search.azure.cn", "https://search.azure.com/")) {
            given().auth().oauth2(appToken(audience + "/.default")).get("/signed-search/indexes" + API)
                .then().statusCode(200);
        }
    }

    @Test
    void aTokenForAnotherAudienceIsAnsweredWithTheChallenge() {
        given().auth().oauth2(appToken("https://management.azure.com/.default")).get("/signed-search/indexes" + API)
            .then().statusCode(401)
            .body("error.code", equalTo("Unauthorized"))
            .body("error.message", containsString("https://management.azure.com"))
            .header("WWW-Authenticate", containsString("resource=\"https://search.azure.com\""))
            .header("WWW-Authenticate", containsString("error=\"invalid_token\""));
    }

    @Test
    void anUnsignedTokenIsRefused() {
        given().header("Authorization", "Bearer " + userToken(ALICE)).get("/signed-search/indexes" + API)
            .then().statusCode(401)
            .body("error.message", containsString("signing key"));
        given().header("Authorization", "Bearer not-a-jwt").get("/signed-search/indexes" + API).then().statusCode(401);
    }

    @Test
    void theHttp403ModeAnswersARefusedTokenWithA403() throws JsonProcessingException {
        registry.put(new SearchServiceInfo("strict403", "/subscriptions/s/resourceGroups/r/providers/Microsoft.Search/"
            + "searchServices/strict403", "admin-key", "admin-key-2", List.of(new SearchQueryKey("", "query-key")),
            false, new ObjectMapper().readTree("{\"aadOrApiKey\":{\"aadAuthFailureMode\":\"http403\"}}"), "enabled",
            null, null, "standard"));
        given().auth().oauth2(appToken("https://vault.azure.net/.default")).get("/strict403-search/indexes" + API)
            .then().statusCode(403)
            .header("WWW-Authenticate", nullValue());
        given().auth().oauth2(appToken(SEARCH_SCOPE)).get("/strict403-search/indexes" + API).then().statusCode(200);
        given().header("api-key", "admin-key").get("/strict403-search/indexes" + API).then().statusCode(200);
    }

    @Test
    void theEndUsersTokenIsValidatedToo() {
        given().header("api-key", ADMIN).contentType("application/json").body(ACL_INDEX)
            .post("/signed-search/indexes" + API).then().statusCode(201);
        given().header("api-key", ADMIN).contentType("application/json").body(ACL_DOCUMENTS)
            .post("/signed-search/indexes('acl')/docs/search.index" + API).then().statusCode(200);

        given().header("api-key", ADMIN).header("x-ms-query-source-authorization", userToken(ALICE))
            .contentType("application/json").body("{\"search\":\"*\"}")
            .post("/signed-search/indexes('acl')/docs/search.post.search" + API).then().statusCode(401)
            .body("error.message", containsString("x-ms-query-source-authorization"));
        given().header("api-key", ADMIN)
            .header("x-ms-query-source-authorization", unsignedToken("{\"oid\":\"" + ALICE + "\"}") + "x")
            .get("/signed-search/indexes('acl')/docs/$count" + API).then().statusCode(401);

        // A signed token names the dev application, which no document grants: only the public document is visible.
        given().header("api-key", ADMIN).header("x-ms-query-source-authorization", appToken(SEARCH_SCOPE))
            .contentType("application/json").body("{\"search\":\"*\"}")
            .post("/signed-search/indexes('acl')/docs/search.post.search" + API).then().statusCode(200)
            .body("value.id", containsInAnyOrder("2"));
    }
}
