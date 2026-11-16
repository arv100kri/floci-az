package io.floci.az.services.network;

/**
 * A service that owns the state of the private endpoint connections to its own resources, as the provider of a
 * private link resource does in Azure: the connections are approved, rejected and removed on the service's side,
 * through its {@code privateEndpointConnections}, and a private endpoint's own PUT does not change their state.
 *
 * <p>{@link NetworkService} asks every owner when it writes a private endpoint. A connection to an owned resource keeps
 * the state its endpoint already holds for it (same connection name, same target), and a new one starts
 * {@code Approved} ({@code privateLinkServiceConnections}) or {@code Pending} ({@code manualPrivateLinkServiceConnections})
 * whatever state the body sends. Connections to resources no owner claims are written as they always were. The owner
 * records its decisions with {@link NetworkService#updatePrivateEndpoint}.
 */
public interface PrivateLinkResourceOwner {

    /** Whether the connections to {@code privateLinkServiceId}, an ARM resource id, are this owner's to approve. */
    boolean ownsConnectionsTo(String privateLinkServiceId);
}
