package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.az.services.search.arm.SearchArmServiceTest.API;
import static io.floci.az.services.search.arm.SearchArmServiceTest.service;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchArmSovereignCloudTest.GovernmentCloud.class)
@DisplayName("Microsoft.Search ARM: a service's endpoint and default location follow its subscription's cloud")
class SearchArmSovereignCloudTest {

    public static class GovernmentCloud implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.cloud.default", "AzureUSGovernment");
        }
    }

    @Test
    void aServiceInTheGovernmentCloudAnswersOnItsSearchSuffix() {
        String path = service("sub-srch-gov", "rg", "gov-svc");
        given().contentType("application/json").body("{\"sku\":{\"name\":\"basic\"}}").when().put(path + API)
            .then().statusCode(201)
            .body("location", equalTo("usgovvirginia"))
            .body("properties.endpoint", equalTo("https://gov-svc.search.azure.us"));
    }
}
