package io.floci.az.services.search.engine.vector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion, per "Relevance scoring in hybrid search using Reciprocal Rank Fusion" (Cormack, Clarke and
 * Buettcher, 2009): each ranked list adds {@code weight / (k + rank)} to the score of every document it holds, rank
 * counting from 1 and {@code k} the constant 60, and documents rank by the sum.
 */
public final class ReciprocalRankFusion {

    /** The RRF constant; unrelated to a vector query's {@code k}. */
    public static final int K = 60;

    private ReciprocalRankFusion() {
    }

    /** The fused scores of the documents of {@code lists}, by ordinal, in the order documents are first seen. */
    public static Map<Integer, Double> fuse(List<RankedList> lists) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (RankedList list : lists) {
            int rank = 1;
            for (int ordinal : list.ordinals()) {
                scores.merge(ordinal, list.weight() / (K + rank), Double::sum);
                rank++;
            }
        }
        return scores;
    }

    /**
     * One ranked list.
     *
     * @param ordinals the documents, best first
     * @param weight   the list's weight: a vector query's {@code weight}, 1 for the text query
     */
    public record RankedList(List<Integer> ordinals, double weight) {

        public RankedList {
            ordinals = List.copyOf(ordinals);
        }
    }
}
