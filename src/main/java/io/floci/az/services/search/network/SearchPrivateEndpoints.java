package io.floci.az.services.search.network;

import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.network.NetworkService;
import io.floci.az.services.search.network.PrivateEndpointLink.State;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The private endpoints that connect to search services, read from the Microsoft.Network store that keeps them. Search
 * holds no copy: an endpoint's create, update and delete show on the service's {@code privateEndpointConnections} at
 * once, and an approval, a rejection or a removal made on the service is written onto the endpoint
 * ({@link NetworkService#updatePrivateEndpoint}), whose own GET then shows it. {@link SearchPrivateLinkOwner} keeps an
 * endpoint's PUT from resetting what the service decided.
 *
 * <p>A connection the service removed is {@code Disconnected} on the endpoint, as Private Link documents it ("removed
 * by the private-link resource owner"), and no longer one of the service's connections.
 *
 * <p><b>Private callers.</b> How floci-az decides that a data-plane request came through a private endpoint is its own
 * emulation, not Azure's mechanism: a request is private when its transport peer address
 * ({@code AzureRequest.remoteAddress()}, never a forwarded header) falls inside the address prefix
 * ({@code addressPrefix} or {@code addressPrefixes}, IPv4 or IPv6) of the subnet that hosts a private endpoint whose
 * connection to the service is {@code Approved}, and is not one of the subnet addresses Azure reserves for the network,
 * its default gateway and its broadcast. Locally, put floci-az and the application containers on a Docker network
 * created with the same CIDR as that subnet: Docker gives the network's first address to its gateway, through which
 * requests from the host arrive, so they stay public.
 */
@ApplicationScoped
public class SearchPrivateEndpoints {

    /** The group id of a search service's one private link resource. */
    public static final String GROUP_ID = "searchService";

    private static final String AUTOMATIC = "privateLinkServiceConnections";
    private static final String MANUAL = "manualPrivateLinkServiceConnections";

    private final NetworkService network;

    @Inject
    public SearchPrivateEndpoints(NetworkService network) {
        this.network = network;
    }

    /** The connections to the service {@code serviceId}, by name, except those the service removed. */
    public List<PrivateEndpointLink> connections(String serviceId) {
        return links(serviceId).stream()
            .filter(link -> !link.state().is(State.DISCONNECTED))
            .toList();
    }

    /** The connection to {@code serviceId} named {@code name}, ignoring case; empty for none or a removed one. */
    public Optional<PrivateEndpointLink> connection(String serviceId, String name) {
        return connections(serviceId).stream().filter(link -> link.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Records {@code state} on the connection {@code name} of {@code serviceId}, on the endpoint that holds it.
     *
     * @return the connection as it now is; empty when the service has no such connection
     */
    public Optional<PrivateEndpointLink> update(String serviceId, String name, State state) {
        Optional<PrivateEndpointLink> found = connection(serviceId, name);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PrivateEndpointLink link = found.get();
        return network.updatePrivateEndpoint(link.privateEndpointId(),
                endpoint -> withState(endpoint, link, serviceId, state))
            .map(updated -> link.withState(state));
    }

    /** Disconnects every connection to {@code serviceId}: what becomes of them when the service is deleted. */
    public void disconnectAll(String serviceId) {
        for (PrivateEndpointLink link : connections(serviceId)) {
            update(serviceId, link.name(), link.state().withStatus(State.DISCONNECTED));
        }
    }

    /**
     * Whether {@code address} is a client address of a subnet that hosts a private endpoint whose connection to
     * {@code serviceId} is {@code Approved} ({@link IpPrefix#containsHost}). An endpoint whose subnet floci-az does
     * not hold admits no one.
     */
    public boolean isPrivateCaller(String serviceId, InetAddress address) {
        for (PrivateEndpointLink link : links(serviceId)) {
            if (link.state().is(State.APPROVED) && link.subnetId() != null
                    && subnetPrefixes(link.subnetId()).stream().anyMatch(prefix -> prefix.containsHost(address))) {
                return true;
            }
        }
        return false;
    }

    /** Every connection a stored private endpoint makes to {@code serviceId}, whatever its state, by name. */
    private List<PrivateEndpointLink> links(String serviceId) {
        Optional<String> target = canonical(serviceId);
        if (target.isEmpty()) {
            return List.of();
        }
        List<PrivateEndpointLink> links = new ArrayList<>();
        for (Map<String, Object> endpoint : network.privateEndpoints()) {
            Map<String, Object> properties = ArmJson.cast(endpoint.get("properties"));
            for (Map<String, Object> connection : connections(properties, AUTOMATIC)) {
                link(endpoint, properties, connection, false, target.get()).ifPresent(links::add);
            }
            for (Map<String, Object> connection : connections(properties, MANUAL)) {
                link(endpoint, properties, connection, true, target.get()).ifPresent(links::add);
            }
        }
        links.sort(Comparator.comparing(link -> link.name().toLowerCase(Locale.ROOT)));
        return links;
    }

    private static Optional<PrivateEndpointLink> link(Map<String, Object> endpoint, Map<String, Object> properties,
                                                      Map<String, Object> connection, boolean manual, String target) {
        Map<String, Object> connectionProperties = ArmJson.cast(connection.get("properties"));
        boolean toService = canonical(String.valueOf(connectionProperties.get("privateLinkServiceId")))
            .filter(target::equals)
            .isPresent();
        if (!toService || !(endpoint.get("id") instanceof String endpointId)) {
            return Optional.empty();
        }
        String endpointName = endpoint.get("name") instanceof String text ? text : ArmResourceId.parse(endpointId)
            .map(ArmResourceId::name).orElse(endpointId);
        String connectionName = connection.get("name") instanceof String text ? text : "connection";
        State state = State.of(ArmJson.cast(connectionProperties.get("privateLinkServiceConnectionState")));
        String subnetId = ArmJson.cast(properties.get("subnet")).get("id") instanceof String id ? id : null;
        return Optional.of(new PrivateEndpointLink(linkName(endpointId, endpointName, connectionName), endpointId,
            connectionName, manual, groupId(connectionProperties), state, subnetId));
    }

    /**
     * The name of a connection on the search side, {@code {endpoint name}.{guid}} as Azure names them, with a GUID
     * derived from the endpoint and the connection, so the name is stable across restarts and casings.
     */
    private static String linkName(String endpointId, String endpointName, String connectionName) {
        String seed = "search-private-endpoint-connection:" + endpointId.toLowerCase(Locale.ROOT) + "/"
            + connectionName.toLowerCase(Locale.ROOT);
        return endpointName + "." + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String groupId(Map<String, Object> connectionProperties) {
        if (connectionProperties.get("groupIds") instanceof List<?> groupIds && !groupIds.isEmpty()
                && groupIds.getFirst() instanceof String groupId) {
            return groupId;
        }
        return GROUP_ID;
    }

    /** The address prefixes of the subnet {@code subnetId}: {@code addressPrefix} and {@code addressPrefixes}. */
    private List<IpPrefix> subnetPrefixes(String subnetId) {
        Optional<Map<String, Object>> subnet = network.resource(subnetId);
        if (subnet.isEmpty()) {
            return List.of();
        }
        Map<String, Object> properties = ArmJson.cast(subnet.get().get("properties"));
        List<String> texts = new ArrayList<>();
        if (properties.get("addressPrefix") instanceof String prefix) {
            texts.add(prefix);
        }
        if (properties.get("addressPrefixes") instanceof List<?> prefixes) {
            prefixes.stream().filter(String.class::isInstance).map(String.class::cast).forEach(texts::add);
        }
        List<IpPrefix> parsed = new ArrayList<>();
        texts.forEach(text -> IpPrefix.parse(text).ifPresent(parsed::add));
        return parsed;
    }

    /** A copy of {@code endpoint} in which the connection {@code link} names holds {@code state}. */
    private static Map<String, Object> withState(Map<String, Object> endpoint, PrivateEndpointLink link,
                                                 String serviceId, State state) {
        Optional<String> target = canonical(serviceId);
        Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(endpoint.get("properties")));
        String field = link.manual() ? MANUAL : AUTOMATIC;
        List<Object> updated = new ArrayList<>();
        for (Map<String, Object> connection : connections(properties, field)) {
            Map<String, Object> connectionProperties = ArmJson.cast(connection.get("properties"));
            boolean named = link.connectionName().equalsIgnoreCase(String.valueOf(connection.get("name")))
                && canonical(String.valueOf(connectionProperties.get("privateLinkServiceId"))).equals(target);
            if (!named) {
                updated.add(connection);
                continue;
            }
            Map<String, Object> changedProperties = new LinkedHashMap<>(connectionProperties);
            changedProperties.put("privateLinkServiceConnectionState", state.toJson());
            Map<String, Object> changed = new LinkedHashMap<>(connection);
            changed.put("properties", changedProperties);
            updated.add(changed);
        }
        properties.put(field, updated);
        Map<String, Object> copy = new LinkedHashMap<>(endpoint);
        copy.put("properties", properties);
        return copy;
    }

    private static List<Map<String, Object>> connections(Map<String, Object> properties, String field) {
        if (!(properties.get(field) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(ArmJson::cast).toList();
    }

    /** The canonical key of an ARM resource id, so ids that differ only in case or slashes compare equal. */
    private static Optional<String> canonical(String id) {
        return ArmResourceId.parse(id).filter(parsed -> parsed.name() != null).map(ArmResourceId::canonicalKey);
    }
}
