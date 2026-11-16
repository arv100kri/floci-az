package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * <b>STUB, to be replaced by indexers</b> (Create, Create or Update, Get, List and Delete Indexer; Run, Reset, Reset
 * Documents, Resync and Get Indexer Status). Every operation answers an Azure-shaped 501 {@code NotImplemented} until
 * then.
 */
@ApplicationScoped
public class IndexersController implements SearchResourceCounter {

    private static final String FEATURE = "Indexers";

    public Response list(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response create(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response createOrUpdate(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response get(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response delete(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response status(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response run(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response reset(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response resetDocuments(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    public Response resync(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    @Override
    public String counterName() {
        return "indexersCount";
    }

    @Override
    public long usage(String service) {
        return 0;
    }
}
