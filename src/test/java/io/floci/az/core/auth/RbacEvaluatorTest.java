package io.floci.az.core.auth;

import io.floci.az.core.StoredObject;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectoryOrigin;
import io.floci.az.core.directory.DirectorySeed;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.SubscriptionState;
import io.floci.az.core.directory.Tenant;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.authorization.RoleAssignmentStore;
import io.floci.az.services.authorization.RoleDefinitionCatalog;
import io.floci.az.services.authorization.RoleDefinitionCatalog.RoleDefinition;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.floci.az.services.entra.InMemoryGraphDirectory;
import io.floci.az.services.managementgroups.ManagementGroupStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RbacEvaluator} over in-memory stores: which assignments apply at a scope, to whom, and what they grant, as
 * the Azure RBAC docs describe it.
 */
class RbacEvaluatorTest {

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER_TENANT = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String SUB = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String UNPLACED_SUB = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String OTHER_TENANT_SUB = "bbbbbbbb-0000-0000-0000-000000000003";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/search-rg";
    private static final String SERVICE = RG + "/providers/Microsoft.Search/searchServices/search-1";
    private static final String HOTELS = SERVICE + "/indexes/hotels";
    private static final String USER = "cccccccc-0000-0000-0000-000000000001";
    private static final String OTHER_USER = "cccccccc-0000-0000-0000-000000000002";
    private static final String GROUP = "dddddddd-0000-0000-0000-000000000001";
    private static final String OUTER_GROUP = "dddddddd-0000-0000-0000-000000000002";

    private static final String READER = "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String OWNER = "8e3af657-a8ff-443c-a75c-2fe8c4bcb635";
    private static final String CONTRIBUTOR = "b24988ac-6180-42a0-ab88-20f7382dd24c";
    private static final String USER_ACCESS_ADMINISTRATOR = "18d7d88d-d35e-4fb5-a5c3-7773c20a72d9";
    private static final String SEARCH_INDEX_DATA_READER = "1407120a-92aa-4202-b7e9-c0e197c71c8f";
    private static final String SEARCH_SERVICE_CONTRIBUTOR = "7ca78c08-252a-4471-8644-bb5ff32d4ba0";

    private static final String MG_PREFIX = "/providers/Microsoft.Management/managementGroups/";
    private static final RbacOperation READ_DOCUMENTS =
            RbacOperation.dataAction("Microsoft.Search/searchServices/indexes/documents/read");
    private static final RbacOperation WRITE_INDEX =
            RbacOperation.action("Microsoft.Search/searchServices/indexes/write");

    private RoleAssignmentStore assignments;
    private GraphDirectoryStore graph;
    private ManagementGroupStore managementGroups;
    private RbacEvaluator evaluator;

    @BeforeEach
    void setUp() {
        String cloud = CloudCatalog.PUBLIC_CLOUD;
        List<Tenant> tenantList = List.of(new Tenant(TENANT, cloud, "Default", "default.test", DirectoryOrigin.SEED),
                new Tenant(OTHER_TENANT, cloud, "Other", "other.test", DirectoryOrigin.CONFIG));
        List<Subscription> subscriptions = List.of(
                new Subscription(SUB, TENANT, "Default", SubscriptionState.ENABLED, DirectoryOrigin.SEED),
                new Subscription(UNPLACED_SUB, TENANT, "Unplaced", SubscriptionState.ENABLED, DirectoryOrigin.CONFIG),
                new Subscription(OTHER_TENANT_SUB, OTHER_TENANT, "Other", SubscriptionState.ENABLED,
                        DirectoryOrigin.CONFIG));
        AzureDirectory tenants = new AzureDirectory(new DirectorySeed(CloudCatalog.forNameOrPublic(cloud), TENANT, SUB,
                tenantList, subscriptions), new InMemoryStorage<>());
        managementGroups = new ManagementGroupStore(tenants);
        assignments = new RoleAssignmentStore(over(new InMemoryStorage<>()));
        graph = InMemoryGraphDirectory.create(TENANT);
        evaluator = new RbacEvaluator(assignments, new DirectoryReader(graph), tenants, managementGroups);
    }

