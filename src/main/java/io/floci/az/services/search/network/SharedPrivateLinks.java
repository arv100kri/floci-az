package io.floci.az.services.search.network;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * The shared private link resources of the search services: what a PUT leaves, and the status each link has now.
 *
 * <p>A link is approved when it is written, as floci-az's private endpoints are. Under {@code strict-network} a link to
 * a resource whose private endpoint connections floci-az models ({@link SharedPrivateLinkTarget}) is {@code Pending}
 * instead, and from then on its status is whatever that resource's owner decided. No floci-az service models those
 * connections yet, so today every link is approved, with or without {@code strict-network}.
 *
 * <p>A PUT that names the same resource and group as before keeps the link's status, so updating the request message
 * does not undo an approval; one that names another resource or group asks again.
 */
@ApplicationScoped
public class SharedPrivateLinks {

    private static final Logger LOG = Logger.getLogger(SharedPrivateLinks.class);

    private final EmulatorConfig config;
    private final SharedPrivateLinkStore store;
    private final List<SharedPrivateLinkTarget> targets;

    @Inject
    public SharedPrivateLinks(EmulatorConfig config, SharedPrivateLinkStore store,
                              @All List<SharedPrivateLinkTarget> targets) {
        this.config = config;
        this.store = store;
        this.targets = List.copyOf(targets);
    }

    /** The links of the search service {@code serviceId}, by name, with the status each has now. */
    public List<SharedPrivateLink> of(ArmResourceId serviceId) {
        return store.of(serviceId).stream().map(this::current).toList();
    }

    /** The link {@code linkId}, with the status it has now. */
    public Optional<SharedPrivateLink> get(ArmResourceId linkId) {
        return store.get(linkId).map(this::current);
    }

    /**
     * Writes the link {@code linkId} of the search service {@code serviceId}; a replace keeps the id and name the link
     * was created with.
     */
    public Written<SharedPrivateLink> put(ArmResourceId serviceId, ArmResourceId linkId, Spec spec) {
        Optional<SharedPrivateLink> existing = store.get(linkId);
        Optional<SharedPrivateLink> kept = existing
            .filter(link -> link.connectsTo(spec.privateLinkResourceId(), spec.groupId()));
        String status;
        boolean statusOnTarget;
        if (kept.isPresent()) {
            status = kept.get().status();
            statusOnTarget = kept.get().statusOnTarget();
        } else {
            existing.filter(SharedPrivateLink::statusOnTarget).ifPresent(this::withdraw);
            Optional<SharedPrivateLinkTarget> target = config.strictNetwork()
                ? target(spec.privateLinkResourceId(), spec.groupId()) : Optional.empty();
            target.ifPresent(found -> found.request(new SharedPrivateLinkTarget.Request(linkId.id(), serviceId.id(),
                spec.privateLinkResourceId(), spec.groupId(), spec.requestMessage())));
            status = target.isPresent() ? SharedPrivateLink.PENDING : SharedPrivateLink.APPROVED;
            statusOnTarget = target.isPresent();
        }
        Written<SharedPrivateLink> written = store.createOrUpdate(linkId, previous -> new SharedPrivateLink(
            previous.map(SharedPrivateLink::id).orElse(linkId.id()),
            previous.map(SharedPrivateLink::name).orElse(linkId.name()),
            spec.privateLinkResourceId(), spec.groupId(), spec.requestMessage(), spec.resourceRegion(), status,
            statusOnTarget));
        LOG.infov("ARM: {0} shared private link {1} to {2} ({3}): {4}", written.created() ? "created" : "replaced",
            linkId.name(), spec.privateLinkResourceId(), spec.groupId(), status);
        return written;
    }

    /** Deletes the link and withdraws the connection it asked for; empty when there was none. */
    public Optional<SharedPrivateLink> delete(ArmResourceId linkId) {
        Optional<SharedPrivateLink> deleted = store.delete(linkId);
        deleted.filter(SharedPrivateLink::statusOnTarget).ifPresent(this::withdraw);
        deleted.ifPresent(link -> LOG.infov("ARM: deleted shared private link {0}", link.name()));
        return deleted;
    }

    /** Deletes every link of the search service {@code serviceId}: the service is gone. */
    public void deleteAll(ArmResourceId serviceId) {
        for (SharedPrivateLink link : store.of(serviceId)) {
            ArmResourceId.parse(link.id()).ifPresent(this::delete);
        }
    }

    /**
     * {@code link} with the status it has now: the one recorded, or the target's when the target decides it, which is
     * {@code Disconnected} once the target holds no connection for the link.
     */
    private SharedPrivateLink current(SharedPrivateLink link) {
        if (!link.statusOnTarget()) {
            return link;
        }
        return target(link.privateLinkResourceId(), link.groupId())
            .map(target -> link.withStatus(target.status(link.id()).orElse(SharedPrivateLink.DISCONNECTED)))
            .orElse(link);
    }

    private void withdraw(SharedPrivateLink link) {
        target(link.privateLinkResourceId(), link.groupId()).ifPresent(target -> target.withdraw(link.id()));
    }

    private Optional<SharedPrivateLinkTarget> target(String resourceId, String groupId) {
        return targets.stream().filter(target -> target.models(resourceId, groupId)).findFirst();
    }

    /**
     * What a PUT sets on a link.
     *
     * @param privateLinkResourceId the resource the link connects to
     * @param groupId               its group id
     * @param requestMessage        the message for the resource's owner, or null
     * @param resourceRegion        the resource's location for regional DNS, or null
     */
    public record Spec(String privateLinkResourceId, String groupId, String requestMessage, String resourceRegion) {
    }
}
