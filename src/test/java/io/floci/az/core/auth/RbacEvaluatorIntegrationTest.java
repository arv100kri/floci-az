package io.floci.az.core.auth;

import io.floci.az.services.entra.EntraStore;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Role assignments created through the ARM surface the way the Azure AI Search docs create them
 * (search-security-rbac: at a search service, and "scoped to an index"), then evaluated by {@link RbacEvaluator}.
 */
@QuarkusTest
class RbacEvaluatorIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "eeeeeeee-1111-2222-3333-000000000001";
    private static final String OTHER_SUB = "eeeeeeee-1111-2222-3333-000000000002";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/search-rg";
    private static final String SERVICE = RG + "/providers/Microsoft.Search/searchServices/search-1";
    private static final String HOTELS = SERVICE + "/indexes/hotels";
    private static final String MOTELS = SERVICE + "/indexes/motels";
    private static final String USER = "ffffffff-0000-0000-0000-000000000001";
    private static final String MG = "/providers/Microsoft.Management/managementGroups/search-mg";

    private static final String SEARCH_INDEX_DATA_READER = "1407120a-92aa-4202-b7e9-c0e197c71c8f";
    private static final String SEARCH_INDEX_DATA_CONTRIBUTOR = "8ebe5a00-799e-43f5-93ac-243d3dce84a7";
    private static final String SEARCH_SERVICE_CONTRIBUTOR = "7ca78c08-252a-4471-8644-bb5ff32d4ba0";

    private static final String DOCUMENTS = "Microsoft.Search/searchServices/indexes/documents/";
    private static final RbacOperation READ_DOCUMENTS = RbacOperation.dataAction(DOCUMENTS + "read");
    private static final RbacOperation WRITE_DOCUMENTS = RbacOperation.dataAction(DOCUMENTS + "write");

    @Inject
    RbacEvaluator evaluator;

    @Inject
    GraphDirectoryStore directory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void searchIndexDataReaderAtTheServiceReadsTheDocumentsOfEveryIndex() {
        String id = assign(SERVICE, SEARCH_INDEX_DATA_READER, USER, "User");
        assertEquals(List.of(id), evaluate(USER, HOTELS, READ_DOCUMENTS).assignmentIds());
        assertEquals(List.of(id), evaluate(USER, MOTELS, READ_DOCUMENTS).assignmentIds());
        assertFalse(evaluate(USER, HOTELS, WRITE_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, HOTELS, RbacOperation.action("Microsoft.Search/searchServices/indexes/read"))
                .allowed());
        assertFalse(evaluate(UUID.randomUUID().toString(), HOTELS, READ_DOCUMENTS).allowed());
    }

    @Test
    void searchIndexDataContributorAtAnIndexWritesThatIndexOnly() {
        String id = assign(HOTELS, SEARCH_INDEX_DATA_CONTRIBUTOR, USER, "User");
        for (RbacOperation operation : List.of(READ_DOCUMENTS, WRITE_DOCUMENTS,
                RbacOperation.dataAction(DOCUMENTS + "delete"))) {
            assertEquals(List.of(id), evaluate(USER, HOTELS, operation).assignmentIds(), operation.name());
            assertFalse(evaluate(USER, MOTELS, operation).allowed(), operation.name());
            assertFalse(evaluate(USER, SERVICE, operation).allowed(), operation.name());
        }
        assertFalse(evaluate(USER, HOTELS, RbacOperation.action("Microsoft.Search/searchServices/indexes/write"))
                .allowed());
    }

    @Test
    void searchServiceContributorManagesTheServiceButReadsNoDocuments() {
        String id = assign(RG, SEARCH_SERVICE_CONTRIBUTOR, USER, "User");
        assertEquals(List.of(id), evaluate(USER, HOTELS,
                RbacOperation.action("Microsoft.Search/searchServices/indexes/write")).assignmentIds());
        assertEquals(List.of(id), evaluate(USER, SERVICE,
                RbacOperation.action("Microsoft.Search/searchServices/listAdminKeys/action")).assignmentIds());
        assertFalse(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, SERVICE, RbacOperation.action("Microsoft.Authorization/roleAssignments/write"))
                .allowed());
    }

    /** "Role assignments are transitive for groups": membership added through Microsoft Graph, nested once. */
    @Test
    void anAssignmentToAGroupReachesTheMembersOfItsNestedGroups() {
        String inner = UUID.randomUUID().toString();
        String outer = UUID.randomUUID().toString();
        directory.put(GraphDirectoryStore.group(directory.defaultTenantId(), inner, "Search readers", true));
        directory.put(GraphDirectoryStore.group(directory.defaultTenantId(), outer, "Search users", true));
        addMember(inner, EntraStore.DEV_USER_OBJECT_ID);
        addMember(outer, inner);
        String id = assign(SERVICE, SEARCH_INDEX_DATA_READER, outer, null);

        assertEquals(List.of(id), evaluate(EntraStore.DEV_USER_OBJECT_ID, HOTELS, READ_DOCUMENTS).assignmentIds());
        given().when().delete("/v1.0/groups/{group}/members/{member}/$ref", inner, EntraStore.DEV_USER_OBJECT_ID)
                .then().statusCode(204);
        assertFalse(evaluate(EntraStore.DEV_USER_OBJECT_ID, HOTELS, READ_DOCUMENTS).allowed());
    }

    /** A token floci-az issues names the principal; its groups come from the directory. */
    @Test
    void aTokenFromTheEmulatorIsAllTheEvaluatorNeeds() {
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("username", EntraStore.DEV_USER_UPN)
                .formParam("password", "whatever")
                .formParam("scope", "https://search.azure.com/.default")
                .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
                .then().statusCode(200)
                .extract().path("access_token");
        RbacPrincipal principal = RbacPrincipal.of(BearerClaims.parse("Bearer " + token));
        assertEquals(EntraStore.DEV_USER_OBJECT_ID, principal.objectId());

        String id = assign(HOTELS, SEARCH_INDEX_DATA_READER, EntraStore.DEV_GROUP_OBJECT_ID, "Group");
        assertEquals(List.of(id), evaluator.evaluate(principal, HOTELS, READ_DOCUMENTS).assignmentIds());
    }

    /** A management group's assignment reaches the subscriptions placed under it, and no other. */
    @Test
    void aManagementGroupAssignmentReachesItsSubscriptions() {
        given().contentType("application/json").body("{}").queryParam("api-version", "2023-04-01")
                .when().put(MG).then().statusCode(200);
        given().queryParam("api-version", "2023-04-01")
                .when().put(MG + "/subscriptions/" + SUB).then().statusCode(200);
        String id = assign(MG, SEARCH_INDEX_DATA_READER, USER, "User");

        assertEquals(List.of(id), evaluate(USER, HOTELS, READ_DOCUMENTS).assignmentIds());
        assertFalse(evaluate(USER, "/subscriptions/" + OTHER_SUB + "/resourceGroups/search-rg/providers"
                + "/Microsoft.Search/searchServices/search-1/indexes/hotels", READ_DOCUMENTS).allowed());
    }

    @Test
    void aDeletedAssignmentGrantsNothing() {
        String id = assign(SERVICE, SEARCH_INDEX_DATA_CONTRIBUTOR, USER, "User");
        assertTrue(evaluate(USER, HOTELS, WRITE_DOCUMENTS).allowed());
        given().queryParam("api-version", "2022-04-01").when().delete(id).then().statusCode(200);
        assertFalse(evaluate(USER, HOTELS, WRITE_DOCUMENTS).allowed());
    }

    private RbacDecision evaluate(String principalId, String scope, RbacOperation operation) {
        return evaluator.evaluate(RbacPrincipal.of(principalId), scope, operation);
    }

    /** {@code PUT {scope}/providers/Microsoft.Authorization/roleAssignments/{guid}}, as the search docs send it. */
    private static String assign(String scope, String roleGuid, String principalId, String principalType) {
        String id = scope + "/providers/Microsoft.Authorization/roleAssignments/" + UUID.randomUUID();
        String roleDefinitionId = (scope.startsWith("/subscriptions/") ? "/subscriptions/" + SUB : "")
                + "/providers/Microsoft.Authorization/roleDefinitions/" + roleGuid;
        String type = principalType == null ? "" : ",\"principalType\":\"" + principalType + "\"";
        given().contentType("application/json").queryParam("api-version", "2022-04-01")
                .body("{\"properties\":{\"roleDefinitionId\":\"" + roleDefinitionId + "\",\"principalId\":\""
                        + principalId + "\"" + type + "}}")
                .when().put(id)
                .then().statusCode(201);
        return id;
    }

    private static void addMember(String groupId, String memberId) {
        given().contentType("application/json")
                .body("{\"@odata.id\":\"https://graph.microsoft.com/v1.0/directoryObjects/" + memberId + "\"}")
                .when().post("/v1.0/groups/{id}/members/$ref", groupId)
                .then().statusCode(204);
    }
}
