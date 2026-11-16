package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.security.AuthorizationDecision.Caller;
import io.floci.az.services.search.security.SearchOperation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One authorised data-plane request, as a controller receives it.
 *
 * @param request        the request
 * @param service        the search service it addresses
 * @param operation      the operation the dispatch table matched
 * @param caller         how the caller authenticated
 * @param pathParameters the path's variables by name ({@code index}, {@code key}, {@code name}, ...)
 * @param apiVersion     the validated api-version
 * @param serviceUrl     the service endpoint as the caller addressed it ({@code https://svc.search.windows.net} or
 *                       {@code http://localhost:4577/svc-search}), for {@code @odata.context} and other URLs
 * @param requestUrl     the request URL as the caller addressed it, without the query string
 * @param body           the request body, at most 16 MB; empty when there is none
 */
public record SearchCall(AzureRequest request, SearchServiceInfo service, SearchOperation operation, Caller caller,
                         Map<String, String> pathParameters, String apiVersion, String serviceUrl, String requestUrl,
                         byte[] body) {

    public SearchCall {
        pathParameters = Map.copyOf(pathParameters);
    }

    /** The service name, lowercased, as the engine keys services. */
    public String serviceName() {
        return service.name().toLowerCase(Locale.ROOT);
    }

    /** The path variable {@code name}, or null. */
    public String path(String name) {
        return pathParameters.get(name);
    }

    public String header(String name) {
        return request.headers() == null ? null : request.headers().getHeaderString(name);
    }

    /** The first value of query parameter {@code name}, or null. */
    public String query(String name) {
        return request.queryParams() == null ? null : request.queryParams().get(name);
    }

    /** Every value of query parameter {@code name}. */
    public List<String> queryValues(String name) {
        List<String> values = request.queryParamsMulti() == null ? null : request.queryParamsMulti().get(name);
        if (values != null) {
            return values;
        }
        String single = query(name);
        return single == null ? List.of() : List.of(single);
    }

    /** The body as JSON; null when there is none. A non-JSON {@code Content-Type} is a 415. */
    public JsonNode json() {
        requireJsonContent();
        return SearchJson.parse(body);
    }

    /** The body as a JSON object; a 400 when it is missing or not an object, a 415 when it is not JSON. */
    public ObjectNode jsonObject(String what) {
        requireJsonContent();
        return SearchJson.parseObject(body, what);
    }

    private void requireJsonContent() {
        String contentType = header("Content-Type");
        if (body.length > 0 && contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase(Locale.ROOT).contains("json")) {
            throw new SearchException(SearchErrorCode.UNSUPPORTED_MEDIA_TYPE, "The content type '" + contentType
                + "' is not supported; send the request body as application/json.");
        }
    }

    /**
     * Whether responses carry OData metadata ({@code @odata.context}): the {@code Accept} header's
     * {@code odata.metadata} is {@code minimal} (the default) or {@code full}, not {@code none}.
     */
    public boolean minimalMetadata() {
        String accept = header("Accept");
        return accept == null || !accept.toLowerCase(Locale.ROOT).replace(" ", "").contains("odata.metadata=none");
    }

    /**
     * The {@code Prefer} header's choice: true for {@code return=representation}, false for {@code return=minimal},
     * null when the header names neither.
     */
    public Boolean preferRepresentation() {
        String prefer = header("Prefer");
        if (prefer == null) {
            return null;
        }
        String normalized = prefer.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.contains("return=representation")) {
            return Boolean.TRUE;
        }
        return normalized.contains("return=minimal") ? Boolean.FALSE : null;
    }
}
