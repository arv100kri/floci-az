package io.floci.az.services.search.arm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.arm.SearchArmModels.SearchServiceRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The search services created through ARM, in an {@link ArmResourceStore} keyed by canonical id over the
 * {@value #STORE} backend, so they follow {@code storage.services.search} and survive a restart in a persistent mode.
 * The data plane keeps its own entries in the same backend under keys that are not ARM ids, which this store never
 * reads. Search services were never stored before, so there are no older entries.
 *
 * <p>Also the source the {@code SearchServiceRegistry} rebuilds from: {@link #serviceInfos()} is every stored service
 * as the data plane sees it.
 */
@ApplicationScoped
public class SearchServiceStore implements Resettable {

    /** The {@link StorageFactory} backend the services live in, shared with the search data plane. */
    static final String STORE = "search";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArmResourceStore<SearchServiceRecord> services;

    @Inject
    public SearchServiceStore(StorageFactory storageFactory) {
        this.services = ArmResourceStore.create(storageFactory, STORE, SearchServiceDocuments.TYPE,
            SearchServiceRecord.class, SearchServiceRecord::id, key -> false);
    }

    public Optional<SearchServiceRecord> get(ArmResourceId id) {
        return services.get(id).map(Stored::value);
    }

    /** Writes {@code change} applied to the stored service, or to empty for a new one, in one store operation. */
    public Written<SearchServiceRecord> createOrUpdate(ArmResourceId id,
                                                       Function<Optional<SearchServiceRecord>, SearchServiceRecord> change) {
        return services.createOrUpdate(id, change);
    }

    /** Applies {@code change} to the stored service; empty when there is none. */
    public Optional<SearchServiceRecord> update(ArmResourceId id, UnaryOperator<SearchServiceRecord> change) {
        return services.update(id, change).map(Stored::value);
    }

    public Optional<SearchServiceRecord> delete(ArmResourceId id) {
        return services.delete(id).map(Stored::value);
    }

    /** The services in a subscription, or in one of its resource groups, in the order of their ids. */
    public List<SearchServiceRecord> list(ArmScope scope) {
        return services.list(scope).stream()
            .sorted(Comparator.comparing(stored -> stored.id().canonicalKey()))
            .map(Stored::value)
            .toList();
    }

    public List<Stored<SearchServiceRecord>> all() {
        return services.all();
    }

    /** Every stored service as the data plane sees it. */
    public List<SearchServiceInfo> serviceInfos() {
        return services.all().stream().map(stored -> info(stored.value())).toList();
    }

    /**
     * {@code service} as the data plane sees it. The service's first query key has no name, which the registry spells
     * as the empty string; the public network access setting takes the registry's camel case.
     */
    static SearchServiceInfo info(SearchServiceRecord service) {
        List<SearchQueryKey> queryKeys = service.queryKeys().stream()
            .map(key -> new SearchQueryKey(key.name() == null ? "" : key.name(), key.key()))
            .toList();
        return new SearchServiceInfo(service.name(), service.id(), service.primaryAdminKey(),
            service.secondaryAdminKey(), queryKeys, service.settings().disableLocalAuth(),
            tree(SearchServiceDocuments.authOptions(service.settings().authOptions())),
            camelCase(service.settings().publicNetworkAccess()),
            tree(SearchServiceDocuments.networkRuleSet(service.settings().networkRuleSet())),
            tree(SearchServiceDocuments.identity(service.identity())), service.sku());
    }

    private static JsonNode tree(Object value) {
        return value == null ? null : MAPPER.valueToTree(value);
    }

    private static String camelCase(String value) {
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }

    @Override
    public void clear() {
        services.clear();
    }
}
