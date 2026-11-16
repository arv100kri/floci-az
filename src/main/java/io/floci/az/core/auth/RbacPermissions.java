package io.floci.az.core.auth;

import io.floci.az.services.authorization.RoleDefinitionCatalog.Permission;
import io.floci.az.services.authorization.RoleDefinitionCatalog.RoleDefinition;

import java.util.List;
import java.util.Locale;

/**
 * How a role definition's permissions answer one operation, after the Azure RBAC docs.
 *
 * <ul>
 *   <li>A permission entry grants an operation when one of its {@code actions} matches it and none of the same
 *       entry's {@code notActions} does, or for a data action its {@code dataActions} and {@code notDataActions}:
 *       "Actions - NotActions = Effective control plane permissions" (Understand Azure role definitions). A
 *       {@code notActions} entry only subtracts from its own entry, never from another entry or role: "NotActions
 *       is not a deny rule".</li>
 *   <li>A role grants an operation when any of its entries does.</li>
 *   <li>A {@code *} matches any run of characters, {@code /} included, so {@code *}, {@code Microsoft.Search/*},
 *       <code>&#42;/read</code> and a wildcard inside a segment all match everything the string they stand in
 *       covers: "A wildcard (*) extends a permission to everything that matches the action string you provide"
 *       (Azure custom roles). Matching ignores case: "Permission strings are case-insensitive".</li>
 *   <li>An entry with a {@code condition} grants only under that condition. floci-az does not evaluate ABAC
 *       conditions, so such an entry is reported as {@link Outcome#GRANTS_UNDER_CONDITION} and the evaluator lets it
 *       grant nothing.</li>
 * </ul>
 */
final class RbacPermissions {

    /** What a role does for one operation. */
    enum Outcome {
        GRANTS,
        /** Only an entry whose ABAC condition floci-az cannot evaluate grants it. */
        GRANTS_UNDER_CONDITION,
        DOES_NOT_GRANT
    }

    private RbacPermissions() {
    }

    static Outcome outcome(RoleDefinition role, RbacOperation operation) {
        boolean underCondition = false;
        for (Permission permission : role.permissions()) {
            if (!grants(permission, operation)) {
                continue;
            }
            if (permission.condition() == null || permission.condition().isBlank()) {
                return Outcome.GRANTS;
            }
            underCondition = true;
        }
        return underCondition ? Outcome.GRANTS_UNDER_CONDITION : Outcome.DOES_NOT_GRANT;
    }

    /** Whether one entry grants the operation, its condition aside. */
    static boolean grants(Permission permission, RbacOperation operation) {
        List<String> allowed = operation.dataAction() ? permission.dataActions() : permission.actions();
        List<String> excluded = operation.dataAction() ? permission.notDataActions() : permission.notActions();
        return anyMatches(allowed, operation.name()) && !anyMatches(excluded, operation.name());
    }

    /** Whether {@code pattern}, with {@code *} standing for any run of characters, matches all of {@code operation}. */
    static boolean matches(String pattern, String operation) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String s = operation.toLowerCase(Locale.ROOT);
        int pi = 0;
        int si = 0;
        int star = -1;
        int resume = 0;
        while (si < s.length()) {
            if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                resume = si;
            } else if (pi < p.length() && p.charAt(pi) == s.charAt(si)) {
                pi++;
                si++;
            } else if (star >= 0) {
                pi = star + 1;
                si = ++resume;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }

    private static boolean anyMatches(List<String> patterns, String operation) {
        for (String pattern : patterns) {
            if (matches(pattern, operation)) {
                return true;
            }
        }
        return false;
    }
}
