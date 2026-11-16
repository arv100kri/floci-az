package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.engine.vector.NearestNeighborSearch;
import io.floci.az.services.search.engine.vector.VectorFilterMode;
import io.floci.az.services.search.engine.vector.VectorFilters;
import io.floci.az.services.search.engine.vector.VectorList;
import io.floci.az.services.search.engine.vector.VectorMatch;
import io.floci.az.services.search.engine.vector.VectorMetric;
import io.floci.az.services.search.engine.vector.VectorQuery;
import io.floci.az.services.search.engine.vector.VectorSearchConfig;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.FieldSettings;
import io.floci.az.services.search.engine.vector.VectorSearchContext;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Runs the request's {@code vectorQueries} (validated and vectorized before the read lock, see
 * {@link VectorSearchContext}): for each query and each of its fields, an exact nearest-neighbour search
 * ({@link NearestNeighborSearch}) that returns the {@code k} closest documents. Each (query, field) pair is one ranked
 * list, added to {@link SearchExecution#vectorResults()} with the query's weight and, with its similarities, to the
 * context's lists.
 *
 * <p>Filters follow {@code vectorFilterMode}: {@code preFilter} (the default) chooses the neighbours among the
 * documents the filter admits, so {@code k} results come back whenever {@code k} documents pass;
 * {@code postFilter} and {@code strictPostFilter} choose among every document and then drop those the filter rejects,
 * so fewer than {@code k} can remain. A query's {@code filterOverride} replaces the request's filter for that query. A
 * {@code threshold} then drops the matches below it, before fusion.</p>
 */
public final class VectorRetrievalStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        VectorSearchContext context = execution.vectorSearch();
        if (!context.active()) {
            return;
        }
        LiveIndex index = execution.index();
        VectorSearchConfig config = VectorSearchConfig.of(index.definition());
        VectorFilterMode mode = VectorFilterMode.parse(execution.request().vectorFilterMode());
        for (VectorQuery query : context.queries()) {
            BitSet filter = query.filterOverride() == null ? execution.filterMatches()
                : VectorFilters.evaluate(query.filterOverride(), index, execution.fieldWeights());
            for (SearchFieldDefinition field : query.fields()) {
                FieldSettings settings = config.settings(field);
                float[] vector = context.vector(query, field);
                List<VectorMatch> matches;
                if (mode.filtersCandidates()) {
                    matches = NearestNeighborSearch.search(index, settings, vector, query.k(), filter,
                        query.oversampling());
                } else {
                    matches = admitted(NearestNeighborSearch.search(index, settings, vector, query.k(), null,
                        query.oversampling()), filter);
                }
                matches = aboveThreshold(matches, query.threshold(), settings.metric());
                context.lists().add(new VectorList(query, field, settings.metric(), matches));
                execution.vectorResults().add(new SearchExecution.RankedResults(
                    "vectorQueries[" + query.position() + "]/" + field.path(), scores(matches), query.weight()));
            }
        }
    }

    private static List<VectorMatch> admitted(List<VectorMatch> matches, BitSet filter) {
        if (filter == null) {
            return matches;
        }
        List<VectorMatch> admitted = new ArrayList<>(matches.size());
        for (VectorMatch match : matches) {
            if (filter.get(match.ordinal())) {
                admitted.add(match);
            }
        }
        return admitted;
    }

    private static List<VectorMatch> aboveThreshold(List<VectorMatch> matches, VectorQuery.Threshold threshold,
                                                    VectorMetric metric) {
        if (threshold == null) {
            return matches;
        }
        List<VectorMatch> kept = new ArrayList<>(matches.size());
        for (VectorMatch match : matches) {
            boolean passes = threshold.searchScore() ? match.score() >= threshold.value()
                : metric.meets(match.similarity(), threshold.value());
            if (passes) {
                kept.add(match);
            }
        }
        return kept;
    }

    private static ScoredDocs scores(List<VectorMatch> matches) {
        ScoredDocs docs = new ScoredDocs();
        for (VectorMatch match : matches) {
            docs.put(match.ordinal(), match.score());
        }
        return docs;
    }
}
