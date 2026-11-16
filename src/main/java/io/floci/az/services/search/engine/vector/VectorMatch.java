package io.floci.az.services.search.engine.vector;

/**
 * One nearest neighbour a vector query found in one field.
 *
 * @param ordinal    the document
 * @param similarity the canonical similarity of the field's metric (cosine similarity, distance, dot product)
 * @param score      the {@code @search.score} the similarity converts to
 */
public record VectorMatch(int ordinal, double similarity, double score) {
}
