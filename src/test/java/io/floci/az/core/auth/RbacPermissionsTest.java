package io.floci.az.core.auth;

import io.floci.az.core.auth.RbacPermissions.Outcome;
import io.floci.az.services.authorization.RoleDefinitionCatalog;
import io.floci.az.services.authorization.RoleDefinitionCatalog.Permission;
import io.floci.az.services.authorization.RoleDefinitionCatalog.RoleDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action patterns and permission entries as the Azure RBAC docs define them ("Understand Azure role definitions",
 * "Azure custom roles"), and the three Azure AI Search built-in roles granting what {@code role-definitions.json}
 * lists for them, no more.
 */
class RbacPermissionsTest {

    private static final String SEARCH_INDEX_DATA_READER = "1407120a-92aa-4202-b7e9-c0e197c71c8f";
    private static final String SEARCH_INDEX_DATA_CONTRIBUTOR = "8ebe5a00-799e-43f5-93ac-243d3dce84a7";
    private static final String SEARCH_SERVICE_CONTRIBUTOR = "7ca78c08-252a-4471-8644-bb5ff32d4ba0";
    private static final String OWNER = "8e3af657-a8ff-443c-a75c-2fe8c4bcb635";
    private static final String CONTRIBUTOR = "b24988ac-6180-42a0-ab88-20f7382dd24c";
    private static final String READER = "acdd72a7-3385-48ef-bd42-f606fba81ae7";

    private static final String SERVICES = "Microsoft.Search/searchServices/";
    private static final String DOCUMENTS = SERVICES + "indexes/documents/";
    private static final String ELEVATED_READ = SERVICES + "indexes/contentSecurity/elevatedOperations/read";
    private static final String WRITE_ROLE_ASSIGNMENTS = "Microsoft.Authorization/roleAssignments/write";

    // ── Patterns ─────────────────────────────────────────────────────────────────

    @Test
    void aLoneWildcardMatchesEveryOperation() {
        assertTrue(RbacPermissions.matches("*", SERVICES + "write"));
        assertTrue(RbacPermissions.matches("*", "Microsoft.Storage/storageAccounts/listKeys/action"));
    }

    /** {@code *}/read "Grants access to read actions for all resource types of all Azure resource providers". */
    @Test
    void aWildcardSpansSegments() {
        assertTrue(RbacPermissions.matches("*/read", "Microsoft.Compute/virtualMachines/read"));
        assertTrue(RbacPermissions.matches("*/read", "Microsoft.Network/virtualNetworks/subnets/read"));
        assertTrue(RbacPermissions.matches("Microsoft.Network/*/read",
                "Microsoft.Network/virtualNetworks/subnets/read"));
        assertFalse(RbacPermissions.matches("*/read", "Microsoft.Compute/virtualMachines/write"));
        assertFalse(RbacPermissions.matches("Microsoft.Network/*/read", "Microsoft.Compute/virtualMachines/read"));
    }

    @Test
    void aProviderWildcardStaysInItsProvider() {
        assertTrue(RbacPermissions.matches("Microsoft.Search/*", DOCUMENTS + "read"));
        assertTrue(RbacPermissions.matches(SERVICES + "*", SERVICES + "indexes/write"));
        assertFalse(RbacPermissions.matches("Microsoft.Search/*", "Microsoft.SearchService/searchServices/read"));
        assertFalse(RbacPermissions.matches("Microsoft.Search/*", "Microsoft.Storage/storageAccounts/read"));
    }

    @Test
    void aWildcardInsideASegmentMatchesWhatItStandsFor() {
        assertTrue(RbacPermissions.matches("Microsoft.Search/search*/read", SERVICES + "read"));
        assertTrue(RbacPermissions.matches("Microsoft.Search/*Services/read", SERVICES + "read"));
        assertTrue(RbacPermissions.matches(DOCUMENTS + "re*", DOCUMENTS + "read"));
        assertFalse(RbacPermissions.matches("Microsoft.Search/*Services/read", SERVICES + "write"));
        assertFalse(RbacPermissions.matches(DOCUMENTS + "re*", DOCUMENTS + "write"));
    }

    @Test
    void severalWildcardsMatchTogether() {
        assertTrue(RbacPermissions.matches("*/indexes/*/read", DOCUMENTS + "read"));
        assertFalse(RbacPermissions.matches("*/indexes/*/read", SERVICES + "read"));
    }

