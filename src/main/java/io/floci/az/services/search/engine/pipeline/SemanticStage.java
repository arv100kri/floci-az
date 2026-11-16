package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;

/**
 * <b>STUB, to be replaced by the semantic ranker stand-in.</b> Will rerank the top 50 hits of a
 * {@code queryType=semantic} query by the semantic configuration's prioritized fields, set
 * {@link SearchHit#rerankerScore(Double)} and {@link SearchExecution#rankedByReranker(boolean)}, and add captions and
 * answers. Today a semantic query is refused: with a 400 when {@code semantic-ranker} is {@code disabled}, as on a
 * service whose semantic ranking is off, and with a 501 otherwise.
 */
public final class SemanticStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        if (execution.request().effectiveQueryType() != QueryType.SEMANTIC) {
            return;
        }
        if (!execution.settings().semanticRankerEnabled()) {
            throw SearchException.badRequest("Semantic search is not enabled for this service.\r\nParameter name: "
                + "queryType");
        }
        throw SearchException.notImplemented("Semantic ranking (queryType=semantic)");
    }
}
