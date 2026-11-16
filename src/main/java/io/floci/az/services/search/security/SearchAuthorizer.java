package io.floci.az.services.search.security;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.ParityLevel;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.auth.BearerClaims;
import io.floci.az.core.auth.RbacDecision;
import io.floci.az.core.auth.RbacEvaluator;
import io.floci.az.core.auth.RbacOperation;
import io.floci.az.core.auth.RbacPrincipal;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.services.entra.TokenValidator;
import io.floci.az.services.entra.TokenValidator.Rejection;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.dataplane.SearchPath;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.security.AuthorizationDecision.Allowed;
import io.floci.az.services.search.security.AuthorizationDecision.Caller;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import io.floci.az.services.search.security.SearchAuthOptions.FailureMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every authorization decision of the search data plane, made in one place before a request reaches its controller,
 * per Learn's "Connect to Azure AI Search using keys", "Enable or disable role-based access control", "Connect using
 * Azure roles" and "Query-time ACL and RBAC enforcement".
 *
 * <p><b>Credentials.</b> Which ones a service accepts is its own setting ({@link SearchAuthOptions}): keys only, keys
 * or tokens, or tokens only. A service a data-plane request provisioned accepts both.</p>
 * <ul>
 *   <li>An {@code api-key} header wins over an {@code Authorization} header: "if you provide both in the same request,
 *       the API key is used". When the service refuses keys ({@code disableLocalAuth}), a request with a key is a 403
 *       whatever else it carries: the service "refuses all data-related requests that pass an API key".</li>
 *   <li>An admin key may call every operation; a query key only the reads of an index's documents collection. A key
 *       the service does not know is accepted as an admin key, floci-az's usual leniency, and refused with a 403
 *       under {@code parity: strict}.</li>
 *   <li>A bearer token sent to a keys-only service is a 403: such requests "are automatically denied, regardless of
 *       the underlying permissions". Otherwise, when {@link EmulatorConfig#strictTokens()} is on, the token must be
 *       one floci-az's Entra issued for a search audience (the {@code https://search.azure.com/.default} scope and
 *       its sovereign-cloud forms); when it is off, its claims are read without verification, as everywhere in
 *       floci-az.</li>
 *   <li>A request that fails authentication (no credential, or a refused token) is answered as the service's
 *       {@code aadAuthFailureMode} says: a 401 with a bearer challenge naming {@code https://search.azure.com}, or a
 *       403. A keys-only service answers a request without a key with a plain 401.</li>
 * </ul>
 *
 * <p><b>Roles.</b> When {@link EmulatorConfig#strictRbac()} is on, a bearer caller also needs a role assignment that
 * grants the operation's {@link SearchOperation#permission()}, evaluated by {@link RbacEvaluator} at the service's ARM
 * id, or at {@code {service}/indexes/{index}} for a document operation, where Learn documents index-scoped
 * assignments. Keys bypass roles. A service without an ARM resource has no scope for an assignment, so a bearer
 * caller is refused there; an API key still works. Off, a bearer caller may call every operation.</p>
 *
 * <p><b>Document reads.</b> A read of an index's documents also gets a {@link DocumentAccess}: the end user of the
 * {@code x-ms-query-source-authorization} token (validated like the caller's token under strict tokens), or no end
 * user, or every document for an {@code x-ms-enable-elevated-read: true} read. An elevated read needs
 * {@code Microsoft.Search/searchServices/indexes/contentSecurity/elevatedOperations/read}: an admin key holds every
 * data-plane permission, a query key none, and a bearer caller needs a role that grants it when roles are enforced.</p>
 *
 * <p>The texts of the errors and of the challenge are floci-az's own: Learn documents the statuses, not the bodies.</p>
 */
@ApplicationScoped
public class SearchAuthorizer {

    private static final Logger LOG = Logger.getLogger(SearchAuthorizer.class);

    /** The header that carries an API key. */
    public static final String API_KEY_HEADER = "api-key";

    /** The header that carries the end user's token for document-level permission filters. */
    public static final String QUERY_SOURCE_HEADER = "x-ms-query-source-authorization";

    /** The header that asks for a read that bypasses document-level permission filters. */
    public static final String ELEVATED_READ_HEADER = "x-ms-enable-elevated-read";

    /** The data action an elevated read needs. */
    public static final RbacOperation ELEVATED_READ =
        RbacOperation.dataAction("Microsoft.Search/searchServices/indexes/contentSecurity/elevatedOperations/read");

    /**
     * The audiences a search token may carry: the {@code SearchAudience} values of azure-search-documents (public
     * cloud, Azure US Government, Azure China), whose {@code /.default} scopes the SDKs request.
     */
    public static final List<String> AUDIENCES =
        List.of("https://search.azure.com", "https://search.azure.us", "https://search.azure.cn"); // cloud-literal: the SDKs' SearchAudience values for every cloud

    /** The resource a bearer challenge names: the public cloud's search audience, the SDKs' default. */
    private static final String CHALLENGE_RESOURCE = "https://search.azure.com"; // cloud-literal: the SDKs' default SearchAudience

    private static final String BEARER = "Bearer ";

    private final EmulatorConfig config;
    private final TokenValidator tokens;
    private final RbacEvaluator rbac;
    private final QuerySourceIdentity identities;
    private final AzureDirectory directory;

    @Inject
    public SearchAuthorizer(EmulatorConfig config, TokenValidator tokens, RbacEvaluator rbac,
                            QuerySourceIdentity identities, AzureDirectory directory) {
        this.config = config;
        this.tokens = tokens;
        this.rbac = rbac;
        this.identities = identities;
        this.directory = directory;
    }

    /** Decides whether {@code request} may perform {@code operation} on {@code service}. */
    public AuthorizationDecision authorize(AzureRequest request, SearchServiceInfo service, SearchOperation operation) {
        SearchAuthOptions options = SearchAuthOptions.of(service);
        String apiKey = header(request, API_KEY_HEADER);
        String authorization = header(request, "Authorization");
        AuthorizationDecision decision;
        if (apiKey != null && !apiKey.isBlank()) {
            decision = authorizeKey(apiKey.trim(), service, operation);
        } else if (isBearer(authorization)) {
            decision = authorizeToken(request, authorization.trim(), service, operation, options);
        } else {
            decision = unauthenticated(request, service, options);
        }
        if (decision instanceof Allowed allowed && operation.isDocumentRead()) {
            return documentRead(request, service, operation, allowed.caller());
        }
        return decision;
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

    private AuthorizationDecision authorizeToken(AzureRequest request, String authorization, SearchServiceInfo service,
                                                 SearchOperation operation, SearchAuthOptions options) {
        if (!options.tokensAccepted()) {
            return new Denied(SearchErrorCode.FORBIDDEN, "Access denied: the search service '" + service.name()
                + "' accepts only API keys (authOptions apiKeyOnly), so a Microsoft Entra token cannot authorize the "
                + "request. Enable role-based access control on the service to use tokens.");
        }
        if (config.strictTokens()) {
            Optional<Rejection> rejection = tokens.validate(authorization, SearchAuthorizer::isSearchAudience);
            if (rejection.isPresent()) {
                LOG.infov("Refused search request {0} /{1}: {2} (floci-az.parity-overrides.strict-tokens is on)",
                    request.method(), request.resourcePath(), rejection.get().message());
                return authenticationFailed(request, service, options, "Access denied. "
                    + rejection.get().message(), true);
            }
        }
        if (config.strictRbac()) {
            Optional<Denied> refused = requireRole(request, BearerClaims.parse(authorization), service,
                operation.rbacOperation(), scope(request, service, operation));
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        return new Allowed(Caller.BEARER_TOKEN);
    }

    private AuthorizationDecision unauthenticated(AzureRequest request, SearchServiceInfo service,
                                                  SearchAuthOptions options) {
        if (!options.tokensAccepted()) {
            return new Denied(SearchErrorCode.UNAUTHORIZED, "Access denied: the request carries no api-key header.");
        }
        String message = options.keysAccepted()
            ? "Access denied: the request carries neither an api-key header nor a bearer token."
            : "Access denied: the request carries no bearer token, and the search service '" + service.name()
                + "' accepts no API keys.";
        return authenticationFailed(request, service, options, message, false);
    }

    /** A request that failed authentication, answered as the service's {@code aadAuthFailureMode} says. */
    private Denied authenticationFailed(AzureRequest request, SearchServiceInfo service, SearchAuthOptions options,
                                        String message, boolean invalidToken) {
        if (options.failureMode() == FailureMode.HTTP_403) {
            return new Denied(SearchErrorCode.FORBIDDEN, message);
        }
        return new Denied(SearchErrorCode.UNAUTHORIZED, message,
            Map.of("WWW-Authenticate", challenge(request, service, invalidToken)));
    }

    /**
     * The bearer challenge: the emulator's authorize endpoint in the service's tenant, and the search audience as the
     * resource. Every value is the emulator's own, never one the client sent.
     */
    private String challenge(AzureRequest request, SearchServiceInfo service, boolean invalidToken) {
        String authorizationUri = RequestUrls.resolveBaseUrl(request, config) + "/" + tenantOf(service)
            + "/oauth2/v2.0/authorize";
        return "Bearer authorization_uri=\"" + authorizationUri + "\", resource=\"" + CHALLENGE_RESOURCE + "\""
            + (invalidToken ? ", error=\"invalid_token\"" : "");
    }

    /** The tenant of the service's subscription; the default tenant for a service without an ARM resource. */
    private String tenantOf(SearchServiceInfo service) {
        String id = service.resourceId();
        String prefix = "/subscriptions/";
        if (id != null && id.regionMatches(true, 0, prefix, 0, prefix.length())) {
            int end = id.indexOf('/', prefix.length());
            return directory.tenantOfSubscription(end < 0 ? id.substring(prefix.length())
                : id.substring(prefix.length(), end));
        }
        return directory.defaultTenantId();
    }

    /**
     * Refuses a bearer caller no role assignment grants {@code permission} at {@code scope}; empty when one does. A
     * service without an ARM resource has no scope to assign a role at.
     */
    private Optional<Denied> requireRole(AzureRequest request, BearerClaims claims, SearchServiceInfo service,
                                         RbacOperation permission, String scope) {
        if (service.resourceId() == null) {
            return Optional.of(new Denied(SearchErrorCode.FORBIDDEN, "Access denied: the search service '"
                + service.name() + "' was provisioned by a data-plane request and has no Azure resource, so no role "
                + "assignment can grant the " + permission.kind() + " '" + permission.name() + "'. Create the "
                + "service through Microsoft.Search, or authenticate with an API key."));
        }
        RbacDecision decision = rbac.evaluate(RbacPrincipal.of(claims), scope, permission);
        if (decision.allowed()) {
            return Optional.empty();
        }
        LOG.infov("Refused search request {0} /{1}: {2} (floci-az.parity-overrides.strict-rbac is on)",
            request.method(), request.resourcePath(), decision.reason());
        String caller = claims.oid() == null ? "the caller" : "the principal '" + claims.oid() + "'";
        return Optional.of(new Denied(SearchErrorCode.FORBIDDEN, "Access denied: no role assignment of " + caller
            + " grants the " + permission.kind() + " '" + permission.name() + "' at scope '" + scope + "' or above "
            + "it. The built-in role " + grantingRole(permission) + " grants it."));
    }

    /** The built-in Search role that grants {@code permission}, for the hint in a refusal. */
    private static String grantingRole(RbacOperation permission) {
        if (!permission.dataAction()) {
            return "Search Service Contributor";
        }
        return permission.name().endsWith("/documents/read") ? "Search Index Data Reader"
            : "Search Index Data Contributor";
    }

    /** The scope a role check is made at: the index for a document operation, the service otherwise. */
    private static String scope(AzureRequest request, SearchServiceInfo service, SearchOperation operation) {
        String index = operation.resource() == SearchResourceKind.DOCUMENT ? indexName(request) : null;
        return index == null ? service.resourceId() : service.resourceId() + "/indexes/" + index;
    }

    /** The index a path names ({@code indexes('x')/...} or {@code indexes/x/...}), or null. */
    private static String indexName(AzureRequest request) {
        List<SearchPath.Segment> segments = SearchPath.parse(request.resourcePath()).segments();
        return segments.size() >= 2 && "indexes".equalsIgnoreCase(segments.get(0).value())
            ? segments.get(1).value() : null;
    }

    /** The decision for a read of an index's documents: who it is for, or a refused elevated read or user token. */
    private AuthorizationDecision documentRead(AzureRequest request, SearchServiceInfo service,
                                               SearchOperation operation, Caller caller) {
        String elevated = header(request, ELEVATED_READ_HEADER);
        if (elevated != null && "true".equalsIgnoreCase(elevated.trim())) {
            Optional<Denied> refused = elevatedRead(request, service, operation, caller);
            return refused.isPresent() ? refused.get() : new Allowed(caller, DocumentAccess.UNRESTRICTED);
        }
        String token = header(request, QUERY_SOURCE_HEADER);
        if (token == null || token.isBlank()) {
            return new Allowed(caller, DocumentAccess.ANONYMOUS);
        }
        String authorization = isBearer(token.trim()) ? token.trim() : BEARER + token.trim();
        if (config.strictTokens()) {
            Optional<Rejection> rejection = tokens.validate(authorization, SearchAuthorizer::isSearchAudience);
            if (rejection.isPresent()) {
                LOG.infov("Refused search request {0} /{1}: the {2} token: {3} (floci-az.parity-overrides."
                    + "strict-tokens is on)", request.method(), request.resourcePath(), QUERY_SOURCE_HEADER,
                    rejection.get().message());
                return new Denied(SearchErrorCode.UNAUTHORIZED, "Access denied. The token in the "
                    + QUERY_SOURCE_HEADER + " header is not valid: " + rejection.get().message());
            }
        }
        return new Allowed(caller, DocumentAccess.of(identities.principalIds(BearerClaims.parse(authorization))));
    }

    /** Refuses an elevated read the caller may not make; empty when it may. */
    private Optional<Denied> elevatedRead(AzureRequest request, SearchServiceInfo service, SearchOperation operation,
                                          Caller caller) {
        return switch (caller) {
            case ADMIN_KEY, UNKNOWN_KEY -> Optional.empty();
            case QUERY_KEY -> Optional.of(new Denied(SearchErrorCode.FORBIDDEN, "Access denied: an elevated read ("
                + ELEVATED_READ_HEADER + ") needs the data action '" + ELEVATED_READ.name() + "', which a query key "
                + "does not hold."));
            case BEARER_TOKEN -> config.strictRbac()
                ? requireRole(request, BearerClaims.of(request), service, ELEVATED_READ,
                    scope(request, service, operation))
                : Optional.empty();
        };
    }

    /** Whether a token's {@code aud} is a search audience, ignoring case and a trailing slash. */
    static boolean isSearchAudience(String audience) {
        String wanted = audience.endsWith("/") ? audience.substring(0, audience.length() - 1) : audience;
        return AUDIENCES.stream().anyMatch(wanted::equalsIgnoreCase);
    }

    private static boolean isBearer(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length());
    }

    private static String header(AzureRequest request, String name) {
        return request.headers() == null ? null : request.headers().getHeaderString(name);
    }
}
