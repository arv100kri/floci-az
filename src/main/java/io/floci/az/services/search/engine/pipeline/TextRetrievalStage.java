package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.analysis.SynonymExpansion;
import io.floci.az.services.search.engine.query.QueryExecutor;
import io.floci.az.services.search.engine.query.QueryNode;
import io.floci.az.services.search.engine.query.QueryParsers;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.model.SearchRequest;

/**
 * Full-text retrieval: parses {@code search} with the parser of the request's {@code queryType} and scores the matches
 * with BM25 over {@link SearchExecution#searchFields()}. A query of {@code *}, blank or absent matches every document
 * and leaves {@link SearchExecution#textResults()} null.
 */
public final class TextRetrievalStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        SearchRequest request = execution.request();
        if (request.matchesAllText()) {
            return;
        }
        QueryNode query = QueryParsers.forType(request.effectiveQueryType())
            .parse(request.search(), request.effectiveSearchMode());
        query = SynonymExpansion.expand(query, execution.index(), execution.searchFields());
        ScoredDocs docs = new QueryExecutor(execution.index(), execution.searchFields(), execution.fieldWeights())
            .execute(query);
        execution.textResults(docs == null ? new ScoredDocs() : docs);
    }
}
