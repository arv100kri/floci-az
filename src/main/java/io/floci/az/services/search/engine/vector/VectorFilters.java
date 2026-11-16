package io.floci.az.services.search.engine.vector;

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
import java.util.Map;

/**
 * Evaluates a vector query's {@code filterOverride}: an OData filter, compiled against the schema like the request's
 * {@code filter}, that replaces the request's filter for that vector query only ("Hybrid search with filters targeting
 * vector subqueries"). Its {@code search.ismatch} calls run through the text engine.
 */
public final class VectorFilters {

    private VectorFilters() {
    }

    /** The documents of {@code index} that {@code filter} admits; {@code fieldWeights} weight its text calls. */
    public static BitSet evaluate(String filter, LiveIndex index, Map<String, Double> fieldWeights) {
        ODataFilter compiled = ODataFilter.compile(filter, index.definition(), index.analysis());
        List<ScoredDocs> callResults = new ArrayList<>();
        for (FullTextCall call : compiled.fullTextCalls()) {
            callResults.add(run(call, index, fieldWeights));
        }
        ODataFilter.FullTextMatches matches = (id, ordinal) -> callResults.get(id).contains(ordinal);
        BitSet admitted = new BitSet(index.ordinalLimit());
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document != null && compiled.matches(document, matches)) {
                admitted.set(ordinal);
            }
        }
        return admitted;
    }

    private static ScoredDocs run(FullTextCall call, LiveIndex index, Map<String, Double> fieldWeights) {
        SearchIndexDefinition definition = index.definition();
        List<SearchFieldDefinition> fields = call.searchFields().isEmpty()
            ? definition.fullTextSearchableFields()
            : call.searchFields().stream().map(path -> definition.field(path).orElseThrow()).toList();
        QueryNode query = QueryParsers.forType(call.queryType()).parse(call.search(), call.searchMode());
        ScoredDocs docs = new QueryExecutor(index, fields, fieldWeights).execute(query);
        return docs == null ? new ScoredDocs() : docs;
    }
}
