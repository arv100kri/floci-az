package io.floci.az.services.search.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.network.IpPrefix;
import io.floci.az.services.search.network.SearchPrivateEndpoints;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The decisions of {@link SearchNetworkFirewall}, apart from how a private caller is recognized. */
class SearchNetworkFirewallRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVICE_ID =
        "/subscriptions/s/resourceGroups/r/providers/Microsoft.Search/searchServices/fw-unit";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final SearchPrivateEndpoints privateEndpoints = mock(SearchPrivateEndpoints.class);
    private final SearchNetworkFirewall firewall = new SearchNetworkFirewall(config, privateEndpoints);

    @BeforeEach
    void strictNetwork() {
        when(config.strictNetwork()).thenReturn(true);
    }

    private static SearchServiceInfo service(String resourceId, String access, String... rules) {
        List<Map<String, String>> ipRules = List.of(rules).stream().map(rule -> Map.of("value", rule)).toList();
        JsonNode networkRuleSet = MAPPER.valueToTree(Map.of("ipRules", ipRules, "bypass", "None"));
        return new SearchServiceInfo("fw-unit", resourceId, "admin", "admin2", List.of(), false, null, access,
            networkRuleSet, null, "standard");
    }

    private static AzureRequest request(String remoteAddress, String clientIp) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(SearchNetworkFirewall.CLIENT_IP_HEADER)).thenReturn(clientIp);
        return new AzureRequest("GET", "fw-unit", "search", "indexes", headers, null, Map.of(), null, false,
            remoteAddress);
    }

    private Optional<Denied> check(SearchServiceInfo service, AzureRequest request) {
        return firewall.check(request, service, SearchOperation.LIST_INDEXES);
    }

    @Test
    void withoutStrictNetworkNothingIsRefusedOrLookedUp() {
        when(config.strictNetwork()).thenReturn(false);

        assertTrue(check(service(SERVICE_ID, "disabled"), request("203.0.113.9", null)).isEmpty());
        verifyNoInteractions(privateEndpoints);
    }

    @Test
    void anOpenServiceIsNeverLookedUp() {
        assertTrue(check(service(SERVICE_ID, "enabled"), request("203.0.113.9", null)).isEmpty());
        assertTrue(check(service(SERVICE_ID, null), request(null, null)).isEmpty());
        verifyNoInteractions(privateEndpoints);
    }

    @Test
    void withTheHookOffTheClientIpHeaderIsIgnored() {
        when(config.services().search().trustClientIpHeader()).thenReturn(false);

        Optional<Denied> denied = check(service(SERVICE_ID, "enabled", "203.0.113.0/24"),
            request("127.0.0.1", "203.0.113.9"));

        assertTrue(denied.isPresent());
        assertEquals(403, denied.get().code().status());
        assertEquals("Forbidden", denied.get().code().code());
    }

    @Test
    void withTheHookOnTheClientIpHeaderIsTheCallersAddressForTheRules() {
        when(config.services().search().trustClientIpHeader()).thenReturn(true);
        SearchServiceInfo service = service(SERVICE_ID, "enabled", "203.0.113.0/24");

        assertTrue(check(service, request("127.0.0.1", "203.0.113.9")).isEmpty());
        assertTrue(check(service, request("127.0.0.1", "198.51.100.1")).isPresent());
        assertTrue(check(service, request("203.0.113.9", "garbage")).isEmpty(),
            "a header that holds no address leaves the transport address");
    }

    @Test
    void anIpv6CallerMatchesNoIpv4Rule() {
        assertTrue(check(service(SERVICE_ID, "enabled", "127.0.0.0/8"), request("::1", null)).isPresent());
        assertTrue(check(service(SERVICE_ID, "enabled", "127.0.0.0/8"), request("::ffff:127.0.0.1", null)).isEmpty());
    }

    @Test
    void aCallerIsPrivateByItsTransportAddressOnly() {
        when(config.services().search().trustClientIpHeader()).thenReturn(true);
        when(privateEndpoints.isPrivateCaller(any(), any())).thenAnswer(call ->
            IpPrefix.parse("10.0.1.0/24").orElseThrow().contains(call.getArgument(1)));
        SearchServiceInfo disabled = service(SERVICE_ID, "disabled");

        assertTrue(check(disabled, request("10.0.1.5", null)).isEmpty());
        assertTrue(check(disabled, request("127.0.0.1", "10.0.1.5")).isPresent());
        assertTrue(check(service(SERVICE_ID, "securedByPerimeter"), request("10.0.1.5", null)).isEmpty());
        assertTrue(check(service(SERVICE_ID, "securedByPerimeter"), request("127.0.0.1", null)).isPresent());
    }

    @Test
    void aServiceWithoutAnArmResourceHasNoPrivateEndpoints() {
        assertTrue(check(service(null, "disabled"), request("10.0.1.5", null)).isPresent());
        verifyNoInteractions(privateEndpoints);
    }
}
