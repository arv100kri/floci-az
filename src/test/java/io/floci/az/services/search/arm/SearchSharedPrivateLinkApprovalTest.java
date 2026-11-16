package io.floci.az.services.search.arm;

import io.floci.az.services.search.network.FakeSharedPrivateLinkTarget;
import io.floci.az.services.search.network.SearchOutboundNetwork;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.floci.az.services.search.arm.SearchNetworkFixtures.SEARCH_API;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.createService;
import static io.floci.az.services.search.arm.SearchNetworkFixtures.searchService;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SearchNetworkFirewallTest.StrictNetwork.class)
@DisplayName("Shared private links under strict-network: approved on the resource when floci-az models its connections")
class SearchSharedPrivateLinkApprovalTest {

    private static final String SUB = "sub-srch-spl-strict";
    private static final String RG = "rg-spl-strict";
    private static final String NAME = "spl-strict";
    private static final String SERVICE = searchService(SUB, RG, NAME);
    private static final String LINK = SERVICE + "/sharedPrivateLinkResources/to-modelled";
    private static final String MODELLED = "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/"
        + FakeSharedPrivateLinkTarget.NAMESPACE + "/accounts/modelled";
    private static final String STORAGE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
        + "/providers/Microsoft.Storage/storageAccounts/unmodelled";

    @Inject
    FakeSharedPrivateLinkTarget target;

    @Inject
    SearchOutboundNetwork outbound;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createService(SERVICE, "standard", null);
    }

    private static void putLink(String path, String resourceId, String message) {
        given().contentType("application/json")
            .body("{\"properties\":{\"privateLinkResourceId\":\"" + resourceId + "\",\"groupId\":\"account\","
                + "\"requestMessage\":\"" + message + "\"}}")
            .when().put(path + SEARCH_API)
            .then().statusCode(202);
    }

    private static void statusIs(String path, String status) {
        given().when().get(path + SEARCH_API).then().statusCode(200)
            .body("properties.status", equalTo(status))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void aLinkToAModelledResourceIsPendingUntilItsOwnerApprovesIt() {
        putLink(LINK, MODELLED, "please approve");

        statusIs(LINK, "Pending");
        assertEquals("please approve", target.requestOf(LINK).orElseThrow().requestMessage());
        assertEquals(SERVICE, target.requestOf(LINK).orElseThrow().searchServiceId());
        assertFalse(outbound.allows(NAME, MODELLED, "account"));

        target.decide(LINK, "Approved");
        statusIs(LINK, "Approved");
        assertTrue(outbound.allows(NAME, MODELLED, "account"));

        putLink(LINK, MODELLED, "a new message");
        statusIs(LINK, "Approved");

        target.decide(LINK, "Rejected");
        statusIs(LINK, "Rejected");
        assertFalse(outbound.allows(NAME, MODELLED, "account"));
    }

    @Test
    void aLinkWhoseConnectionTheResourceDroppedIsDisconnected() {
        putLink(LINK, MODELLED, "please approve");
        target.withdraw(LINK);

        statusIs(LINK, "Disconnected");
    }

    @Test
    void deletingOrRetargetingALinkWithdrawsItsRequest() {
        putLink(LINK, MODELLED, "please approve");
        putLink(LINK, STORAGE, "please approve");

        assertTrue(target.requestOf(LINK).isEmpty());
        statusIs(LINK, "Approved");

        putLink(LINK, MODELLED, "please approve");
        given().when().delete(LINK + SEARCH_API).then().statusCode(202);
        assertTrue(target.requestOf(LINK).isEmpty());
    }

    @Test
    void aLinkToAResourceFlociAzDoesNotModelIsApprovedEvenUnderStrictNetwork() {
        String path = SERVICE + "/sharedPrivateLinkResources/to-storage";
        putLink(path, STORAGE, "please approve");

        statusIs(path, "Approved");
        assertTrue(target.requestOf(path).isEmpty());
    }
}
