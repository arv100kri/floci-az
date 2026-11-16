package io.floci.az.services.search.engine.pipeline;

/**
 * <b>STUB, to be replaced by vector search.</b> Will run {@code vectorQueries} (exact nearest neighbours over the
 * vector fields, with {@code vectorFilterMode}, {@code k}, weights and thresholds) and add one
 * {@link SearchExecution.RankedResults} per query to {@link SearchExecution#vectorResults()}. Today it does nothing,
 * so vector queries contribute no results.
 */
public final class VectorRetrievalStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        // Stub: vector retrieval lands with vector search.
    }
}
