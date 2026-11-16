package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * <b>STUB, to be replaced by skillsets</b> (Create, Create or Update, Get, List and Delete Skillset, and Reset
 * Skills). Every operation answers an Azure-shaped 501 {@code NotImplemented} until then.
 */
@ApplicationScoped
public class SkillsetsController implements SearchResourceCounter {

    private static final String FEATURE = "Skillsets";

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

    public Response resetSkills(SearchCall call) {
        return SearchErrors.notImplemented(FEATURE);
    }

    @Override
    public String counterName() {
        return "skillsetCount";
    }

    @Override
    public long usage(String service) {
        return 0;
    }
}
