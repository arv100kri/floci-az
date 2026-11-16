package io.floci.az.services.search.engine.vector;

import java.util.Locale;
import java.util.Optional;

/**
 * The similarity metrics of a vector search algorithm, per "Vector relevance and ranking". Each metric has a canonical
 * similarity (cosine similarity, Euclidean distance, dot product, Hamming distance: what a {@code vectorSimilarity}
 * threshold and the debug subscores report) and a {@code @search.score} derived from it that always decreases as the
 * match gets worse:
 * <ul>
 *   <li>{@code cosine}: {@code 1 / (1 + cosine distance)}, the documented formula, so 0.333 to 1;</li>
 *   <li>{@code euclidean} and {@code hamming}: {@code 1 / (1 + distance)}, 0 to 1;</li>
 *   <li>{@code dotProduct}: {@code 1 - 0.5 / (1 + dot)} for a non-negative dot product and {@code 0.5 / (1 - dot)}
 *       otherwise, 0 to 1.</li>
 * </ul>
 * Azure documents the cosine formula and the 0 to 1 range of the others; the formulas for the others are the emulator's.
 */
public enum VectorMetric {
    COSINE("cosine", true),
    EUCLIDEAN("euclidean", false),
    DOT_PRODUCT("dotProduct", true),
    HAMMING("hamming", false);

    private final String wireName;
    private final boolean higherIsBetter;

    VectorMetric(String wireName, boolean higherIsBetter) {
        this.wireName = wireName;
        this.higherIsBetter = higherIsBetter;
    }

    /** The name the index definition spells. */
    public String wireName() {
        return wireName;
    }

    /** Whether a larger canonical similarity is a closer match (cosine, dot product) or a smaller one (distances). */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    /** The metric named {@code name}, ignoring case. */
    public static Optional<VectorMetric> fromWireName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (VectorMetric metric : values()) {
            if (metric.wireName.toLowerCase(Locale.ROOT).equals(lower)) {
                return Optional.of(metric);
            }
        }
        return Optional.empty();
    }

    /**
     * The canonical similarity of two vectors over their first {@code length} components. Hamming compares the
     * components as packed bytes (0 to 255), counting the bits that differ.
     */
    public double similarity(float[] query, float[] document, int length) {
        return switch (this) {
            case COSINE -> cosine(query, document, length);
            case EUCLIDEAN -> euclidean(query, document, length);
            case DOT_PRODUCT -> dot(query, document, length);
            case HAMMING -> hamming(query, document, length);
        };
    }

    /** The {@code @search.score} of a match whose canonical similarity is {@code similarity}. */
    public double score(double similarity) {
        return switch (this) {
            case COSINE -> 1.0 / (2.0 - Math.max(-1.0, Math.min(1.0, similarity)));
            case EUCLIDEAN, HAMMING -> 1.0 / (1.0 + Math.max(0.0, similarity));
            case DOT_PRODUCT -> similarity >= 0 ? 1.0 - 0.5 / (1.0 + similarity) : 0.5 / (1.0 - similarity);
        };
    }

    /** Whether {@code similarity} passes a {@code vectorSimilarity} threshold of {@code threshold}. */
    public boolean meets(double similarity, double threshold) {
        return higherIsBetter ? similarity >= threshold : similarity <= threshold;
    }

    private static double cosine(float[] a, float[] b, int length) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double euclidean(float[] a, float[] b, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            double difference = (double) a[i] - b[i];
            sum += difference * difference;
        }
        return Math.sqrt(sum);
    }

    private static double dot(float[] a, float[] b, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static double hamming(float[] a, float[] b, int length) {
        int bits = 0;
        for (int i = 0; i < length; i++) {
            bits += Integer.bitCount(((int) a[i] ^ (int) b[i]) & 0xFF);
        }
        return bits;
    }
}
