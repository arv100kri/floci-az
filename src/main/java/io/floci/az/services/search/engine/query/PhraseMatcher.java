package io.floci.az.services.search.engine.query;

import java.util.Arrays;

/**
 * Counts phrase occurrences in one document field from the positions of the phrase's terms. With slop 0 every term
 * must sit at its exact offset from the first; with slop n, an occurrence matches when the spread of the terms'
 * displacements (position minus offset in the phrase) is at most n, so swapping two adjacent words costs 2, as
 * Lucene's PhraseQuery documents.
 */
final class PhraseMatcher {

    private PhraseMatcher() {
    }

    /**
     * @param positions the sorted positions of each phrase term in the document
     * @param offsets   each term's offset from the first term in the phrase
     * @param slop      the allowed displacement spread
     * @return the number of occurrences anchored at distinct positions of the first term
     */
    static int count(int[][] positions, int[] offsets, int slop) {
        int occurrences = 0;
        for (int anchor : positions[0]) {
            int expectedStart = anchor - offsets[0];
            if (slop == 0 ? exact(positions, offsets, expectedStart) : sloppy(positions, offsets, expectedStart,
                    slop)) {
                occurrences++;
            }
        }
        return occurrences;
    }

    private static boolean exact(int[][] positions, int[] offsets, int start) {
        for (int term = 1; term < positions.length; term++) {
            if (Arrays.binarySearch(positions[term], start + offsets[term]) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean sloppy(int[][] positions, int[] offsets, int start, int slop) {
        int minDisplacement = 0;
        int maxDisplacement = 0;
        for (int term = 1; term < positions.length; term++) {
            int expected = start + offsets[term];
            int nearest = nearest(positions[term], expected);
            int displacement = nearest - expected;
            minDisplacement = Math.min(minDisplacement, displacement);
            maxDisplacement = Math.max(maxDisplacement, displacement);
        }
        return maxDisplacement - minDisplacement <= slop;
    }

    private static int nearest(int[] sorted, int target) {
        int index = Arrays.binarySearch(sorted, target);
        if (index >= 0) {
            return sorted[index];
        }
        int insertion = -index - 1;
        if (insertion == 0) {
            return sorted[0];
        }
        if (insertion == sorted.length) {
            return sorted[sorted.length - 1];
        }
        int before = sorted[insertion - 1];
        int after = sorted[insertion];
        return target - before <= after - target ? before : after;
    }
}
