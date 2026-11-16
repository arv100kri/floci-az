package io.floci.az.services.search.engine.query;

/**
 * Okapi BM25, the scoring function of Learn's "BM25 relevance scoring" page, with Azure's defaults k1 = 1.2 and
 * b = 0.75, each overridable by the index's {@code similarity}:
 * <pre>
 *   idf(t)      = ln(1 + (N - n(t) + 0.5) / (n(t) + 0.5))
 *   score(t, d) = idf(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * |d| / avgdl))
 * </pre>
 * where N is the number of documents with the field, n(t) the number containing the term, tf the term's frequency in
 * the field of the document, |d| the field's length in tokens and avgdl its average. Field lengths are exact (Lucene
 * stores them lossily, so its scores can differ in the third decimal place).
 *
 * @param k1 the term-frequency saturation
 * @param b  the length normalisation
 */
public record Bm25(double k1, double b) {

    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;

    /** BM25 with the index's parameters, defaulting the ones it leaves unset. */
    public static Bm25 of(Double k1, Double b) {
        return new Bm25(k1 == null ? DEFAULT_K1 : k1, b == null ? DEFAULT_B : b);
    }

    public double idf(int documentCount, int documentFrequency) {
        return Math.log(1 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    public double score(double idf, double termFrequency, int fieldLength, double averageFieldLength) {
        double lengthRatio = averageFieldLength > 0 ? fieldLength / averageFieldLength : 1;
        double norm = k1 * (1 - b + b * lengthRatio);
        return idf * termFrequency * (k1 + 1) / (termFrequency + norm);
    }
}
