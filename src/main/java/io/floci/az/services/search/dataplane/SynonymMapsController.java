package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * <b>STUB, to be replaced by synonym maps</b> (Create, Create or Update, Get, List and Delete Synonym Map, the Solr
 * rule format, ETags). Every operation answers an Azure-shaped 501 {@code NotImplemented} until then.
 */
@ApplicationScoped
public class SynonymMapsController implements SearchResourceCounter {

    private static final String FEATURE = "Synonym maps";

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

    @Override
    public String counterName() {
        return "synonymMaps";
    }

    @Override
    public long usage(String service) {
        return 0;
    }
}
