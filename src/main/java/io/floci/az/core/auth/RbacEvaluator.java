package io.floci.az.core.auth;

import io.floci.az.core.auth.RbacPermissions.Outcome;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.authorization.RoleAssignmentStore;
import io.floci.az.services.authorization.RoleAssignmentStore.AssignmentView;
import io.floci.az.services.authorization.RoleDefinitionCatalog;
import io.floci.az.services.authorization.RoleDefinitionCatalog.RoleDefinition;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.managementgroups.ManagementGroupStore;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a principal may perform an action or a data action at a scope, from the role assignments floci-az
 * stores, the way Azure RBAC decides it ("How Azure RBAC determines if a user has access to a resource", What is
 * Azure RBAC). It only answers: a data plane that honours Azure RBAC asks it, behind
 * {@code floci-az.parity-overrides.strict-rbac}, and turns a denial into its own 403.
 *
 * <ul>
 *   <li><b>Scope.</b> An assignment applies at its own scope and every scope below it: "Lower levels inherit role
 *       permissions from higher levels" (Understand scope for Azure RBAC). The scope asked about is a full ARM id,
 *       possibly with a data plane child path ({@code .../searchServices/x/indexes/y}). It lies below each of its
 *       parent paths, its subscription's management group and that group's ancestors (floci-az's management-group
 *       hierarchy, where an unplaced subscription sits under the tenant root group), and the tenant root {@code /}.
 *       Scopes compare ignoring case. Management groups and the tenant root are per tenant: the subscription's
 *       tenant for a scope inside one, the caller's otherwise, as {@code RbacHandler} lists them.</li>
 *   <li><b>Principal.</b> An assignment applies to its principal and, through groups, to every member,
 *       transitively: "Role assignments are transitive for groups". The caller's groups are those of the token's
 *       {@code groups} claim plus every group the Entra directory holds it in, directly or through nested groups,
 *       read in the directory of the scope's tenant. Ids compare ignoring case.</li>
 *   <li><b>Permissions.</b> A role grants as {@link RbacPermissions} decides: actions minus notActions per permission
 *       entry, data actions only from dataActions, wildcards, ignoring case. Assignments add up: "Azure RBAC is an
 *       additive model".</li>
 *   <li><b>Roles.</b> The built-in roles of {@link RoleDefinitionCatalog}. floci-az refuses custom role definitions,
 *       so there are no others; an assignment naming an unknown role grants nothing.</li>
 *   <li><b>Conditions (Azure ABAC), decided conservatively.</b> A condition "filters down permissions", and access is
 *       allowed only "if conditions are met" (What is Azure ABAC; What is Azure RBAC, steps 8 and 9). floci-az does
 *       not evaluate conditions, so an assignment with a {@code condition}, or a role permission entry with one,
 *       grants nothing: the evaluator never allows what Azure might refuse, though it may refuse what Azure would
 *       allow. The reason names such assignments.</li>
 *   <li><b>Deny assignments.</b> None: floci-az keeps none, and no client can create one ("Deny assignments are
 *       created and managed by Azure", List Azure deny assignments).</li>
 * </ul>
 */
@ApplicationScoped
public class RbacEvaluator {

    private static final Logger LOG = Logger.getLogger(RbacEvaluator.class);

    private static final Pattern SUBSCRIPTION_SCOPE = Pattern.compile("subscriptions/([^/]+)(?:/.*)?");
    private static final Pattern MANAGEMENT_GROUP_SCOPE =
            Pattern.compile("providers/microsoft\\.management/managementgroups/([^/]+)(?:/.*)?");
    private static final String MANAGEMENT_GROUP_PREFIX = "providers/microsoft.management/managementgroups/";

    private final RoleAssignmentStore assignments;
    private final DirectoryReader directory;
    private final AzureDirectory tenants;
    private final ManagementGroupStore managementGroups;

    /** The keys of a scope and every scope above it, and the tenant whose hierarchy and directory they belong to. */
    private record ScopeChain(String tenantId, Set<String> keys) {
    }

    @Inject
    public RbacEvaluator(RoleAssignmentStore assignments, DirectoryReader directory, AzureDirectory tenants,
                         ManagementGroupStore managementGroups) {
        this.assignments = assignments;
        this.directory = directory;
        this.tenants = tenants;
        this.managementGroups = managementGroups;
    }

    /**
     * Decides whether {@code principal} may perform {@code operation} at {@code scope}.
     *
     * @param principal who asks: an object id, and optionally the token's tenant and groups
     * @param scope     a full ARM id: {@code /} (the tenant root), a management group, a subscription, a resource
     *                  group, a resource, or a resource with a data plane child path
     * @param operation the action or data action
     */
    public RbacDecision evaluate(RbacPrincipal principal, String scope, RbacOperation operation) {
        RbacDecision decision = decide(principal, scope, operation);
        LOG.debugv("RBAC {0}: {1}", decision.allowed() ? "allowed" : "denied", decision.reason());
        return decision;
    }

    private RbacDecision decide(RbacPrincipal principal, String scope, RbacOperation operation) {
        String principalId = principal == null ? null : principal.objectId();
        if (operation == null || isBlank(operation.name())) {
            return denied(principalId, scope, operation, "No operation was named, so no role grants it.");
        }
        if (isBlank(scope)) {
            return denied(principalId, scope, operation, "No scope was named, so no role assignment applies.");
        }
        if (principal == null || (isBlank(principalId) && principal.groupIds().isEmpty())) {
            return denied(principalId, scope, operation,
                    "The caller has no object id, so no role assignment applies to it.");
        }
        ScopeChain chain = scopeChain(stripSlashes(scope), callerTenant(principal.tenantId()));
        List<AssignmentView> applicable = applicable(principal, chain);
        if (applicable.isEmpty()) {
            String reason = ("No role assignment of %s or of its groups applies at scope '%s' or above it, so %s is "
                    + "not granted.").formatted(who(principalId), scope, what(operation));
            return denied(principalId, scope, operation, reason);
        }
        return weigh(principalId, scope, operation, applicable);
    }

    /** The assignments at the chain's scope or above it whose principal is the caller or one of its groups. */
    private List<AssignmentView> applicable(RbacPrincipal principal, ScopeChain chain) {
        List<AssignmentView> atScope = new ArrayList<>();
        for (AssignmentView assignment : assignments.views()) {
            if (chain.keys().contains(assignmentKey(assignment))) {
                atScope.add(assignment);
            }
        }
        if (atScope.isEmpty()) {
            return atScope;
        }
        Set<String> principalIds = principalIds(principal, chain.tenantId());
        List<AssignmentView> applicable = new ArrayList<>();
        for (AssignmentView assignment : atScope) {
            if (assignment.principalId() != null && principalIds.contains(lower(assignment.principalId()))) {
                applicable.add(assignment);
            }
        }
        return applicable;
    }

    /** The decision among the assignments that apply to the principal at the scope. */
    private static RbacDecision weigh(String principalId, String scope, RbacOperation operation,
                                      List<AssignmentView> applicable) {
        List<String> granting = new ArrayList<>();
        List<String> grantingDescribed = new ArrayList<>();
        List<String> considered = new ArrayList<>();
        List<String> conditional = new ArrayList<>();
        for (AssignmentView assignment : applicable) {
            Optional<RoleDefinition> role = RoleDefinitionCatalog.byId(assignment.roleDefinitionId());
            Outcome outcome = role.map(definition -> RbacPermissions.outcome(definition, operation))
                    .orElse(Outcome.DOES_NOT_GRANT);
            String described = describe(assignment, role);
            considered.add(described);
            if (outcome == Outcome.GRANTS && isBlank(assignment.condition())) {
                granting.add(assignment.id());
                grantingDescribed.add(described);
            } else if (outcome != Outcome.DOES_NOT_GRANT) {
                conditional.add("'" + assignment.id() + "'");
            }
        }
        if (!granting.isEmpty()) {
            String reason = "Granted %s at scope '%s' to %s by %s %s.".formatted(what(operation), scope,
                    who(principalId), assignmentsNoun(granting.size()), String.join(", ", grantingDescribed));
            return new RbacDecision(true, principalId, scope, operation, granting, reason);
        }
        String reason = "None of the role assignments of %s at scope '%s' or above it grants %s: %s.".formatted(
                who(principalId), scope, what(operation), String.join(", ", considered));
        if (!conditional.isEmpty()) {
            reason += " %s %s would grant it only under an ABAC condition, which floci-az does not evaluate.".formatted(
                    conditional.size() == 1 ? "Role assignment" : "Role assignments", String.join(", ", conditional));
        }
        return denied(principalId, scope, operation, reason);
    }

    /**
     * {@code path} and every scope above it, lowercased: its parent paths, its subscription's management group and
     * that group's ancestors, and the tenant root, tenant-qualified as {@link #qualified} says.
     */
    private ScopeChain scopeChain(String path, String callerTenant) {
        String key = lower(path);
        String tenant = callerTenant;
        String group = null;
        Matcher subscription = SUBSCRIPTION_SCOPE.matcher(key);
        Matcher managementGroup = MANAGEMENT_GROUP_SCOPE.matcher(key);
        if (subscription.matches()) {
            tenant = tenants.tenantOfSubscription(subscription.group(1));
            group = managementGroups.parentOf(tenant, subscription.group(1)).name();
        } else if (managementGroup.matches()) {
            group = managementGroup.group(1);
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(qualified("", tenant));
        if (group != null) {
            keys.add(qualified(MANAGEMENT_GROUP_PREFIX + lower(group), tenant));
            for (Group ancestor : managementGroups.ancestors(tenant, group)) {
                keys.add(qualified(MANAGEMENT_GROUP_PREFIX + lower(ancestor.name()), tenant));
            }
        }
        StringBuilder prefix = new StringBuilder();
        for (String segment : key.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            keys.add(qualified(prefix.append(segment).toString(), tenant));
        }
        return new ScopeChain(tenant, keys);
    }

    /** An assignment's scope as {@link #scopeChain} keys it; null when it has no scope, so it applies nowhere. */
    private String assignmentKey(AssignmentView assignment) {
        if (assignment.scope() == null) {
            return null;
        }
        String tenant = assignment.tenantId() != null ? assignment.tenantId() : tenants.defaultTenantId();
        return qualified(lower(stripSlashes(assignment.scope())), tenant);
    }

    /**
     * A lowercased scope key, qualified with its tenant ({@code {tenant}|{key}}) at the tenant root and a management
     * group, whose ids do not name their tenant; any other scope is unique across tenants as it is.
     */
    private static String qualified(String scopeKey, String tenant) {
        boolean tenantBound = scopeKey.isEmpty() || MANAGEMENT_GROUP_SCOPE.matcher(scopeKey).matches();
        return tenantBound ? lower(tenant) + "|" + scopeKey : scopeKey;
    }

    /** The token's tenant when the directory holds it, else the default tenant, as {@code RbacHandler} reads it. */
    private String callerTenant(String tokenTenantId) {
        return tenants.tenant(tokenTenantId).map(Tenant::id).orElse(tenants.defaultTenantId());
    }

    /** The principal's own id and its groups, with every group those groups are in, transitively; lowercased. */
    private Set<String> principalIds(RbacPrincipal principal, String tenantId) {
        Set<String> ids = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(principal.groupIds());
        if (!isBlank(principal.objectId())) {
            pending.addFirst(principal.objectId());
        }
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (ids.add(lower(id))) {
                pending.addAll(directory.memberGroupIds(tenantId, id));
            }
        }
        return ids;
    }

    private static String describe(AssignmentView assignment, Optional<RoleDefinition> role) {
        String roleName = role.map(RoleDefinition::roleName)
                .orElse("unknown role '" + assignment.roleDefinitionId() + "'");
        return "'%s' (%s at '%s')".formatted(assignment.id(), roleName, assignment.scope());
    }

    private static String who(String principalId) {
        return principalId == null ? "the caller" : "principal '" + principalId + "'";
    }

    private static String assignmentsNoun(int count) {
        return count == 1 ? "role assignment" : "role assignments";
    }

    private static String what(RbacOperation operation) {
        return operation.kind() + " '" + operation.name() + "'";
    }

    private static RbacDecision denied(String principalId, String scope, RbacOperation operation, String reason) {
        return new RbacDecision(false, principalId, scope, operation, List.of(), reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String stripSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
