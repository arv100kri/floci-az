package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.LiveIndex;

import java.time.Instant;

/**
 * Applies the request's {@code scoringProfile}, or the index's {@code defaultScoringProfile} when the request names
 * none (see {@link ScoringProfile}): its text weights in {@link #prepare}, through
 * {@link SearchExecution#fieldWeights()}, so text scoring uses them; then its functions in {@link #apply}, multiplying
 * each hit's score. An unknown profile, or a missing or malformed scoring parameter, is a 400 before any stage runs.
 */
public final class ScoringProfileStage implements SearchStage {

    @Override
    public void prepare(SearchExecution execution) {
        ScoringProfile profile = ScoringProfile.resolve(execution);
        if (profile != null) {
            execution.fieldWeights().putAll(profile.textWeights());
        }
    }

    @Override
    public void apply(SearchExecution execution) {
        ScoringProfile profile = ScoringProfile.resolve(execution);
        if (profile == null || !profile.hasFunctions()) {
            return;
        }
        LiveIndex index = execution.index();
        Instant now = Instant.now();
        for (SearchHit hit : execution.hits()) {
            hit.score(hit.score() * profile.multiplier(index.document(hit.ordinal()), now));
        }
    }
}
