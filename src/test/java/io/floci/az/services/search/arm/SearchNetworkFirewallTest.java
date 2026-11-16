package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.az.services.search.arm.SearchNetworkFixtures.SEARCH_API;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.adminKey;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createService;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createSubnet;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.endpointBody;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.putEndpoint;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.searchService;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The search firewall under {@code strict-network}, with the {@code trust-client-ip-header} test hook on. The tests
 * call from the loopback address, so a subnet over {@code 127.0.0.1/32} (a range of one address reserves none) stands
 * for the subnet of a private endpoint the caller sits in, and the {@code x-floci-az-client-ip} header for a public
 * address.
 */
@QuarkusTest
@TestProfile(SearchNetworkFirewallTest.StrictNetwork.class)
@DisplayName("Search data plane network isolation under strict-network")
class SearchNetworkFirewallTest {

    public static class StrictNetwork implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-network", "true",
                "floci-az.services.search.trust-client-ip-header", "true");
        }
    }

    private static final String SUB = "sub-srch-fw";
    private static final String RG = "rg-fw";
    private static final String NAME = "fw-svc";
    private static final String SERVICE = searchService(SUB, RG, NAME);
    private static final String DATA_API = "?api-version=2024-07-01";
    private static final List<String> LOOPBACK = List.of("127.0.0.1/32", "::1/128");

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification dataPlane(String clientIp) {
        RequestSpecification request = given().header("api-key", adminKey(SERVICE));
        return clientIp == null ? request : request.header("x-floci-az-client-ip", clientIp);
    }

    private static void listIndexes(String clientIp, int status) {
        dataPlane(clientIp).when().get("/" + NAME + "-search/indexes" + DATA_API).then().statusCode(status);
    }

    private static String privateEndpoint(List<String> subnetPrefixes, boolean manual) {
        String subnet = createSubnet(SUB, RG, "vnet-fw", "pe-subnet", subnetPrefixes);
        return putEndpoint(SUB, RG, "pe-fw", endpointBody(subnet, SERVICE, manual));
    }

    private static String onlyConnection() {
        return given().when().get(SERVICE + "/privateEndpointConnections" + SEARCH_API).then().statusCode(200)
            .extract().path("value[0].name");
    }

    private static void decide(String connection, String status) {
        given().contentType("application/json")
            .body("{\"properties\":{\"privateLinkServiceConnectionState\":{\"status\":\"" + status + "\"}}}")
            .when().put(SERVICE + "/privateEndpointConnections/" + connection + SEARCH_API)
            .then().statusCode(200);
    }

    @Test
    void publicNetworkAccessDisabledRefusesAPublicCallerBeforeLookingAtCredentials() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");

        dataPlane(null).when().get("/" + NAME + "-search/indexes" + DATA_API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"))
            .body("error.message", containsString("approved private endpoint"));
        given().when().get("/" + NAME + "-search/indexes" + DATA_API).then().statusCode(403);
    }

    @Test
    void anApprovedPrivateEndpointAdmitsTheCallersOfItsSubnet() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");
        privateEndpoint(LOOPBACK, false);

        listIndexes(null, 200);
        listIndexes("203.0.113.9", 200);
    }

    @Test
    void onlyAnApprovedConnectionAdmits() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");
        privateEndpoint(LOOPBACK, true);
        listIndexes(null, 403);

        String connection = onlyConnection();
        decide(connection, "Approved");
        listIndexes(null, 200);

        decide(connection, "Rejected");
        listIndexes(null, 403);

        decide(connection, "Approved");
        given().when().delete(SERVICE + "/privateEndpointConnections/" + connection + SEARCH_API)
            .then().statusCode(200);
        listIndexes(null, 403);
    }

    @Test
    void aCallerOutsideTheEndpointsSubnetIsPublicWhateverItsHeaderSays() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");
        privateEndpoint(List.of("10.0.1.0/24", "fd00:db8:1::/64"), false);

        listIndexes(null, 403);
        listIndexes("10.0.1.5", 403);
    }

    @Test
    void theSubnetsGatewayAddressIsNoPrivateCaller() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Disabled\"}");
        privateEndpoint(List.of("127.0.0.0/24"), false);

        listIndexes(null, 403);
    }

    @Test
    void ipRulesAdmitTheAddressesTheyContain() {
        createService(SERVICE, "standard", "{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"203.0.113.0/24\"},"
            + "{\"value\":\"198.51.100.7\"}]}}");

        listIndexes("203.0.113.9", 200);
        listIndexes("198.51.100.7", 200);
        listIndexes("198.51.100.8", 403);
        listIndexes(null, 403);
        listIndexes("not-an-address", 403);
    }

    @Test
    void withoutTheHeaderTheRulesSeeTheTransportAddress() {
        createService(SERVICE, "standard", "{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"127.0.0.0/8\"}]}}");

        listIndexes(null, 200);
        listIndexes("203.0.113.9", 403);
    }

    @Test
    void aPrivateCallerIsNotSubjectToTheIpRules() {
        createService(SERVICE, "standard", "{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"203.0.113.0/24\"}]}}");
        privateEndpoint(LOOPBACK, false);

        listIndexes(null, 200);
    }

    @Test
    void aBypassAdmitsNoOneBeyondTheRules() {
        createService(SERVICE, "standard", "{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"203.0.113.0/24\"}],"
            + "\"bypass\":\"AzureServices\"}}");

        listIndexes("198.51.100.8", 403);
        listIndexes("203.0.113.1", 200);
    }

    @Test
    void securedByPerimeterAdmitsOnlyPrivateEndpointsForNoPerimeterIsModelled() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"SecuredByPerimeter\"}");
        listIndexes("203.0.113.9", 403);

        privateEndpoint(LOOPBACK, false);
        listIndexes(null, 200);
    }

    @Test
    void anOpenServiceAdmitsEveryone() {
        createService(SERVICE, "standard", "{\"publicNetworkAccess\":\"Enabled\"}");

        listIndexes(null, 200);
        listIndexes("203.0.113.9", 200);
    }

    @Test
    void aServiceADataPlaneRequestProvisionedIsOpen() {
        given().header("api-key", "any-key").when().get("/fw-dataplane-search/indexes" + DATA_API)
            .then().statusCode(200);
    }
}
