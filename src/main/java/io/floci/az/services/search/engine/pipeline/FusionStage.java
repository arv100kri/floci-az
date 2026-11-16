package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.engine.vector.HybridSearchOptions;
import io.floci.az.services.search.engine.vector.ReciprocalRankFusion;
import io.floci.az.services.search.engine.vector.ReciprocalRankFusion.RankedList;
import io.floci.az.services.search.engine.vector.VectorDebugInfo;
import io.floci.az.services.search.engine.vector.VectorSearchContext;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the retrieval results into {@link SearchExecution#hits()}: every retrieved document with its
 * {@code @search.score}.
 *
 * <p>Without vector queries the hits are the text results: the BM25 score of each full-text match, or 1 for every
 * document when the query text matches everything, plus any {@code search.ismatchscoring} score.</p>
 *
 * <p>With vector queries, per "Hybrid search" and "Relevance scoring in hybrid search using Reciprocal Rank Fusion":
 * a single vector list (one query, one field, no query text) keeps its similarity scores; anything more (several vector
 * queries or fields, or a text query beside them) is fused with {@link ReciprocalRankFusion}, each vector list weighted
 * by its query's {@code weight} and the text list by 1. The text list is the BM25 ranking of the filtered text
 * matches ({@code *} matches every document), cut to {@code hybridSearch.maxTextRecallSize}; with
 * {@code countAndFacetMode} {@code countAllResults} (the default) the matches beyond that window still count toward
 * {@code @odata.count} and facets ({@link SearchExecution#countOnlyMatches()}). A query without text ({@code search}
 * absent or blank) has no text list. {@code debug} {@code vector} or {@code all} adds the subscores.</p>
 */
public final class FusionStage implements SearchStage {

    private static final Comparator<Map.Entry<Integer, Double>> BEST_FIRST =
        Map.Entry.<Integer, Double>comparingByValue().reversed()
            .thenComparing(Map.Entry.<Integer, Double>comparingByKey());

    @Override
    public void apply(SearchExecution execution) {
        if (!execution.vectorSearch().active() && execution.vectorResults().isEmpty()) {
            execution.hits(textHits(execution));
            return;
        }
        fuse(execution);
    }

    private static List<SearchHit> textHits(SearchExecution execution) {
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
        return hits;
    }

    private static void fuse(SearchExecution execution) {
        SearchRequest request = execution.request();
        HybridSearchOptions options = HybridSearchOptions.parse(request.hybridSearch());
        Map<Integer, Double> textScores = hasTextQuery(request) ? textScores(execution) : null;
        List<Integer> textRanking = textScores == null ? null : ranking(textScores);
        List<SearchExecution.RankedResults> vectorLists = execution.vectorResults();

        Map<Integer, Double> scores;
        if (textRanking == null && vectorLists.size() == 1) {
            scores = new HashMap<>(vectorLists.getFirst().docs().asMap());
        } else {
            List<RankedList> lists = new ArrayList<>();
            if (textRanking != null) {
                lists.add(new RankedList(textRanking.subList(0,
                    Math.min(textRanking.size(), options.maxTextRecallSize())), 1.0));
            }
            for (SearchExecution.RankedResults results : vectorLists) {
                lists.add(new RankedList(ranking(results.docs().asMap()), results.weight()));
            }
            scores = ReciprocalRankFusion.fuse(lists);
        }
        List<SearchHit> hits = new ArrayList<>(scores.size());
        scores.forEach((ordinal, score) -> hits.add(new SearchHit(ordinal, score)));

        if (textRanking != null && options.countAllResults() && textRanking.size() > options.maxTextRecallSize()) {
            for (int ordinal : textRanking.subList(options.maxTextRecallSize(), textRanking.size())) {
                if (!scores.containsKey(ordinal)) {
                    execution.countOnlyMatches().set(ordinal);
                }
            }
        }
        if (VectorDebugInfo.requested(request.debug())) {
            attachDebugInfo(execution.vectorSearch(), textScores, hits);
        }
        execution.hits(hits);
    }

    /** Whether the request has query text beside its vector queries; {@code *} is a text query matching everything. */
    private static boolean hasTextQuery(SearchRequest request) {
        return request.search() != null && !request.search().isBlank();
    }

    /** The text query's scores of the documents the filter admits. */
    private static Map<Integer, Double> textScores(SearchExecution execution) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SearchHit hit : textHits(execution)) {
            scores.put(hit.ordinal(), hit.score());
        }
        return scores;
    }

    /** The ordinals of {@code scores}, highest score first, earlier documents first among equal scores. */
    private static List<Integer> ranking(Map<Integer, Double> scores) {
        List<Map.Entry<Integer, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(BEST_FIRST);
        List<Integer> ordinals = new ArrayList<>(entries.size());
        entries.forEach(entry -> ordinals.add(entry.getKey()));
        return ordinals;
    }

    private static void attachDebugInfo(VectorSearchContext context, Map<Integer, Double> textScores,
                                        List<SearchHit> hits) {
        Set<Integer> ordinals = new HashSet<>();
        hits.forEach(hit -> ordinals.add(hit.ordinal()));
        Map<Integer, ObjectNode> subscores = VectorDebugInfo.subscores(context.queries(), context.lists(), textScores,
            ordinals);
        for (SearchHit hit : hits) {
            ObjectNode debug = hit.documentDebugInfo() == null ? SearchJson.object() : hit.documentDebugInfo();
            debug.set("vectors", subscores.get(hit.ordinal()));
            hit.documentDebugInfo(debug);
        }
    }
}
