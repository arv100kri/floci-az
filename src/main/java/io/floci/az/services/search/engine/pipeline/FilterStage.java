package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataFilter;
import io.floci.az.services.search.engine.odata.ODataFilter.FullTextCall;
import io.floci.az.services.search.engine.query.QueryExecutor;
import io.floci.az.services.search.engine.query.QueryNode;
import io.floci.az.services.search.engine.query.QueryParsers;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Evaluates {@code $filter} over every document: compiles it against the schema (a 400 for an invalid expression),
 * runs its {@code search.ismatch} and {@code search.ismatchscoring} calls once through the text engine, then keeps the
 * documents the expression admits. Matches of {@code search.ismatchscoring} calls also contribute their scores.
 */
public final class FilterStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        String filter = execution.request().filter();
        if (filter == null || filter.isBlank()) {
            return;
        }
        LiveIndex index = execution.index();
        ODataFilter compiled = ODataFilter.compile(filter, index.definition(), index.analysis());
        List<ScoredDocs> callResults = new ArrayList<>();
        boolean scoring = false;
        for (FullTextCall call : compiled.fullTextCalls()) {
            callResults.add(run(call, execution));
            scoring |= call.scoring();
        }
        ODataFilter.FullTextMatches matches = (id, ordinal) -> callResults.get(id).contains(ordinal);
        BitSet admitted = new BitSet(index.ordinalLimit());
        ScoredDocs scores = scoring ? new ScoredDocs() : null;
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document == null || !compiled.matches(document, matches)) {
                continue;
            }
            admitted.set(ordinal);
            if (scores != null) {
                for (FullTextCall call : compiled.fullTextCalls()) {
                    if (call.scoring() && callResults.get(call.id()).contains(ordinal)) {
                        scores.add(ordinal, callResults.get(call.id()).score(ordinal));
                    }
                }
            }
        }
        execution.filterMatches(admitted);
        execution.filterScores(scores);
    }

    private static ScoredDocs run(FullTextCall call, SearchExecution execution) {
        SearchIndexDefinition definition = execution.index().definition();
        List<SearchFieldDefinition> fields = call.searchFields().isEmpty()
            ? definition.fullTextSearchableFields()
            : call.searchFields().stream().map(path -> definition.field(path).orElseThrow()).toList();
        QueryNode query = QueryParsers.forType(call.queryType()).parse(call.search(), call.searchMode());
        ScoredDocs docs = new QueryExecutor(execution.index(), fields, execution.fieldWeights()).execute(query);
        return docs == null ? new ScoredDocs() : docs;
    }
}
