package io.floci.az.services.search.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.search.arm.SearchArmModels.SearchServiceRecord;
import io.floci.az.services.search.arm.SearchServiceWrite.RefusedWrite;
import io.floci.az.services.search.network.PrivateEndpointLink;
import io.floci.az.services.search.network.PrivateEndpointLink.State;
import io.floci.az.services.search.network.SearchPrivateEndpoints;
import io.floci.az.services.search.network.SharedPrivateLink;
import io.floci.az.services.search.network.SharedPrivateLinks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The network children of a search service, as the 2026-11-01 spec defines them; {@link SearchArmService} hands them
 * every path under a service whose first child segment is one of {@link #CHILDREN}, once the service exists.
 *
 * <ul>
 *   <li>{@code privateEndpointConnections}: list, get, update and delete. The connections are the ones Microsoft.Network
 *       private endpoints make to the service ({@link SearchPrivateEndpoints}); an update approves or rejects one, and a
 *       delete disconnects it, answering it {@code Disconnected} as the spec's example does.</li>
 *   <li>{@code privateLinkResources}: the service's one private link resource, {@code searchService}, with the
 *       resource types the spec's example lists for shared private links.</li>
 *   <li>{@code sharedPrivateLinkResources}: list, get, create or update, and delete ({@link SharedPrivateLinks}). Create
 *       and delete are long-running in the spec: they answer {@code 202} with {@code Azure-AsyncOperation} and
 *       {@code Location} headers naming an {@code operationStatuses} monitor. The work is done before the answer, so the
 *       monitor reports {@code Succeeded} on its first poll and the link is already {@code Succeeded}.</li>
 *   <li>{@code networkSecurityPerimeterConfigurations}: floci-az models no network security perimeter, so a service is
 *       in none: the list is empty, and a get or a reconcile of a configuration is {@code 404}.</li>
 * </ul>
 *
 * <p>Azure's error codes for a refused shared private link are not public: a value outside the documented rules is a
 * {@code 400 BadRequest} naming the rule, as {@link SearchServiceWrite} answers, and a second link to one resource and
 * group, which Learn rules out, is ARM's generic {@code 409 Conflict}.
 */
@ApplicationScoped
public class SearchNetworkingArm {

    static final String PRIVATE_ENDPOINT_CONNECTIONS = "privateEndpointConnections";
    static final String PRIVATE_LINK_RESOURCES = "privateLinkResources";
    static final String SHARED_PRIVATE_LINK_RESOURCES = "sharedPrivateLinkResources";
    static final String NSP_CONFIGURATIONS = "networkSecurityPerimeterConfigurations";

    /** The child segments this class serves, lowercased. */
    static final Set<String> CHILDREN = Set.of(PRIVATE_ENDPOINT_CONNECTIONS.toLowerCase(Locale.ROOT),
        PRIVATE_LINK_RESOURCES.toLowerCase(Locale.ROOT), SHARED_PRIVATE_LINK_RESOURCES.toLowerCase(Locale.ROOT),
        NSP_CONFIGURATIONS.toLowerCase(Locale.ROOT));

    private static final String OPERATION_STATUSES = "operationStatuses";
    private static final String RECONCILE = "reconcile";
    private static final String RETRY_AFTER_SECONDS = "1";

    private static final List<String> CONNECTION_STATUSES = List.of(State.PENDING, State.APPROVED, State.REJECTED,
        State.DISCONNECTED);

    /** The SKUs Learn's "Shared private link resource limits" table gives no shared private links ("N/A"). */
    private static final Set<String> NO_SHARED_PRIVATE_LINKS = Set.of("free", "serverless");

    /**
     * The most shared private links, and the most distinct group ids among them, per SKU (Learn: "Shared private link
     * resource limits"). Basic is "10 or 30" there, depending on when the service was created; the emulator takes 30.
     */
    private static final Map<String, Limits> LIMITS = Map.of(
        "basic", new Limits(30, 4),
        "standard", new Limits(100, 7),
        "standard2", new Limits(400, 15),
        "standard3", new Limits(400, 15),
        "storage_optimized_l1", new Limits(20, 4),
        "storage_optimized_l2", new Limits(20, 4));

    private final EmulatorConfig config;
    private final SearchPrivateEndpoints privateEndpoints;
    private final SharedPrivateLinks sharedPrivateLinks;

    @Inject
    public SearchNetworkingArm(EmulatorConfig config, SearchPrivateEndpoints privateEndpoints,
                               SharedPrivateLinks sharedPrivateLinks) {
        this.config = config;
        this.privateEndpoints = privateEndpoints;
        this.sharedPrivateLinks = sharedPrivateLinks;
    }

    /** Whether {@code childType}, the segment after a service's name, is one this class serves. */
    static boolean serves(String childType) {
        return CHILDREN.contains(childType.toLowerCase(Locale.ROOT));
    }

    /**
     * Answers the request {@code id} addresses under {@code service}.
     *
     * @param endpointSuffix the search DNS suffix of the request's cloud
     */
    Response handle(AzureRequest req, String method, ArmResourceId id, SearchServiceRecord service,
                    String endpointSuffix) {
        List<String> types = id.types();
        List<String> names = id.names();
        String child = types.get(1).toLowerCase(Locale.ROOT);
        String name = names.size() > 1 ? names.get(1) : null;
        if (types.size() == 2) {
            return switch (child) {
                case "privateendpointconnections" -> name == null
                    ? collection(method, privateEndpointConnections(service))
                    : privateEndpointConnection(req, method, service, name);
                case "privatelinkresources" -> name == null
                    ? collection(method, List.of(privateLinkResource(service, endpointSuffix)))
                    : armNotFound(id);
                case "sharedprivatelinkresources" -> name == null
                    ? collection(method, sharedPrivateLinkResources(service))
                    : sharedPrivateLink(req, method, service, name);
                case "networksecurityperimeterconfigurations" -> name == null
                    ? nspConfigurations(method)
                    : only(method, "GET", () -> childNotFound(service, NSP_CONFIGURATIONS, name));
                default -> armNotFound(id);
            };
        }
        if (types.size() == 3 && names.size() == 3 && child.equals("sharedprivatelinkresources")
                && types.get(2).equalsIgnoreCase(OPERATION_STATUSES)) {
            return only(method, "GET", SearchNetworkingArm::operationSucceeded);
        }
        if (types.size() == 3 && names.size() == 2 && child.equals("networksecurityperimeterconfigurations")
                && types.get(2).equalsIgnoreCase(RECONCILE)) {
            return only(method, "POST", () -> childNotFound(service, NSP_CONFIGURATIONS, name));
        }
        return armNotFound(id);
    }

    /** The service's {@code privateEndpointConnections} property: its connections, as their GET answers them. */
    List<Map<String, Object>> privateEndpointConnections(SearchServiceRecord service) {
        return privateEndpoints.connections(service.id()).stream()
            .map(link -> privateEndpointConnection(service, link))
            .toList();
    }

    /** The service's {@code sharedPrivateLinkResources} property: its links, as their GET answers them. */
    List<Map<String, Object>> sharedPrivateLinkResources(SearchServiceRecord service) {
        return sharedPrivateLinks.of(serviceId(service)).stream()
            .map(SearchNetworkingArm::sharedPrivateLinkResource)
            .toList();
    }

    /**
     * What becomes of a deleted service's children: its shared private links go with it, and the private endpoints
     * that connected to it are left {@code Disconnected}, as Private Link leaves the endpoints of a removed resource.
     */
    void serviceDeleted(SearchServiceRecord service) {
        sharedPrivateLinks.deleteAll(serviceId(service));
        privateEndpoints.disconnectAll(service.id());
    }

    // ── Private endpoint connections ────────────────────────────────────────

    private Response privateEndpointConnection(AzureRequest req, String method, SearchServiceRecord service,
                                               String name) {
        return switch (method) {
            case "GET" -> privateEndpoints.connection(service.id(), name)
                .map(link -> json(Response.ok(privateEndpointConnection(service, link))))
                .orElseGet(() -> childNotFound(service, PRIVATE_ENDPOINT_CONNECTIONS, name));
            case "PUT" -> updateConnection(req, service, name);
            case "DELETE" -> deleteConnection(service, name);
            default -> Response.status(405).build();
        };
    }

    /**
     * {@code PrivateEndpointConnections_Update}: the body's {@code privateLinkServiceConnectionState} becomes the
     * connection's. A body that names no status keeps the current one; {@code actionsRequired} defaults to
     * {@code None}, as the spec defaults it. Only existing connections can be updated: a service never creates one.
     */
    private Response updateConnection(AzureRequest req, SearchServiceRecord service, String name) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> properties = object(body.get("properties"), "properties");
        String stateName = "properties.privateLinkServiceConnectionState";
        Map<String, Object> requested = object(properties.get("privateLinkServiceConnectionState"), stateName);
        Optional<PrivateEndpointLink> found = privateEndpoints.connection(service.id(), name);
        if (found.isEmpty()) {
            return childNotFound(service, PRIVATE_ENDPOINT_CONNECTIONS, name);
        }
        String status = requested.containsKey("status")
            ? connectionStatus(requested.get("status")) : found.get().state().status();
        State state = new State(status,
            optionalText(requested, "description", stateName + ".description", ""),
            optionalText(requested, "actionsRequired", stateName + ".actionsRequired", "None"));
        return privateEndpoints.update(service.id(), name, state)
            .map(link -> json(Response.ok(privateEndpointConnection(service, link))))
            .orElseGet(() -> childNotFound(service, PRIVATE_ENDPOINT_CONNECTIONS, name));
    }

    /**
     * {@code PrivateEndpointConnections_Delete}: {@code 200} with the connection, now {@code Disconnected}, as the
     * spec's example answers it, or a bare {@code 404} for a connection the service does not have.
     */
    private Response deleteConnection(SearchServiceRecord service, String name) {
        Optional<PrivateEndpointLink> found = privateEndpoints.connection(service.id(), name);
        if (found.isEmpty()) {
            return Response.status(404).build();
        }
        return privateEndpoints.update(service.id(), name, found.get().state().withStatus(State.DISCONNECTED))
            .map(link -> json(Response.ok(privateEndpointConnection(service, link))))
            .orElseGet(() -> Response.status(404).build());
    }

    private static String connectionStatus(Object value) {
        if (!(value instanceof String text)) {
            throw SearchServiceWrite.wrongType("properties.privateLinkServiceConnectionState.status", "a string");
        }
        return CONNECTION_STATUSES.stream().filter(status -> status.equalsIgnoreCase(text)).findFirst()
            .orElseThrow(() -> SearchServiceWrite.refused("'" + text + "' is not a valid status; the valid values are "
                + String.join(", ", CONNECTION_STATUSES) + "."));
    }

    private static Map<String, Object> privateEndpointConnection(SearchServiceRecord service,
                                                                 PrivateEndpointLink link) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("privateEndpoint", Map.of("id", link.privateEndpointId()));
        properties.put("privateLinkServiceConnectionState", link.state().toJson());
        properties.put("groupId", link.groupId());
        properties.put("provisioningState", "Succeeded");
        return child(service.id() + "/" + PRIVATE_ENDPOINT_CONNECTIONS + "/" + link.name(), link.name(),
            PRIVATE_ENDPOINT_CONNECTIONS, properties);
    }

    // ── Private link resources ──────────────────────────────────────────────

    /**
     * {@code PrivateLinkResources_ListSupported}: the service's one private link resource. Its private DNS zone is
     * {@code privatelink.} and the search suffix of the request's cloud; the shareable types are the spec's example's.
     */
    private static Map<String, Object> privateLinkResource(SearchServiceRecord service, String endpointSuffix) {
        String groupId = SearchPrivateEndpoints.GROUP_ID;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("groupId", groupId);
        properties.put("requiredMembers", List.of(groupId));
        properties.put("requiredZoneNames", List.of("privatelink." + endpointSuffix));
        properties.put("shareablePrivateLinkResourceTypes", ShareableTypes.ALL);
        return child(service.id() + "/" + PRIVATE_LINK_RESOURCES + "/" + groupId, groupId, PRIVATE_LINK_RESOURCES,
            properties);
    }

    // ── Shared private link resources ───────────────────────────────────────

    private Response sharedPrivateLink(AzureRequest req, String method, SearchServiceRecord service, String name) {
        ArmResourceId linkId = linkId(service, name);
        return switch (method) {
            case "GET" -> sharedPrivateLinks.get(linkId)
                .map(link -> json(Response.ok(sharedPrivateLinkResource(link))))
                .orElseGet(() -> childNotFound(service, SHARED_PRIVATE_LINK_RESOURCES, name));
            case "PUT" -> putSharedPrivateLink(req, service, linkId);
            case "DELETE" -> sharedPrivateLinks.delete(linkId).isPresent()
                ? accepted(req, linkId) : Response.noContent().build();
            default -> Response.status(405).build();
        };
    }

    /**
     * {@code SharedPrivateLinkResources_CreateOrUpdate}: checked against the tier's documented limits and Learn's "one
     * shared private link ... for each resource and subresource combination", then written; {@code 202} with the
     * monitor to poll. A create names the resource and the group; a PUT to an existing link keeps whatever it omits,
     * as azurerm's update relies on when it sends {@code requestMessage} alone. The read-only {@code status} and
     * {@code provisioningState} a client sends are ignored.
     */
    private Response putSharedPrivateLink(AzureRequest req, SearchServiceRecord service, ArmResourceId linkId) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> properties = object(body.get("properties"), "properties");
        Optional<SharedPrivateLink> existing = sharedPrivateLinks.get(linkId);
        SharedPrivateLinks.Spec spec = new SharedPrivateLinks.Spec(
            properties.get("privateLinkResourceId") == null
                ? existing.map(SharedPrivateLink::privateLinkResourceId).orElseThrow(() -> required(
                    "privateLinkResourceId", "the ID of the resource the shared private link connects to"))
                : resourceId(properties.get("privateLinkResourceId")),
            properties.get("groupId") == null
                ? existing.map(SharedPrivateLink::groupId).orElseThrow(() -> required("groupId",
                    "the group ID (sub-resource) of that resource"))
                : requiredText(properties, "groupId"),
            properties.get("requestMessage") == null
                ? existing.map(SharedPrivateLink::requestMessage).orElse(null)
                : optionalText(properties, "requestMessage", "properties.requestMessage", null),
            properties.get("resourceRegion") == null
                ? existing.map(SharedPrivateLink::resourceRegion).orElse(null)
                : blankToNull(optionalText(properties, "resourceRegion", "properties.resourceRegion", null)));
        checkTier(service);
        List<SharedPrivateLink> others = sharedPrivateLinks.of(serviceId(service)).stream()
            .filter(link -> !link.name().equalsIgnoreCase(linkId.name()))
            .toList();
        others.stream().filter(link -> link.connectsTo(spec.privateLinkResourceId(), spec.groupId())).findFirst()
            .ifPresent(duplicate -> {
                throw new RefusedWrite(409, "Conflict", "The search service '" + service.name() + "' already has the "
                    + "shared private link resource '" + duplicate.name() + "' to the group '" + spec.groupId()
                    + "' of '" + spec.privateLinkResourceId() + "'; a service has one shared private link for each "
                    + "resource and group.");
            });
        checkLimits(service, others, spec.groupId());
        sharedPrivateLinks.put(serviceId(service), linkId, spec);
        return accepted(req, linkId);
    }

    /** The tiers Learn's limits table gives no shared private links: Free, Serverless and S3 in high-density mode. */
    private static void checkTier(SearchServiceRecord service) {
        if (NO_SHARED_PRIVATE_LINKS.contains(service.sku())) {
            throw SearchServiceWrite.refused("Shared private link resources are not available on the "
                + service.sku() + " tier.");
        }
        if ("standard3".equals(service.sku()) && "highDensity".equals(service.settings().hostingMode())) {
            throw SearchServiceWrite.refused("Shared private link resources are not available on the standard3 tier "
                + "in high-density hosting mode.");
        }
    }

    /**
     * The tier's maximum number of links, and of distinct group ids among them, which Learn counts "irrespective of
     * the status of the resource".
     */
    private static void checkLimits(SearchServiceRecord service, List<SharedPrivateLink> others, String groupId) {
        Limits limits = LIMITS.get(service.sku());
        if (limits == null) {
            return;
        }
        if (others.size() + 1 > limits.links()) {
            throw SearchServiceWrite.refused("A " + service.sku() + " search service has at most " + limits.links()
                + " shared private link resources.");
        }
        Set<String> groups = new LinkedHashSet<>();
        others.forEach(link -> groups.add(link.groupId().toLowerCase(Locale.ROOT)));
        groups.add(groupId.toLowerCase(Locale.ROOT));
        if (groups.size() > limits.groupIds()) {
            throw SearchServiceWrite.refused("The shared private link resources of a " + service.sku() + " search "
                + "service connect to at most " + limits.groupIds() + " distinct group IDs.");
        }
    }

    private static Map<String, Object> sharedPrivateLinkResource(SharedPrivateLink link) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("privateLinkResourceId", link.privateLinkResourceId());
        properties.put("groupId", link.groupId());
        properties.put("requestMessage", link.requestMessage());
        properties.put("resourceRegion", link.resourceRegion());
        properties.put("status", link.status());
        properties.put("provisioningState", SharedPrivateLink.SUCCEEDED);
        return child(link.id(), link.name(), SHARED_PRIVATE_LINK_RESOURCES, properties);
    }

    /**
     * The {@code 202} of a create or delete: {@code Azure-AsyncOperation} and {@code Location} name the link's
     * {@code operationStatuses} monitor, as the spec's examples do, built from the caller's own scheme and host.
     */
    private Response accepted(AzureRequest req, ArmResourceId linkId) {
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        String monitor = RequestUrls.resolveBaseUrl(req, config) + linkId.id() + "/" + OPERATION_STATUSES + "/"
            + UUID.randomUUID() + (apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion);
        return Response.status(202)
            .header("Azure-AsyncOperation", monitor)
            .header("Location", monitor)
            .header("Retry-After", RETRY_AFTER_SECONDS)
            .build();
    }

    /** The monitor of a link's create or delete, which completed before its {@code 202}. */
    private static Response operationSucceeded() {
        return json(Response.ok(Map.of("status", "Succeeded")));
    }

    // ── Network security perimeter configurations ───────────────────────────

    private static Response nspConfigurations(String method) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("value", List.of());
        page.put("nextLink", null);
        return only(method, "GET", () -> json(Response.ok(page)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ArmResourceId serviceId(SearchServiceRecord service) {
        return ArmResourceId.parse(service.id()).orElseThrow();
    }

    /** A link's id, under the service's id in the casing the service was created with. */
    private static ArmResourceId linkId(SearchServiceRecord service, String name) {
        return ArmResourceId.parse(service.id() + "/" + SHARED_PRIVATE_LINK_RESOURCES + "/" + name).orElseThrow();
    }

    private static Map<String, Object> child(String id, String name, String childType,
                                             Map<String, Object> properties) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("name", name);
        document.put("type", SearchServiceDocuments.TYPE + "/" + childType);
        document.put("properties", properties);
        return document;
    }

    private static Response collection(String method, List<Map<String, Object>> value) {
        return only(method, "GET", () -> json(Response.ok(Map.of("value", value))));
    }

    private static Response only(String method, String expected, Supplier<Response> served) {
        return expected.equals(method) ? served.get() : Response.status(405).build();
    }

    private static Response json(Response.ResponseBuilder response) {
        return response.type(MediaType.APPLICATION_JSON).build();
    }

    private static Map<String, Object> object(Object value, String name) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw SearchServiceWrite.wrongType(name, "an object");
        }
        return ArmJson.cast(map);
    }

    /** The refusal of a create that omits {@code properties.key}, which is {@code what}. */
    private static RefusedWrite required(String key, String what) {
        return SearchServiceWrite.refused("properties." + key + " is required: " + what + ".");
    }

    private static String resourceId(Object value) {
        if (!(value instanceof String text)) {
            throw SearchServiceWrite.wrongType("properties.privateLinkResourceId", "a string");
        }
        boolean resource = ArmResourceId.parse(text)
            .filter(id -> id.providerNamespace() != null && id.name() != null && id.resourceGroup() != null)
            .isPresent();
        if (!resource) {
            throw SearchServiceWrite.refused("The privateLinkResourceId '" + text + "' is not the ID of an Azure "
                + "resource.");
        }
        return text;
    }

    private static String requiredText(Map<String, Object> properties, String key) {
        if (!(properties.get(key) instanceof String text)) {
            throw SearchServiceWrite.wrongType("properties." + key, "a string");
        }
        if (text.isBlank()) {
            throw SearchServiceWrite.refused("properties." + key + " cannot be empty.");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> object, String key, String name, String fallback) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw SearchServiceWrite.wrongType(name, "a string");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Response childNotFound(SearchServiceRecord service, String childType, String name) {
        ArmResourceId id = serviceId(service);
        return ArmErrors.notFound("The Resource '" + SearchServiceDocuments.TYPE + "/" + service.name() + "/"
            + childType + "/" + name + "' under resource group '" + id.resourceGroup() + "' was not found. For more "
            + "details please go to https://aka.ms/ARMResourceNotFoundFix");
    }

    private static Response armNotFound(ArmResourceId id) {
        return ArmErrors.notFound("Resource not found: " + id.id());
    }

    /** A tier's shared private link limits. */
    private record Limits(int links, int groupIds) {
    }

    /**
     * The {@code shareablePrivateLinkResourceTypes} of the spec's {@code ListSupportedPrivateLinkResources} example
     * (2026-11-01), as it lists them: names, types, group ids and descriptions.
     */
    private static final class ShareableTypes {

        static final List<Map<String, Object>> ALL = List.of(
            type("blob", "Microsoft.Storage/storageAccounts", "blob", "Azure AI Search indexers can connect to blobs in "
                + "Azure Storage for reading data from an indexer data source, for writing intermediate results of "
                + "indexer execution or for storing any knowledge store projections."),
            type("table", "Microsoft.Storage/storageAccounts", "table", "Azure AI Search indexers can connect to tables "
                + "in Azure Storage for reading data from an indexer data source, for writing intermediate results of "
                + "indexer execution or for storing any knowledge store projections."),
            type("Sql", "Microsoft.DocumentDB/databaseAccounts", "Sql", "Azure AI Search indexers can connect to Azure "
                + "Cosmos DB API for NoSQL for reading data from an indexer data source."),
            type("plr", "Microsoft.Sql/servers", "sqlServer", "Azure AI Search indexers can connect to Azure SQL "
                + "database on a server for reading data from an indexer data source."),
            type("vault", "Microsoft.KeyVault/vaults", "vault", "Azure AI Search can access keys in Azure Key Vault to "
                + "encrypt sensitive data in a search index and synonym map data."),
            type("plr", "Microsoft.DBforMySQL/servers", "mysqlServer", "Azure AI Search indexers can connect to MySQL "
                + "databases for reading data from an indexer data source."),
            type("site", "Microsoft.Web/sites", "sites", "For custom skills that connect to an app service, you can "
                + "specify sites as the group ID."));

        private ShareableTypes() {
        }

        private static Map<String, Object> type(String name, String type, String groupId, String description) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("type", type);
            properties.put("groupId", groupId);
            properties.put("description", description);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("properties", properties);
            return entry;
        }
    }
}
