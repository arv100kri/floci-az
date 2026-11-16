package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.ParityLevel;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.dataplane.ApiVersions;
import io.floci.az.services.search.dataplane.SearchCall;
import io.floci.az.services.search.dataplane.SearchDispatchTable;
import io.floci.az.services.search.dataplane.SearchPath;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.security.AuthorizationDecision;
import io.floci.az.services.search.security.AuthorizationDecision.Allowed;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import io.floci.az.services.search.security.SearchAuthorizer;
import io.floci.az.services.search.security.SearchNetworkPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * The Azure AI Search data plane, at {@code {service}.search.windows.net} and {@code /{service}-search/}: the service
 * name is the account name. Every request goes through the same steps: the {@link SearchDispatchTable} matches the
 * route (both {@code indexes('x')} and {@code indexes/x} forms), the {@code api-version} is checked, the service is
 * resolved (provisioned with the development keys on first use, unless {@code parity: strict} requires it to exist),
 * {@link SearchAuthorizer} decides, and the matched controller answers. Responses carry {@code request-id} (the
 * caller's {@code client-request-id} when it sent one), {@code elapsed-time} and the echoed client request ids.
 */
@ApplicationScoped
public class SearchHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(SearchHandler.class);

    /** The documented maximum size of a request body. */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final EmulatorConfig config;
    private final SearchServiceRegistry services;
    private final SearchNetworkPolicy networkPolicy;
    private final SearchAuthorizer authorizer;
    private final SearchDispatchTable dispatchTable;
    private final SearchEngine engine;

    @Inject
    public SearchHandler(EmulatorConfig config, SearchServiceRegistry services, SearchNetworkPolicy networkPolicy,
                         SearchAuthorizer authorizer, SearchDispatchTable dispatchTable, SearchEngine engine) {
        this.config = config;
        this.services = services;
        this.networkPolicy = networkPolicy;
        this.authorizer = authorizer;
        this.dispatchTable = dispatchTable;
        this.engine = engine;
    }

    @Override
    public String getServiceType() {
        return "search";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().search().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
            .host(".search.windows.net")
            .account("-search", "search")
            .build();
    }

    @Override
    public Response handle(AzureRequest request) {
        long started = System.nanoTime();
        LOG.debugv("Search {0} /{1}", request.method(), request.resourcePath());
        Response response;
        try {
            response = dispatch(request);
        } catch (SearchException e) {
            response = SearchErrors.toResponse(e);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Search request {0} /{1} failed", request.method(), request.resourcePath());
            response = SearchErrors.error(SearchErrorCode.INTERNAL_ERROR, "An unexpected error occurred while "
                + "processing the request.");
        }
        return decorate(response, request, started);
    }

    private Response dispatch(AzureRequest request) {
        SearchDispatchTable.Match match = dispatchTable.match(request.method(),
            SearchPath.parse(request.resourcePath()));
        String apiVersion = ApiVersions.require(request.queryParams() == null ? null
            : request.queryParams().get("api-version"));
        SearchServiceInfo service = resolveService(request.accountName());
        Optional<Denied> blocked = networkPolicy.check(request, service, match.operation());
        if (blocked.isPresent()) {
            return SearchErrors.toResponse(blocked.get());
        }
        AuthorizationDecision decision = authorizer.authorize(request, service, match.operation());
        return switch (decision) {
            case Denied denied -> SearchErrors.toResponse(denied);
            case Allowed allowed -> {
                String serviceUrl = serviceUrl(request);
                SearchCall call = new SearchCall(request, service, match.operation(), allowed.caller(),
                    match.parameters(), apiVersion, serviceUrl, requestUrl(request, serviceUrl), readBody(request));
                yield match.endpoint().handle(call);
            }
        };
    }

    private SearchServiceInfo resolveService(String name) {
        if (config.parity().includes(ParityLevel.STRICT)) {
            return services.find(name).orElseThrow(() -> new SearchException(SearchErrorCode.SERVICE_NOT_FOUND,
                "The search service '" + name + "' does not exist. Create it through Microsoft.Search first."));
        }
        return services.findOrProvision(name);
    }

    /** The service endpoint the caller addressed: the host, plus the {@code {service}-search} segment path-style. */
    private String serviceUrl(AzureRequest request) {
        String origin = RequestUrls.resolveBaseUrl(request, config);
        String suffix = request.headers() == null ? null : request.headers().getHeaderString("x-floci-account-suffix");
        if (suffix == null) {
            return origin;
        }
        String rawPath = request.rawPath() == null ? "" : request.rawPath();
        int slash = rawPath.indexOf('/');
        String accountSegment = slash < 0 ? rawPath : rawPath.substring(0, slash);
        if (accountSegment.isEmpty()) {
            accountSegment = request.accountName() + suffix;
        }
        return origin + "/" + accountSegment;
    }

    private static String requestUrl(AzureRequest request, String serviceUrl) {
        return serviceUrl + "/" + request.resourcePath();
    }

    private static byte[] readBody(AzureRequest request) {
        InputStream body = request.bodyStream();
        if (body == null) {
            return new byte[0];
        }
        try {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new SearchException(SearchErrorCode.REQUEST_TOO_LARGE, "The request body is larger than 16 MB, "
                    + "the maximum size of a request to the search service.");
            }
            return bytes;
        } catch (IOException e) {
            throw SearchException.badRequest("The request body could not be read: " + e.getMessage());
        }
    }

    /** Adds the headers every search response carries. */
    private static Response decorate(Response response, AzureRequest request, long started) {
        String clientRequestId = header(request, "client-request-id");
        String msClientRequestId = header(request, "x-ms-client-request-id");
        String requestId = clientRequestId != null ? clientRequestId
            : msClientRequestId != null ? msClientRequestId : UUID.randomUUID().toString();
        Response.ResponseBuilder builder = Response.fromResponse(response)
            .header("request-id", requestId)
            .header("elapsed-time", String.valueOf((System.nanoTime() - started) / 1_000_000))
            .header("OData-Version", "4.0");
        if (clientRequestId != null && "true".equalsIgnoreCase(header(request, "return-client-request-id"))) {
            builder.header("client-request-id", clientRequestId);
        }
        if (msClientRequestId != null) {
            builder.header("x-ms-client-request-id", msClientRequestId);
        }
        return builder.build();
    }

    private static String header(AzureRequest request, String name) {
        return request.headers() == null ? null : request.headers().getHeaderString(name);
    }

    @Override
    public void clear() {
        engine.clear();
    }
}
