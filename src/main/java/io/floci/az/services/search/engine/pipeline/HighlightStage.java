package io.floci.az.services.search.engine.pipeline;

/**
 * <b>STUB, to be replaced by hit highlighting.</b> Will set {@link SearchHit#highlights(java.util.Map)} on the page's
 * hits for the requested {@code highlight} fields, built from token offsets with {@code highlightPreTag} and
 * {@code highlightPostTag} ({@code <em>} and {@code </em>} by default). Today results carry no highlights.
 */
public final class HighlightStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        // Stub: hit highlighting lands with the query-breadth work.
    }
}
