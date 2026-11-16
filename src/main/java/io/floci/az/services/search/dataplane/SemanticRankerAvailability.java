package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.arm.SearchServiceStore;
import io.floci.az.services.search.engine.semantic.SemanticOptions;
import io.floci.az.services.search.model.SearchRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a service answers semantic queries, per its {@code semanticSearch} plan: a service created through
 * Microsoft.Search with the plan {@code disabled} refuses them as a service without semantic ranking does; the
 * {@code free} and {@code standard} plans answer them. A service a data-plane request provisioned has no ARM resource
 * and counts as the default free plan. ({@code semantic-ranker: disabled} refuses them on every service, in the
 * engine.)
 */
@ApplicationScoped
public class SemanticRankerAvailability {

    private static final String DISABLED = "disabled";

    private final SearchServiceStore store;

    @Inject
    public SemanticRankerAvailability(SearchServiceStore store) {
        this.store = store;
    }

    /** Refuses {@code request} with a 400 when it is semantic and the service's semantic ranking is disabled. */
    public void requireAvailable(SearchCall call, SearchRequest request) {
        String resourceId = call.service().resourceId();
        if (resourceId == null || !SemanticOptions.isRequested(request)) {
            return;
        }
        if (store.semanticSearch(resourceId).filter(DISABLED::equalsIgnoreCase).isPresent()) {
            throw SemanticOptions.notEnabled(request);
        }
    }
}
