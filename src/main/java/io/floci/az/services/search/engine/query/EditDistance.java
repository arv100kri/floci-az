package io.floci.az.services.search.engine.query;

/**
 * The Damerau-Levenshtein distance Learn's "Fuzzy search" page names for fuzzy matching: insertions, deletions,
 * substitutions and transpositions of two adjacent characters each cost one edit (the optimal string alignment
 * variant, where no substring is edited twice).
 */
public final class EditDistance {

    private EditDistance() {
    }

    /** The distance between {@code a} and {@code b}, or {@code maxEdits + 1} when it is larger than {@code maxEdits}. */
    public static int distance(String a, String b, int maxEdits) {
        int[] left = a.codePoints().toArray();
        int[] right = b.codePoints().toArray();
        if (Math.abs(left.length - right.length) > maxEdits) {
            return maxEdits + 1;
        }
        int[][] distance = new int[left.length + 1][right.length + 1];
        for (int i = 0; i <= left.length; i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= right.length; j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i <= left.length; i++) {
            int rowMinimum = Integer.MAX_VALUE;
            for (int j = 1; j <= right.length; j++) {
                int cost = left[i - 1] == right[j - 1] ? 0 : 1;
                int value = Math.min(Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                    distance[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    value = Math.min(value, distance[i - 2][j - 2] + 1);
                }
                distance[i][j] = value;
                rowMinimum = Math.min(rowMinimum, value);
            }
            if (rowMinimum > maxEdits) {
                return maxEdits + 1;
            }
        }
        return Math.min(distance[left.length][right.length], maxEdits + 1);
    }

    /** Whether {@code a} is within {@code maxEdits} edits of {@code b}. */
    public static boolean within(String a, String b, int maxEdits) {
        return distance(a, b, maxEdits) <= maxEdits;
    }
}
