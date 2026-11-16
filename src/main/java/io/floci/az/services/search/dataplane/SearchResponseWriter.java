package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.pipeline.SearchHit;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link SearchOutcome} as {@code SearchDocumentsResult}: {@code @odata.count}, {@code @search.coverage},
 * {@code @search.facets}, {@code @search.answers}, {@code @search.nextPageParameters}, {@code value} (each result's
 * {@code @search.*} annotations, then its fields) and {@code @odata.nextLink}.
 */
final class SearchResponseWriter {

    private SearchResponseWriter() {
    }

    static ObjectNode write(SearchCall call, String index, SearchOutcome outcome, boolean get) {
        ObjectNode body = SearchJson.object();
        if (outcome.count() != null) {
            body.put("@odata.count", outcome.count());
        }
        if (outcome.coverage() != null) {
            body.put("@search.coverage", outcome.coverage());
        }
        if (outcome.facets() != null) {
            ObjectNode facets = body.putObject("@search.facets");
            outcome.facets().forEach(facets::set);
        }
        if (outcome.answers() != null) {
            body.set("@search.answers", outcome.answers());
        }
        outcome.annotations().forEach(body::set);
        if (outcome.nextPage() != null) {
            body.set("@search.nextPageParameters", outcome.nextPage().toJson());
        }
        ArrayNode value = body.putArray("value");
        for (SearchOutcome.Result result : outcome.results()) {
            value.add(result(result));
        }
        if (outcome.nextPage() != null) {
            body.put("@odata.nextLink", nextLink(call, outcome, get));
        }
        return SearchResponses.withIndexContext(call, index, "docs(*)", body);
    }

    private static ObjectNode result(SearchOutcome.Result result) {
        SearchHit hit = result.hit();
        ObjectNode item = SearchJson.object();
        item.set("@search.score", JsonNodeFactory.instance.numberNode((float) hit.score()));
        if (hit.rerankerScore() != null) {
            item.put("@search.rerankerScore", hit.rerankerScore());
        }
        if (hit.rerankerBoostedScore() != null) {
            item.put("@search.rerankerBoostedScore", hit.rerankerBoostedScore());
        }
        if (hit.highlights() != null) {
            ObjectNode highlights = item.putObject("@search.highlights");
            hit.highlights().forEach((field, fragments) -> {
                ArrayNode array = highlights.putArray(field);
                fragments.forEach(array::add);
            });
        }
        if (hit.captions() != null) {
            ArrayNode captions = item.putArray("@search.captions");
            hit.captions().forEach(captions::add);
        }
        if (hit.documentDebugInfo() != null) {
            item.set("@search.documentDebugInfo", hit.documentDebugInfo());
        }
        hit.annotations().forEach(item::set);
        item.setAll(result.document());
        return item;
    }

    /** The continuation URL: the request URL for a POST, the GET URL with the next {@code $skip} and {@code $top}. */
    private static String nextLink(SearchCall call, SearchOutcome outcome, boolean get) {
        String url = call.requestUrl();
        if (!get) {
            return url + "?api-version=" + SearchResponses.encode(call.apiVersion());
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        if (call.request().queryParamsMulti() != null) {
            call.request().queryParamsMulti().forEach((name, values) -> parameters.put(name, new ArrayList<>(values)));
        }
        parameters.put("$skip", List.of(String.valueOf(outcome.nextPage().effectiveSkip())));
        if (outcome.nextPage().top() == null) {
            parameters.remove("$top");
        } else {
            parameters.put("$top", List.of(String.valueOf(outcome.nextPage().top())));
        }
        StringBuilder query = new StringBuilder();
        parameters.forEach((name, values) -> values.forEach(parameter -> {
            query.append(query.isEmpty() ? "?" : "&");
            query.append(SearchResponses.encode(name)).append('=').append(SearchResponses.encode(parameter));
        }));
        return url + query;
    }
}
