package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.List;

/**
 * The ranked results of one vector query in one of its fields: one of the lists Reciprocal Rank Fusion combines.
 *
 * @param query   the vector query
 * @param field   the field searched
 * @param metric  the field's similarity metric
 * @param matches the matches, best first
 */
public record VectorList(VectorQuery query, SearchFieldDefinition field, VectorMetric metric,
                         List<VectorMatch> matches) {

    public VectorList {
        matches = List.copyOf(matches);
    }
}
