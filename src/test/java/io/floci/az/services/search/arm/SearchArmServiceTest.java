package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@DisplayName("Microsoft.Search ARM: search services, their defaults, validation and checkNameAvailability")
class SearchArmServiceTest {

    static final String API = "?api-version=2026-11-01";
    static final String STANDARD = "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    static String service(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Search/searchServices/" + name;
    }

    static ExtractableResponse<Response> put(String path, String body, int status) {
        return given().contentType("application/json").body(body).when().put(path + API)
            .then().statusCode(status).extract();
    }

    static ExtractableResponse<Response> patch(String path, String body, int status) {
        return given().contentType("application/json").body(body).when().patch(path + API)
            .then().statusCode(status).extract();
    }

    @Test
    void aCreateAnswersARunningServiceWithTheSpecDefaults() {
        String path = service("sub-srch-create", "rg", "create-svc");
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"tags\":{\"app\":\"shop\"},"
                + "\"properties\":{\"hostingMode\":\"Default\",\"publicNetworkAccess\":\"enabled\"}}")
            .when().put(path + API)
            .then().statusCode(201)
            .header("ETag", startsWith("W/\"datetime'"))
            .body("id", equalTo(path))
            .body("name", equalTo("create-svc"))
            .body("type", equalTo("Microsoft.Search/searchServices"))
            .body("location", equalTo("westus"))
            .body("tags.app", equalTo("shop"))
            .body("sku.name", equalTo("standard"))
            .body("$", not(hasKey("identity")))
            .body("properties.replicaCount", equalTo(1))
            .body("properties.partitionCount", equalTo(1))
            .body("properties.endpoint", equalTo("https://create-svc.search.windows.net"))
            .body("properties.status", equalTo("running"))
            .body("properties.statusDetails", equalTo(""))
            .body("properties.provisioningState", equalTo("succeeded"))
            .body("properties.hostingMode", equalTo("default"))
            .body("properties.computeType", equalTo("Default"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"))
            .body("properties.networkRuleSet.ipRules", hasSize(0))
            .body("properties.networkRuleSet.bypass", equalTo("None"))
            .body("properties.privateEndpointConnections", hasSize(0))
            .body("properties.sharedPrivateLinkResources", hasSize(0))
            .body("properties.encryptionWithCmk.enforcement", equalTo("Unspecified"))
            .body("properties.encryptionWithCmk.encryptionComplianceStatus", equalTo("Compliant"))
            .body("properties.disableLocalAuth", equalTo(false))
            .body("properties.authOptions.apiKeyOnly", notNullValue())
            .body("properties.dataExfiltrationProtections", hasSize(0))
            .body("properties.semanticSearch", equalTo("free"))
            .body("properties.upgradeAvailable", equalTo("notAvailable"))
            .body("properties", hasKey("serviceUpgradedAt"))
            .body("properties.serviceUpgradedAt", nullValue())
            .body("systemData.createdAt", notNullValue());

        ExtractableResponse<Response> read = given().when().get(path + API).then().statusCode(200).extract();
        assertEquals(read.header("ETag"), read.path("properties.eTag"));
        assertEquals("running", read.path("properties.status"));
    }

    @Test
    void aReplaceKeepsTheKeysAndResetsWhatItOmits() {
        String path = service("sub-srch-replace", "rg", "replace-svc");
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"tags\":{\"a\":\"1\"},"
            + "\"properties\":{\"replicaCount\":3,\"semanticSearch\":\"standard\"}}", 201);
        String primary = given().when().post(path + "/listAdminKeys" + API).then().statusCode(200)
            .extract().path("primaryKey");

        given().contentType("application/json").body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},"
                + "\"properties\":{\"partitionCount\":2}}")
            .when().put(path + API)
            .then().statusCode(200)
            .body("properties.replicaCount", equalTo(1))
            .body("properties.partitionCount", equalTo(2))
            .body("properties.semanticSearch", equalTo("free"))
            .body("tags", equalTo(Map.of()));
        given().when().post(path + "/listAdminKeys" + API).then().statusCode(200)
            .body("primaryKey", equalTo(primary));
    }

    @Test
    void aPatchChangesOnlyWhatItNames() {
        String path = service("sub-srch-patch", "rg", "patch-svc");
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"tags\":{\"a\":\"1\"},"
            + "\"properties\":{\"replicaCount\":2,\"networkRuleSet\":{\"bypass\":\"AzureServices\"}}}", 201);
        String before = given().when().get(path + API).then().extract().path("properties.eTag");

        patch(path, "{\"tags\":{\"b\":\"2\"},\"properties\":{\"partitionCount\":3,\"publicNetworkAccess\":\"disabled\","
            + "\"networkRuleSet\":{\"ipRules\":[{\"value\":\"10.1.2.3\"},{\"value\":\"10.2.0.0/16\"}]}}}", 200);

        ExtractableResponse<Response> read = given().when().get(path + API).then().statusCode(200)
            .body("tags.b", equalTo("2"))
            .body("tags", not(hasKey("a")))
            .body("properties.replicaCount", equalTo(2))
            .body("properties.partitionCount", equalTo(3))
            .body("properties.publicNetworkAccess", equalTo("Disabled"))
            .body("properties.networkRuleSet.ipRules.value", equalTo(List.of("10.1.2.3", "10.2.0.0/16")))
            .body("properties.networkRuleSet.bypass", equalTo("AzureServices"))
            .extract();
        assertNotEquals(before, read.path("properties.eTag"));
    }

    @Test
    void disablingLocalAuthClearsTheAuthOptionsAndSettingThemEnablesItAgain() {
        String path = service("sub-srch-auth", "rg", "auth-svc");
        put(path, STANDARD, 201);
        patch(path, "{\"properties\":{\"disableLocalAuth\":true}}", 200);
        given().when().get(path + API).then()
            .body("properties.disableLocalAuth", equalTo(true))
            .body("properties", hasKey("authOptions"))
            .body("properties.authOptions", nullValue());

        patch(path, "{\"properties\":{\"authOptions\":{\"aadOrApiKey\":{\"aadAuthFailureMode\":"
            + "\"http401WithBearerChallenge\"}}}}", 200);
        given().when().get(path + API).then()
            .body("properties.disableLocalAuth", equalTo(false))
            .body("properties.authOptions.aadOrApiKey.aadAuthFailureMode", equalTo("http401WithBearerChallenge"));
    }

    @Test
    void disablingLocalAuthInAReadModifyWriteDropsTheStoredAuthOptions() {
        String path = service("sub-srch-rmw", "rg", "rmw-svc");
        String aad = "\"authOptions\":{\"aadOrApiKey\":{\"aadAuthFailureMode\":\"http403\"}}";
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"properties\":{" + aad + "}}", 201);

        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"properties\":{\"disableLocalAuth\":true,"
                + aad + "}}")
            .when().put(path + API).then().statusCode(200)
            .body("properties.disableLocalAuth", equalTo(true))
            .body("properties.authOptions", nullValue());

        given().contentType("application/json")
            .body("{\"properties\":{\"disableLocalAuth\":true,\"authOptions\":{\"apiKeyOnly\":{}}}}")
            .when().patch(path + API).then().statusCode(400).body("error.code", equalTo("BadRequest"));
    }

    @Test
    void servicesAreListedByResourceGroupAndBySubscription() {
        put(service("sub-srch-list", "rg-one", "list-one"), STANDARD, 201);
        put(service("sub-srch-list", "rg-one", "list-two"), STANDARD, 201);
        put(service("sub-srch-list", "rg-two", "list-three"), STANDARD, 201);
        put(service("sub-srch-other", "rg-one", "list-four"), STANDARD, 201);

        given().when().get("/subscriptions/sub-srch-list/resourceGroups/RG-ONE/providers/Microsoft.Search/searchServices"
                + API)
            .then().statusCode(200)
            .body("value", hasSize(2))
            .body("value.name", equalTo(List.of("list-one", "list-two")))
            .body("value[0].properties.endpoint", equalTo("https://list-one.search.windows.net"));
        given().when().get("/subscriptions/sub-srch-list/providers/Microsoft.Search/searchServices" + API)
            .then().statusCode(200).body("value", hasSize(3));
    }

    @Test
    void aDeleteRemovesTheServiceAndADeleteOfNothingIsNoContent() {
        String path = service("sub-srch-del", "rg-del", "del-svc");
        put(path, STANDARD, 201);
        given().when().delete(path + API).then().statusCode(200);
        given().when().delete(path + API).then().statusCode(204);
        given().when().get(path + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", equalTo("The Resource 'Microsoft.Search/searchServices/del-svc' under resource "
                + "group 'rg-del' was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix"));
        patch(path, "{\"tags\":{}}", 404);
    }

    @Test
    void aPathInAnotherCasingAddressesTheOneService() {
        String path = service("sub-srch-case", "RG-Case", "case-svc");
        put(path, STANDARD, 201);
        given().contentType("application/json").body(STANDARD)
            .when().put("/subscriptions/SUB-SRCH-CASE/resourcegroups/rg-case/providers/microsoft.search/"
                + "SEARCHSERVICES/case-svc" + API)
            .then().statusCode(200)
            .body("id", equalTo(path));
        given().when().get("/subscriptions/sub-srch-case/providers/Microsoft.Search/searchServices" + API)
            .then().body("value", hasSize(1));
    }

    @Test
    void aServerlessServiceHasNoReplicasPartitionsOrComputeType() {
        put(service("sub-srch-sls", "rg", "sls-svc"), "{\"location\":\"westus\",\"sku\":{\"name\":\"serverless\"},"
            + "\"properties\":{\"hostingMode\":\"Default\",\"replicaCount\":1}}", 201);
        given().when().get(service("sub-srch-sls", "rg", "sls-svc") + API).then().statusCode(200)
            .body("sku.name", equalTo("serverless"))
            .body("properties", not(hasKey("replicaCount")))
            .body("properties", not(hasKey("partitionCount")))
            .body("properties", not(hasKey("computeType")));
    }

    @Test
    void aServiceNameBelongsToItsFirstOwner() {
        put(service("sub-srch-a", "rg", "owned-svc"), STANDARD, 201);
        given().contentType("application/json").body(STANDARD)
            .when().put(service("sub-srch-b", "rg", "owned-svc") + API)
            .then().statusCode(409)
            .body("error.code", equalTo("Conflict"))
            .body("error.message", equalTo("The search service name 'owned-svc' is already in use."));
        given().when().get(service("sub-srch-b", "rg", "owned-svc") + API).then().statusCode(404);
    }

    @Test
    void systemDataNamesTheCallersOfTheCreateAndTheLastChange() {
        String path = service("sub-srch-audit", "rg", "audit-svc");
        given().contentType("application/json").header("Authorization", bearer("{\"upn\":\"dev@contoso.com\","
                + "\"idtyp\":\"user\",\"oid\":\"0b3c8e2a-0000-0000-0000-000000000001\"}"))
            .body(STANDARD).when().put(path + API).then().statusCode(201)
            .body("systemData.createdBy", equalTo("dev@contoso.com"))
            .body("systemData.createdByType", equalTo("User"))
            .body("systemData.lastModifiedBy", equalTo("dev@contoso.com"));

        given().contentType("application/json").header("Authorization", bearer("{\"appid\":"
                + "\"5f1d7e9c-0000-0000-0000-000000000002\",\"idtyp\":\"app\"}"))
            .body("{\"tags\":{\"k\":\"v\"}}").when().patch(path + API).then().statusCode(200)
            .body("systemData.createdBy", equalTo("dev@contoso.com"))
            .body("systemData.lastModifiedBy", equalTo("5f1d7e9c-0000-0000-0000-000000000002"))
            .body("systemData.lastModifiedByType", equalTo("Application"));
    }

    /** An unsigned token carrying {@code claims}: ARM reads the caller from it while strict-tokens is off. */
    private static String bearer(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
            + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    void aSystemAssignedIdentityKeepsItsPrincipalUntilItIsRemoved() {
        String path = service("sub-srch-mi", "rg", "mi-svc");
        String principal = put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},"
            + "\"identity\":{\"type\":\"SystemAssigned\"}}", 201).path("identity.principalId");
        given().when().get(path + API).then()
            .body("identity.type", equalTo("SystemAssigned"))
            .body("identity.tenantId", notNullValue());

        given().contentType("application/json").body("{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"}}")
            .when().put(path + API).then().statusCode(200).body("identity.principalId", equalTo(principal));

        patch(path, "{\"identity\":{\"type\":\"None\"}}", 200);
        given().when().get(path + API).then().body("$", not(hasKey("identity")));

        String again = patch(path, "{\"identity\":{\"type\":\"SystemAssigned\"}}", 200).path("identity.principalId");
        assertNotEquals(principal, again);
    }

    @Test
    void aUserAssignedIdentityCarriesTheIdsOfTheManagedIdentity() {
        String identity = "/subscriptions/sub-srch-uai/resourceGroups/rg/providers/Microsoft.ManagedIdentity/"
            + "userAssignedIdentities/search-mi";
        ExtractableResponse<Response> created = given().contentType("application/json").body("{\"location\":\"westus\"}")
            .when().put(identity + "?api-version=2023-01-31").then().statusCode(201).extract();

        String path = service("sub-srch-uai", "rg", "uai-svc");
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"identity\":{\"type\":"
                + "\"SystemAssigned, UserAssigned\",\"userAssignedIdentities\":{\"" + identity + "\":{}}}}")
            .when().put(path + API)
            .then().statusCode(201)
            .body("identity.type", equalTo("SystemAssigned, UserAssigned"))
            .body("identity.principalId", notNullValue())
            .body("identity.userAssignedIdentities.'" + identity + "'.principalId",
                equalTo(created.path("properties.principalId")))
            .body("identity.userAssignedIdentities.'" + identity + "'.clientId",
                equalTo(created.path("properties.clientId")));
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"location\":\"westus\"}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"premium\"}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"properties\":{\"replicaCount\":4}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"properties\":{\"partitionCount\":4}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"partitionCount\":5}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"replicaCount\":13}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"replicaCount\":0}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"replicaCount\":12,"
            + "\"partitionCount\":6}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"hostingMode\":\"highDensity\"}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard3\"},\"properties\":{\"hostingMode\":\"highDensity\","
            + "\"partitionCount\":4}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"free\"},\"properties\":{\"replicaCount\":2}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"free\"},\"properties\":{\"networkRuleSet\":{\"ipRules\":"
            + "[{\"value\":\"1.2.3.4\"}]}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"free\"},\"identity\":{\"type\":\"SystemAssigned\"}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"free\"},\"properties\":{\"semanticSearch\":\"standard\"}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"free\"},\"properties\":{\"encryptionWithCmk\":"
            + "{\"enforcement\":\"Enabled\"}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"serverless\"},\"properties\":{\"partitionCount\":2}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"disableLocalAuth\":true,"
            + "\"authOptions\":{\"apiKeyOnly\":{}}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"authOptions\":{\"apiKeyOnly\":{},"
            + "\"aadOrApiKey\":{}}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"authOptions\":{\"aadOrApiKey\":"
            + "{\"aadAuthFailureMode\":\"http500\"}}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"networkRuleSet\":{\"ipRules\":"
            + "[{\"value\":\"1.2.3.256\"}]}}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"publicNetworkAccess\":\"open\"}}",
        "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"identity\":{\"type\":\"Shared\"}}"
    })
    void aCreateOutsideTheRulesIsRefused(String body) {
        given().contentType("application/json").body(body)
            .when().put(service("sub-srch-bad", "rg", "bad-svc") + API)
            .then().statusCode(400)
            .body("error.code", equalTo("BadRequest"));
        given().when().get(service("sub-srch-bad", "rg", "bad-svc") + API).then().statusCode(404);
    }

    @Test
    void aValueOfTheWrongTypeIsInvalidContent() {
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":{\"replicaCount\":\"two\"}}")
            .when().put(service("sub-srch-type", "rg", "type-svc") + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        given().contentType("application/json").body("{not json")
            .when().put(service("sub-srch-type", "rg", "type-svc") + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    void theLargestScaleOfEachTierIsAccepted() {
        put(service("sub-srch-scale", "rg", "basic-max"), "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},"
            + "\"properties\":{\"replicaCount\":3,\"partitionCount\":3}}", 201);
        put(service("sub-srch-scale", "rg", "s1-max"), "{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},"
            + "\"properties\":{\"replicaCount\":3,\"partitionCount\":12}}", 201);
        put(service("sub-srch-scale", "rg", "s3hd-max"), "{\"location\":\"westus\",\"sku\":{\"name\":\"standard3\"},"
            + "\"properties\":{\"hostingMode\":\"HighDensity\",\"replicaCount\":12,\"partitionCount\":3}}", 201);
        given().when().get(service("sub-srch-scale", "rg", "s3hd-max") + API).then()
            .body("properties.hostingMode", equalTo("highDensity"));
    }

    @Test
    void theTierChangesOnlyBetweenBasicAndTheStandardTiers() {
        String path = service("sub-srch-tier", "rg", "tier-svc");
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"}}", 201);
        patch(path, "{\"sku\":{\"name\":\"standard2\"}}", 200);
        given().when().get(path + API).then().body("sku.name", equalTo("standard2"));

        given().contentType("application/json").body("{\"sku\":{\"name\":\"storage_optimized_l1\"}}")
            .when().patch(path + API).then().statusCode(400).body("error.code", equalTo("BadRequest"));
        given().contentType("application/json").body("{\"sku\":{\"name\":\"free\"}}")
            .when().patch(path + API).then().statusCode(400);
        given().contentType("application/json").body("{\"properties\":{\"hostingMode\":\"highDensity\"}}")
            .when().patch(path + API).then().statusCode(400);
        given().contentType("application/json").body("{\"sku\":{\"name\":\"basic\"},\"properties\":{\"replicaCount\":6}}")
            .when().patch(path + API).then().statusCode(400);
        given().when().get(path + API).then().body("sku.name", equalTo("standard2"));
    }

    // ── checkNameAvailability ───────────────────────────────────────────────

    @Test
    void checkNameAvailabilityAnswersAvailableTakenAndInvalid() {
        String check = "/subscriptions/sub-srch-check/providers/Microsoft.Search/checkNameAvailability" + API;
        given().contentType("application/json").body("{\"name\":\"free-name\",\"type\":\"searchServices\"}")
            .when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));

        put(service("sub-srch-elsewhere", "rg", "taken-name"), STANDARD, 201);
        given().contentType("application/json").body("{\"name\":\"taken-name\",\"type\":\"searchServices\"}")
            .when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo(""));

        given().contentType("application/json").body("{\"name\":\"Bad--Name\",\"type\":\"searchServices\"}")
            .when().post("/subscriptions/sub-srch-check/providers/microsoft.search/CHECKNAMEAVAILABILITY" + API)
            .then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("Invalid"))
            .body("message", containsString("Bad--Name"));

        given().contentType("application/json").body("{\"name\":\"ok-name\",\"type\":\"vaults\"}")
            .when().post(check).then().statusCode(400);
        given().contentType("application/json").body("{\"name\":{\"name\":\"ok-name\"},\"type\":\"searchServices\"}")
            .when().post(check).then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a-b", "-ab", "ab-", "ab--cd", "Upper", "under_score",
        "a234567890123456789012345678901234567890123456789012345678901"})
    void checkNameAvailabilityCallsANameOutsideTheRuleInvalid(String name) {
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"type\":\"searchServices\"}")
            .when().post("/subscriptions/sub-srch-check/providers/Microsoft.Search/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("Invalid"));
    }

    @Test
    void unservedPathsUnderAServiceAreNotFound() {
        String path = service("sub-srch-child", "rg", "child-svc");
        put(path, STANDARD, 201);
        given().when().get(path + "/upgrade" + API).then().statusCode(404);
        given().when().get(path + "/listAdminKeys" + API).then().statusCode(405);
        given().when().get("/subscriptions/sub-srch-child/providers/Microsoft.Search/locations/westus/usages" + API)
            .then().statusCode(404);
    }
}
