package io.floci.az.services.search.engine.query;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Matching documents by ordinal, each with its score. Not thread-safe; one per query step. */
public final class ScoredDocs {

    private final Map<Integer, Double> scores;

    public ScoredDocs() {
        this.scores = new HashMap<>();
    }

    private ScoredDocs(Map<Integer, Double> scores) {
        this.scores = scores;
    }

    /** Adds {@code score} to the document's score, adding the document when it is not a match yet. */
    public void add(int ordinal, double score) {
        scores.merge(ordinal, score, Double::sum);
    }

    /** Sets the document's score, replacing any score it had. */
    public void put(int ordinal, double score) {
        scores.put(ordinal, score);
    }

    public void remove(int ordinal) {
        scores.remove(ordinal);
    }

    public boolean contains(int ordinal) {
        return scores.containsKey(ordinal);
    }

    /** The document's score, 0 when it is not a match. */
    public double score(int ordinal) {
        return scores.getOrDefault(ordinal, 0.0);
    }

    public Set<Integer> ordinals() {
        return Collections.unmodifiableSet(scores.keySet());
    }

    public int size() {
        return scores.size();
    }

    public boolean isEmpty() {
        return scores.isEmpty();
    }

    /** The scores, by ordinal. */
    public Map<Integer, Double> asMap() {
        return Collections.unmodifiableMap(scores);
    }

    /** A copy whose scores are multiplied by {@code factor}. */
    public ScoredDocs scaled(double factor) {
        Map<Integer, Double> scaled = new HashMap<>(scores.size());
        scores.forEach((ordinal, score) -> scaled.put(ordinal, score * factor));
        return new ScoredDocs(scaled);
    }
}
