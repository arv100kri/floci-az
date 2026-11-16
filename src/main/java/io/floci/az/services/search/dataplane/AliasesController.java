package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

/**
 * <b>STUB, to be replaced by index aliases</b> (Create, Create or Update, Get, List and Delete Alias, ETags, and
 * resolving an alias name wherever document operations name an index). Every operation answers an Azure-shaped 501
 * {@code NotImplemented} until then, and {@link #resolveIndex} resolves nothing.
 */
@ApplicationScoped
public class AliasesController implements SearchResourceCounter {

    private static final String FEATURE = "Index aliases";

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

    /**
     * The index the alias {@code name} of {@code service} points to. Document operations call it when no index has
     * the name they were given.
     */
    public Optional<String> resolveIndex(String service, String name) {
        return Optional.empty();
    }

    @Override
    public String counterName() {
        return "aliasesCount";
    }

    @Override
    public long usage(String service) {
        return 0;
    }
}
