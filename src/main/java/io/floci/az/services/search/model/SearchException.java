package io.floci.az.services.search.model;

/**
 * A request the search data plane refuses, carrying the Azure error it is answered with. The engine, the model and the
 * controllers throw it; {@code SearchErrors} turns it into the {@code {"error":{"code","message"}}} envelope.
 */
public class SearchException extends RuntimeException {

    private final SearchErrorCode errorCode;

    public SearchException(SearchErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SearchErrorCode errorCode() {
        return errorCode;
    }

    public int status() {
        return errorCode.status();
    }

    /** A 400 for a request whose URL, headers or body the service cannot accept. */
    public static SearchException badRequest(String message) {
        return new SearchException(SearchErrorCode.INVALID_REQUEST, message);
    }

    /** A 404 for a resource that does not exist. */
    public static SearchException notFound(String message) {
        return new SearchException(SearchErrorCode.RESOURCE_NOT_FOUND, message);
    }

    /** A 409 for a create that names an existing resource. */
    public static SearchException alreadyExists(String message) {
        return new SearchException(SearchErrorCode.RESOURCE_ALREADY_EXISTS, message);
    }

    /** A 412 for an {@code If-Match} or {@code If-None-Match} condition that does not hold. */
    public static SearchException preconditionFailed(String message) {
        return new SearchException(SearchErrorCode.PRECONDITION_FAILED, message);
    }

    /** A 501 for a documented feature floci-az does not emulate yet. */
    public static SearchException notImplemented(String feature) {
        return new SearchException(SearchErrorCode.NOT_IMPLEMENTED,
            feature + " is not implemented by the floci-az search emulator yet.");
    }
}
