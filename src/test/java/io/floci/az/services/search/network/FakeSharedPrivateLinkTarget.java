package io.floci.az.services.search.network;

import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmResourceId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test stand-in for a service that models its resources' private endpoint connections: it claims the resources of
 * the made-up {@value #NAMESPACE} provider only, so no other test sees it, and lets a test decide a link's status the
 * way the resource's owner would.
 */
@ApplicationScoped
public class FakeSharedPrivateLinkTarget implements SharedPrivateLinkTarget, Resettable {

    public static final String NAMESPACE = "Microsoft.FlociTest";

    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private final Map<String, Request> requests = new ConcurrentHashMap<>();

    @Override
    public boolean models(String resourceId, String groupId) {
        return ArmResourceId.parse(resourceId)
            .filter(id -> NAMESPACE.equalsIgnoreCase(id.providerNamespace()))
            .isPresent();
    }

    @Override
    public void request(Request request) {
        requests.put(key(request.linkId()), request);
        statuses.put(key(request.linkId()), "Pending");
    }

    @Override
    public Optional<String> status(String linkId) {
        return Optional.ofNullable(statuses.get(key(linkId)));
    }

    @Override
    public void withdraw(String linkId) {
        statuses.remove(key(linkId));
        requests.remove(key(linkId));
    }

    /** Decides the link's connection as the resource's owner would: Approved, Rejected or Disconnected. */
    public void decide(String linkId, String status) {
        statuses.put(key(linkId), status);
    }

    /** The request the link made, if it made one and it was not withdrawn. */
    public Optional<Request> requestOf(String linkId) {
        return Optional.ofNullable(requests.get(key(linkId)));
    }

    private static String key(String linkId) {
        return linkId.toLowerCase(Locale.ROOT);
    }

    @Override
    public void clear() {
        statuses.clear();
        requests.clear();
    }
}
