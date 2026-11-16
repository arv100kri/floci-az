package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.semantic.SemanticConfig;
import io.floci.az.services.search.engine.semantic.SemanticOptions;
import io.floci.az.services.search.engine.semantic.SemanticRanking;
import io.floci.az.services.search.engine.semantic.SemanticRanking.Judgement;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Semantic ranking, answered by floci-az's deterministic stand-in ({@link SemanticRanking}) for a request with
 * {@code queryType=semantic} or a {@code semanticQuery}. Per Learn's "Semantic ranking in Azure AI Search": the top 50
 * hits of the L1 ranking (BM25 or fusion, after the filter and any scoring profile) get a
 * {@code @search.rerankerScore}, a {@code @search.rerankerBoostedScore} when a scoring profile applies, captions and
 * the requested debug information; the response gets answers and the query rewrite annotations.
 * {@link SortAndPageStage} then puts the reranked hits first, by {@link SearchHit#semanticRankingScore()} with the L1
 * order breaking ties; the other hits follow in L1 order without a reranker score. Responses to semantic requests
 * carry {@value #STAND_IN_HEADER}{@code : stand-in}.
 *
 * <p>Checked in {@link #prepare}, before retrieval runs: a semantic request is a 400, as on a service without
 * semantic ranking, when {@code semantic-ranker} is {@code disabled}; so are invalid semantic parameters, a semantic
 * request with {@code $orderby}, and a semantic configuration the index does not define.</p>
 */
public final class SemanticStage implements SearchStage {

    /** The response header that marks a semantic response as the stand-in's. */
    public static final String STAND_IN_HEADER = "x-floci-az-semantic-ranker";

    /** The number of L1 results the semantic ranker reranks. */
    static final int RERANKED_RESULTS = 50;

    private static final String DEFAULT_PRE_TAG = "<em>";
    private static final String DEFAULT_POST_TAG = "</em>";

    @Override
    public void prepare(SearchExecution execution) {
        plan(execution);
    }

    @Override
    public void apply(SearchExecution execution) {
        Plan plan = plan(execution);
        if (plan == null) {
            return;
        }
        execution.headers().put(STAND_IN_HEADER, "stand-in");
        SearchRequest request = execution.request();
        SemanticRanking ranking = new SemanticRanking(plan.options(), plan.config(),
            request.highlightPreTag() == null ? DEFAULT_PRE_TAG : request.highlightPreTag(),
            request.highlightPostTag() == null ? DEFAULT_POST_TAG : request.highlightPostTag());
        List<Judgement> reranked = ranking.ranks() ? rerank(execution, ranking, plan.config()) : List.of();
        ArrayNode answers = ranking.answers(reranked);
        if (answers != null) {
            execution.answers(answers);
        }
        String rewrites = ranking.queryRewritesResultType();
        if (rewrites != null) {
            execution.annotations().put("@search.semanticQueryRewritesResultType", TextNode.valueOf(rewrites));
        }
        JsonNode debug = ranking.responseDebug();
        if (debug != null) {
            execution.annotations().put("@search.debug", debug);
        }
    }

    /** The semantic plan of the request, validated; null when the request is not semantic. */
    private static Plan plan(SearchExecution execution) {
        SearchRequest request = execution.request();
        boolean requested = SemanticOptions.isRequested(request);
        if (requested && !execution.settings().semanticRankerEnabled()) {
            throw SemanticOptions.notEnabled(request);
        }
        SemanticOptions options = SemanticOptions.parse(request);
        if (!requested) {
            return null;
        }
        if (!execution.orderBy().isEmpty()) {
            throw SearchException.badRequest("Invalid search request: semantic ranking cannot be applied to results "
                + "sorted by $orderby.\r\nParameter name: orderby");
        }
        SemanticConfig config = SemanticConfig.resolve(execution.index().definition(),
            request.semanticConfiguration(), request.semanticFields());
        return new Plan(options, config);
    }

    /**
     * Judges the top hits, in L1 order, and records their scores, captions and debug information; returns their
     * judgements in reranked order.
     */
    private static List<Judgement> rerank(SearchExecution execution, SemanticRanking ranking, SemanticConfig config) {
        boolean boosted = scoringProfileApplies(execution);
        List<Reranked> reranked = new ArrayList<>();
        for (SearchHit hit : topHits(execution.hits())) {
            IndexedDocument document = execution.index().document(hit.ordinal());
            Judgement judgement = ranking.judge(document.key(), document.source());
            double score = judgement.rerankerScore();
            hit.rerankerScore(score);
            hit.semanticRankingScore(score);
            if (boosted) {
                double boostedScore = score * functionBoost(hit);
                hit.rerankerBoostedScore(boostedScore);
                if (config.rankByBoostedScore()) {
                    hit.semanticRankingScore(boostedScore);
                }
            }
            hit.captions(ranking.captions(judgement));
            ObjectNode semanticDebug = ranking.documentDebug(judgement);
            if (semanticDebug != null) {
                ObjectNode debugInfo = hit.documentDebugInfo() == null ? SearchJson.object() : hit.documentDebugInfo();
                debugInfo.set("semantic", semanticDebug);
                hit.documentDebugInfo(debugInfo);
            }
            reranked.add(new Reranked(hit, judgement));
        }
        execution.rankedByReranker(true);
        reranked.sort(Comparator.comparingDouble((Reranked entry) -> entry.hit().semanticRankingScore()).reversed());
        return reranked.stream().map(Reranked::judgement).toList();
    }

    /** The first {@value #RERANKED_RESULTS} hits in L1 order: by score, highest first, then in index order. */
    private static List<SearchHit> topHits(List<SearchHit> hits) {
        List<SearchHit> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparingDouble((SearchHit hit) -> hit.score()).reversed()
            .thenComparingInt(SearchHit::ordinal));
        return ordered.subList(0, Math.min(RERANKED_RESULTS, ordered.size()));
    }

    /** Whether a scoring profile applies: the request names one, or the index sets a default one. */
    private static boolean scoringProfileApplies(SearchExecution execution) {
        String requested = execution.request().scoringProfile();
        if (requested != null && !requested.isBlank()) {
            return true;
        }
        String defaultProfile = SearchJson.text(execution.index().definition().json(), "defaultScoringProfile");
        return defaultProfile != null && !defaultProfile.isBlank();
    }

    /**
     * The factor a scoring profile's functions multiplied the hit's score by: its score now over the score it was
     * retrieved with. Text weights act during retrieval, so they are not part of it, as Learn's "Use scoring profiles
     * with semantic ranking" describes.
     */
    private static double functionBoost(SearchHit hit) {
        return hit.initialScore() > 0 ? hit.score() / hit.initialScore() : 1.0;
    }

    private record Plan(SemanticOptions options, SemanticConfig config) {
    }

    private record Reranked(SearchHit hit, Judgement judgement) {
    }
}