    @Test
    void withoutAWildcardOnlyTheWholeOperationMatches() {
        assertTrue(RbacPermissions.matches(DOCUMENTS + "read", DOCUMENTS + "read"));
        assertFalse(RbacPermissions.matches(DOCUMENTS + "read", DOCUMENTS + "read/extra"));
        assertFalse(RbacPermissions.matches(DOCUMENTS + "read", DOCUMENTS + "rea"));
        assertFalse(RbacPermissions.matches(DOCUMENTS + "read", "x" + DOCUMENTS + "read"));
    }

    /** "Permission strings are case-insensitive" (Azure custom roles). */
    @Test
    void matchingIgnoresCase() {
        assertTrue(RbacPermissions.matches("MICROSOFT.SEARCH/SEARCHSERVICES/*",
                "microsoft.search/searchservices/read"));
        assertTrue(RbacPermissions.matches("Microsoft.Authorization/*/Write", WRITE_ROLE_ASSIGNMENTS));
    }

    // ── Permission entries ───────────────────────────────────────────────────────

    /** "Actions - NotActions = Effective control plane permissions", within one entry. */
    @Test
    void notActionsSubtractFromTheirEntry() {
        RoleDefinition role = role(entry(List.of("*"), List.of("Microsoft.Authorization/*/Write"), List.of(),
                List.of()));
        assertEquals(Outcome.GRANTS, action(role, SERVICES + "write"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(role, WRITE_ROLE_ASSIGNMENTS));
    }

    /** "NotActions is not a deny rule": another entry of the same role still grants what one entry excludes. */
    @Test
    void notActionsDoNotReachAnotherEntry() {
        RoleDefinition role = role(
                entry(List.of("*"), List.of("Microsoft.Authorization/*/Write"), List.of(), List.of()),
                entry(List.of(WRITE_ROLE_ASSIGNMENTS), List.of(), List.of(), List.of()));
        assertEquals(Outcome.GRANTS, action(role, WRITE_ROLE_ASSIGNMENTS));
    }

    @Test
    void notDataActionsSubtractFromDataActions() {
        RoleDefinition role = role(entry(List.of(), List.of(), List.of(DOCUMENTS + "*"),
                List.of(DOCUMENTS + "delete")));
        assertEquals(Outcome.GRANTS, dataAction(role, DOCUMENTS + "write"));
        assertEquals(Outcome.DOES_NOT_GRANT, dataAction(role, DOCUMENTS + "delete"));
    }

    /** "Control plane access is not inherited to your data plane": a wildcard action grants no data action. */
    @Test
    void actionsAndDataActionsNeverMix() {
        assertEquals(Outcome.DOES_NOT_GRANT, dataAction(builtIn(OWNER), DOCUMENTS + "read"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(builtIn(SEARCH_INDEX_DATA_READER), DOCUMENTS + "read"));
    }

    @Test
    void anEntryWithAConditionGrantsOnlyUnderIt() {
        Permission conditional = new Permission(List.of(), List.of(), List.of(DOCUMENTS + "read"), List.of(),
                "@Resource[Microsoft.Search/searchServices/indexes:name] StringEquals 'hotels'", "2.0");
        assertEquals(Outcome.GRANTS_UNDER_CONDITION, dataAction(role(conditional), DOCUMENTS + "read"));
        RoleDefinition alsoUnconditional = role(conditional,
                entry(List.of(), List.of(), List.of(DOCUMENTS + "read"), List.of()));
        assertEquals(Outcome.GRANTS, dataAction(alsoUnconditional, DOCUMENTS + "read"));
    }

    // ── Built-in roles ───────────────────────────────────────────────────────────

    @Test
    void contributorManagesEverythingButAccess() {
        RoleDefinition contributor = builtIn(CONTRIBUTOR);
        assertEquals(Outcome.GRANTS, action(contributor, SERVICES + "write"));
        assertEquals(Outcome.GRANTS, action(contributor, "Microsoft.Authorization/roleAssignments/read"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(contributor, WRITE_ROLE_ASSIGNMENTS));
    }

    @Test
    void readerReadsAndWritesNothing() {
        RoleDefinition reader = builtIn(READER);
        assertEquals(Outcome.GRANTS, action(reader, SERVICES + "indexes/read"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(reader, SERVICES + "write"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(reader, SERVICES + "listAdminKeys/action"));
    }

    /** Search Index Data Reader: the data action {@code .../indexes/documents/read}, nothing else. */
    @Test
    void searchIndexDataReaderReadsDocumentsOnly() {
        RoleDefinition role = builtIn(SEARCH_INDEX_DATA_READER);
        assertEquals(Outcome.GRANTS, dataAction(role, DOCUMENTS + "read"));
        for (String denied : List.of(DOCUMENTS + "write", DOCUMENTS + "delete", ELEVATED_READ)) {
            assertEquals(Outcome.DOES_NOT_GRANT, dataAction(role, denied), denied);
        }
        assertEquals(Outcome.DOES_NOT_GRANT, action(role, SERVICES + "read"));
    }

    /** Search Index Data Contributor: the data actions {@code documents/*} and {@code elevatedOperations/read}. */
    @Test
    void searchIndexDataContributorWritesDocumentsButManagesNothing() {
        RoleDefinition role = builtIn(SEARCH_INDEX_DATA_CONTRIBUTOR);
        for (String granted : List.of(DOCUMENTS + "read", DOCUMENTS + "write", DOCUMENTS + "delete", ELEVATED_READ)) {
            assertEquals(Outcome.GRANTS, dataAction(role, granted), granted);
        }
        assertEquals(Outcome.DOES_NOT_GRANT, dataAction(role, SERVICES + "indexes/write"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(role, SERVICES + "indexes/write"));
        assertEquals(Outcome.DOES_NOT_GRANT, action(role, SERVICES + "listAdminKeys/action"));
    }

    /** Search Service Contributor: the actions {@code Microsoft.Search/searchServices/*} and their support, no data. */
    @Test
    void searchServiceContributorManagesTheServiceButReadsNoData() {
        RoleDefinition role = builtIn(SEARCH_SERVICE_CONTRIBUTOR);
        for (String granted : List.of(SERVICES + "write", SERVICES + "indexes/write", SERVICES + "indexes/delete",
                SERVICES + "listAdminKeys/action", "Microsoft.Authorization/roleAssignments/read",
                "Microsoft.Resources/subscriptions/resourceGroups/read")) {
            assertEquals(Outcome.GRANTS, action(role, granted), granted);
        }
        for (String denied : List.of(WRITE_ROLE_ASSIGNMENTS, "Microsoft.Resources/subscriptions/resourceGroups/write",
                "Microsoft.Storage/storageAccounts/read")) {
            assertEquals(Outcome.DOES_NOT_GRANT, action(role, denied), denied);
        }
        assertEquals(Outcome.DOES_NOT_GRANT, dataAction(role, DOCUMENTS + "read"));
    }

    /**
     * Each Search role grants every action and data action its definition lists (a segment standing in for a
     * wildcard), each in its own plane only.
     */
    @Test
    void eachSearchRoleGrantsWhatItsDefinitionListsInItsPlaneOnly() {
        for (String guid : List.of(SEARCH_INDEX_DATA_READER, SEARCH_INDEX_DATA_CONTRIBUTOR,
                SEARCH_SERVICE_CONTRIBUTOR)) {
            RoleDefinition role = builtIn(guid);
            for (Permission permission : role.permissions()) {
                for (String listed : permission.actions()) {
                    String operation = listed.replace("*", "anything");
                    String what = role.roleName() + " " + operation;
                    assertEquals(Outcome.GRANTS, action(role, operation), what);
                    assertEquals(Outcome.DOES_NOT_GRANT, dataAction(role, operation), what);
                }
                for (String listed : permission.dataActions()) {
                    String operation = listed.replace("*", "anything");
                    String what = role.roleName() + " " + operation;
                    assertEquals(Outcome.GRANTS, dataAction(role, operation), what);
                    assertEquals(Outcome.DOES_NOT_GRANT, action(role, operation), what);
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Outcome action(RoleDefinition role, String name) {
        return RbacPermissions.outcome(role, RbacOperation.action(name));
    }

    private static Outcome dataAction(RoleDefinition role, String name) {
        return RbacPermissions.outcome(role, RbacOperation.dataAction(name));
    }

    private static RoleDefinition builtIn(String guid) {
        return RoleDefinitionCatalog.byGuid(guid).orElseThrow();
    }

    private static RoleDefinition role(Permission... permissions) {
        return new RoleDefinition("00000000-0000-0000-0000-00000000c0de", "Test role", "", "CustomRole", List.of("/"),
                List.of(permissions));
    }

    private static Permission entry(List<String> actions, List<String> notActions, List<String> dataActions,
                                    List<String> notDataActions) {
        return new Permission(actions, notActions, dataActions, notDataActions, null, null);
    }
}
