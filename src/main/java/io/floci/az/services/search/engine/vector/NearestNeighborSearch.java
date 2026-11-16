package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.Compression;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.FieldSettings;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Exact nearest-neighbour search over one vector field, for {@code hnsw} and {@code exhaustiveKnn} fields alike: every
 * candidate document is compared with the query, so results are deterministic (ties go to the earlier document) and
 * match what an exhaustive search returns.
 *
 * <p>A compressed field (a profile with a compression on an HNSW algorithm) follows "Compress vectors using scalar or
 * binary quantization": the candidates are compared on their compressed form (int8 scalar quantization over the
 * field's value range, or one sign bit per dimension for binary quantization, both truncated to
 * {@code truncationDimension}); with rescoring on (the default, or a query's {@code oversampling}) the best
 * {@code k × oversampling} are rescored on the full-precision vectors ({@code preserveOriginals}) or, for binary
 * quantization with {@code discardOriginals}, on the query against the binary vectors, and the best {@code k} keep
 * those scores. Without rescoring the scores are the compressed comparison's. Exhaustive KNN fields hold
 * full-precision vectors, so their scores are always exact.</p>
 *
 * <p>A vector field inside a complex collection holds several vectors per document; a document is then as close as its
 * closest vector.</p>
 */
public final class NearestNeighborSearch {

    private static final Comparator<VectorMatch> WORST_FIRST = Comparator.comparingDouble(VectorMatch::score)
        .thenComparing(Comparator.comparingInt(VectorMatch::ordinal).reversed());

    private NearestNeighborSearch() {
    }

    /**
     * The {@code k} nearest neighbours of {@code query} in the field of {@code settings}, best first.
     *
     * @param index        the index, read-locked by the caller
     * @param settings     the field's vector settings
     * @param query        the query vector, already checked against the field
     * @param k            the number of neighbours
     * @param candidates   the documents that may match, or null for every document
     * @param oversampling the query's oversampling factor, or null
     */
    public static List<VectorMatch> search(LiveIndex index, FieldSettings settings, float[] query, int k,
                                           BitSet candidates, Double oversampling) {
        Compression compression = settings.effectiveCompression();
        if (compression == null) {
            return exact(index, settings, query, k, candidates);
        }
        return compressed(index, settings, compression, query, k, candidates, oversampling);
    }

    private static List<VectorMatch> exact(LiveIndex index, FieldSettings settings, float[] query, int k,
                                           BitSet candidates) {
        VectorMetric metric = settings.metric();
        TopMatches top = new TopMatches(k);
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            if (candidates != null && !candidates.get(ordinal)) {
                continue;
            }
            IndexedDocument document = index.document(ordinal);
            if (document == null) {
                continue;
            }
            VectorMatch best = null;
            for (float[] vector : vectors(document, settings.field(), query.length)) {
                VectorMatch match = match(metric, ordinal, metric.similarity(query, vector, query.length));
                best = better(best, match);
            }
            top.offer(best);
        }
        return top.best();
    }

    private static List<VectorMatch> compressed(LiveIndex index, FieldSettings settings, Compression compression,
                                                float[] query, int k, BitSet candidates, Double oversampling) {
        VectorMetric metric = settings.metric();
        int length = query.length;
        int kept = compression.truncationDimension() == null ? length
            : Math.min(length, compression.truncationDimension());
        boolean rescore = (oversampling != null || compression.rescoring())
            && (compression.binary() || compression.preserveOriginals());
        double factor = Math.max(1.0, oversampling != null ? oversampling : compression.defaultOversampling());
        int wanted = rescore ? (int) Math.min(Integer.MAX_VALUE, Math.ceil(k * factor)) : k;
        float[] range = compression.binary() ? null : range(index, settings.field(), length, kept);
        float[] compressedQuery = compression.binary() ? signs(query, kept) : Arrays.copyOf(query, kept);

        TopMatches first = new TopMatches(wanted);
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            if (candidates != null && !candidates.get(ordinal)) {
                continue;
            }
            IndexedDocument document = index.document(ordinal);
            if (document == null) {
                continue;
            }
            VectorMatch best = null;
            for (float[] vector : vectors(document, settings.field(), length)) {
                float[] compressedVector = compression.binary() ? signs(vector, kept) : dequantized(vector, kept, range);
                best = better(best, match(metric, ordinal, metric.similarity(compressedQuery, compressedVector, kept)));
            }
            first.offer(best);
        }
        if (!rescore) {
            return first.best();
        }
        float[] truncatedQuery = Arrays.copyOf(query, kept);
        TopMatches rescored = new TopMatches(k);
        for (VectorMatch candidate : first.best()) {
            IndexedDocument document = index.document(candidate.ordinal());
            VectorMatch best = null;
            for (float[] vector : vectors(document, settings.field(), length)) {
                double similarity = compression.preserveOriginals()
                    ? metric.similarity(query, vector, length)
                    : metric.similarity(truncatedQuery, signs(vector, kept), kept);
                best = better(best, match(metric, candidate.ordinal(), similarity));
            }
            rescored.offer(best);
        }
        return rescored.best();
    }

    private static VectorMatch match(VectorMetric metric, int ordinal, double similarity) {
        return new VectorMatch(ordinal, similarity, metric.score(similarity));
    }

    private static VectorMatch better(VectorMatch current, VectorMatch candidate) {
        return current == null || candidate.score() > current.score() ? candidate : current;
    }

    /** The vectors of {@code field} in {@code document} that have {@code length} components. */
    static List<float[]> vectors(IndexedDocument document, SearchFieldDefinition field, int length) {
        Object value = field.path().indexOf('/') < 0 ? document.values().get(field.name())
            : DocumentValues.valueAt(document.values(), Arrays.asList(field.path().split("/")));
        List<float[]> vectors = new ArrayList<>(1);
        if (value instanceof float[] vector) {
            if (vector.length == length) {
                vectors.add(vector);
            }
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof float[] vector && vector.length == length) {
                    vectors.add(vector);
                }
            }
        }
        return vectors;
    }

    /** The smallest and largest of the first {@code kept} components of the field's vectors: the int8 range. */
    private static float[] range(LiveIndex index, SearchFieldDefinition field, int length, int kept) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document == null) {
                continue;
            }
            for (float[] vector : vectors(document, field, length)) {
                for (int i = 0; i < kept; i++) {
                    min = Math.min(min, vector[i]);
                    max = Math.max(max, vector[i]);
                }
            }
        }
        return new float[] {min, max};
    }

    /** {@code vector}'s first {@code kept} components after an int8 round trip over {@code range}. */
    private static float[] dequantized(float[] vector, int kept, float[] range) {
        float min = range[0];
        float span = range[1] - range[0];
        float[] result = new float[kept];
        for (int i = 0; i < kept; i++) {
            if (span <= 0) {
                result[i] = vector[i];
            } else {
                long level = Math.round((vector[i] - min) / span * 255.0);
                result[i] = (float) (min + Math.max(0, Math.min(255, level)) * span / 255.0);
            }
        }
        return result;
    }

    /** The binary quantization of {@code vector}'s first {@code kept} components: +1 above zero, -1 otherwise. */
    private static float[] signs(float[] vector, int kept) {
        float[] signs = new float[kept];
        for (int i = 0; i < kept; i++) {
            signs[i] = vector[i] > 0 ? 1f : -1f;
        }
        return signs;
    }

    /** The best {@code limit} matches offered: highest score first, earlier documents first among equals. */
    private static final class TopMatches {
        private final int limit;
        private final PriorityQueue<VectorMatch> heap = new PriorityQueue<>(WORST_FIRST);

        TopMatches(int limit) {
            this.limit = limit;
        }

        void offer(VectorMatch match) {
            if (match == null) {
                return;
            }
            heap.add(match);
            if (heap.size() > limit) {
                heap.poll();
            }
        }

        List<VectorMatch> best() {
            List<VectorMatch> matches = new ArrayList<>(heap);
            matches.sort(WORST_FIRST.reversed());
            return matches;
        }
    }
}
