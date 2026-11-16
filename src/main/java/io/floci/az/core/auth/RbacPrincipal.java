package io.floci.az.core.auth;

import java.util.ArrayList;
import java.util.List;

/**
 * Who an Azure RBAC check is about. The object id is enough: {@link RbacEvaluator} adds every group the directory
 * knows the principal is in, directly or through nested groups. A token's {@code groups} claim, when it carries one,
 * adds the groups it names.
 *
 * @param objectId the principal's object id (a token's {@code oid}); null when the caller has none
 * @param tenantId the tenant the caller's token was issued in ({@code tid}), or null for the default tenant. It
 *                 places a scope outside any subscription (the tenant root, a management group) in that tenant's
 *                 hierarchy; a scope inside a subscription belongs to the subscription's tenant
 * @param groupIds further principal ids the caller acts as, such as the groups of a token's {@code groups} claim
 */
public record RbacPrincipal(String objectId, String tenantId, List<String> groupIds) {

    public RbacPrincipal {
        groupIds = groupIds == null ? List.of()
                : groupIds.stream().filter(id -> id != null && !id.isBlank()).toList();
    }

    /** A principal known by its object id alone. */
    public static RbacPrincipal of(String objectId) {
        return new RbacPrincipal(objectId, null, List.of());
    }

    /** The principal a bearer token names: its {@code oid}, {@code tid} and {@code groups} claims. */
    public static RbacPrincipal of(BearerClaims claims) {
        List<String> groups = new ArrayList<>();
        if (claims.values().get("groups") instanceof List<?> named) {
            for (Object group : named) {
                if (group instanceof String id) {
                    groups.add(id);
                }
            }
        }
        return new RbacPrincipal(claims.oid(), claims.claims().get("tid"), groups);
    }
}
