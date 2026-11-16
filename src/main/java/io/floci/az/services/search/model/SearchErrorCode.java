package io.floci.az.services.search.model;

/**
 * The error codes the search data plane answers with, each with its HTTP status. Every Azure-shaped search error goes
 * through one of these, so a code captured from a live service later is corrected in one place.
 */
public enum SearchErrorCode {
    MISSING_API_VERSION(400, "MissingApiVersionParameter"),
    INVALID_API_VERSION(400, "InvalidApiVersionParameter"),
    INVALID_REQUEST(400, "InvalidRequestParameter"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    RESOURCE_NOT_FOUND(404, "ResourceNotFound"),
    SERVICE_NOT_FOUND(404, "ServiceNotFound"),
    METHOD_NOT_ALLOWED(405, "MethodNotAllowed"),
    RESOURCE_ALREADY_EXISTS(409, "ResourceNameAlreadyInUse"),
    PRECONDITION_FAILED(412, "PreconditionFailed"),
    REQUEST_TOO_LARGE(413, "RequestEntityTooLarge"),
    UNSUPPORTED_MEDIA_TYPE(415, "UnsupportedMediaType"),
    INTERNAL_ERROR(500, "InternalServerError"),
    NOT_IMPLEMENTED(501, "NotImplemented");

    private final int status;
    private final String code;

    SearchErrorCode(int status, String code) {
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
