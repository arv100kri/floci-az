package io.floci.az.services.search.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;

/**
 * Which credentials a search service's data plane accepts, and how it answers a request that fails authentication,
 * read from the service's ARM settings as Learn's "Enable or disable role-based access control" documents them.
 *
 * <ul>
 *   <li>{@code authOptions: {"apiKeyOnly": {}}}, the default for a new service: API keys only. "If you don't change
 *       this setting to Both or Role-based access control, all requests that use role-based authentication are
 *       automatically denied."</li>
 *   <li>{@code authOptions: {"aadOrApiKey": {...}}} ("Both"): an API key or a Microsoft Entra token, the key winning
 *       when a request carries both. {@code aadAuthFailureMode} is {@code http401WithBearerChallenge}, "the default
 *       failure mode for unauthorized requests", or {@code http403}.</li>
 *   <li>{@code disableLocalAuth: true} ("Role-based access control"): tokens only; the service "refuses all
 *       data-related requests that pass an API key in the header". ARM drops {@code authOptions} then, so failures
 *       take the default mode.</li>
 *   <li>A service a data-plane request provisioned has no ARM settings at all. It accepts both, so floci-az's usual
 *       leniency holds for keys and tokens alike.</li>
 * </ul>
 *
 * @param keysAccepted   whether an {@code api-key} header may authenticate a request
 * @param tokensAccepted whether a bearer token may authenticate a request
 * @param failureMode    how a request that fails authentication is answered when tokens are accepted
 */
public record SearchAuthOptions(boolean keysAccepted, boolean tokensAccepted, FailureMode failureMode) {

    /** The {@code aadAuthFailureMode} values. */
    public enum FailureMode {
        /** {@code http401WithBearerChallenge}: a 401 with a {@code WWW-Authenticate} bearer challenge. */
        HTTP_401_WITH_BEARER_CHALLENGE,
        /** {@code http403}: a 403. */
        HTTP_403
    }

    private static final String API_KEY_ONLY = "apiKeyOnly";
    private static final String AAD_OR_API_KEY = "aadOrApiKey";

    /** The options of {@code service}. */
    public static SearchAuthOptions of(SearchServiceInfo service) {
        if (service.disableLocalAuth()) {
            return new SearchAuthOptions(false, true, FailureMode.HTTP_401_WITH_BEARER_CHALLENGE);
        }
        JsonNode options = service.authOptions();
        if (options == null || options.isNull()) {
            // ARM always writes authOptions while keys are enabled: only a service it never saw has none.
            boolean dataPlaneOnly = service.resourceId() == null;
            return new SearchAuthOptions(true, dataPlaneOnly, FailureMode.HTTP_401_WITH_BEARER_CHALLENGE);
        }
        JsonNode both = options.get(AAD_OR_API_KEY);
        if (both != null && !both.isNull() && !options.has(API_KEY_ONLY)) {
            String mode = both.path("aadAuthFailureMode").asText("");
            return new SearchAuthOptions(true, true, "http403".equalsIgnoreCase(mode)
                ? FailureMode.HTTP_403 : FailureMode.HTTP_401_WITH_BEARER_CHALLENGE);
        }
        return new SearchAuthOptions(true, false, FailureMode.HTTP_401_WITH_BEARER_CHALLENGE);
    }
}
