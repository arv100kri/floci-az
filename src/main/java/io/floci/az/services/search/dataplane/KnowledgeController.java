package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * <b>STUB, to be replaced by knowledge sources and knowledge bases</b> (their CRUD, knowledge source status and files,
 * and knowledge base retrieval). Every operation answers an Azure-shaped 501 {@code NotImplemented} until then.
 */
@ApplicationScoped
public class KnowledgeController {

    private static final String FEATURE = "Knowledge bases and knowledge sources";

    public Response notImplemented(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }
}
