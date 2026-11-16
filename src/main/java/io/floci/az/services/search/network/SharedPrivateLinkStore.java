package io.floci.az.services.search.network;

import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The shared private link resources of the search services, in an {@link ArmResourceStore} keyed by canonical id over
 * the {@value #STORE} backend that keeps the services too, so they follow {@code storage.services.search} and survive
 * a restart in a persistent mode. The store owns only the keys of its own type. Links were never stored before, so
 * there are no older entries.
 */
@ApplicationScoped
public class SharedPrivateLinkStore implements Resettable {

    /** The resource type of a shared private link. */
    public static final String TYPE = "Microsoft.Search/searchServices/sharedPrivateLinkResources";

    /** The {@link StorageFactory} backend Search keeps its services and their children in. */
    static final String STORE = "search";

    private final ArmResourceStore<SharedPrivateLink> links;

    @Inject
    public SharedPrivateLinkStore(StorageFactory storageFactory) {
        this.links = ArmResourceStore.create(storageFactory, STORE, TYPE, SharedPrivateLink.class,
            SharedPrivateLink::id, key -> false);
    }

    public Optional<SharedPrivateLink> get(ArmResourceId id) {
        return links.get(id).map(Stored::value);
    }

    /** Writes {@code change} applied to the stored link, or to empty for a new one, in one store operation. */
    public Written<SharedPrivateLink> createOrUpdate(ArmResourceId id,
                                                     Function<Optional<SharedPrivateLink>, SharedPrivateLink> change) {
        return links.createOrUpdate(id, change);
    }

    public Optional<SharedPrivateLink> delete(ArmResourceId id) {
        return links.delete(id).map(Stored::value);
    }

    /** The links of the search service {@code serviceId}, by name. */
    public List<SharedPrivateLink> of(ArmResourceId serviceId) {
        return links.under(serviceId).stream()
            .map(Stored::value)
            .sorted(Comparator.comparing(link -> link.name().toLowerCase(Locale.ROOT)))
            .toList();
    }

    @Override
    public void clear() {
        links.clear();
    }
}
