package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.model.QueryType;

/** The parser of each query syntax. */
public final class QueryParsers {

    private static final QueryParser SIMPLE = new SimpleQueryParser();
    private static final QueryParser FULL = new LuceneQueryParser();

    private QueryParsers() {
    }

    /** The parser for {@code type}; semantic queries retrieve with the simple syntax. */
    public static QueryParser forType(QueryType type) {
        return type == QueryType.FULL ? FULL : SIMPLE;
    }
}
