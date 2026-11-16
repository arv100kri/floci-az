package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.LiveIndex;

/**
 * Suggestions and autocompletion over an index's suggester, separate from the search pipeline because they match
 * partial terms of suggester source fields rather than run a query. Implementations run inside the index's read lock.
 */
public interface SuggestStage {

    /**
     * Answers Suggest Documents.
     *
     * @param index      the index
     * @param parameters the request parameters with their POST body names ({@code search}, {@code suggesterName},
     *                   {@code filter}, {@code fuzzy}, {@code highlightPreTag}, {@code highlightPostTag},
     *                   {@code minimumCoverage}, {@code orderby}, {@code searchFields}, {@code select}, {@code top}),
     *                   whether they came in a GET query string or a POST body
     * @return the response body, with {@code value} holding the suggestions
     */
    ObjectNode suggest(LiveIndex index, ObjectNode parameters);

    /**
     * Answers Autocomplete.
     *
     * @param index      the index
     * @param parameters the request parameters with their POST body names ({@code search}, {@code suggesterName},
     *                   {@code autocompleteMode}, {@code filter}, {@code fuzzy}, {@code highlightPreTag},
     *                   {@code highlightPostTag}, {@code minimumCoverage}, {@code searchFields}, {@code top})
     * @return the response body, with {@code value} holding the completions
     */
    ObjectNode autocomplete(LiveIndex index, ObjectNode parameters);
}
