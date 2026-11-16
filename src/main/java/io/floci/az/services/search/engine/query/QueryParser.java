package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.model.SearchMode;

/** Parses the {@code search} parameter of one query syntax into a {@link QueryNode} tree. Thread-safe. */
public interface QueryParser {

    /**
     * Parses {@code text}.
     *
     * @param text the query text, never null
     * @param mode the search mode: the operator between terms no operator joins
     * @return the query tree; {@link QueryNode.MatchAll} when the text names nothing to match
     */
    QueryNode parse(String text, SearchMode mode);
}
