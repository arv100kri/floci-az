package io.floci.az.services.search.security;

import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;

import java.util.Optional;

/**
 * Whether a request may reach a search service's data plane over the network it arrived on: public network access,
 * IP firewall rules and private endpoint connections. Azure answers a blocked caller before it looks at credentials,
 * so {@code SearchHandler} asks this policy before {@link SearchAuthorizer}.
 */
public interface SearchNetworkPolicy {

    /**
     * Checks {@code request} against {@code service}'s network settings.
     *
     * @return the refusal to answer with, or empty when the request may proceed to authorization
     */
    Optional<Denied> check(AzureRequest request, SearchServiceInfo service, SearchOperation operation);
}
