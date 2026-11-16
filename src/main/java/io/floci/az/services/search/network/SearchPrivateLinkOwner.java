package io.floci.az.services.search.network;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.network.PrivateLinkResourceOwner;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Search's claim on the private endpoint connections to search services: they are approved, rejected and removed
 * through the service's {@code privateEndpointConnections}, so a private endpoint's own PUT keeps the state Search
 * recorded on it ({@link SearchPrivateEndpoints}).
 */
@ApplicationScoped
public class SearchPrivateLinkOwner implements PrivateLinkResourceOwner {

    /** The resource type whose connections Search owns. */
    static final String SEARCH_SERVICES = "Microsoft.Search/searchServices";

    @Override
    public boolean ownsConnectionsTo(String privateLinkServiceId) {
        return ArmResourceId.parse(privateLinkServiceId)
            .filter(id -> SEARCH_SERVICES.equalsIgnoreCase(id.typePath()) && id.name() != null)
            .isPresent();
    }
}
