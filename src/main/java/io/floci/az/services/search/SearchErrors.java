package io.floci.az.services.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.security.AuthorizationDecision.Denied;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The one way the search data plane answers an error: the Azure envelope
 * {@code {"error":{"code":"...","message":"..."}}} as JSON, with the status of its {@link SearchErrorCode}.
 */
public final class SearchErrors {

    /** The content type of error responses. */
    public static final String ERROR_CONTENT_TYPE = "application/json; odata.metadata=minimal; charset=utf-8";

    private SearchErrors() {
    }

    public static Response toResponse(SearchException exception) {
        return error(exception.errorCode(), exception.getMessage(), Map.of());
    }

    public static Response toResponse(Denied denied) {
        return error(denied.code(), denied.message(), denied.headers());
    }

    public static Response error(SearchErrorCode code, String message) {
        return error(code, message, Map.of());
    }

    /** A 501 {@code NotImplemented} for a documented feature the emulator does not provide yet. */
    public static Response notImplemented(String feature) {
        return toResponse(SearchException.notImplemented(feature));
    }

    /** The error envelope. */
    public static ObjectNode envelope(String code, String message) {
        ObjectNode envelope = SearchJson.object();
        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return envelope;
    }

    private static Response error(SearchErrorCode code, String message, Map<String, String> headers) {
        Response.ResponseBuilder builder = Response.status(code.status())
            .entity(SearchJson.write(envelope(code.code(), message)))
            .header("Content-Type", ERROR_CONTENT_TYPE);
        headers.forEach(builder::header);
        return builder.build();
    }
}
