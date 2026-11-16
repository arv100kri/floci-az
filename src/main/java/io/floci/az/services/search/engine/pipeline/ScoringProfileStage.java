package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;

/**
 * <b>STUB, to be replaced by scoring profiles.</b> Will apply the request's {@code scoringProfile} (or the index's
 * {@code defaultScoringProfile}): text weights in {@link #prepare} through {@link SearchExecution#fieldWeights()},
 * then the freshness, magnitude, distance and tag functions with their interpolations and aggregation in
 * {@link #apply}. Today it only refuses a profile the index does not define, as the service does; scores are
 * unchanged.
 */
public final class ScoringProfileStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        String profile = execution.request().scoringProfile();
        if (profile == null || profile.isBlank()) {
            return;
        }
        for (JsonNode defined : execution.index().definition().json().path("scoringProfiles")) {
            if (profile.equals(SearchJson.text(defined, "name"))) {
                return;
            }
        }
        throw SearchException.badRequest("Unknown scoring profile '" + profile + "'.\r\nParameter name: "
            + "scoringProfile");
    }
}
