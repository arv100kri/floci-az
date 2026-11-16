package io.floci.az.services.search.arm;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/** The ARM resources the Search network tests build on: services, virtual networks and private endpoints. */
final class SearchNetworkFixtures {

    static final String SEARCH_API = "?api-version=2026-11-01";
    static final String NETWORK_API = "?api-version=2024-05-01";

    private SearchNetworkFixtures() {
    }

    static String searchService(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Search/searchServices/" + name;
    }

    static String network(String sub, String rg) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network";
    }

    /** Creates a search service of {@code sku} with the given {@code properties} JSON (or none). */
    static void createService(String path, String sku, String properties) {
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"" + sku + "\"}"
                + (properties == null ? "" : ",\"properties\":" + properties) + "}")
            .when().put(path + SEARCH_API)
            .then().statusCode(201);
    }

    /** Creates virtual network {@code vnet} with one subnet {@code subnet} over {@code prefixes}; returns its id. */
    static String createSubnet(String sub, String rg, String vnet, String subnet, List<String> prefixes) {
        String quoted = String.join(",", prefixes.stream().map(prefix -> "\"" + prefix + "\"").toList());
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/8\"]},"
                + "\"subnets\":[{\"name\":\"" + subnet + "\",\"properties\":{\"addressPrefixes\":[" + quoted + "]}}]}}")
            .when().put(network(sub, rg) + "/virtualNetworks/" + vnet + NETWORK_API)
            .then().statusCode(200);
        return network(sub, rg) + "/virtualNetworks/" + vnet + "/subnets/" + subnet;
    }

    /** A private endpoint body with one connection to {@code target}, automatic or manual. */
    static String endpointBody(String subnetId, String target, boolean manual) {
        String field = manual ? "manualPrivateLinkServiceConnections" : "privateLinkServiceConnections";
        return "{\"location\":\"westus\",\"properties\":{\"subnet\":{\"id\":\"" + subnetId + "\"},\"" + field
            + "\":[{\"name\":\"search-connection\",\"properties\":{\"privateLinkServiceId\":\"" + target + "\","
            + "\"groupIds\":[\"searchService\"],\"requestMessage\":\"please approve\"}}]}}";
    }

    /** Creates (or replaces) private endpoint {@code name} with {@code body}; returns its id. */
    static String putEndpoint(String sub, String rg, String name, String body) {
        String path = network(sub, rg) + "/privateEndpoints/" + name;
        given().contentType("application/json").body(body)
            .when().put(path + NETWORK_API)
            .then().statusCode(anyOf(is(200), is(201)));
        return path;
    }

    static ExtractableResponse<Response> get(String path, int status) {
        return given().when().get(path + SEARCH_API).then().statusCode(status).extract();
    }

    /** The primary admin key of the service at {@code path}. */
    static String adminKey(String path) {
        return given().when().post(path + "/listAdminKeys" + SEARCH_API).then().statusCode(200)
            .extract().path("primaryKey");
    }
}
