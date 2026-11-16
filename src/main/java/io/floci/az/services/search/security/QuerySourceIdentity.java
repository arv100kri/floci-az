package io.floci.az.services.search.security;

import io.floci.az.core.auth.BearerClaims;
import io.floci.az.core.auth.RbacPrincipal;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.entra.DirectoryReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The end user an {@code x-ms-query-source-authorization} token names, as document-level permission filters match it
 * ("Query-time ACL and RBAC enforcement"): {@code userIds} hold the token's object id ({@code oid}), and
 * {@code groupIds} the user's groups, which Azure resolves through Microsoft Graph. floci-az resolves them the way its
 * RBAC evaluator does: the groups of the token's {@code groups} claim, plus every group its Entra directory holds the
 * user in, directly or through nested groups, read in the token's tenant ({@code tid}) when the directory holds it
 * and in the default tenant otherwise.
 */
@ApplicationScoped
public class QuerySourceIdentity {

    private final DirectoryReader directory;
    private final AzureDirectory tenants;

    @Inject
    public QuerySourceIdentity(DirectoryReader directory, AzureDirectory tenants) {
        this.directory = directory;
        this.tenants = tenants;
    }

    /** The user's object id and the ids of its groups, as the token and the directory spell them. */
    public Set<String> principalIds(BearerClaims claims) {
        RbacPrincipal principal = RbacPrincipal.of(claims);
        String tenant = tenants.tenant(principal.tenantId()).map(Tenant::id).orElse(tenants.defaultTenantId());
        Set<String> ids = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(principal.groupIds());
        if (principal.objectId() != null && !principal.objectId().isBlank()) {
            pending.addFirst(principal.objectId());
        }
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (visited.add(id.toLowerCase(Locale.ROOT))) {
                ids.add(id);
                pending.addAll(directory.memberGroupIds(tenant, id));
            }
        }
        return ids;
    }
}
