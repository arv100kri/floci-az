package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.floci.az.services.search.arm.SearchNetworkFixtures.adminKey;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createService;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.searchService;
import static io.restassured.RestAssured.given;

@QuarkusTest
@DisplayName("Search data plane network settings without strict-network: stored, not enforced")
class SearchNetworkFirewallOffTest {

    private static final String DATA_API = "?api-version=2024-07-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void publicNetworkAccessDisabledIsNotEnforced() {
        String service = searchService("sub-srch-fw-off", "rg", "fw-off-disabled");
        createService(service, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");

        given().header("api-key", adminKey(service)).when().get("/fw-off-disabled-search/indexes" + DATA_API)
            .then().statusCode(200);
    }

    @Test
    void ipRulesAreNotEnforcedAndTheClientIpHeaderIsIgnored() {
        String service = searchService("sub-srch-fw-off", "rg", "fw-off-rules");
        createService(service, "standard", "{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"203.0.113.0/24\"}]}}");

        given().header("api-key", adminKey(service)).header("x-floci-az-client-ip", "198.51.100.8")
            .when().get("/fw-off-rules-search/indexes" + DATA_API)
            .then().statusCode(200);
    }
}
