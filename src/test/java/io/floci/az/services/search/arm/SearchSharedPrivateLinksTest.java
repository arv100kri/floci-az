package io.floci.az.services.search.arm;

import io.floci.az.services.search.network.SearchOutboundNetwork;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.floci.az.services.search.arm.SearchNetworkFixtures.SEARCH_API;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createService;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.searchService;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Microsoft.Search ARM: shared private link resources")
class SearchSharedPrivateLinksTest {

    private static final String SUB = "sub-srch-spl";
    private static final String RG = "rg-spl";
    private static final String SERVICE_NAME = "spl-svc";
    private static final String SERVICE = searchService(SUB, RG, SERVICE_NAME);
    private static final String LINKS = SERVICE + "/sharedPrivateLinkResources";
    private static final String STORAGE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
        + "/providers/Microsoft.Storage/storageAccounts/splstorage";

    @Inject
    SearchOutboundNetwork outbound;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createService(SERVICE, "standard", null);
    }

    private static String link(String target, String groupId) {
        return "{\"properties\":{\"privateLinkResourceId\":\"" + target + "\",\"groupId\":\"" + groupId + "\","
            + "\"requestMessage\":\"please approve\",\"resourceRegion\":null}}";
    }

    private static ExtractableResponse<Response> put(String path, String body, int status) {
        return given().contentType("application/json").body(body).when().put(path + SEARCH_API)
            .then().statusCode(status).extract();
    }

    /** The path and query of an absolute monitor URL, for RestAssured's own base URI. */
    private static String pathOf(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    @Test
    void aLinkIsCreatedAsynchronouslyAndApproved() {
        ExtractableResponse<Response> created = put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        String monitor = created.header("Azure-AsyncOperation");
        assertTrue(monitor.contains(LINKS + "/blob-link/operationStatuses/"), monitor);
        assertTrue(monitor.endsWith("?api-version=2026-11-01"), monitor);
        assertEquals(monitor, created.header("Location"));
        assertEquals("1", created.header("Retry-After"));
        given().when().get(pathOf(monitor)).then().statusCode(200).body("status", equalTo("Succeeded"));

        given().when().get(LINKS + "/blob-link" + SEARCH_API).then().statusCode(200)
            .body("id", equalTo(LINKS + "/blob-link"))
            .body("name", equalTo("blob-link"))
            .body("type", equalTo("Microsoft.Search/searchServices/sharedPrivateLinkResources"))
            .body("properties.privateLinkResourceId", equalTo(STORAGE))
            .body("properties.groupId", equalTo("blob"))
            .body("properties.requestMessage", equalTo("please approve"))
            .body("properties.resourceRegion", nullValue())
            .body("properties.status", equalTo("Approved"))
            .body("properties.provisioningState", equalTo("Succeeded"));
        given().when().get(LINKS + SEARCH_API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("blob-link"));
        given().when().get(SERVICE + SEARCH_API).then().statusCode(200)
            .body("properties.sharedPrivateLinkResources", hasSize(1))
            .body("properties.sharedPrivateLinkResources[0].properties.status", equalTo("Approved"));
    }

    @Test
    void anUpdateChangesWhatItNamesAndIgnoresTheReadOnlyProperties() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        put(LINKS + "/BLOB-LINK", "{\"properties\":{\"privateLinkResourceId\":\"" + STORAGE + "\",\"groupId\":\"blob\","
            + "\"requestMessage\":\"Please approve this request.\",\"resourceRegion\":\"\",\"status\":\"\","
            + "\"provisioningState\":\"\"}}", 202);

        given().when().get(LINKS + "/blob-link" + SEARCH_API).then().statusCode(200)
            .body("name", equalTo("blob-link"))
            .body("properties.requestMessage", equalTo("Please approve this request."))
            .body("properties.resourceRegion", nullValue())
            .body("properties.status", equalTo("Approved"))
            .body("properties.provisioningState", equalTo("Succeeded"));
        given().when().get(LINKS + SEARCH_API).then().body("value", hasSize(1));
    }

    @Test
    void anUpdateThatNamesOnlyTheRequestMessageKeepsTheRest() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        put(LINKS + "/blob-link", "{\"properties\":{\"requestMessage\":\"a newer message\"}}", 202);

        given().when().get(LINKS + "/blob-link" + SEARCH_API).then().statusCode(200)
            .body("properties.privateLinkResourceId", equalTo(STORAGE))
            .body("properties.groupId", equalTo("blob"))
            .body("properties.requestMessage", equalTo("a newer message"))
            .body("properties.status", equalTo("Approved"));
    }

    @Test
    void aServiceHasOneLinkForEachResourceAndGroup() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        assertEquals("Conflict", put(LINKS + "/blob-again", link(STORAGE.toUpperCase(), "BLOB"), 409)
            .path("error.code"), "resource ids and group ids compare ignoring case");
        given().contentType("application/json").body(link(STORAGE, "blob")).when().put(LINKS + "/blob-again"
            + SEARCH_API).then().statusCode(409).body("error.code", equalTo("Conflict"));
        put(LINKS + "/table-link", link(STORAGE, "table"), 202);
        given().when().get(LINKS + SEARCH_API).then().body("value", hasSize(2))
            .body("value[0].name", equalTo("blob-link"))
            .body("value[1].name", equalTo("table-link"));
    }

    @Test
    void aBasicServiceConnectsToFourGroupIdsAtMost() {
        String basic = searchService(SUB, RG, "spl-basic");
        createService(basic, "basic", null);
        String cosmos = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.DocumentDB/databaseAccounts/splcosmos";
        put(basic + "/sharedPrivateLinkResources/a", link(STORAGE, "blob"), 202);
        put(basic + "/sharedPrivateLinkResources/b", link(STORAGE, "table"), 202);
        put(basic + "/sharedPrivateLinkResources/c", link(STORAGE, "dfs"), 202);
        put(basic + "/sharedPrivateLinkResources/d", link(cosmos, "Sql"), 202);

        given().contentType("application/json").body(link(STORAGE, "file"))
            .when().put(basic + "/sharedPrivateLinkResources/e" + SEARCH_API)
            .then().statusCode(400)
            .body("error.code", equalTo("BadRequest"))
            .body("error.message", containsString("4 distinct group IDs"));
        put(basic + "/sharedPrivateLinkResources/d", link(cosmos.replace("splcosmos", "other"), "Sql"), 202);
    }

    @Test
    void theTiersWithoutSharedPrivateLinksRefuseThem() {
        String free = searchService(SUB, RG, "spl-free");
        createService(free, "free", null);
        String highDensity = searchService(SUB, RG, "spl-hd");
        createService(highDensity, "standard3", "{\"hostingMode\":\"highDensity\"}");

        given().contentType("application/json").body(link(STORAGE, "blob"))
            .when().put(free + "/sharedPrivateLinkResources/a" + SEARCH_API)
            .then().statusCode(400).body("error.message", containsString("free tier"));
        given().contentType("application/json").body(link(STORAGE, "blob"))
            .when().put(highDensity + "/sharedPrivateLinkResources/a" + SEARCH_API)
            .then().statusCode(400).body("error.message", containsString("high-density"));
    }

    @Test
    void aLinkNeedsAResourceIdAndAGroupId() {
        put(LINKS + "/bad", "{\"properties\":{\"groupId\":\"blob\"}}", 400);
        put(LINKS + "/bad", "{\"properties\":{\"privateLinkResourceId\":\"not-an-id\",\"groupId\":\"blob\"}}", 400);
        put(LINKS + "/bad", "{\"properties\":{\"privateLinkResourceId\":\"" + STORAGE + "\"}}", 400);
        assertEquals("InvalidRequestContent", put(LINKS + "/bad", "{\"properties\":{\"privateLinkResourceId\":\""
            + STORAGE + "\",\"groupId\":5}}", 400).path("error.code"));
        put(LINKS + "/bad", "{}", 400);
        given().when().get(LINKS + SEARCH_API).then().body("value", hasSize(0));
    }

    @Test
    void aDeleteIsAsynchronousAndThenTheLinkIsGone() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        ExtractableResponse<Response> deleted = given().when().delete(LINKS + "/blob-link" + SEARCH_API)
            .then().statusCode(202).extract();
        String monitor = deleted.header("Azure-AsyncOperation");
        assertTrue(monitor.contains(LINKS + "/blob-link/operationStatuses/"), monitor);
        assertEquals("1", deleted.header("Retry-After"));
        given().when().get(pathOf(monitor)).then().statusCode(200).body("status", equalTo("Succeeded"));

        given().when().get(LINKS + "/blob-link" + SEARCH_API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        given().when().delete(LINKS + "/blob-link" + SEARCH_API).then().statusCode(204);
        given().when().delete(searchService(SUB, RG, "no-such-svc") + "/sharedPrivateLinkResources/blob-link"
            + SEARCH_API).then().statusCode(404);
    }

    @Test
    void theOutboundViewAllowsAnApprovedLinkToItsResourceAndGroupOnly() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        assertTrue(outbound.allows(SERVICE_NAME, STORAGE, "blob"));
        assertTrue(outbound.allows(SERVICE_NAME.toUpperCase(), STORAGE.toUpperCase(), "Blob"));
        assertFalse(outbound.allows(SERVICE_NAME, STORAGE, "table"));
        assertFalse(outbound.allows(SERVICE_NAME, STORAGE.replace("splstorage", "other"), "blob"));
        assertFalse(outbound.allows("dataplaneonly", STORAGE, "blob"));
        assertEquals(1, outbound.links(SERVICE_NAME).size());
    }

    @Test
    void deletingTheServiceDeletesItsLinks() {
        put(LINKS + "/blob-link", link(STORAGE, "blob"), 202);

        given().when().delete(SERVICE + SEARCH_API).then().statusCode(200);

        assertFalse(outbound.allows(SERVICE_NAME, STORAGE, "blob"));
        createService(SERVICE, "standard", null);
        given().when().get(LINKS + SEARCH_API).then().statusCode(200).body("value", hasSize(0));
    }
}