    // ── Scope ────────────────────────────────────────────────────────────────────

    /** "Lower levels inherit role permissions from higher levels" (Understand scope for Azure RBAC). */
    @Test
    void anAssignmentAppliesAtItsScopeAndEveryScopeBelowIt() {
        String id = assign("/subscriptions/" + SUB, SEARCH_INDEX_DATA_READER, USER);
        for (String scope : List.of("/subscriptions/" + SUB, RG, SERVICE, HOTELS, HOTELS + "/docs/1")) {
            RbacDecision decision = evaluate(USER, scope, READ_DOCUMENTS);
            assertTrue(decision.allowed(), scope);
            assertEquals(List.of(id), decision.assignmentIds());
        }
    }

    /** An index-scoped assignment, as search-security-rbac creates one, covers that index alone. */
    @Test
    void anAssignmentAtAnIndexCoversThatIndexOnly() {
        assign(HOTELS, SEARCH_INDEX_DATA_READER, USER);
        assertTrue(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, SERVICE, READ_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, SERVICE + "/indexes/motels", READ_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, SERVICE + "/indexes/hotels-archive", READ_DOCUMENTS).allowed());
    }

    @Test
    void anAssignmentDoesNotReachAnUnrelatedScope() {
        assign(RG, SEARCH_INDEX_DATA_READER, USER);
        assertFalse(evaluate(USER, RG + "2/providers/Microsoft.Search/searchServices/search-1", READ_DOCUMENTS)
                .allowed());
        assertFalse(evaluate(USER, "/subscriptions/" + UNPLACED_SUB + "/resourceGroups/search-rg", READ_DOCUMENTS)
                .allowed());
        RbacDecision denied = evaluate(USER, "/subscriptions/" + SUB + "/resourceGroups/other-rg", READ_DOCUMENTS);
        assertFalse(denied.allowed());
        assertThat(denied.reason(), containsString("No role assignment of principal '" + USER + "'"));
    }

    @Test
    void scopesAndPrincipalIdsCompareIgnoringCase() {
        String mixedCase = "/subscriptions/" + SUB.toUpperCase(Locale.ROOT)
                + "/resourceGroups/SEARCH-RG/providers/microsoft.search/SEARCHSERVICES/Search-1";
        assign(mixedCase, SEARCH_INDEX_DATA_READER, USER.toUpperCase(Locale.ROOT));
        assertTrue(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
        assertTrue(evaluate(USER, HOTELS.toUpperCase(Locale.ROOT),
                RbacOperation.dataAction(READ_DOCUMENTS.name().toUpperCase(Locale.ROOT))).allowed());
    }

    /** Management groups are a scope above subscriptions; an unplaced subscription sits under the tenant root group. */
    @Test
    void managementGroupsAndTheTenantRootCoverTheirSubscriptions() {
        managementGroups.createOrUpdate(TENANT, "mg-parent", null, null);
        managementGroups.createOrUpdate(TENANT, "mg-child", null, "mg-parent");
        managementGroups.place(TENANT, SUB, "mg-child");
        String atParent = assign(MG_PREFIX + "mg-parent", SEARCH_INDEX_DATA_READER, USER);
        assertEquals(List.of(atParent), evaluate(USER, HOTELS, READ_DOCUMENTS).assignmentIds());
        assertFalse(evaluate(USER, "/subscriptions/" + UNPLACED_SUB, READ_DOCUMENTS).allowed());

        String atRootGroup = assign(MG_PREFIX + TENANT, SEARCH_INDEX_DATA_READER, OTHER_USER);
        assertTrue(evaluate(OTHER_USER, "/subscriptions/" + UNPLACED_SUB, READ_DOCUMENTS).allowed());
        assertEquals(List.of(atRootGroup), evaluate(OTHER_USER, HOTELS, READ_DOCUMENTS).assignmentIds());

        String atTenantRoot = assign("/", READER, USER);
        assertEquals(List.of(atTenantRoot), evaluate(USER, SERVICE,
                RbacOperation.action("Microsoft.Search/searchServices/read")).assignmentIds());
    }

    /** Each tenant has its own hierarchy, and an assignment there keeps the tenant it was made in. */
    @Test
    void anotherTenantsManagementGroupsAndRootDoNotApply() {
        managementGroups.createOrUpdate(TENANT, "prod", null, null);
        managementGroups.place(TENANT, SUB, "prod");
        assignInTenant(MG_PREFIX + "prod", SEARCH_INDEX_DATA_READER, USER, OTHER_TENANT);
        assignInTenant("/", SEARCH_INDEX_DATA_READER, USER, OTHER_TENANT);
        assertFalse(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
        assertTrue(evaluate(USER, "/subscriptions/" + OTHER_TENANT_SUB, READ_DOCUMENTS).allowed());
    }

    // ── Principals ───────────────────────────────────────────────────────────────

    @Test
    void anAssignmentToAGroupAppliesToItsMembersOnly() {
        graph.put(GraphDirectoryStore.group(TENANT, GROUP, "Searchers", true));
        graph.addLink(TENANT, GROUP, GraphDirectoryStore.MEMBERS, USER);
        String id = assign(SERVICE, SEARCH_INDEX_DATA_READER, GROUP);
        assertEquals(List.of(id), evaluate(USER, HOTELS, READ_DOCUMENTS).assignmentIds());
        assertFalse(evaluate(OTHER_USER, HOTELS, READ_DOCUMENTS).allowed());
    }

    /** "Role assignments are transitive for groups" (What is Azure RBAC). */
    @Test
    void groupMembershipIsTransitive() {
        graph.put(GraphDirectoryStore.group(TENANT, GROUP, "Inner", true));
        graph.put(GraphDirectoryStore.group(TENANT, OUTER_GROUP, "Outer", true));
        graph.addLink(TENANT, GROUP, GraphDirectoryStore.MEMBERS, USER);
        graph.addLink(TENANT, OUTER_GROUP, GraphDirectoryStore.MEMBERS, GROUP);
        assign(SERVICE, SEARCH_INDEX_DATA_READER, OUTER_GROUP);
        assertTrue(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
    }

    @Test
    void aGroupCycleEndsTheWalk() {
        graph.put(GraphDirectoryStore.group(TENANT, GROUP, "One", true));
        graph.put(GraphDirectoryStore.group(TENANT, OUTER_GROUP, "Two", true));
        graph.addLink(TENANT, GROUP, GraphDirectoryStore.MEMBERS, USER);
        graph.addLink(TENANT, GROUP, GraphDirectoryStore.MEMBERS, OUTER_GROUP);
        graph.addLink(TENANT, OUTER_GROUP, GraphDirectoryStore.MEMBERS, GROUP);
        assign(SERVICE, SEARCH_INDEX_DATA_READER, OUTER_GROUP);
        assertTrue(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
    }

    /** A token's groups claim counts, and so does every group the directory nests a claimed group in. */
    @Test
    void theGroupsOfATokenClaimApply() {
        graph.put(GraphDirectoryStore.group(TENANT, OUTER_GROUP, "Outer", true));
        graph.addLink(TENANT, OUTER_GROUP, GraphDirectoryStore.MEMBERS, GROUP);
        assign(HOTELS, SEARCH_INDEX_DATA_READER, OUTER_GROUP);
        assertTrue(evaluator.evaluate(new RbacPrincipal(USER, null, List.of(GROUP)), HOTELS, READ_DOCUMENTS).allowed());
        assertFalse(evaluate(USER, HOTELS, READ_DOCUMENTS).allowed());
    }

    @Test
    void aBearerTokenNamesThePrincipalItsTenantAndGroups() {
        String header = bearer("{\"oid\":\"" + USER + "\",\"tid\":\"" + TENANT + "\",\"groups\":[\"" + GROUP + "\"]}");
        RbacPrincipal principal = RbacPrincipal.of(BearerClaims.parse(header));
        assertEquals(new RbacPrincipal(USER, TENANT, List.of(GROUP)), principal);
        assign(SERVICE, SEARCH_INDEX_DATA_READER, GROUP);
        assertTrue(evaluator.evaluate(principal, HOTELS, READ_DOCUMENTS).allowed());
    }

    @Test
    void aCallerWithoutAnObjectIdIsDenied() {
        assign("/", OWNER, USER);
        RbacDecision decision = evaluator.evaluate(new RbacPrincipal(null, null, null), SERVICE, WRITE_INDEX);
        assertFalse(decision.allowed());
        assertThat(decision.reason(), containsString("no object id"));
        assertFalse(evaluator.evaluate(RbacPrincipal.of(USER), SERVICE, new RbacOperation(" ", false)).allowed());
        assertFalse(evaluator.evaluate(RbacPrincipal.of(USER), "", WRITE_INDEX).allowed());
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    /** "Control plane access is not inherited to your data plane" (Understand Azure role definitions). */
    @Test
    void aControlPlaneRoleGrantsNoDataAction() {
        String owner = assign("/subscriptions/" + SUB, OWNER, USER);
        assertEquals(List.of(owner), evaluate(USER, HOTELS, WRITE_INDEX).assignmentIds());
        RbacDecision denied = evaluate(USER, HOTELS, READ_DOCUMENTS);
        assertFalse(denied.allowed());
        assertThat(denied.reason(), containsString("Owner"));
        assertThat(denied.reason(), containsString("data action '" + READ_DOCUMENTS.name() + "'"));
    }

    /** "Azure RBAC is an additive model", and NotActions is not a deny rule: another role grants what one excludes. */
    @Test
    void rolesAddUp() {
        RbacOperation assignRoles = RbacOperation.action("Microsoft.Authorization/roleAssignments/write");
        assign("/subscriptions/" + SUB, CONTRIBUTOR, USER);
        assertFalse(evaluate(USER, SERVICE, assignRoles).allowed());
        String access = assign(RG, USER_ACCESS_ADMINISTRATOR, USER);
        assertEquals(List.of(access), evaluate(USER, SERVICE, assignRoles).assignmentIds());
    }

    @Test
    void everyGrantingAssignmentIsReported() {
        graph.put(GraphDirectoryStore.group(TENANT, GROUP, "Searchers", true));
        graph.addLink(TENANT, GROUP, GraphDirectoryStore.MEMBERS, USER);
        String reader = assign("/subscriptions/" + SUB, READER, USER);
        String contributor = assign(SERVICE, SEARCH_SERVICE_CONTRIBUTOR, GROUP);
        assign(SERVICE, SEARCH_INDEX_DATA_READER, OTHER_USER);

        RbacOperation readIndex = RbacOperation.action("Microsoft.Search/searchServices/indexes/read");
        RbacDecision read = evaluate(USER, HOTELS, readIndex);
        assertThat(read.assignmentIds(), containsInAnyOrder(reader, contributor));
        assertThat(read.reason(), containsString("Reader"));
        assertThat(read.reason(), containsString("Search Service Contributor"));
        assertEquals(List.of(contributor), evaluate(USER, HOTELS, WRITE_INDEX).assignmentIds());
    }

    @Test
    void aDecisionEchoesTheQuestionAndNamesTheAssignment() {
        String id = assign(SERVICE, SEARCH_INDEX_DATA_READER, USER);
        RbacDecision decision = evaluate(USER, HOTELS, READ_DOCUMENTS);
        assertEquals(new RbacDecision(true, USER, HOTELS, READ_DOCUMENTS, List.of(id), decision.reason()), decision);
        assertThat(decision.reason(), containsString("'" + id + "' (Search Index Data Reader at '" + SERVICE + "')"));
    }

    /**
     * Conditions are decided conservatively: a condition "filters down permissions" (What is Azure ABAC) and floci-az
     * does not evaluate one, so a conditional assignment grants nothing.
     */
    @Test
    void anAssignmentWithAConditionGrantsNothing() {
        String id = assign(SERVICE, SEARCH_INDEX_DATA_READER, USER,
                "@Resource[Microsoft.Search/searchServices/indexes:name] StringEquals 'hotels'");
        RbacDecision decision = evaluate(USER, HOTELS, READ_DOCUMENTS);
        assertFalse(decision.allowed());
        assertThat(decision.reason(), containsString("'" + id + "' would grant it only under an ABAC condition"));
    }

    /** Some built-in roles carry a condition on a permission entry; that entry grants nothing either. */
    @Test
    void aRolePermissionWithAConditionGrantsNothing() {
        RoleDefinition conditional = RoleDefinitionCatalog.all().stream()
                .filter(role -> "Log Analytics Data Reader".equals(role.roleName())).findFirst().orElseThrow();
        assign("/subscriptions/" + SUB, conditional.name(), USER);
        RbacDecision decision = evaluate(USER, "/subscriptions/" + SUB + "/resourceGroups/logs/providers"
                        + "/Microsoft.OperationalInsights/workspaces/ws",
                RbacOperation.dataAction("Microsoft.OperationalInsights/workspaces/tables/data/read"));
        assertFalse(decision.allowed());
        assertThat(decision.reason(), containsString("ABAC condition"));
    }

    @Test
    void anAssignmentToAnUnknownRoleGrantsNothing() {
        assign(SERVICE, UUID.randomUUID().toString(), USER);
        RbacDecision decision = evaluate(USER, HOTELS, READ_DOCUMENTS);
        assertFalse(decision.allowed());
        assertThat(decision.reason(), containsString("unknown role"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private RbacDecision evaluate(String principalId, String scope, RbacOperation operation) {
        return evaluator.evaluate(RbacPrincipal.of(principalId), scope, operation);
    }

    private String assign(String scope, String roleGuid, String principalId) {
        return store(scope, roleGuid, principalId, null, null);
    }

    private String assign(String scope, String roleGuid, String principalId, String condition) {
        return store(scope, roleGuid, principalId, condition, null);
    }

    private String assignInTenant(String scope, String roleGuid, String principalId, String tenant) {
        return store(scope, roleGuid, principalId, null, tenant);
    }

    /** An assignment as {@code RbacHandler} stores it, with the tenant it records for a tenant-bound scope. */
    private String store(String scope, String roleGuid, String principalId, String condition, String tenant) {
        String name = UUID.randomUUID().toString();
        String id = ("/".equals(scope) ? "" : scope) + "/providers/Microsoft.Authorization/roleAssignments/" + name;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleDefinitionId",
                "/subscriptions/" + SUB + "/providers/Microsoft.Authorization/roleDefinitions/" + roleGuid);
        properties.put("principalId", principalId);
        properties.put("scope", scope);
        properties.put("condition", condition);
        properties.put("conditionVersion", condition == null ? null : "2.0");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", "Microsoft.Authorization/roleAssignments");
        body.put("name", name);
        body.put("properties", properties);
        if (tenant != null) {
            body.put("_tenant", tenant);
        }
        assignments.put(body);
        return id;
    }

    /** A factory whose every store is {@code backend}. */
    private static StorageFactory over(StorageBackend<String, StoredObject> backend) {
        return new StorageFactory(null) {
            @Override
            public synchronized StorageBackend<String, StoredObject> create(String serviceName) {
                return backend;
            }
        };
    }

    /** An unsigned token: the claims are read, never verified. */
    private static String bearer(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
