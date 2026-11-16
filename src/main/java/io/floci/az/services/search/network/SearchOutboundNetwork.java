package io.floci.az.services.search.network;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * What a search service may reach privately on its way out: the read-only view an indexer, a skillset or a knowledge
 * source needs before it connects to a resource through a shared private link, as an indexer with
 * {@code executionEnvironment: private} does ("Only the private execution environment can use a shared private link").
 *
 * <p>A link is usable once it is provisioned and {@code Approved}: Learn's "if the provisioning state ... is Succeeded
 * and connection state ... is Approved, it means that the shared private link resource is functional". A link is
 * provisioned as soon as its PUT answers in floci-az, so the status decides. A service a data-plane request provisioned
 * has no ARM resource and so no links.
 */
@ApplicationScoped
public class SearchOutboundNetwork {

    private final SearchServiceRegistry services;
    private final SharedPrivateLinks links;

    @Inject
    public SearchOutboundNetwork(SearchServiceRegistry services, SharedPrivateLinks links) {
        this.services = services;
        this.links = links;
    }

    /**
     * Whether the search service {@code serviceName} holds a usable shared private link to {@code groupId} (such as
     * {@code blob}) of the resource {@code targetResourceId}. Resource ids and group ids compare ignoring case.
     */
    public boolean allows(String serviceName, String targetResourceId, String groupId) {
        return links(serviceName).stream()
            .anyMatch(link -> link.usable() && link.connectsTo(targetResourceId, groupId));
    }

    /** The shared private links of the search service {@code serviceName}, with the status each has now. */
    public List<SharedPrivateLink> links(String serviceName) {
        return services.find(serviceName)
            .map(SearchServiceInfo::resourceId)
            .flatMap(ArmResourceId::parse)
            .map(links::of)
            .orElse(List.of());
    }
}
