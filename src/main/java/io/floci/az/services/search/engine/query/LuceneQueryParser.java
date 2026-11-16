package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;

/**
 * <b>STUB, to be replaced by the full Lucene query syntax</b> ({@code queryType=full}: fielded terms, boolean words
 * and operators, proximity, fuzzy, boosts, wildcards and regular expressions, per Learn's "Lucene query syntax" page).
 * Until then every full-syntax query is answered with an Azure-shaped 501 {@code NotImplemented}, never with results
 * from another syntax.
 */
public final class LuceneQueryParser implements QueryParser {

    @Override
    public QueryNode parse(String text, SearchMode mode) {
        throw SearchException.notImplemented("The full Lucene query syntax (queryType=full)");
    }
}
