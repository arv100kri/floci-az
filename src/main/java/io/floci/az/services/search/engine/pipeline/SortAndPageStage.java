package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataOrderBy;
import io.floci.az.services.search.model.SearchRequest;

import java.util.Comparator;
import java.util.List;

/**
 * Sorts the hits and cuts the requested page, per Learn's "Shape search results": the order is {@code $orderby}
 * (ties broken by score, highest first), or the reranker score then the score for semantically ranked hits, or the
 * score; equal hits keep index order, so results are deterministic. The page is {@code skip} plus {@code top} results
 * (50 by default), at most 1000 per response: without {@code top}, a response holding 50 of more results continues
 * at {@code skip + 50}; with a {@code top} above 1000, it continues with the remaining {@code top}.
 */
public final class SortAndPageStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        List<SearchHit> hits = execution.hits();
        execution.totalCount(hits.size());
        hits.sort(comparator(execution));

        SearchRequest request = execution.request();
        int skip = request.effectiveSkip();
        Integer top = request.top();
        int pageSize = top == null ? SearchRequest.DEFAULT_TOP : Math.min(top, SearchRequest.MAX_PAGE_SIZE);
        int from = Math.min(skip, hits.size());
        int to = (int) Math.min(hits.size(), (long) from + pageSize);
        execution.page(List.copyOf(hits.subList(from, to)));

        int nextSkip = skip + pageSize;
        if (top == null) {
            if (nextSkip < hits.size() && nextSkip <= SearchRequest.MAX_SKIP) {
                execution.nextPage(request.withPaging(nextSkip, null));
            }
        } else if (top > SearchRequest.MAX_PAGE_SIZE && nextSkip < hits.size() && nextSkip <= SearchRequest.MAX_SKIP) {
            execution.nextPage(request.withPaging(nextSkip, top - pageSize));
        }
    }

    private static Comparator<SearchHit> comparator(SearchExecution execution) {
        ODataOrderBy orderBy = execution.orderBy();
        Comparator<SearchHit> byScore = Comparator.comparingDouble((SearchHit hit) -> hit.score()).reversed();
        Comparator<SearchHit> order;
        if (!orderBy.isEmpty()) {
            LiveIndex index = execution.index();
            order = orderBy.<SearchHit>comparator(hit -> index.document(hit.ordinal()), hit -> hit.score());
            if (!orderBy.sortsByScore()) {
                order = order.thenComparing(byScore);
            }
        } else if (execution.rankedByReranker()) {
            Comparator<SearchHit> byReranker = Comparator.comparingDouble(
                (SearchHit hit) -> hit.rerankerScore() == null ? Double.NEGATIVE_INFINITY : hit.rerankerScore())
                .reversed();
            order = byReranker.thenComparing(byScore);
        } else {
            order = byScore;
        }
        return order.thenComparingInt(SearchHit::ordinal);
    }
}
