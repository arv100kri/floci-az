package io.floci.az.services.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.core.Resettable;
import io.floci.az.services.search.arm.SearchServiceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The search services this emulator knows, by name: the ones created through {@code Microsoft.Search}, and the ones a
 * data-plane request named first, which are provisioned on use with the well-known development keys. The data plane
 * reads a service's keys and access settings from here and nowhere else.
 *
 * <p>Service names are DNS labels, so lookups ignore case. All access goes through methods: callers hold a
 * normal-scoped client proxy.</p>
 *
 * <p>The services created through ARM outlive a restart in the {@link SearchServiceStore}; the registry reads them
 * from it before its first answer, and again after {@link #clear()}, so the data plane accepts their keys from the
 * first request. A data-plane-only service lives in memory only: it is provisioned again, with the same development
 * keys, on its next use.</p>
 */
@ApplicationScoped
public class SearchServiceRegistry implements Resettable {

    /** The primary admin key of a service a data-plane request provisioned, published like Azurite's account key. */
    public static final String DEV_PRIMARY_ADMIN_KEY = "FlociAzSearchDevPrimaryAdminKey0000000000000000000000";

    /** The secondary admin key of a service a data-plane request provisioned. */
    public static final String DEV_SECONDARY_ADMIN_KEY = "FlociAzSearchDevSecondaryAdminKey00000000000000000000";

    /** The query key of a service a data-plane request provisioned. */
    public static final String DEV_QUERY_KEY = "FlociAzSearchDevQueryKey000000000000000000000000000000";

    private final Map<String, SearchServiceInfo> services = new ConcurrentHashMap<>();

    /** The services created through ARM, as the store keeps them. */
    private final Supplier<List<SearchServiceInfo>> persisted;

    /** Whether {@link #services} holds the persisted services; reset by {@link #clear()}. */
    private volatile boolean loaded;

    /** The registry over the services the {@link SearchServiceStore} keeps across restarts. */
    @Inject
    public SearchServiceRegistry(SearchServiceStore store) {
        this(store::serviceInfos);
    }

    /** A registry with no persisted services, for a test that builds one directly. */
    public SearchServiceRegistry() {
        this(List::of);
    }

    SearchServiceRegistry(Supplier<List<SearchServiceInfo>> persisted) {
        this.persisted = persisted;
    }

    /** The service named {@code name}, if it was created through ARM or provisioned by an earlier request. */
    public Optional<SearchServiceInfo> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        load();
        return Optional.ofNullable(services.get(key(name)));
    }

    /**
     * The service named {@code name}, provisioning a data-plane-only service with the development keys when none
     * exists, the way the other floci-az data planes serve an account they were never told about.
     */
    public SearchServiceInfo findOrProvision(String name) {
        load();
        return services.computeIfAbsent(key(name), SearchServiceRegistry::dataPlaneOnly);
    }

    /** Records a service, replacing any earlier record of the same name. */
    public void put(SearchServiceInfo service) {
        load();
        services.put(key(service.name()), service);
    }

    /** Forgets the service named {@code name}; returns it, or empty when there was none. */
    public Optional<SearchServiceInfo> remove(String name) {
        if (name == null) {
            return Optional.empty();
        }
        load();
        return Optional.ofNullable(services.remove(key(name)));
    }

    @Override
    public synchronized void clear() {
        services.clear();
        loaded = false;
    }

    /**
     * Reads the persisted services in once, replacing a data-plane-only service of the same name. A write waits for
     * a load in progress, so a load that read the store before the write can never overwrite it afterwards.
     */
    private void load() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (!loaded) {
                for (SearchServiceInfo service : persisted.get()) {
                    services.put(key(service.name()), service);
                }
                loaded = true;
            }
        }
    }

    private static SearchServiceInfo dataPlaneOnly(String name) {
        return new SearchServiceInfo(name, null, DEV_PRIMARY_ADMIN_KEY, DEV_SECONDARY_ADMIN_KEY,
            List.of(new SearchQueryKey("", DEV_QUERY_KEY)), false, null, "enabled", null, null, "standard");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * One search service as the data plane needs it.
     *
     * @param name                the service name, the first DNS label of {@code {name}.search.windows.net}
     * @param resourceId          the ARM id, or null for a service a data-plane request provisioned
     * @param primaryAdminKey     the primary admin key
     * @param secondaryAdminKey   the secondary admin key
     * @param queryKeys           the query keys, which may only read documents
     * @param disableLocalAuth    whether API keys are refused
     * @param authOptions         the {@code authOptions} ARM property, or null ({@code apiKeyOnly})
     * @param publicNetworkAccess {@code enabled}, {@code disabled} or {@code securedByPerimeter}
     * @param networkRuleSet      the {@code networkRuleSet} ARM property, or null
     * @param identity            the {@code identity} ARM property, or null
     * @param sku                 the SKU name, such as {@code basic} or {@code standard}
     */
    public record SearchServiceInfo(String name, String resourceId, String primaryAdminKey, String secondaryAdminKey,
                                    List<SearchQueryKey> queryKeys, boolean disableLocalAuth, JsonNode authOptions,
                                    String publicNetworkAccess, JsonNode networkRuleSet, JsonNode identity,
                                    String sku) {

        public SearchServiceInfo {
            queryKeys = List.copyOf(queryKeys);
        }

        /** Whether {@code key} is one of the service's admin keys. */
        public boolean isAdminKey(String key) {
            return key != null && (key.equals(primaryAdminKey) || key.equals(secondaryAdminKey));
        }

        /** Whether {@code key} is one of the service's query keys. */
        public boolean isQueryKey(String key) {
            return key != null && queryKeys.stream().anyMatch(queryKey -> key.equals(queryKey.key()));
        }
    }

    /** A query key: its display name (empty for the service's default key) and value. */
    public record SearchQueryKey(String name, String key) {
    }
}
