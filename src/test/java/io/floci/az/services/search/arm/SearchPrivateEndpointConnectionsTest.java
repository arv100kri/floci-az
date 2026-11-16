package io.floci.az.services.search.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.az.services.search.arm.SearchNetworkFixtures.NETWORK_API;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.SEARCH_API;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createService;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createSubnet;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.endpointBody;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.get;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.network;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.putEndpoint;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.searchService;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Microsoft.Search ARM: private endpoint connections, private link resources and NSP configurations")
class SearchPrivateEndpointConnectionsTest {

    private static final String SUB = "sub-srch-pec";
    private static final String RG = "rg-pec";
    private static final String SERVICE = searchService(SUB, RG, "pec-svc");
    private static final String CONNECTIONS = SERVICE + "/privateEndpointConnections";
    private static final String MANUAL_STATE =
        "properties.manualPrivateLinkServiceConnections[0].properties.privateLinkServiceConnectionState";
    private static final String AUTO_STATE =
        "properties.privateLinkServiceConnections[0].properties.privateLinkServiceConnectionState";

    private String subnet;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createService(SERVICE, "standard", null);
        subnet = createSubnet(SUB, RG, "vnet1", "pe-subnet", List.of("10.0.1.0/24"));
    }

    private static String onlyConnectionName() {
        return get(CONNECTIONS, 200).path("value[0].name");
    }

    private static ExtractableResponse<Response> endpoint(String path) {
        return given().when().get(path + NETWORK_API).then().statusCode(200).extract();
    }

    private static ExtractableResponse<Response> putConnection(String name, String state, int status) {
        return given().contentType("application/json")
            .body("{\"properties\":{\"privateLinkServiceConnectionState\":" + state + "}}")
            .when().put(CONNECTIONS + "/" + name + SEARCH_API)
            .then().statusCode(status).extract();
    }

    @Test
    void aPrivateEndpointToTheServiceIsAnApprovedConnection() {
        String endpoint = putEndpoint(SUB, RG, "pe1", endpointBody(subnet, SERVICE, false));

        String name = onlyConnectionName();
        given().when().get(CONNECTIONS + SEARCH_API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", matchesPattern("pe1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            .body("value[0].id", equalTo(CONNECTIONS + "/" + name))
            .body("value[0].type", equalTo("Microsoft.Search/searchServices/privateEndpointConnections"))
            .body("value[0].properties.privateEndpoint.id", equalTo(endpoint))
            .body("value[0].properties.privateLinkServiceConnectionState.status", equalTo("Approved"))
            .body("value[0].properties.privateLinkServiceConnectionState.actionsRequired", equalTo("None"))
            .body("value[0].properties.groupId", equalTo("searchService"))
            .body("value[0].properties.provisioningState", equalTo("Succeeded"));

        given().when().get(CONNECTIONS + "/" + name.toUpperCase() + SEARCH_API).then().statusCode(200)
            .body("name", equalTo(name))
            .body("properties.privateLinkServiceConnectionState.status", equalTo("Approved"));
        given().when().get(SERVICE + SEARCH_API).then().statusCode(200)
            .body("properties.privateEndpointConnections", hasSize(1))
            .body("properties.privateEndpointConnections[0].name", equalTo(name));
        assertEquals(name, onlyConnectionName(), "the name is stable from one read to the next");
    }

    @Test
    void aManualConnectionIsPendingUntilTheServiceApprovesItAndTheEndpointShowsTheApproval() {
        String endpoint = putEndpoint(SUB, RG, "pe-manual", endpointBody(subnet, SERVICE, true));
        String name = onlyConnectionName();
        given().when().get(CONNECTIONS + "/" + name + SEARCH_API).then()
            .body("properties.privateLinkServiceConnectionState.status", equalTo("Pending"));

        putConnection(name, "{\"status\":\"approved\",\"description\":\"Approved by the search admin\"}", 200);

        given().when().get(CONNECTIONS + "/" + name + SEARCH_API).then().statusCode(200)
            .body("properties.privateLinkServiceConnectionState.status", equalTo("Approved"))
            .body("properties.privateLinkServiceConnectionState.description", equalTo("Approved by the search admin"))
            .body("properties.privateLinkServiceConnectionState.actionsRequired", equalTo("None"));
        given().when().get(endpoint + NETWORK_API).then().statusCode(200)
            .body(MANUAL_STATE + ".status", equalTo("Approved"))
            .body(MANUAL_STATE + ".description", equalTo("Approved by the search admin"));

        putEndpoint(SUB, RG, "pe-manual", endpointBody(subnet, SERVICE, true));
        assertEquals("Approved", endpoint(endpoint).path(MANUAL_STATE + ".status"),
            "the endpoint's own PUT keeps the approval the service recorded");
        given().when().get(CONNECTIONS + "/" + name + SEARCH_API).then()
            .body("properties.privateLinkServiceConnectionState.status", equalTo("Approved"));
    }

    @Test
    void aRejectionOnTheServiceShowsOnTheEndpointAndSurvivesItsPut() {
        String endpoint = putEndpoint(SUB, RG, "pe-reject", endpointBody(subnet, SERVICE, false));
        String name = onlyConnectionName();

        assertEquals("Rejected", putConnection(name,
            "{\"status\":\"Rejected\",\"description\":\"Rejected for some reason.\"}", 200)
            .path("properties.privateLinkServiceConnectionState.status"));

        assertEquals("Rejected", endpoint(endpoint).path(AUTO_STATE + ".status"));
        putEndpoint(SUB, RG, "pe-reject", endpointBody(subnet, SERVICE, false));
        assertEquals("Rejected", endpoint(endpoint).path(AUTO_STATE + ".status"));
        assertEquals("Rejected", get(CONNECTIONS + "/" + name, 200)
            .path("properties.privateLinkServiceConnectionState.status"));
    }

    @Test
    void aNewManualConnectionToTheServiceStartsPendingWhateverTheBodySays() {
        String body = endpointBody(subnet, SERVICE, true).replace("\"requestMessage\":\"please approve\"",
            "\"privateLinkServiceConnectionState\":{\"status\":\"Approved\"}");
        String endpoint = putEndpoint(SUB, RG, "pe-self-approved", body);

        assertEquals("Pending", endpoint(endpoint).path(MANUAL_STATE + ".status"));
    }

    @Test
    void deletingAConnectionDisconnectsTheEndpointAndRemovesTheConnection() {
        String endpoint = putEndpoint(SUB, RG, "pe-delete", endpointBody(subnet, SERVICE, false));
        String name = onlyConnectionName();

        given().when().delete(CONNECTIONS + "/" + name + SEARCH_API).then().statusCode(200)
            .body("name", equalTo(name))
            .body("properties.privateLinkServiceConnectionState.status", equalTo("Disconnected"));

        get(CONNECTIONS + "/" + name, 404);
        given().when().get(CONNECTIONS + SEARCH_API).then().statusCode(200).body("value", hasSize(0));
        assertEquals("Disconnected", endpoint(endpoint).path(AUTO_STATE + ".status"),
            "the endpoint stays, informative, as Private Link leaves it");
        given().when().delete(CONNECTIONS + "/" + name + SEARCH_API).then().statusCode(404);
    }

    @Test
    void deletingTheEndpointRemovesTheConnection() {
        String endpoint = putEndpoint(SUB, RG, "pe-gone", endpointBody(subnet, SERVICE, false));
        given().when().get(CONNECTIONS + SEARCH_API).then().body("value", hasSize(1));

        given().when().delete(endpoint + NETWORK_API).then().statusCode(200);

        given().when().get(CONNECTIONS + SEARCH_API).then().statusCode(200).body("value", hasSize(0));
        given().when().get(SERVICE + SEARCH_API).then().body("properties.privateEndpointConnections", hasSize(0));
    }

    @Test
    void deletingTheServiceDisconnectsItsEndpointsAndANewServiceOfTheNameHasNone() {
        String endpoint = putEndpoint(SUB, RG, "pe-orphan", endpointBody(subnet, SERVICE, false));

        given().when().delete(SERVICE + SEARCH_API).then().statusCode(200);

        assertEquals("Disconnected", endpoint(endpoint).path(AUTO_STATE + ".status"));
        createService(SERVICE, "standard", null);
        given().when().get(CONNECTIONS + SEARCH_API).then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void connectionsToOtherServicesAndOtherTargetsAreNotTheServices() {
        String other = searchService(SUB, RG, "pec-other");
        createService(other, "standard", null);
        String storage = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.Storage/storageAccounts/pecstorage";
        putEndpoint(SUB, RG, "pe-other", endpointBody(subnet, other, false));
        String toStorage = putEndpoint(SUB, RG, "pe-storage", endpointBody(subnet, storage, true)
            .replace("\"requestMessage\":\"please approve\"",
                "\"privateLinkServiceConnectionState\":{\"status\":\"Approved\",\"description\":\"as sent\"}"));

        given().when().get(CONNECTIONS + SEARCH_API).then().body("value", hasSize(0));
        given().when().get(other + "/privateEndpointConnections" + SEARCH_API).then().body("value", hasSize(1));
        assertEquals("Approved", endpoint(toStorage).path(MANUAL_STATE + ".status"),
            "a connection to a target no service owns keeps the state its body sends, as before");
        assertEquals("as sent", endpoint(toStorage).path(MANUAL_STATE + ".description"));
    }

    @Test
    void anUnknownConnectionOrStatusIsRefused() {
        putEndpoint(SUB, RG, "pe-bad", endpointBody(subnet, SERVICE, true));
        String name = onlyConnectionName();

        assertEquals("ResourceNotFound",
            putConnection("nosuch.connection", "{\"status\":\"Approved\"}", 404).path("error.code"));
        given().when().delete(CONNECTIONS + "/nosuch.connection" + SEARCH_API).then().statusCode(404);
        putConnection(name, "{\"status\":\"Accepted\"}", 400);
        putConnection(name, "{\"status\":7}", 400);
        given().contentType("application/json").body("{\"properties\":[]}")
            .when().put(CONNECTIONS + "/" + name + SEARCH_API).then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
        given().when().patch(CONNECTIONS + "/" + name + SEARCH_API).then().statusCode(405);
        assertEquals("Pending", get(CONNECTIONS + "/" + name, 200)
            .path("properties.privateLinkServiceConnectionState.status"));
    }

    @Test
    void theChildrenOfAServiceThatDoesNotExistAreNotFound() {
        String missing = searchService(SUB, RG, "no-such-svc");
        for (String child : List.of("privateEndpointConnections", "privateLinkResources",
                "sharedPrivateLinkResources", "networkSecurityPerimeterConfigurations")) {
            given().when().get(missing + "/" + child + SEARCH_API).then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
        }
    }

    @Test
    void theServicesPrivateLinkResourceIsSearchServiceInItsCloudsZone() {
        given().when().get(SERVICE + "/privateLinkResources" + SEARCH_API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].id", equalTo(SERVICE + "/privateLinkResources/searchService"))
            .body("value[0].name", equalTo("searchService"))
            .body("value[0].type", equalTo("Microsoft.Search/searchServices/privateLinkResources"))
            .body("value[0].properties.groupId", equalTo("searchService"))
            .body("value[0].properties.requiredMembers", equalTo(List.of("searchService")))
            .body("value[0].properties.requiredZoneNames", equalTo(List.of("privatelink.search.windows.net")))
            .body("value[0].properties.shareablePrivateLinkResourceTypes", hasSize(7))
            .body("value[0].properties.shareablePrivateLinkResourceTypes[0].name", equalTo("blob"))
            .body("value[0].properties.shareablePrivateLinkResourceTypes[0].properties.type",
                equalTo("Microsoft.Storage/storageAccounts"))
            .body("value[0].properties.shareablePrivateLinkResourceTypes[2].properties.groupId", equalTo("Sql"));
        given().when().get(SERVICE + "/privateLinkResources/searchService" + SEARCH_API).then().statusCode(404);
        given().when().post(SERVICE + "/privateLinkResources" + SEARCH_API).then().statusCode(405);
    }

    @Test
    void aServiceIsInNoNetworkSecurityPerimeter() {
        String configurations = SERVICE + "/networkSecurityPerimeterConfigurations";
        given().when().get(configurations + SEARCH_API).then().statusCode(200)
            .body("value", hasSize(0))
            .body("nextLink", nullValue());
        String name = "00000001-2222-3333-4444-111144444444.assoc1";
        given().when().get(configurations + "/" + name + SEARCH_API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        given().when().post(configurations + "/" + name + "/reconcile" + SEARCH_API).then().statusCode(404);
        given().when().delete(configurations + "/" + name + SEARCH_API).then().statusCode(405);
    }

    @Test
    void theNetworkGetOfTheEndpointStillAnswersItsOwnShape() {
        String endpoint = putEndpoint(SUB, RG, "pe-shape", endpointBody(subnet, SERVICE, false));
        given().when().get(network(SUB, RG) + "/privateEndpoints" + NETWORK_API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].id", equalTo(endpoint))
            .body("value[0]." + AUTO_STATE + ".description", equalTo("Auto-approved"));
    }
}
