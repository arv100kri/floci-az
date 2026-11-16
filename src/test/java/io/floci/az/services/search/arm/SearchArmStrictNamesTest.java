package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.floci.az.services.search.arm.SearchArmServiceTest.API;
import static io.floci.az.services.search.arm.SearchArmServiceTest.STANDARD;
import static io.floci.az.services.search.arm.SearchArmServiceTest.service;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchArmStrictNamesTest.StrictNames.class)
@DisplayName("Microsoft.Search ARM: parity-overrides.strict-names refuses a service name outside Azure's rule")
class SearchArmStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a-b", "-ab", "ab-", "ab--cd", "Upper-Case", "under_score",
        "a234567890123456789012345678901234567890123456789012345678901"})
    void aServiceNameOutsideTheRuleIsRefused(String name) {
        given().contentType("application/json").body(STANDARD)
            .when().put(service("sub-srch-names", "rg", name) + API)
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceName"));
    }

    @Test
    void namesInsideTheRuleAreCreated() {
        for (String name : new String[] {"ab", "a1-b2-c3", "123",
            "a23456789012345678901234567890123456789012345678901234567890"}) {
            given().contentType("application/json").body(STANDARD)
                .when().put(service("sub-srch-names", "rg", name) + API)
                .then().statusCode(201);
        }
    }
}
