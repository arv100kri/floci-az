package io.floci.az.services.search.dataplane;

import jakarta.ws.rs.core.Response;

/** A controller method the dispatch table calls for one operation. */
@FunctionalInterface
public interface SearchEndpoint {

    Response handle(SearchCall call);
}
