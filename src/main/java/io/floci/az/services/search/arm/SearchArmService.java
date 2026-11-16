package io.floci.az.services.search.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.auth.BearerClaims;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.services.entra.TokenIssuer;
import io.floci.az.services.managedidentity.ManagedIdentityStore;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.arm.SearchArmModels.QueryKey;
import io.floci.az.services.search.arm.SearchArmModels.SearchServiceRecord;
import io.floci.az.services.search.arm.SearchArmModels.UserIdentity;
import io.floci.az.services.search.arm.SearchServiceWrite.RefusedWrite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Microsoft.Search's management plane: search services, their admin and query API keys, and
 * {@code checkNameAvailability}, as the 2026-11-01 spec defines them; earlier api-versions are answered the same way.
 * Reached through {@code ArmHandler}'s provider lane, inside the ARM request gate, which owns each service name
 * ({@link SearchArmSurface}) before a PUT gets here.
 *
 * <p>A create or update completes before it answers: the service is {@code running} and {@code succeeded} at once,
 * with no long-running operation to poll, as floci-az's other ARM services answer. A create is {@code 201} and a
 * replace {@code 200}; a delete is {@code 200}, or {@code 204} for a service that does not exist, as the spec lists.
 *
 * <p>The services are kept in the {@link SearchServiceStore}, and every write is mirrored into the
 * {@link SearchServiceRegistry} at once, so the data plane accepts a service's keys the moment ARM answers with them:
 * a create or update puts the service, replacing a service a data-plane request provisioned under the same name, and
 * a delete removes it. Writes are serialized, so the registry never holds an older write than the store.
 */
