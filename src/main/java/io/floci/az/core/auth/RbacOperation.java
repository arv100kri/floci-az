package io.floci.az.core.auth;

/**
 * The operation an Azure RBAC check asks about. A control plane action is matched against a role's {@code actions}
 * and {@code notActions}, a data action against its {@code dataActions} and {@code notDataActions}: the two never
 * mix, so a wildcard in {@code actions} grants no data action ("Control and data actions", Understand Azure role
 * definitions). Names have the form {@code {Company}.{ProviderName}/{resourceType}/{action}} and compare ignoring
 * case.
 *
 * @param name       the operation, such as {@code Microsoft.Search/searchServices/indexes/documents/read}
 * @param dataAction true for a data action, false for a control plane action
 */
public record RbacOperation(String name, boolean dataAction) {

    /** A control plane action, such as {@code Microsoft.Search/searchServices/indexes/write}. */
    public static RbacOperation action(String name) {
        return new RbacOperation(name, false);
    }

    /** A data action, such as {@code Microsoft.Search/searchServices/indexes/documents/read}. */
    public static RbacOperation dataAction(String name) {
        return new RbacOperation(name, true);
    }

    /** {@code action} or {@code data action}, for messages. */
    public String kind() {
        return dataAction ? "data action" : "action";
    }
}
