package io.floci.az.services.search.engine.analysis;

import java.util.function.IntUnaryOperator;

/** Rewrites text before it is tokenized (HTML stripping, character mappings, pattern replacement). Thread-safe. */
public interface CharFilter {

    /** The rewritten text and how its offsets map back to the input. */
    Result apply(String input);

    /**
     * Filtered text.
     *
     * @param text             the rewritten text
     * @param offsetCorrection maps an offset in {@code text} to the corresponding offset in the input
     */
    record Result(String text, IntUnaryOperator offsetCorrection) {

        public static Result unchanged(String text) {
            return new Result(text, IntUnaryOperator.identity());
        }
    }
}
