package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.SearchErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * <b>STUB, to be replaced by the Analyze API</b> ({@code POST indexes('x')/search.analyze}: text through a named
 * analyzer, or a tokenizer with token and character filters, returning {@code tokens} with offsets and positions).
 * Answers an Azure-shaped 501 {@code NotImplemented} until then.
 */
@ApplicationScoped
public class AnalyzeController {

    public Response analyze(SearchCall call) {
        return SearchErrors.notImplemented("The Analyze API (search.analyze)");
    }
}