@ApplicationScoped
public class SearchArmService implements ArmProviderService, StoreIndexSource, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(SearchArmService.class);

    private static final String SEARCH_SERVICES = "searchServices";

    /** The most query keys a service holds (Learn: "API key limits"). */
    private static final int MAX_QUERY_KEYS = 50;

    private final EmulatorConfig config;
    private final SearchServiceStore store;
    private final SearchServiceRegistry registry;
    private final GlobalNameRegistry names;
    private final AzureDirectory directory;
    private final CloudDefaults defaults;
    private final ManagedIdentityStore identities;
    /** Serializes every write, so the store and the registry see writes in the same order. */
    private final Object writes = new Object();

    @Inject
    public SearchArmService(EmulatorConfig config, SearchServiceStore store, SearchServiceRegistry registry,
                            GlobalNameRegistry names, AzureDirectory directory, CloudDefaults defaults,
                            ManagedIdentityStore identities) {
        this.config = config;
        this.store = store;
        this.registry = registry;
        this.names = names;
        this.directory = directory;
        this.defaults = defaults;
        this.identities = identities;
    }

    @Override
    public Set<String> providerNamespaces() {
        return Set.of(SearchArmSurface.NAMESPACE);
    }

    @Override
    public boolean servesSubscriptionPaths() {
        return true;
    }

    @Override
    public boolean armEnabled() {
        return config.services().search().enabled();
    }

    @Override
    public Response handleArm(AzureRequest req, String path, String method, String sub) {
        Optional<ArmResourceId> parsed = ArmResourceId.parse(path);
        if (parsed.isEmpty() || !SearchArmSurface.NAMESPACE.equalsIgnoreCase(parsed.get().providerNamespace())) {
            return armNotFound(path);
        }
        ArmResourceId id = parsed.get();
        try {
            return id.resourceGroup() == null
                ? subscriptionPath(req, path, method, id)
                : resourceGroupPath(req, path, method, id);
        } catch (RefusedWrite refused) {
            return refused.response();
        }
    }

    // ── Subscription level ──────────────────────────────────────────────────

    private Response subscriptionPath(AzureRequest req, String path, String method, ArmResourceId id) {
        if (id.types().size() != 1 || !id.names().isEmpty()) {
            return armNotFound(path);
        }
        String type = id.types().getFirst();
        if (type.equalsIgnoreCase("checkNameAvailability")) {
            return "POST".equals(method) ? checkNameAvailability(req) : Response.status(405).build();
        }
        if (type.equalsIgnoreCase(SEARCH_SERVICES)) {
            return "GET".equals(method) ? list(req, new ArmScope(id.subscriptionId(), null))
                : Response.status(405).build();
        }
        return armNotFound(path);
    }

    /**
     * {@code Services_CheckNameAvailability}: {@code Invalid} for a name outside the service name rule, whatever
     * {@code strict-names} says, and {@code AlreadyExists} for a name the name registry holds in any subscription,
     * with the empty message the spec's example answers. The spec's example shows no available name; one is answered
     * {@code nameAvailable: true} with the same empty message.
     */
    private Response checkNameAvailability(AzureRequest req) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Object type = body.get("type");
        if (type != null && !SEARCH_SERVICES.equalsIgnoreCase(String.valueOf(type))) {
            throw SearchServiceWrite.refused("The type '" + type + "' is not valid; the type must be '"
                + SEARCH_SERVICES + "'.");
        }
        if (!(body.get("name") instanceof String name)) {
            throw SearchServiceWrite.wrongType("name", "a string");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (!SearchArmSurface.SERVICE_NAME.accepts(name)) {
            result.put("nameAvailable", false);
            result.put("reason", "Invalid");
            result.put("message", SearchArmSurface.SERVICE_NAME.message(name));
        } else if (names.owner(cloud(req), CloudSuffix.SEARCH, name).isPresent()) {
            result.put("nameAvailable", false);
            result.put("reason", "AlreadyExists");
            result.put("message", "");
        } else {
            result.put("nameAvailable", true);
            result.put("message", "");
        }
        return json(Response.ok(result));
    }

    // ── Resource group level ────────────────────────────────────────────────

    /**
     * A service's own path, the group's service list, and the key actions under a service. Any other path under a
     * service is a child Microsoft.Search serves elsewhere or floci-az does not serve, answered ARM 404, so a child
     * write is never taken for a write of its parent.
     */
    private Response resourceGroupPath(AzureRequest req, String path, String method, ArmResourceId id) {
        List<String> types = id.types();
        List<String> segments = id.names();
        if (types.isEmpty() || !types.getFirst().equalsIgnoreCase(SEARCH_SERVICES)) {
            return armNotFound(path);
        }
        if (types.size() == 1) {
            if (segments.isEmpty()) {
                return "GET".equals(method) ? list(req, new ArmScope(id.subscriptionId(), id.resourceGroup()))
                    : Response.status(405).build();
            }
            return service(req, method, serviceId(id));
        }
        if (types.size() != 2) {
            return armNotFound(path);
        }
        ArmResourceId serviceId = serviceId(id);
        String argument = segments.size() == 2 ? segments.get(1) : null;
        return switch (types.get(1).toLowerCase(Locale.ROOT)) {
            case "listadminkeys" -> argument == null
                ? action(method, "POST", () -> listAdminKeys(serviceId)) : armNotFound(path);
            case "regenerateadminkey" -> argument != null
                ? action(method, "POST", () -> regenerateAdminKey(serviceId, argument)) : armNotFound(path);
            case "listquerykeys" -> argument != null ? armNotFound(path)
                : "POST".equals(method) || "GET".equals(method) ? listQueryKeys(serviceId)
                : Response.status(405).build();
            case "createquerykey" -> argument != null
                ? action(method, "POST", () -> createQueryKey(serviceId, argument)) : armNotFound(path);
            case "deletequerykey" -> argument != null
                ? action(method, "DELETE", () -> deleteQueryKey(serviceId, argument)) : armNotFound(path);
            default -> armNotFound(path);
        };
    }

    /** One of a service's actions, served for its one method; the rest are {@code 405}. */
    private static Response action(String method, String expected, Supplier<Response> served) {
        return expected.equals(method) ? served.get() : Response.status(405).build();
    }

    private Response service(AzureRequest req, String method, ArmResourceId id) {
        return switch (method) {
            case "PUT" -> createOrUpdate(req, id);
            case "PATCH" -> update(req, id);
            case "GET" -> store.get(id).map(service -> document(req, 200, service)).orElseGet(() -> notFound(id));
            case "DELETE" -> delete(id);
            default -> Response.status(405).build();
        };
    }

    /**
     * {@code Services_CreateOrUpdate}. The gate has reserved the name before this runs, so a name another service
     * owns never gets here. A replace keeps the id, name, location and keys the service was created with.
     */
    private Response createOrUpdate(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        SearchServiceWrite.Context context = context(req, id);
        synchronized (writes) {
            Written<SearchServiceRecord> written = store.createOrUpdate(id,
                existing -> SearchServiceWrite.put(context, body, existing));
            SearchServiceRecord service = written.stored().value();
            registry.put(SearchServiceStore.info(service));
            LOG.infov("ARM: {0} search service {1} ({2})", written.created() ? "created" : "replaced",
                service.name(), service.sku());
            return document(req, written.created() ? 201 : 200, service);
        }
    }

    /** {@code Services_Update}: the stored service with what the body names changed; 404 for no such service. */
    private Response update(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        SearchServiceWrite.Context context = context(req, id);
        synchronized (writes) {
            Optional<SearchServiceRecord> updated = store.update(id,
                existing -> SearchServiceWrite.patch(context, body, existing));
            if (updated.isEmpty()) {
                return notFound(id);
            }
            registry.put(SearchServiceStore.info(updated.get()));
            LOG.infov("ARM: updated search service {0}", updated.get().name());
            return document(req, 200, updated.get());
        }
    }

    private Response delete(ArmResourceId id) {
        synchronized (writes) {
            Optional<SearchServiceRecord> deleted = store.delete(id);
            if (deleted.isEmpty()) {
                return Response.noContent().build();
            }
            registry.remove(deleted.get().name());
            LOG.infov("ARM: deleted search service {0}", deleted.get().name());
            return Response.ok().build();
        }
    }

    private Response list(AzureRequest req, ArmScope scope) {
        String suffix = endpointSuffix(req);
        List<Map<String, Object>> value = store.list(scope).stream()
            .map(service -> SearchServiceDocuments.document(service, suffix))
            .toList();
        return json(Response.ok(Map.of("value", value)));
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    /** {@code AdminKeys_Get}. */
    private Response listAdminKeys(ArmResourceId id) {
        return store.get(id).map(SearchArmService::adminKeys).orElseGet(() -> notFound(id));
    }

    /** {@code AdminKeys_Regenerate}: a new key of {@code keyKind}, {@code primary} or {@code secondary}. */
    private Response regenerateAdminKey(ArmResourceId id, String keyKind) {
        boolean primary = "primary".equalsIgnoreCase(keyKind);
        if (!primary && !"secondary".equalsIgnoreCase(keyKind)) {
            throw SearchServiceWrite.refused("'" + keyKind + "' is not a valid keyKind; the valid values are primary "
                + "and secondary.");
        }
        String key = SearchApiKeys.newKey();
        synchronized (writes) {
            Optional<SearchServiceRecord> updated = store.update(id, service -> primary
                ? service.withAdminKeys(key, service.secondaryAdminKey())
                : service.withAdminKeys(service.primaryAdminKey(), key));
            if (updated.isEmpty()) {
                return notFound(id);
            }
            registry.put(SearchServiceStore.info(updated.get()));
            LOG.infov("ARM: regenerated the {0} admin key of search service {1}", keyKind.toLowerCase(Locale.ROOT),
                updated.get().name());
            return adminKeys(updated.get());
        }
    }

    /** {@code QueryKeys_ListBySearchService}, which the 2015-08-19 api-version also serves as a GET. */
    private Response listQueryKeys(ArmResourceId id) {
        return store.get(id).map(service -> {
            List<Map<String, Object>> value = new ArrayList<>();
            service.queryKeys().forEach(key -> value.add(queryKey(key)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", value);
            result.put("nextLink", null);
            return json(Response.ok(result));
        }).orElseGet(() -> notFound(id));
    }

    /** {@code QueryKeys_Create}: a new query key named {@code name}, up to the service's 50. */
    private Response createQueryKey(ArmResourceId id, String name) {
        QueryKey created = new QueryKey(name, SearchApiKeys.newKey());
        synchronized (writes) {
            Optional<SearchServiceRecord> updated = store.update(id, service -> {
                if (service.queryKeys().size() >= MAX_QUERY_KEYS) {
                    throw SearchServiceWrite.refused("A search service has at most " + MAX_QUERY_KEYS
                        + " query keys; delete one before creating another.");
                }
                List<QueryKey> keys = new ArrayList<>(service.queryKeys());
                keys.add(created);
                return service.withQueryKeys(keys);
            });
            if (updated.isEmpty()) {
                return notFound(id);
            }
            registry.put(SearchServiceStore.info(updated.get()));
            LOG.infov("ARM: created query key {0} of search service {1}", name, updated.get().name());
            return json(Response.ok(queryKey(created)));
        }
    }

    /**
     * {@code QueryKeys_Delete}: query keys are identified by value. {@code 204} once the key is gone, as Azure answers
     * it, and a bare {@code 404}, which the spec lists, for a key the service does not have.
     */
    private Response deleteQueryKey(ArmResourceId id, String key) {
        synchronized (writes) {
            Optional<SearchServiceRecord> found = store.get(id);
            if (found.isEmpty()) {
                return notFound(id);
            }
            if (found.get().queryKeys().stream().noneMatch(queryKey -> queryKey.key().equals(key))) {
                return Response.status(404).build();
            }
            Optional<SearchServiceRecord> updated = store.update(id, service -> service.withQueryKeys(
                service.queryKeys().stream().filter(queryKey -> !queryKey.key().equals(key)).toList()));
            updated.ifPresent(service -> registry.put(SearchServiceStore.info(service)));
            LOG.infov("ARM: deleted a query key of search service {0}", found.get().name());
            return Response.noContent().build();
        }
    }

    private static Response adminKeys(SearchServiceRecord service) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("primaryKey", service.primaryAdminKey());
        keys.put("secondaryKey", service.secondaryAdminKey());
        return json(Response.ok(keys));
    }

    /** A query key; the one the service was created with has a null name. */
    private static Map<String, Object> queryKey(QueryKey key) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", key.name());
        entry.put("key", key.key());
        return entry;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SearchServiceWrite.Context context(AzureRequest req, ArmResourceId id) {
        BearerClaims claims = BearerClaims.of(req);
        return new SearchServiceWrite.Context(id, defaults.defaultLocation(req.scope()),
            directory.tenantOfSubscription(id.subscriptionId()), this::userIdentity, Instant.now(),
            claims.present() ? claims.principal() : null, claims.present() ? claims.principalType() : null);
    }

    /**
     * A user-assigned identity's ids from the Managed Identity store. The gate refuses a write naming an identity the
     * store lacks while Managed Identity is enabled; with it disabled, the ids are derived from the identity's id.
     */
    private UserIdentity userIdentity(String identityId) {
        String seed = identityId.toLowerCase(Locale.ROOT);
        return identities.findByResourceId(identityId)
            .map(found -> new UserIdentity(found.principalId(), found.clientId()))
            .orElseGet(() -> new UserIdentity(TokenIssuer.deterministicGuid("search-uai-principal:" + seed),
                TokenIssuer.deterministicGuid("search-uai-client:" + seed)));
    }

    /** The service a path under it names, in the casing of the path and the spec's spelling of its type. */
    private static ArmResourceId serviceId(ArmResourceId id) {
        return ArmResourceId.parse("/subscriptions/" + id.subscriptionId() + "/resourceGroups/" + id.resourceGroup()
            + "/providers/" + SearchServiceDocuments.TYPE + "/" + id.names().getFirst()).orElseThrow();
    }

    private Response document(AzureRequest req, int status, SearchServiceRecord service) {
        return json(Response.status(status)
            .entity(SearchServiceDocuments.document(service, endpointSuffix(req)))
            .header("ETag", SearchServiceDocuments.eTag(service)));
    }

    private static Response json(Response.ResponseBuilder response) {
        return response.type(MediaType.APPLICATION_JSON).build();
    }

    /** The search DNS suffix of the request's cloud: {@code search.windows.net} in the public cloud. */
    private static String endpointSuffix(AzureRequest req) {
        return cloud(req).suffix(CloudSuffix.SEARCH).orElseGet(() ->
            CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD).suffix(CloudSuffix.SEARCH).orElseThrow());
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    /** ARM's answer for a missing resource, worded as Azure words it for a search service. */
    private static Response notFound(ArmResourceId id) {
        return ArmErrors.notFound("The Resource '" + SearchServiceDocuments.TYPE + "/" + id.name()
            + "' under resource group '" + id.resourceGroup() + "' was not found. For more details please go to "
            + "https://aka.ms/ARMResourceNotFoundFix");
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }

    // ── Resource index ──────────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().search().enabled();
    }

    /** Joined the index after the default listings were pinned; listed only under {@code resource-group-delete}. */
    @Override
    public boolean inDefaultListing() {
        return false;
    }

    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return store.list(scope).stream().map(SearchServiceDocuments::indexEntry).toList();
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────

    /** Every search service owns {@code {name}.search.windows.net}. */
    @Override
    public List<Claim> globalNames() {
        return store.all().stream()
            .map(stored -> new Claim(CloudSuffix.SEARCH, stored.value().name(), stored.id()))
            .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.SEARCH
            && store.get(owner).filter(service -> name.equalsIgnoreCase(service.name())).isPresent();
    }
}
