package io.floci.az.services.search.network;

import java.util.Optional;

/**
 * A floci-az service that models the private endpoint connections of its own resources, so that a shared private link
 * to one of them waits, under {@code strict-network}, until the connection is approved on that resource, as Azure's
 * resource owner approves it ("Approval of the private endpoint connection is granted on the Azure PaaS side").
 *
 * <p>No floci-az service implements it yet. Storage (blob, table, dfs, file), Cosmos DB ({@code Sql}) and Key Vault
 * ({@code vault}) would, once they serve their own {@code privateEndpointConnections}; until then every shared private
 * link is approved when it is written, with or without {@code strict-network}.
 */
public interface SharedPrivateLinkTarget {

    /** Whether this service models the private endpoint connections of {@code resourceId} for {@code groupId}. */
    boolean models(String resourceId, String groupId);

    /**
     * Asks the resource for a connection on behalf of a shared private link: the target records a {@code Pending}
     * connection carrying the link's request message, which its owner then approves or rejects. Asking again for the
     * same link replaces the request.
     */
    void request(Request request);

    /**
     * The state of the connection a link requested: {@code Pending}, {@code Approved}, {@code Rejected} or
     * {@code Disconnected}; empty when the target holds none for it.
     */
    Optional<String> status(String linkId);

    /** Withdraws the connection a link requested: the link was deleted, or now names another resource. */
    void withdraw(String linkId);

    /**
     * A shared private link asking a resource for a private endpoint connection.
     *
     * @param linkId                the ARM id of the shared private link
     * @param searchServiceId       the ARM id of the search service that owns the link
     * @param privateLinkResourceId the resource asked
     * @param groupId               the group id (sub-resource) asked for
     * @param requestMessage        the message for the resource's owner, or null
     */
    record Request(String linkId, String searchServiceId, String privateLinkResourceId, String groupId,
                   String requestMessage) {
    }
}
