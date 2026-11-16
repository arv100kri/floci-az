package io.floci.az.services.search.security;

import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;

import java.util.Map;

/** What {@link SearchAuthorizer} decided about a request. */
public sealed interface AuthorizationDecision {

    /**
     * The request may proceed.
     *
     * @param caller how the caller authenticated
     */
    record Allowed(Caller caller) implements AuthorizationDecision {
    }

    /**
     * The request is refused with an Azure-shaped error.
     *
     * @param code    the error code and HTTP status
     * @param message the error message
     * @param headers extra response headers, such as a {@code WWW-Authenticate} bearer challenge
     */
    record Denied(SearchErrorCode code, String message, Map<String, String> headers) implements AuthorizationDecision {

        public Denied {
            headers = Map.copyOf(headers);
        }

        public Denied(SearchErrorCode code, String message) {
            this(code, message, Map.of());
        }

        public SearchException toException() {
            return new SearchException(code, message);
        }
    }

    /** How a caller authenticated. */
    enum Caller {
        /** One of the service's admin keys. */
        ADMIN_KEY,
        /** One of the service's query keys: document reads only. */
        QUERY_KEY,
        /** A key the service does not know, accepted as an admin key outside strict parity. */
        UNKNOWN_KEY,
        /** A Microsoft Entra bearer token. */
        BEARER_TOKEN
    }
}
