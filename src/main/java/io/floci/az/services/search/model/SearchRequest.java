package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every parameter of a Search Documents request (POST body or GET query string), including the ones later stages
 * interpret: facets, highlights, scoring profiles, vector and hybrid queries, and the semantic ranker's parameters.
 * Unset parameters are null (or empty lists), so {@link #toJson()} echoes exactly what the caller sent, which is what
 * {@code @search.nextPageParameters} carries.
 *
 * @param search                        the query text; null or {@code *} matches every document
 * @param searchMode                    {@code any} or {@code all}; null means {@code any}
 * @param queryType                     {@code simple}, {@code full} or {@code semantic}; null means {@code simple}
 * @param searchFields                  the fields full-text search is scoped to
 * @param filter                        the {@code $filter} expression
 * @param orderBy                       the {@code $orderby} expression, its clauses comma-separated
 * @param select                        the fields to return; empty means every retrievable field
 * @param top                           the number of results to return
 * @param skip                          the number of results to skip
 * @param count                         whether to return {@code @odata.count}
 * @param facets                        the facet expressions
 * @param highlightFields               the fields to return hit highlights for
 * @param highlightPreTag               the tag before each highlight
 * @param highlightPostTag              the tag after each highlight
 * @param minimumCoverage               the coverage the query must reach
 * @param scoringProfile                the scoring profile to apply
 * @param scoringParameters             the scoring profile's parameters, {@code name-value1,value2}
 * @param scoringStatistics             {@code local} or {@code global}
 * @param sessionId                     the sticky-session id
 * @param vectorQueries                 the vector queries, as sent
 * @param vectorFilterMode              {@code preFilter}, {@code postFilter} or {@code strictPostFilter}
 * @param hybridSearch                  the hybrid search options, as sent
 * @param semanticConfiguration         the semantic configuration
 * @param semanticQuery                 the separate query for semantic reranking
 * @param semanticErrorHandling         {@code partial} or {@code fail}
 * @param semanticMaxWaitInMilliseconds the semantic ranker's time budget
 * @param semanticFields                the fields used for semantic ranking
 * @param answers                       the semantic answers option
 * @param captions                      the semantic captions option
 * @param queryRewrites                 the query rewrites option
 * @param debug                         the debug option
 * @param queryLanguage                 the query language
 * @param speller                       the speller option
 * @param moreLikeThis                  the key of the document to find similar documents to
 * @param agenticOptions                the agentic search options, as sent
 * @param additionalProperties          body properties this record does not name, kept for later stages
 */
public record SearchRequest(String search, SearchMode searchMode, QueryType queryType, List<String> searchFields,
                            String filter, String orderBy, List<String> select, Integer top, Integer skip,
                            Boolean count, List<String> facets, List<String> highlightFields, String highlightPreTag,
                            String highlightPostTag, Double minimumCoverage, String scoringProfile,
                            List<String> scoringParameters, String scoringStatistics, String sessionId,
                            List<ObjectNode> vectorQueries, String vectorFilterMode, ObjectNode hybridSearch,
                            String semanticConfiguration, String semanticQuery, String semanticErrorHandling,
                            Integer semanticMaxWaitInMilliseconds, List<String> semanticFields, String answers,
                            String captions, String queryRewrites, String debug, String queryLanguage,
                            String speller, String moreLikeThis, ObjectNode agenticOptions,
                            Map<String, JsonNode> additionalProperties) {

    /** The page size when the request names no {@code top}. */
    public static final int DEFAULT_TOP = 50;

    /** The most results one response carries; a larger {@code top} is served in pages. */
    public static final int MAX_PAGE_SIZE = 1000;

    /** The largest {@code skip} the service accepts. */
    public static final int MAX_SKIP = 100_000;

    public SearchRequest {
        searchFields = searchFields == null ? List.of() : List.copyOf(searchFields);
        select = select == null ? List.of() : List.copyOf(select);
        facets = facets == null ? List.of() : List.copyOf(facets);
        highlightFields = highlightFields == null ? List.of() : List.copyOf(highlightFields);
        scoringParameters = scoringParameters == null ? List.of() : List.copyOf(scoringParameters);
        vectorQueries = vectorQueries == null ? List.of() : List.copyOf(vectorQueries);
        semanticFields = semanticFields == null ? List.of() : List.copyOf(semanticFields);
        additionalProperties = additionalProperties == null ? Map.of() : Map.copyOf(additionalProperties);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The effective search mode. */
    public SearchMode effectiveSearchMode() {
        return searchMode == null ? SearchMode.ANY : searchMode;
    }

    /** The effective query type. */
    public QueryType effectiveQueryType() {
        return queryType == null ? QueryType.SIMPLE : queryType;
    }

    /** Whether {@code @odata.count} is requested. */
    public boolean includeTotalCount() {
        return Boolean.TRUE.equals(count);
    }

    /** The effective number of results to skip. */
    public int effectiveSkip() {
        return skip == null ? 0 : skip;
    }

    /** Whether the query text matches every document: absent, blank or {@code *}. */
    public boolean matchesAllText() {
        return search == null || search.isBlank() || "*".equals(search.trim());
    }

    /** This request with another {@code skip} and {@code top}, for the next page of a paged response. */
    public SearchRequest withPaging(Integer newSkip, Integer newTop) {
        return toBuilder().skip(newSkip).top(newTop).build();
    }

    /** The request as a POST body, with only the parameters the caller set. */
    public ObjectNode toJson() {
        ObjectNode json = SearchJson.object();
        if (count != null) {
            json.put("count", count);
        }
        putList(json, "facets", facets);
        putText(json, "filter", filter);
        putCsv(json, "highlight", highlightFields);
        putText(json, "highlightPostTag", highlightPostTag);
        putText(json, "highlightPreTag", highlightPreTag);
        if (minimumCoverage != null) {
            json.put("minimumCoverage", minimumCoverage);
        }
        putText(json, "moreLikeThis", moreLikeThis);
        putText(json, "orderby", orderBy);
        if (queryType != null) {
            json.put("queryType", queryType.wireName());
        }
        putText(json, "scoringStatistics", scoringStatistics);
        putText(json, "sessionId", sessionId);
        putList(json, "scoringParameters", scoringParameters);
        putText(json, "scoringProfile", scoringProfile);
        putText(json, "debug", debug);
        putText(json, "search", search);
        putCsv(json, "searchFields", searchFields);
        if (searchMode != null) {
            json.put("searchMode", searchMode.wireName());
        }
        putText(json, "queryLanguage", queryLanguage);
        putText(json, "speller", speller);
        putCsv(json, "select", select);
        if (skip != null) {
            json.put("skip", skip);
        }
        if (top != null) {
            json.put("top", top);
        }
        putText(json, "semanticConfiguration", semanticConfiguration);
        putText(json, "semanticErrorHandling", semanticErrorHandling);
        if (semanticMaxWaitInMilliseconds != null) {
            json.put("semanticMaxWaitInMilliseconds", semanticMaxWaitInMilliseconds);
        }
        putText(json, "semanticQuery", semanticQuery);
        putText(json, "answers", answers);
        putText(json, "captions", captions);
        putText(json, "queryRewrites", queryRewrites);
        putCsv(json, "semanticFields", semanticFields);
        if (!vectorQueries.isEmpty()) {
            ArrayNode array = json.putArray("vectorQueries");
            vectorQueries.forEach(query -> array.add(query.deepCopy()));
        }
        putText(json, "vectorFilterMode", vectorFilterMode);
        if (hybridSearch != null) {
            json.set("hybridSearch", hybridSearch.deepCopy());
        }
        if (agenticOptions != null) {
            json.set("agenticOptions", agenticOptions.deepCopy());
        }
        additionalProperties.forEach((name, value) -> json.set(name, value.deepCopy()));
        return json;
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.search = search;
        builder.searchMode = searchMode;
        builder.queryType = queryType;
        builder.searchFields = searchFields;
        builder.filter = filter;
        builder.orderBy = orderBy;
        builder.select = select;
        builder.top = top;
        builder.skip = skip;
        builder.count = count;
        builder.facets = facets;
        builder.highlightFields = highlightFields;
        builder.highlightPreTag = highlightPreTag;
        builder.highlightPostTag = highlightPostTag;
        builder.minimumCoverage = minimumCoverage;
        builder.scoringProfile = scoringProfile;
        builder.scoringParameters = scoringParameters;
        builder.scoringStatistics = scoringStatistics;
        builder.sessionId = sessionId;
        builder.vectorQueries = vectorQueries;
        builder.vectorFilterMode = vectorFilterMode;
        builder.hybridSearch = hybridSearch;
        builder.semanticConfiguration = semanticConfiguration;
        builder.semanticQuery = semanticQuery;
        builder.semanticErrorHandling = semanticErrorHandling;
        builder.semanticMaxWaitInMilliseconds = semanticMaxWaitInMilliseconds;
        builder.semanticFields = semanticFields;
        builder.answers = answers;
        builder.captions = captions;
        builder.queryRewrites = queryRewrites;
        builder.debug = debug;
        builder.queryLanguage = queryLanguage;
        builder.speller = speller;
        builder.moreLikeThis = moreLikeThis;
        builder.agenticOptions = agenticOptions;
        builder.additionalProperties = new LinkedHashMap<>(additionalProperties);
        return builder;
    }

    private static void putText(ObjectNode json, String name, String value) {
        if (value != null) {
            json.put(name, value);
        }
    }

    private static void putCsv(ObjectNode json, String name, List<String> values) {
        if (!values.isEmpty()) {
            json.put(name, String.join(",", values));
        }
    }

    private static void putList(ObjectNode json, String name, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode array = json.putArray(name);
            values.forEach(array::add);
        }
    }

    /** Builds a {@link SearchRequest}; every parameter starts unset. */
    public static final class Builder {
        private String search;
        private SearchMode searchMode;
        private QueryType queryType;
        private List<String> searchFields;
        private String filter;
        private String orderBy;
        private List<String> select;
        private Integer top;
        private Integer skip;
        private Boolean count;
        private List<String> facets;
        private List<String> highlightFields;
        private String highlightPreTag;
        private String highlightPostTag;
        private Double minimumCoverage;
        private String scoringProfile;
        private List<String> scoringParameters;
        private String scoringStatistics;
        private String sessionId;
        private List<ObjectNode> vectorQueries;
        private String vectorFilterMode;
        private ObjectNode hybridSearch;
        private String semanticConfiguration;
        private String semanticQuery;
        private String semanticErrorHandling;
        private Integer semanticMaxWaitInMilliseconds;
        private List<String> semanticFields;
        private String answers;
        private String captions;
        private String queryRewrites;
        private String debug;
        private String queryLanguage;
        private String speller;
        private String moreLikeThis;
        private ObjectNode agenticOptions;
        private Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

        public Builder search(String value) {
            search = value;
            return this;
        }

        public Builder searchMode(SearchMode value) {
            searchMode = value;
            return this;
        }

        public Builder queryType(QueryType value) {
            queryType = value;
            return this;
        }

        public Builder searchFields(List<String> value) {
            searchFields = value;
            return this;
        }

        public Builder filter(String value) {
            filter = value;
            return this;
        }

        public Builder orderBy(String value) {
            orderBy = value;
            return this;
        }

        public Builder select(List<String> value) {
            select = value;
            return this;
        }

        public Builder top(Integer value) {
            top = value;
            return this;
        }

        public Builder skip(Integer value) {
            skip = value;
            return this;
        }

        public Builder count(Boolean value) {
            count = value;
            return this;
        }

        public Builder facets(List<String> value) {
            facets = value;
            return this;
        }

        public Builder highlightFields(List<String> value) {
            highlightFields = value;
            return this;
        }

        public Builder highlightPreTag(String value) {
            highlightPreTag = value;
            return this;
        }

        public Builder highlightPostTag(String value) {
            highlightPostTag = value;
            return this;
        }

        public Builder minimumCoverage(Double value) {
            minimumCoverage = value;
            return this;
        }

        public Builder scoringProfile(String value) {
            scoringProfile = value;
            return this;
        }

        public Builder scoringParameters(List<String> value) {
            scoringParameters = value;
            return this;
        }

        public Builder scoringStatistics(String value) {
            scoringStatistics = value;
            return this;
        }

        public Builder sessionId(String value) {
            sessionId = value;
            return this;
        }

        public Builder vectorQueries(List<ObjectNode> value) {
            vectorQueries = value;
            return this;
        }

        public Builder vectorFilterMode(String value) {
            vectorFilterMode = value;
            return this;
        }

        public Builder hybridSearch(ObjectNode value) {
            hybridSearch = value;
            return this;
        }

        public Builder semanticConfiguration(String value) {
            semanticConfiguration = value;
            return this;
        }

        public Builder semanticQuery(String value) {
            semanticQuery = value;
            return this;
        }

        public Builder semanticErrorHandling(String value) {
            semanticErrorHandling = value;
            return this;
        }

        public Builder semanticMaxWaitInMilliseconds(Integer value) {
            semanticMaxWaitInMilliseconds = value;
            return this;
        }

        public Builder semanticFields(List<String> value) {
            semanticFields = value;
            return this;
        }

        public Builder answers(String value) {
            answers = value;
            return this;
        }

        public Builder captions(String value) {
            captions = value;
            return this;
        }

        public Builder queryRewrites(String value) {
            queryRewrites = value;
            return this;
        }

        public Builder debug(String value) {
            debug = value;
            return this;
        }

        public Builder queryLanguage(String value) {
            queryLanguage = value;
            return this;
        }

        public Builder speller(String value) {
            speller = value;
            return this;
        }

        public Builder moreLikeThis(String value) {
            moreLikeThis = value;
            return this;
        }

        public Builder agenticOptions(ObjectNode value) {
            agenticOptions = value;
            return this;
        }

        public Builder additionalProperty(String name, JsonNode value) {
            additionalProperties.put(name, value);
            return this;
        }

        public SearchRequest build() {
            return new SearchRequest(search, searchMode, queryType, searchFields, filter, orderBy, select, top, skip,
                count, facets, highlightFields, highlightPreTag, highlightPostTag, minimumCoverage, scoringProfile,
                scoringParameters, scoringStatistics, sessionId, vectorQueries, vectorFilterMode, hybridSearch,
                semanticConfiguration, semanticQuery, semanticErrorHandling, semanticMaxWaitInMilliseconds,
                semanticFields, answers, captions, queryRewrites, debug, queryLanguage, speller, moreLikeThis,
                agenticOptions, additionalProperties);
        }
    }
}
