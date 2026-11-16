package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchRequest;

import java.util.List;
import java.util.Map;

/**
 * The result of a query, ready to serialise: the page of documents and the response-level values.
 *
 * @param results      the page, in order
 * @param count        the {@code @odata.count}, or null when not requested
 * @param coverage     the {@code @search.coverage}, or null when {@code minimumCoverage} was not requested
 * @param facets       the {@code @search.facets} by facet name, or null when no facets were requested
 * @param answers      the {@code @search.answers}, or null
 * @param nextPage     the {@code @search.nextPageParameters}, or null when the results fit in this response
 * @param annotations  further top-level {@code @search.*} properties, by full name
 * @param headers      response headers stages add (a semantic stand-in marker, for instance)
 */
public record SearchOutcome(List<Result> results, Long count, Double coverage, Map<String, ArrayNode> facets,
                            ArrayNode answers, SearchRequest nextPage, Map<String, JsonNode> annotations,
                            Map<String, String> headers) {

    public SearchOutcome {
        results = List.copyOf(results);
        annotations = Map.copyOf(annotations);
        headers = Map.copyOf(headers);
    }

    /**
     * One result.
     *
     * @param hit      the hit, with its scores and annotations
     * @param document the document's selected fields
     */
    public record Result(SearchHit hit, ObjectNode document) {
    }
}
