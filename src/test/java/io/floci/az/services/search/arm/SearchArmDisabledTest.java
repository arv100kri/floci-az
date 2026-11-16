package io.floci.az.services.search.arm;

import io.floci.az.services.search.SearchServiceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.az.services.search.arm.SearchArmServiceTest.API;
import static io.floci.az.services.search.arm.SearchArmServiceTest.STANDARD;
import static io.floci.az.services.search.arm.SearchArmServiceTest.service;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SearchArmDisabledTest.Disabled.class)
@DisplayName("Microsoft.Search ARM: floci-az.services.search.enabled=false answers the namespace like any unserved one")
class SearchArmDisabledTest {

    public static class Disabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.search.enabled", "false");
        }
    }

    @Inject
    SearchServiceRegistry registry;

    @Test
    void everySearchPathIsNotFound() {
        String path = service("sub-srch-off", "rg-off", "off-svc");
        given().contentType("application/json").body(STANDARD).when().put(path + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
        given().when().get(path + API).then().statusCode(404);
        given().when().post(path + "/listAdminKeys" + API).then().statusCode(404);
        given().when().get("/subscriptions/sub-srch-off/providers/Microsoft.Search/searchServices" + API)
            .then().statusCode(404);
        given().contentType("application/json").body("{\"name\":\"off-svc\",\"type\":\"searchServices\"}")
            .when().post("/subscriptions/sub-srch-off/providers/Microsoft.Search/checkNameAvailability" + API)
            .then().statusCode(404);
        assertTrue(registry.find("off-svc").isEmpty());
    }

    @Test
    void theRestOfArmKeepsWorking() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-srch-off/resourceGroups/rg-off" + API)
            .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }
}
