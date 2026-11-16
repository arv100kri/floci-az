package io.floci.az.services.search;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** Tokens, ARM services, role assignments and permission-filtered indexes for the search identity tests. */
final class SearchIdentityFixtures {

    static final String API = "?api-version=2026-10-01";
    static final String ARM_API = "?api-version=2026-11-01";
    static final String TENANT = "00000000-0000-0000-0000-000000000002";
    static final String SEARCH_SCOPE = "https://search.azure.com/.default";

    static final String SEARCH_INDEX_DATA_READER = "1407120a-92aa-4202-b7e9-c0e197c71c8f";
    static final String SEARCH_INDEX_DATA_CONTRIBUTOR = "8ebe5a00-799e-43f5-93ac-243d3dce84a7";
    static final String SEARCH_SERVICE_CONTRIBUTOR = "7ca78c08-252a-4471-8644-bb5ff32d4ba0";

    static final String ALICE = "aaaaaaaa-0000-0000-0000-00000000a11c";
    static final String BOB = "bbbbbbbb-0000-0000-0000-000000000b0b";
    static final String READERS = "cccccccc-0000-0000-0000-0000000000c1";

    /**
     * An index with document-level permission filters, and five documents: Alice's, everyone's, the readers group's,
     * nobody's, and Bob's.
     */
    static final String ACL_INDEX = "{\"name\":\"acl\",\"permissionFilterOption\":\"enabled\",\"fields\":["
        + "{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
        + "{\"name\":\"title\",\"type\":\"Edm.String\"},"
        + "{\"name\":\"oid\",\"type\":\"Collection(Edm.String)\",\"filterable\":true,\"permissionFilter\":\"userIds\"},"
        + "{\"name\":\"group\",\"type\":\"Collection(Edm.String)\",\"filterable\":true,"
        + "\"permissionFilter\":\"groupIds\"}]}";

    static final String ACL_DOCUMENTS = "{\"value\":["
        + "{\"@search.action\":\"upload\",\"id\":\"1\",\"title\":\"alice report\",\"oid\":[\"" + ALICE + "\"],"
        + "\"group\":[\"none\"]},"
        + "{\"@search.action\":\"upload\",\"id\":\"2\",\"title\":\"public report\",\"oid\":[\"all\"],\"group\":[]},"
        + "{\"@search.action\":\"upload\",\"id\":\"3\",\"title\":\"readers report\",\"oid\":[\"none\"],"
        + "\"group\":[\"" + READERS + "\"]},"
        + "{\"@search.action\":\"upload\",\"id\":\"4\",\"title\":\"secret report\",\"oid\":[\"none\"],"
        + "\"group\":[\"none\"]},"
        + "{\"@search.action\":\"upload\",\"id\":\"5\",\"title\":\"bob report\",\"oid\":[\"" + BOB + "\"]}]}";

    private SearchIdentityFixtures() {
    }

    /** An unsigned token carrying {@code claims}, which floci-az reads while strict tokens are off. */
    static String unsignedToken(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
            + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    /** An unsigned token for the user {@code oid}, issued for search. */
    static String userToken(String oid) {
        return unsignedToken("{\"aud\":\"https://search.azure.com\",\"oid\":\"" + oid + "\",\"tid\":\"" + TENANT
            + "\",\"idtyp\":\"user\"}");
    }

    /** A token floci-az's Entra signs for the dev application, for {@code scope}. */
    static String appToken(String scope) {
        return given().formParam("grant_type", "client_credentials")
            .formParam("client_id", "11111111-1111-1111-1111-111111111111")
            .formParam("client_secret", "floci-az-dev-secret")
            .formParam("scope", scope)
            .when().post("/" + TENANT + "/oauth2/v2.0/token")
            .then().statusCode(200).extract().path("access_token");
    }

    /** A token floci-az's Entra signs for the dev user (a member of the dev group), for {@code scope}. */
    static String devUserToken(String scope) {
        return given().formParam("grant_type", "password")
            .formParam("client_id", "11111111-1111-1111-1111-111111111111")
            .formParam("username", "dev-user@floci-az.local")
            .formParam("password", "any-password")
            .formParam("scope", scope)
            .when().post("/" + TENANT + "/oauth2/v2.0/token")
            .then().statusCode(200).extract().path("access_token");
    }

    static String serviceId(String subscription, String name) {
        return "/subscriptions/" + subscription + "/resourceGroups/search-identity/providers/Microsoft.Search/"
            + "searchServices/" + name;
    }

    /** Creates a search service through Microsoft.Search with {@code properties}; returns its primary admin key. */
    static String createService(String id, String properties) {
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"properties\":" + properties + "}")
            .when().put(id + ARM_API).then().statusCode(201);
        return given().when().post(id + "/listAdminKeys" + ARM_API).then().statusCode(200)
            .extract().path("primaryKey");
    }

    /** Assigns a built-in role at {@code scope} through Microsoft.Authorization, as the search docs do. */
    static String assignRole(String scope, String roleGuid, String principalId, String principalType) {
        String subscription = scope.split("/")[2];
        String id = scope + "/providers/Microsoft.Authorization/roleAssignments/" + UUID.randomUUID();
        given().contentType("application/json").queryParam("api-version", "2022-04-01")
            .body("{\"properties\":{\"roleDefinitionId\":\"/subscriptions/" + subscription
                + "/providers/Microsoft.Authorization/roleDefinitions/" + roleGuid + "\",\"principalId\":\""
                + principalId + "\",\"principalType\":\"" + principalType + "\"}}")
            .when().put(id)
            .then().statusCode(201);
        return id;
    }
}
