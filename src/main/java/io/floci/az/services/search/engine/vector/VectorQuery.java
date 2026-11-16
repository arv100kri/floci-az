package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One validated entry of {@code vectorQueries}, per "Create a vector query" and the REST spec's {@code VectorQuery}.
 *
 * @param position       the entry's index in {@code vectorQueries}
 * @param kind           how the query supplies its vector
 * @param vector         the query vector ({@code kind: vector}), or null until a vectorizer supplies it
 * @param text           the text to vectorize ({@code kind: text}), or null
 * @param imageUrl       the image URL to vectorize ({@code kind: imageUrl}), or null
 * @param imageBase64    the base64 image to vectorize ({@code kind: imageBinary}), or null
 * @param fields         the vector fields searched, at most ten
 * @param k              the number of nearest neighbours to return
 * @param exhaustive     whether an exhaustive search was requested (every search here is exact)
 * @param oversampling   the oversampling factor overriding the compression's, or null
 * @param weight         the weight of each of the query's ranked lists in fusion
 * @param threshold      the minimum similarity or score, or null
 * @param filterOverride the filter replacing the request's {@code filter} for this query, or null
 */
public record VectorQuery(int position, Kind kind, float[] vector, String text, String imageUrl, String imageBase64,
                          List<SearchFieldDefinition> fields, int k, boolean exhaustive, Double oversampling,
                          double weight, Threshold threshold, String filterOverride) {

    public VectorQuery {
        fields = List.copyOf(fields);
    }

    /** Whether a vectorizer supplies the vector. */
    public boolean needsVectorizer() {
        return kind != Kind.VECTOR;
    }

    /** How a vector query supplies its vector. */
    public enum Kind {
        VECTOR("vector"),
        TEXT("text"),
        IMAGE_URL("imageUrl"),
        IMAGE_BINARY("imageBinary");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** The kind named {@code name}, ignoring case. */
        public static Optional<Kind> fromWireName(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.wireName.toLowerCase(Locale.ROOT).equals(lower)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A {@code threshold}: results below it are dropped from the query's lists before fusion.
     *
     * @param searchScore whether it compares {@code @search.score} ({@code searchScore}) rather than the canonical
     *                    similarity of the field's metric ({@code vectorSimilarity})
     * @param value       the threshold
     */
    public record Threshold(boolean searchScore, double value) {
    }
}
