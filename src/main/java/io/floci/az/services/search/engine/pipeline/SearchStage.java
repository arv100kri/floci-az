package io.floci.az.services.search.engine.pipeline;

/**
 * One step of the search pipeline. Each stage reads and writes the shared {@link SearchExecution}; the pipeline calls
 * {@link #prepare} on every stage, in order, before it calls {@link #apply} on any, so a later stage can set up
 * something an earlier one uses (a scoring profile's field weights before text scoring).
 *
 * <p>Stages run inside the index's read lock and must not keep references to the execution after they return.</p>
 */
public interface SearchStage {

    /** Runs before any stage's {@link #apply}. */
    default void prepare(SearchExecution execution) {
    }

    void apply(SearchExecution execution);
}
