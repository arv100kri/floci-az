package io.floci.az.services.search.engine.vector;

/**
 * What a vectorizer turns into a vector: the text, image URL or base64 image of a vectorizable vector query.
 *
 * @param kind  the query kind ({@code text}, {@code imageUrl} or {@code imageBinary})
 * @param value the text, the URL or the base64 image
 */
public record VectorInput(VectorQuery.Kind kind, String value) {

    /** The input of {@code query}, a query that needs a vectorizer. */
    public static VectorInput of(VectorQuery query) {
        return switch (query.kind()) {
            case TEXT -> new VectorInput(query.kind(), query.text());
            case IMAGE_URL -> new VectorInput(query.kind(), query.imageUrl());
            case IMAGE_BINARY -> new VectorInput(query.kind(), query.imageBase64());
            case VECTOR -> throw new IllegalArgumentException("A vector query of kind 'vector' needs no vectorizer");
        };
    }
}
