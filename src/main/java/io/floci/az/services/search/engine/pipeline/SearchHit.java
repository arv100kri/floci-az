package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One matching document while a query runs: its ordinal, its {@code @search.score}, and what later stages attach
 * (reranker scores, highlights, captions, debug information, other {@code @search.*} annotations).
 */
public final class SearchHit {

    private final int ordinal;
    private final double initialScore;
    private double score;
    private Double rerankerScore;
    private Double rerankerBoostedScore;
    private Double semanticRankingScore;
    private Map<String, List<String>> highlights;
    private List<ObjectNode> captions;
    private ObjectNode documentDebugInfo;
    private final Map<String, JsonNode> annotations = new LinkedHashMap<>();

    public SearchHit(int ordinal, double score) {
        this.ordinal = ordinal;
        this.initialScore = score;
        this.score = score;
    }

    public int ordinal() {
        return ordinal;
    }

    /** The score the hit was retrieved with, before later stages (a scoring profile's functions) rescored it. */
    public double initialScore() {
        return initialScore;
    }

    /** The {@code @search.score}. */
    public double score() {
        return score;
    }

    public void score(double newScore) {
        score = newScore;
    }

    /** The {@code @search.rerankerScore}, or null when the semantic ranker did not rank the hit. */
    public Double rerankerScore() {
        return rerankerScore;
    }

    public void rerankerScore(Double value) {
        rerankerScore = value;
    }

    public Double rerankerBoostedScore() {
        return rerankerBoostedScore;
    }

    public void rerankerBoostedScore(Double value) {
        rerankerBoostedScore = value;
    }

    /**
     * The score semantic ranking orders the hit by: its {@code @search.rerankerBoostedScore} when the semantic
     * configuration ranks by it, else its {@code @search.rerankerScore}; null when the semantic ranker did not rank it.
     */
    public Double semanticRankingScore() {
        return semanticRankingScore != null ? semanticRankingScore : rerankerScore;
    }

    public void semanticRankingScore(Double value) {
        semanticRankingScore = value;
    }

    /** The {@code @search.highlights} by field, or null when none were requested. */
    public Map<String, List<String>> highlights() {
        return highlights;
    }

    public void highlights(Map<String, List<String>> value) {
        highlights = value;
    }

    /** The {@code @search.captions}, or null. */
    public List<ObjectNode> captions() {
        return captions;
    }

    public void captions(List<ObjectNode> value) {
        captions = value;
    }

    /** The {@code @search.documentDebugInfo}, or null. */
    public ObjectNode documentDebugInfo() {
        return documentDebugInfo;
    }

    public void documentDebugInfo(ObjectNode value) {
        documentDebugInfo = value;
    }

    /** Further per-result annotations, by their full name ({@code @search.something}), written as given. */
    public Map<String, JsonNode> annotations() {
        return annotations;
    }
}
