package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds data-plane responses: JSON with the OData content type the {@code Accept} header asks for, and
 * {@code @odata.context} URLs relative to the endpoint the caller addressed.
 */
public final class SearchResponses {

    private static final String MINIMAL = "application/json; odata.metadata=minimal; odata.streaming=true; "
        + "charset=utf-8";
    private static final String NONE = "application/json; odata.metadata=none; odata.streaming=true; charset=utf-8";

    private SearchResponses() {
    }

    /** A JSON response; {@code body} is written as given. */
    public static Response json(SearchCall call, int status, ObjectNode body) {
        return Response.status(status)
            .entity(SearchJson.write(body))
            .header("Content-Type", call.minimalMetadata() ? MINIMAL : NONE)
            .build();
    }

    /** A JSON response builder, for responses that add headers. */
    public static Response.ResponseBuilder jsonBuilder(SearchCall call, int status, ObjectNode body) {
        return Response.status(status)
            .entity(SearchJson.write(body))
            .header("Content-Type", call.minimalMetadata() ? MINIMAL : NONE);
    }

    /**
     * {@code body} with {@code @odata.context} first when the caller asked for OData metadata.
     *
     * @param fragment what follows {@code $metadata#}, such as {@code indexes/$entity}
     */
    public static ObjectNode withContext(SearchCall call, String fragment, ObjectNode body) {
        return withContextUrl(call, call.serviceUrl() + "/$metadata#" + fragment, body);
    }

    /** {@code body} with an index-scoped {@code @odata.context}: {@code {service}/indexes('x')/$metadata#...}. */
    public static ObjectNode withIndexContext(SearchCall call, String index, String fragment, ObjectNode body) {
        return withContextUrl(call, indexUrl(call, index) + "/$metadata#" + fragment, body);
    }

    private static ObjectNode withContextUrl(SearchCall call, String context, ObjectNode body) {
        if (!call.minimalMetadata()) {
            return body;
        }
        ObjectNode result = SearchJson.object();
        result.put("@odata.context", context);
        result.setAll(body);
        return result;
    }

    /** The URL of an index: {@code {service}/indexes('name')}. */
    public static String indexUrl(SearchCall call, String index) {
        return call.serviceUrl() + "/indexes('" + index + "')";
    }

    /** {@code value} encoded for a URL query string. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
