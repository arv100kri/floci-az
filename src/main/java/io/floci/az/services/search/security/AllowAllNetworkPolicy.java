package io.floci.az.services.search.security;

import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/** The network policy used until one that models network isolation is present: every request may proceed. */
@DefaultBean
@ApplicationScoped
public class AllowAllNetworkPolicy implements SearchNetworkPolicy {

    @Override
    public Optional<Denied> check(AzureRequest request, SearchServiceInfo service, SearchOperation operation) {
        return Optional.empty();
    }
}
