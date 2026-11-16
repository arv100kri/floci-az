package io.floci.az.services.search.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.network.IpPrefix;
import io.floci.az.services.search.network.SearchPrivateEndpoints;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The network isolation of a search service's data plane, enforced only under {@link EmulatorConfig#strictNetwork()}
 * ({@code parity: strict}, or {@code parity-overrides.strict-network}); without it every request may proceed, and the
 * settings are stored and answered only.
 *
 * <p>Under it, as Learn's "Configure network access" and the spec's {@code PublicNetworkAccess} and
 * {@code NetworkRuleSet} describe them:
 * <ul>
 *   <li>a caller that reaches the service through an approved private endpoint is always admitted: firewall rules
 *       apply to the public endpoint only;</li>
 *   <li>{@code publicNetworkAccess: disabled} refuses every other caller with a {@code 403};</li>
 *   <li>{@code publicNetworkAccess: enabled} admits every caller when {@code networkRuleSet.ipRules} is empty, else
 *       only a caller whose address one rule (an IPv4 address or CIDR range) contains: "requests from IP addresses
 *       outside the allowed list are rejected with a 403 Forbidden response";</li>
 *   <li>{@code securedByPerimeter} defers to a network security perimeter's rules. floci-az models no perimeter, so it
 *       is treated as a perimeter with no inbound access rule: only private endpoints reach the service.</li>
 * </ul>
 *
 * <p>{@code networkRuleSet.bypass} lets "Azure trusted services" ({@code AzureServices}) or the Azure portal
 * ({@code AzurePortal}) past the IP rules. No request reaches floci-az from either, so in floci-az a bypass admits no
 * one beyond the rules.
 *
 * <p><b>Addresses.</b> A caller is private when its transport peer address is inside the subnet of a private endpoint
 * whose connection to the service is approved ({@link SearchPrivateEndpoints}, floci-az's own emulation of Private
 * Link). IP rules are matched against the same address, or, when the test hook
 * {@code services.search.trust-client-ip-header} is on, against the address the {@value #CLIENT_IP_HEADER} header
 * names, so a rule that names a public address can be tested from one machine. The header never makes a caller
 * private. Behind floci-az's same-port TLS proxy ({@code floci-az.tls.enabled}) the transport peer address is still the
 * caller's: the proxy hands it to the emulator in a PROXY protocol header.
 *
 * <p>The error code and message of a refusal are not public: it is answered as the data plane's {@code 403 Forbidden}.
 */
@ApplicationScoped
public class SearchNetworkFirewall implements SearchNetworkPolicy {

    private static final Logger LOG = Logger.getLogger(SearchNetworkFirewall.class);

    /** The header {@code services.search.trust-client-ip-header} reads the caller's address from. */
    public static final String CLIENT_IP_HEADER = "x-floci-az-client-ip";

    private static final String ENABLED = "enabled";

    private final EmulatorConfig config;
    private final SearchPrivateEndpoints privateEndpoints;

    @Inject
    public SearchNetworkFirewall(EmulatorConfig config, SearchPrivateEndpoints privateEndpoints) {
        this.config = config;
        this.privateEndpoints = privateEndpoints;
    }

    @Override
    public Optional<Denied> check(AzureRequest request, SearchServiceInfo service, SearchOperation operation) {
        if (!config.strictNetwork()) {
            return Optional.empty();
        }
        boolean publicAccess = service.publicNetworkAccess() == null
            || ENABLED.equalsIgnoreCase(service.publicNetworkAccess());
        List<IpPrefix> rules = publicAccess ? ipRules(service.networkRuleSet()) : List.of();
        if (publicAccess && rules.isEmpty()) {
            return Optional.empty();
        }
        Optional<InetAddress> peer = IpPrefix.address(request.remoteAddress());
        if (peer.isPresent() && service.resourceId() != null
                && privateEndpoints.isPrivateCaller(service.resourceId(), peer.get())) {
            return Optional.empty();
        }
        if (!publicAccess) {
            LOG.debugv("Search: refused {0} {1} for {2}: public network access is {3}", request.method(),
                request.resourcePath(), request.remoteAddress(), service.publicNetworkAccess());
            return Optional.of(new Denied(SearchErrorCode.FORBIDDEN, "Public network access to the search service '"
                + service.name() + "' is disabled; it accepts requests only through an approved private endpoint."));
        }
        Optional<InetAddress> caller = clientAddress(request).or(() -> peer);
        if (caller.isPresent() && rules.stream().anyMatch(rule -> rule.contains(caller.get()))) {
            return Optional.empty();
        }
        String address = caller.map(InetAddress::getHostAddress).orElse("an unknown address");
        LOG.debugv("Search: refused {0} {1} for {2}: not in the IP firewall rules {3}", request.method(),
            request.resourcePath(), address, rules);
        return Optional.of(new Denied(SearchErrorCode.FORBIDDEN, "The request from " + address + " is blocked by the "
            + "IP firewall rules of the search service '" + service.name() + "'."));
    }

    /** The address the test hook header names, when the hook is on and the header holds an address. */
    private Optional<InetAddress> clientAddress(AzureRequest request) {
        if (!config.services().search().trustClientIpHeader() || request.headers() == null) {
            return Optional.empty();
        }
        return IpPrefix.address(request.headers().getHeaderString(CLIENT_IP_HEADER));
    }

    /** The {@code value} of each {@code networkRuleSet.ipRules} entry, as address ranges. */
    private static List<IpPrefix> ipRules(JsonNode networkRuleSet) {
        List<IpPrefix> rules = new ArrayList<>();
        if (networkRuleSet == null) {
            return rules;
        }
        for (JsonNode rule : networkRuleSet.path("ipRules")) {
            IpPrefix.parse(rule.path("value").asText(null)).ifPresent(rules::add);
        }
        return rules;
    }
}
