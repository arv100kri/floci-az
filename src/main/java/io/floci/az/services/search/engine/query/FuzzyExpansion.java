package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.index.InvertedField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuzzy query expansion and scoring, per Learn's "Fuzzy search": a term expands to at most 50 index terms within the
 * edit distance (the closest first, ties in term order), and the documents containing any of them match. Unlike
 * prefix, wildcard and regex queries, fuzzy matches are scored: the expansions are scored as one term with BM25, its
 * frequency in a document being the summed frequency of the expansions there and its document frequency the number
 * of documents holding any of them, so a rare misspelling does not outrank the common word it resembles.
 */
final class FuzzyExpansion {

    /** The most terms one fuzzy term expands to. */
    static final int MAX_EXPANSIONS = 50;

    private FuzzyExpansion() {
    }

    /** The terms of {@code dictionary} within {@code maxEdits} edits of {@code term}, the closest first. */
    static List<String> expand(Iterable<String> dictionary, String term, int maxEdits) {
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();
        for (String indexed : dictionary) {
            int distance = EditDistance.distance(indexed, term, maxEdits);
            if (distance <= maxEdits) {
                candidates.add(Map.entry(indexed, distance));
            }
        }
        candidates.sort(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(
            Comparator.naturalOrder())));
        List<String> expansions = new ArrayList<>(Math.min(candidates.size(), MAX_EXPANSIONS));
        for (int index = 0; index < candidates.size() && index < MAX_EXPANSIONS; index++) {
            expansions.add(candidates.get(index).getKey());
        }
        return expansions;
    }

    /** The documents of {@code inverted} matching the fuzzy {@code term}, with their BM25 scores times {@code weight}. */
    static ScoredDocs score(InvertedField inverted, String term, int maxEdits, Bm25 bm25, double weight) {
        ScoredDocs result = new ScoredDocs();
        if (inverted == null) {
            return result;
        }
        Map<Integer, Integer> frequencies = new HashMap<>();
        for (String expansion : expand(inverted.dictionary().keySet(), term, maxEdits)) {
            inverted.postings(expansion).forEach((ordinal, positions) ->
                frequencies.merge(ordinal, positions.length, Integer::sum));
        }
        if (frequencies.isEmpty()) {
            return result;
        }
        double idf = bm25.idf(inverted.documentCount(), frequencies.size());
        double averageLength = inverted.averageLength();
        frequencies.forEach((ordinal, frequency) -> result.put(ordinal,
            weight * bm25.score(idf, frequency, inverted.length(ordinal), averageLength)));
        return result;
    }
}
