package io.floci.az.services.search;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchStrictParityTest.Strict.class)
@DisplayName("Search data plane under parity: strict")
class SearchStrictParityTest {

    public static class Strict implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity", "strict");
        }
    }

    private static final String API = "?api-version=2024-07-01";

    @Inject
    SearchServiceRegistry registry;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        registry.put(new SearchServiceInfo("strictsvc", "/subscriptions/s/resourceGroups/r/providers/Microsoft.Search/"
            + "searchServices/strictsvc", "admin-key", "admin-key-2", List.of(new SearchQueryKey("", "query-key")),
            false, null, "enabled", null, null, "standard"));
    }

    @Test
    void anUnknownKeyIsRefused() {
        given().header("api-key", "some-other-key").get("/strictsvc-search/indexes" + API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"));
    }

    @Test
    void theServicesOwnKeysWork() {
        given().header("api-key", "admin-key").get("/strictsvc-search/indexes" + API).then().statusCode(200);
        given().header("api-key", "query-key").get("/strictsvc-search/indexes" + API).then().statusCode(403);
    }

    @Test
    void aServiceThatWasNeverCreatedIsNotProvisioned() {
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).get("/nosuchsvc-search/indexes" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ServiceNotFound"));
    }
}
