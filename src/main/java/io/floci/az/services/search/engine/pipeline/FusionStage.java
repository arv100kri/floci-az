package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.ScoredDocs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the retrieval results into {@link SearchExecution#hits()}: every admitted document with its score.
 *
 * <p><b>STUB for hybrid fusion.</b> Today it carries the text results through: the BM25 score of each full-text match,
 * or 1 for every document when the query text matches everything, plus any {@code search.ismatchscoring} score.
 * Reciprocal Rank Fusion of text and {@link SearchExecution#vectorResults()} (k = 60, query weights,
 * {@code maxTextRecallSize}) replaces this when vector search lands.</p>
 */
public final class FusionStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        LiveIndex index = execution.index();
        ScoredDocs text = execution.textResults();
        ScoredDocs filterScores = execution.filterScores();
        List<SearchHit> hits = new ArrayList<>();
        if (text == null) {
            for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
                if (index.document(ordinal) != null && execution.admitted(ordinal)) {
                    hits.add(new SearchHit(ordinal, filterScores == null ? 1.0 : filterScores.score(ordinal)));
                }
            }
        } else {
            for (Map.Entry<Integer, Double> match : text.asMap().entrySet()) {
                int ordinal = match.getKey();
                if (execution.admitted(ordinal)) {
                    double extra = filterScores == null ? 0 : filterScores.score(ordinal);
                    hits.add(new SearchHit(ordinal, match.getValue() + extra));
                }
            }
        }
        execution.hits(hits);
    }
}
