package io.floci.az.services.search.security;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.ParityLevel;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.security.AuthorizationDecision.Allowed;
import io.floci.az.services.search.security.AuthorizationDecision.Caller;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * Every authorization decision of the search data plane, made in one place before a request reaches its controller.
 *
 * <p>Today, per Learn's "Connect to Azure AI Search using keys":</p>
 * <ul>
 *   <li>an {@code api-key} header wins over an {@code Authorization} header, as the service documents for requests
 *       carrying both;</li>
 *   <li>an admin key may call every operation; a query key only the reads of an index's documents collection
 *       (search, suggest, autocomplete, lookup, count), anything else is a 403;</li>
 *   <li>a key the service does not know is accepted as an admin key, floci-az's usual leniency, and refused with a
 *       403 under {@code parity: strict};</li>
 *   <li>API keys are refused with a 403 when the service sets {@code disableLocalAuth};</li>
 *   <li>a bearer token is accepted as an admin (until Entra validation, RBAC and {@code authOptions} land here);</li>
 *   <li>a request with neither credential is a 401, as the service answers missing credentials.</li>
 * </ul>
 */
@ApplicationScoped
public class SearchAuthorizer {

    private static final String API_KEY_HEADER = "api-key";
    private static final String BEARER_PREFIX = "bearer ";

    private final EmulatorConfig config;

    @Inject
    public SearchAuthorizer(EmulatorConfig config) {
        this.config = config;
    }

    /** Decides whether {@code request} may perform {@code operation} on {@code service}. */
    public AuthorizationDecision authorize(AzureRequest request, SearchServiceInfo service, SearchOperation operation) {
        String apiKey = header(request, API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return authorizeKey(apiKey.trim(), service, operation);
        }
        String authorization = header(request, "Authorization");
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return new Allowed(Caller.BEARER_TOKEN);
        }
        return new Denied(SearchErrorCode.UNAUTHORIZED, "Access denied: the request carries neither an api-key header "
            + "nor a bearer token.");
    }

    private AuthorizationDecision authorizeKey(String key, SearchServiceInfo service, SearchOperation operation) {
        if (service.disableLocalAuth()) {
            return new Denied(SearchErrorCode.FORBIDDEN, "API keys are disabled on the search service '"
                + service.name() + "'; authenticate with Microsoft Entra ID instead.");
        }
        if (service.isAdminKey(key)) {
            return new Allowed(Caller.ADMIN_KEY);
        }
        if (service.isQueryKey(key)) {
            return operation.queryKeyAllowed()
                ? new Allowed(Caller.QUERY_KEY)
                : new Denied(SearchErrorCode.FORBIDDEN, "Access denied: a query key can only read the documents of an "
                    + "index; this operation needs an admin key.");
        }
        if (config.parity().includes(ParityLevel.STRICT)) {
            return new Denied(SearchErrorCode.FORBIDDEN, "Access denied: the api-key is not a key of the search "
                + "service '" + service.name() + "'.");
        }
        return new Allowed(Caller.UNKNOWN_KEY);
    }

    private static String header(AzureRequest request, String name) {
        return request.headers() == null ? null : request.headers().getHeaderString(name);
    }
}
