package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataOrderBy;
import io.floci.az.services.search.engine.odata.ODataSelect;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state of one query as it moves through the {@link SearchPipeline}. Built inside the index's read lock; the
 * request's field references ({@code searchFields}, {@code select}, {@code orderby}) are validated on construction, so
 * an invalid request fails before any stage runs.
 *
 * <p>Data flow between the stages:</p>
 * <ol>
 *   <li>{@link FilterStage} sets {@link #filterMatches()} (null when there is no filter) and
 *       {@link #filterScores()} (scores from {@code search.ismatchscoring}, null when none);</li>
 *   <li>{@link TextRetrievalStage} sets {@link #textResults()} (null when the query text matches everything);</li>
 *   <li>{@link VectorRetrievalStage} adds to {@link #vectorResults()};</li>
 *   <li>{@link FusionStage} turns those into {@link #hits()}, every matching document with its score;</li>
 *   <li>{@link ScoringProfileStage}, {@link FacetStage} and {@link SemanticStage} rescore, count and rerank hits;</li>
 *   <li>{@link SortAndPageStage} sorts, sets {@link #totalCount()}, {@link #page()} and {@link #nextPage()};</li>
 *   <li>{@link HighlightStage} decorates the page.</li>
 * </ol>
 */
public final class SearchExecution {

    private final SearchRequest request;
    private final LiveIndex index;
    private final EngineSettings settings;
    private final List<SearchFieldDefinition> searchFields;
    private final ODataSelect select;
    private final ODataOrderBy orderBy;
    private final Map<String, Double> fieldWeights = new HashMap<>();
    private final List<RankedResults> vectorResults = new ArrayList<>();
    private final Map<String, ArrayNode> facets = new LinkedHashMap<>();
    private final Map<String, JsonNode> annotations = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private BitSet filterMatches;
    private ScoredDocs filterScores;
    private ScoredDocs textResults;
    private List<SearchHit> hits = new ArrayList<>();
    private long totalCount;
    private List<SearchHit> page = List.of();
    private SearchRequest nextPage;
    private ArrayNode answers;
    private boolean rankedByReranker;

    public SearchExecution(SearchRequest request, LiveIndex index, EngineSettings settings) {
        this.request = request;
        this.index = index;
        this.settings = settings;
        SearchIndexDefinition definition = index.definition();
        this.searchFields = resolveSearchFields(request, definition);
        this.select = ODataSelect.parse(request.select(), definition, "$select");
        this.orderBy = ODataOrderBy.parse(request.orderBy(), definition);
        validatePaging(request);
    }

    private static List<SearchFieldDefinition> resolveSearchFields(SearchRequest request,
                                                                   SearchIndexDefinition definition) {
        if (request.searchFields().isEmpty()) {
            return definition.fullTextSearchableFields();
        }
        List<SearchFieldDefinition> fields = new ArrayList<>();
        for (String path : request.searchFields()) {
            SearchFieldDefinition field = definition.field(path).orElseThrow(() -> SearchException.badRequest(
                "Invalid search request: Could not find a property named '" + path + "' on type 'search.document'."
                    + "\r\nParameter name: searchFields"));
            if (!field.isFullTextSearchable()) {
                throw SearchException.badRequest("Invalid search request: The field '" + path + "' in searchFields "
                    + "is not searchable.\r\nParameter name: searchFields");
            }
            fields.add(field);
        }
        return fields;
    }

    private static void validatePaging(SearchRequest request) {
        if (request.top() != null && request.top() < 0) {
            throw SearchException.badRequest("Invalid value " + request.top() + " for parameter $top. It must not be "
                + "negative.");
        }
        if (request.skip() != null && (request.skip() < 0 || request.skip() > SearchRequest.MAX_SKIP)) {
            throw SearchException.badRequest("Invalid value " + request.skip() + " for parameter $skip. It must be "
                + "between 0 and " + SearchRequest.MAX_SKIP + ".");
        }
        if (request.minimumCoverage() != null
                && (request.minimumCoverage() < 0 || request.minimumCoverage() > 100)) {
            throw SearchException.badRequest("Invalid value " + request.minimumCoverage() + " for parameter "
                + "minimumCoverage. It must be between 0 and 100.");
        }
    }

    public SearchRequest request() {
        return request;
    }

    /** The index; the pipeline holds its read lock while stages run. */
    public LiveIndex index() {
        return index;
    }

    public EngineSettings settings() {
        return settings;
    }

    /** The fields full-text search covers: the validated {@code searchFields}, or every searchable field. */
    public List<SearchFieldDefinition> searchFields() {
        return searchFields;
    }

    public ODataSelect select() {
        return select;
    }

    public ODataOrderBy orderBy() {
        return orderBy;
    }

    /** Per-field multipliers of text scores, by field path; a scoring profile's text weights set them. */
    public Map<String, Double> fieldWeights() {
        return fieldWeights;
    }

    /** The documents the filter admits, or null when the request has no filter. */
    public BitSet filterMatches() {
        return filterMatches;
    }

    public void filterMatches(BitSet value) {
        filterMatches = value;
    }

    /** Whether the filter admits the document (true when there is no filter). */
    public boolean admitted(int ordinal) {
        return filterMatches == null || filterMatches.get(ordinal);
    }

    /** Scores {@code search.ismatchscoring} contributes, or null when the filter has no such call. */
    public ScoredDocs filterScores() {
        return filterScores;
    }

    public void filterScores(ScoredDocs value) {
        filterScores = value;
    }

    /** The full-text matches with their BM25 scores, or null when the query text matches every document. */
    public ScoredDocs textResults() {
        return textResults;
    }

    public void textResults(ScoredDocs value) {
        textResults = value;
    }

    /** The ranked lists of the vector queries, for fusion. */
    public List<RankedResults> vectorResults() {
        return vectorResults;
    }

    /** Every matching document with its score, after fusion. */
    public List<SearchHit> hits() {
        return hits;
    }

    public void hits(List<SearchHit> value) {
        hits = value;
    }

    /** The number of matching documents ({@code @odata.count}). */
    public long totalCount() {
        return totalCount;
    }

    public void totalCount(long value) {
        totalCount = value;
    }

    /** The hits of the requested page, in order. */
    public List<SearchHit> page() {
        return page;
    }

    public void page(List<SearchHit> value) {
        page = value;
    }

    /** The parameters of the next page, or null when the response holds everything requested. */
    public SearchRequest nextPage() {
        return nextPage;
    }

    public void nextPage(SearchRequest value) {
        nextPage = value;
    }

    /** The facet results by facet name; the facet stage fills it. */
    public Map<String, ArrayNode> facets() {
        return facets;
    }

    public ArrayNode answers() {
        return answers;
    }

    public void answers(ArrayNode value) {
        answers = value;
    }

    /** Whether the semantic ranker ordered the hits, so they sort by reranker score first. */
    public boolean rankedByReranker() {
        return rankedByReranker;
    }

    public void rankedByReranker(boolean value) {
        rankedByReranker = value;
    }

    /** Further top-level {@code @search.*} response properties, by full name. */
    public Map<String, JsonNode> annotations() {
        return annotations;
    }

    /** Response headers the stages add. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The outcome: the page projected through {@code select}, and the response-level values. */
    public SearchOutcome outcome() {
        List<SearchOutcome.Result> results = new ArrayList<>(page.size());
        for (SearchHit hit : page) {
            results.add(new SearchOutcome.Result(hit, select.project(index.document(hit.ordinal()).source())));
        }
        return new SearchOutcome(results, request.includeTotalCount() ? totalCount : null,
            request.minimumCoverage() == null ? null : 100.0,
            request.facets().isEmpty() ? null : facets, answers, nextPage, annotations, headers);
    }

    /**
     * A ranked list of documents from one retriever (a vector query), with the weight fusion gives it.
     *
     * @param name   the retriever, for debugging
     * @param docs   the matches with their similarity scores
     * @param weight the list's weight in fusion
     */
    public record RankedResults(String name, ScoredDocs docs, double weight) {
    }
}
