package io.floci.az.services.search.network;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A shared private link resource of a search service, as the store keeps it: what its PUT set, and the status it got.
 *
 * @param id                    the ARM id, in the casing of the request that created it
 * @param name                  the link's name, in that casing
 * @param privateLinkResourceId the ARM id of the resource the link connects to
 * @param groupId               the group id (sub-resource) of that resource, such as {@code blob}
 * @param requestMessage        the message asking the resource's owner for approval, or null
 * @param resourceRegion        the location of the resource, for resources with regional DNS, or null
 * @param status                {@code Pending}, {@code Approved}, {@code Rejected} or {@code Disconnected}, as
 *                              recorded at the last PUT
 * @param statusOnTarget        whether the resource decides the status instead: a link written under
 *                              {@code strict-network} to a resource whose private endpoint connections floci-az models
 *                              ({@link SharedPrivateLinkTarget})
 */
@RegisterForReflection
public record SharedPrivateLink(String id, String name, String privateLinkResourceId, String groupId,
                                String requestMessage, String resourceRegion, String status,
                                boolean statusOnTarget) {

    public static final String PENDING = "Pending";
    public static final String APPROVED = "Approved";
    public static final String DISCONNECTED = "Disconnected";

    /** The provisioning state of every link: its creation completes before the PUT answers. */
    public static final String SUCCEEDED = "Succeeded";

    /** This link with {@code newStatus} as its status. */
    public SharedPrivateLink withStatus(String newStatus) {
        return new SharedPrivateLink(id, name, privateLinkResourceId, groupId, requestMessage, resourceRegion,
            newStatus, statusOnTarget);
    }

    /** Whether the link connects to {@code resourceId}'s {@code group}: both compare ignoring case. */
    public boolean connectsTo(String resourceId, String group) {
        return privateLinkResourceId != null && groupId != null && privateLinkResourceId.equalsIgnoreCase(resourceId)
            && groupId.equalsIgnoreCase(group);
    }

    /** Whether the link is usable: provisioned (always, in floci-az) and {@code Approved}. */
    public boolean usable() {
        return APPROVED.equalsIgnoreCase(status);
    }
}
