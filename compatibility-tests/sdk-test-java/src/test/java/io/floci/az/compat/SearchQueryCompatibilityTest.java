package io.floci.az.compat;

import com.azure.core.exception.HttpResponseException;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.AnalyzeTextOptions;
import com.azure.search.documents.indexes.models.AnalyzedTokenInfo;
import com.azure.search.documents.indexes.models.CharFilterName;
import com.azure.search.documents.indexes.models.CustomAnalyzer;
import com.azure.search.documents.indexes.models.LexicalAnalyzerName;
import com.azure.search.documents.indexes.models.LexicalTokenizerName;
import com.azure.search.documents.indexes.models.MagnitudeScoringFunction;
import com.azure.search.documents.indexes.models.MagnitudeScoringParameters;
import com.azure.search.documents.indexes.models.ScoringFunctionInterpolation;
import com.azure.search.documents.indexes.models.ScoringProfile;
import com.azure.search.documents.indexes.models.SearchAlias;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.SearchSuggester;
import com.azure.search.documents.indexes.models.SynonymMap;
import com.azure.search.documents.indexes.models.TextWeights;
import com.azure.search.documents.indexes.models.TokenFilterName;
import com.azure.search.documents.models.AutocompleteItem;
import com.azure.search.documents.models.AutocompleteMode;
import com.azure.search.documents.models.AutocompleteOptions;
import com.azure.search.documents.models.FacetResult;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchMode;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedResponse;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.SuggestOptions;
import com.azure.search.documents.models.SuggestResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.floci.az.compat.SearchTestSupport.ADMIN_KEY;
import static io.floci.az.compat.SearchTestSupport.doc;
import static io.floci.az.compat.SearchTestSupport.eventually;
import static io.floci.az.compat.SearchTestSupport.fields;
import static io.floci.az.compat.SearchTestSupport.searchKeys;
import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search query features through {@code azure-search-documents}: the full Lucene syntax, facets, hit
 * highlighting, suggestions and autocomplete, synonym maps, scoring profiles, index aliases, the analyze API and custom
 * analyzers.
 */
@Tag("search-query")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search Query Compatibility")
class SearchQueryCompatibilityTest {

    private static final String SUGGESTER = "sg";

    private final String service = SearchTestSupport.serviceName("javaquery");
    private final String hotelsIndex = SearchTestSupport.name("hotels");

    private SearchIndexClient indexClient;
    private SearchClient hotels;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        indexClient = SearchTestSupport.indexClient(service, ADMIN_KEY);
        indexClient.createIndex(SearchTestSupport.hotelsIndex(hotelsIndex)
                .setSuggesters(new SearchSuggester(SUGGESTER, "name")));
        hotels = SearchTestSupport.searchClient(service, hotelsIndex, ADMIN_KEY);
        SearchTestSupport.upload(hotels, SearchTestSupport.hotels());
        eventually(() -> assertEquals(6L, hotels.getDocumentCount()));
    }

    @AfterAll
    void teardown() {
        if (indexClient != null) {
            SearchTestSupport.deleteIndexQuietly(indexClient, hotelsIndex);
        }
    }

    // ── Full Lucene syntax ───────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/query-lucene-syntax (fielded search, Boolean operators, NOT in full
    // syntax: "only fetches documents that contain the term wifi, and then applies the negation")
    @Test
    void fullSyntaxFieldedAndBooleanQueries() {
        assertEquals(List.of("4"), lucene("name:lodge"));
        assertEquals(Set.of("1"), new HashSet<>(lucene("pool AND view")));
        assertEquals(Set.of("1", "3", "6"), new HashSet<>(lucene("pool OR view")));
        assertEquals(Set.of("1"), new HashSet<>(lucene("pool -rooftop")));
        assertEquals(Set.of("1"), new HashSet<>(lucene("pool NOT rooftop")));
    }

    // https://learn.microsoft.com/azure/search/query-lucene-syntax (wildcard: prefix and infix with * and ?, suffix
    // through a regular expression; regular expression search "/[mh]otel/"; fuzzy search "blue~1"; proximity search
    // "\"hotel airport\"~5")
    @Test
    void fullSyntaxWildcardRegexFuzzyAndProximity() {
        assertEquals(Set.of("3", "4"), new HashSet<>(lucene("mo*n")));
        assertEquals(List.of("4"), lucene("l?dge"));
        assertEquals(Set.of("2", "5"), new HashSet<>(lucene("/.*port/")));
        assertEquals(Set.of("2", "3", "6"), new HashSet<>(lucene("/[mh]otel/")));
        assertEquals(List.of("4"), lucene("montain~1"));
        assertEquals(List.of("6"), lucene("\"hotel harbor\"~5"));
        assertEquals(List.of(), lucene("\"hotel harbor\""), "without slop the terms must be adjacent");
    }

    // ── Facets ───────────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/search-faceted-navigation (count, sort: "Use value to sort ascending by
    // value"; values: "produces three buckets: one for base rate 0 up to but not including 10, one for 10 up to but not
    // including 20, one for 20 and above"; interval)
    @Test
    void facetsCountValuesRangesAndIntervals() {
        SearchPagedResponse page = hotels.search(new SearchOptions().setSearchText("*").setFacets(
                "category,count:10,sort:value", "tags", "price,values:100|200", "rating,interval:2"))
                .iterableByPage().iterator().next();
        Map<String, List<FacetResult>> facets = page.getFacets();

        List<FacetResult> categories = facets.get("category");
        assertEquals(List.of("Boutique", "Budget", "Luxury", "Resort"),
                categories.stream().map(f -> f.getAdditionalProperties().get("value")).toList());
        assertEquals(List.of(1L, 2L, 1L, 2L), categories.stream().map(FacetResult::getCount).toList());

        Map<Object, Long> tags = new LinkedHashMap<>();
        facets.get("tags").forEach(f -> tags.put(f.getAdditionalProperties().get("value"), f.getCount()));
        assertEquals(Map.of("wifi", 3L, "pool", 2L, "view", 2L, "parking", 1L, "gym", 1L, "hiking", 1L,
                "fireplace", 1L, "shuttle", 1L, "boutique", 1L), tags);

        List<FacetResult> prices = facets.get("price");
        assertEquals(3, prices.size());
        assertEquals(2L, prices.get(0).getCount());
        assertEquals(100.0, number(prices.get(0), "to"));
        assertEquals(3L, prices.get(1).getCount());
        assertEquals(100.0, number(prices.get(1), "from"));
        assertEquals(200.0, number(prices.get(1), "to"));
        assertEquals(1L, prices.get(2).getCount());
        assertEquals(200.0, number(prices.get(2), "from"));

        Map<Double, Long> ratingBuckets = new LinkedHashMap<>();
        facets.get("rating").forEach(f -> ratingBuckets.put(number(f, "value"), f.getCount()));
        assertEquals(Map.of(2.0, 3L, 4.0, 3L), ratingBuckets);
    }

    // https://learn.microsoft.com/azure/search/search-faceted-navigation (facets summarize the documents the query
    // matched)
    @Test
    void facetsReflectTheFilteredResults() {
        Map<String, List<FacetResult>> facets = hotels.search(new SearchOptions().setSearchText("*")
                .setFilter("parking eq true").setFacets("category")).iterableByPage().iterator().next().getFacets();
        Map<Object, Long> categories = new LinkedHashMap<>();
        facets.get("category").forEach(f -> categories.put(f.getAdditionalProperties().get("value"), f.getCount()));
        assertEquals(Map.of("Resort", 2L, "Budget", 1L), categories);
    }

    // ── Hit highlighting ─────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/search-pagination-page-layout#hit-highlighting ("By default, the markup
    // format is <em>, but you can override the tag by using the highlightPreTag and highlightPostTag parameters")
    @Test
    void highlightsWrapMatchedTerms() {
        List<SearchResult> results = SearchTestSupport.searchAll(hotels, new SearchOptions().setSearchText("pool")
                .setHighlightFields("description"));
        assertEquals(Set.of("1", "3"), new HashSet<>(SearchTestSupport.keys(results, "id")));
        for (SearchResult result : results) {
            List<String> fragments = result.getHighlights().get("description");
            assertNotNull(fragments, "highlights for every matching result");
            assertTrue(fragments.stream().anyMatch(f -> f.contains("<em>pool</em>")), fragments.toString());
        }

        List<SearchResult> custom = SearchTestSupport.searchAll(hotels, new SearchOptions().setSearchText("pool")
                .setHighlightFields("description").setHighlightPreTag("<b>").setHighlightPostTag("</b>"));
        for (SearchResult result : custom) {
            List<String> fragments = result.getHighlights().get("description");
            assertTrue(fragments.stream().anyMatch(f -> f.contains("<b>pool</b>")), fragments.toString());
            assertTrue(fragments.stream().noneMatch(f -> f.contains("<em>")), fragments.toString());
        }
    }

    // ── Suggestions and autocomplete ─────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/index-add-suggesters
    // https://learn.microsoft.com/rest/api/searchservice/documents/suggest-post (select: "If unspecified, only the key
    // field will be included in the results"; fuzzy: "find suggestions even if there's a substituted or missing
    // character"; filter; highlightPreTag)
    @Test
    void suggestReturnsMatchingSourceFieldText() {
        List<SuggestResult> budget = hotels.suggest(new SuggestOptions("bud", SUGGESTER)).getResults();
        assertEquals(1, budget.size());
        assertEquals("Budget Inn", budget.getFirst().getText());
        assertEquals(Map.of("id", "2"), fields(budget.getFirst().getAdditionalProperties()));

        List<SuggestResult> selected = hotels.suggest(new SuggestOptions("bud", SUGGESTER).setSelect("id", "category"))
                .getResults();
        assertEquals("Budget", selected.getFirst().getAdditionalProperties().get("category"));

        List<SuggestResult> fuzzy = hotels.suggest(new SuggestOptions("bidget", SUGGESTER).setUseFuzzyMatching(true))
                .getResults();
        assertEquals(List.of("Budget Inn"), fuzzy.stream().map(SuggestResult::getText).toList());

        Set<String> bothB = new HashSet<>(hotels.suggest(new SuggestOptions("b", SUGGESTER)).getResults().stream()
                .map(SuggestResult::getText).toList());
        assertEquals(Set.of("Budget Inn", "Harbor Boutique"), bothB);
        List<SuggestResult> filtered = hotels.suggest(new SuggestOptions("b", SUGGESTER)
                .setFilter("category eq 'Boutique'")).getResults();
        assertEquals(List.of("Harbor Boutique"), filtered.stream().map(SuggestResult::getText).toList());

        String highlighted = hotels.suggest(new SuggestOptions("bud", SUGGESTER)
                .setHighlightPreTag("<b>").setHighlightPostTag("</b>")).getResults().getFirst().getText();
        assertTrue(highlighted.contains("<b>") && highlighted.contains("</b>"), highlighted);
        assertEquals("Budget Inn", highlighted.replace("<b>", "").replace("</b>", ""));
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/autocomplete-post (AutocompleteMode: oneTerm,
    // twoTerms, oneTermWithContext; AutocompleteItem.text is "The completed term", queryPlusText "The query along with
    // the completed term")
    @Test
    void autocompleteCompletesTermsInEachMode() {
        List<AutocompleteItem> oneTerm = hotels.autocomplete(new AutocompleteOptions("moun", SUGGESTER)).getResults();
        assertEquals(List.of("mountain"), lower(oneTerm.stream().map(AutocompleteItem::getText).toList()));
        assertEquals(List.of("mountain"), lower(oneTerm.stream().map(AutocompleteItem::getQueryPlusText).toList()));

        List<AutocompleteItem> lastTerm = hotels.autocomplete(new AutocompleteOptions("budget i", SUGGESTER)
                .setAutocompleteMode(AutocompleteMode.ONE_TERM)).getResults();
        assertEquals(List.of("inn"), lower(lastTerm.stream().map(AutocompleteItem::getText).toList()));
        assertEquals(List.of("budget inn"), lower(lastTerm.stream().map(AutocompleteItem::getQueryPlusText).toList()));

        List<AutocompleteItem> twoTerms = hotels.autocomplete(new AutocompleteOptions("moun", SUGGESTER)
                .setAutocompleteMode(AutocompleteMode.TWO_TERMS)).getResults();
        assertTrue(lower(twoTerms.stream().map(AutocompleteItem::getText).toList()).contains("mountain lodge"),
                twoTerms.stream().map(AutocompleteItem::getText).toList().toString());

        List<AutocompleteItem> withContext = hotels.autocomplete(new AutocompleteOptions("harbor b", SUGGESTER)
                .setAutocompleteMode(AutocompleteMode.ONE_TERM_WITH_CONTEXT)).getResults();
        assertEquals(List.of("harbor boutique"),
                lower(withContext.stream().map(AutocompleteItem::getQueryPlusText).toList()));
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/suggest-post (suggesterName: "The name of the
    // suggester as specified in the suggesters collection that's part of the index definition")
    @Test
    void suggestWithAnUnknownSuggesterIsABadRequest() {
        assertEquals(400, statusOf(() -> hotels.suggest(new SuggestOptions("bud", "no-such-suggester"))));
    }

    // ── Synonym maps ─────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/search-synonyms (equivalency rules; explicit mapping: "a query for
    // Washington, Wash. or WA is rewritten as WA, and the query engine only looks for matches on the term WA"; "If the
    // synonym map exists on the search service, it's used on the next query, with no reindexing or rebuild required";
    // "if you then delete a synonym map, any query that includes the fields in question fails with a 404 error")
    // https://learn.microsoft.com/rest/api/searchservice/synonym-maps/create (201 Created)
    @Test
    void synonymMapsExpandQueriesOnAssignedFields() {
        String mapName = SearchTestSupport.name("syn");
        String indexName = SearchTestSupport.name("synonyms");
        SynonymMap created = indexClient.createSynonymMap(new SynonymMap(mapName,
                "dog, canine, puppy", "Washington, Wash., WA => WA"));
        try {
            assertEquals(mapName, created.getName());
            assertEquals("solr", created.getFormat());
            assertNotNull(created.getETag());
            SynonymMap fetched = indexClient.getSynonymMap(mapName);
            assertEquals(List.of("dog, canine, puppy", "Washington, Wash., WA => WA"),
                    String.join("\n", fetched.getSynonyms()).lines().map(String::trim).toList());
            List<String> mapNames = new ArrayList<>();
            indexClient.listSynonymMapNames().forEach(mapNames::add);
            assertTrue(mapNames.contains(mapName), mapNames.toString());

            indexClient.createIndex(new SearchIndex(indexName,
                    new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                    new SearchField("description", SearchFieldDataType.STRING).setSearchable(true)));
            SearchClient client = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
            SearchTestSupport.upload(client, List.of(
                    doc("id", "dog", "description", "a friendly dog"),
                    doc("id", "wa", "description", "visiting WA state"),
                    doc("id", "washington", "description", "the Washington monument")));
            eventually(() -> assertEquals(3L, client.getDocumentCount()));
            assertEquals(List.of(), searchKeys(client, new SearchOptions().setSearchText("canine")),
                    "no synonyms before the map is assigned");

            SearchIndex withSynonyms = indexClient.getIndex(indexName);
            withSynonyms.getFields().get(1).setSynonymMapNames(mapName);
            indexClient.createOrUpdateIndex(withSynonyms);

            eventually(() -> assertEquals(List.of("dog"),
                    searchKeys(client, new SearchOptions().setSearchText("canine"))));
            assertEquals(List.of("dog"), searchKeys(client, new SearchOptions().setSearchText("puppy")));
            // The query is scoped to the synonym-enabled field: "id" is searchable too, because "Fields of type
            // Edm.String or Collection(Edm.String) are searchable by default"
            // (https://learn.microsoft.com/rest/api/searchservice/indexes/create), and synonyms apply "only for fields
            // that have a synonym assignment" (https://learn.microsoft.com/azure/search/search-synonyms), so an
            // unscoped query also matches the key "washington" through the id field.
            assertEquals(List.of("wa"), searchKeys(client, new SearchOptions().setSearchText("Washington")
                    .setSearchFields("description")), "the explicit mapping rewrites the query to WA only");
            assertEquals(List.of("washington"), searchKeys(client, new SearchOptions().setSearchText("monument")));

            indexClient.deleteSynonymMap(mapName);
            eventually(() -> assertEquals(404, statusOf(() -> searchKeys(client,
                    new SearchOptions().setSearchText("canine")))));
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, indexName);
            try {
                indexClient.deleteSynonymMap(mapName);
            } catch (HttpResponseException ignored) {
                // Cleanup only: the test deletes the map itself when it gets that far.
            }
        }
    }

    // ── Scoring profiles ─────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/index-add-scoring-profiles (weighted fields: "matches found in a
    // 'Subject' field are considered more relevant than the same match found in a 'Description' field"; magnitude
    // functions "Alter rankings based on the range of values for a numeric field")
    // https://learn.microsoft.com/rest/api/searchservice/indexes/create (defaultScoringProfile: "The name of the scoring
    // profile to use if none is specified in the query")
    @Test
    void scoringProfilesBoostWeightedFieldsAndMagnitudes() {
        String indexName = SearchTestSupport.name("scoring");
        SearchIndex index = new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("title", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("body", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("rating", SearchFieldDataType.INT32).setFilterable(true).setSortable(true))
                .setScoringProfiles(
                        new ScoringProfile("titleBoost").setTextWeights(new TextWeights(Map.of("title", 5.0))),
                        new ScoringProfile("ratingBoost").setFunctions(new MagnitudeScoringFunction("rating", 10.0,
                                new MagnitudeScoringParameters(1, 5)).setInterpolation(ScoringFunctionInterpolation.LINEAR)))
                .setDefaultScoringProfile("titleBoost");
        indexClient.createIndex(index);
        try {
            SearchClient client = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
            SearchTestSupport.upload(client, List.of(
                    doc("id", "in-title", "title", "harbor view", "body", "quiet inn", "rating", 1),
                    doc("id", "in-body", "title", "quiet inn", "body", "harbor view", "rating", 1),
                    doc("id", "low", "title", "lake cabin", "body", "pine forest", "rating", 1),
                    doc("id", "high", "title", "lake cabin", "body", "pine forest", "rating", 5)));
            eventually(() -> assertEquals(4L, client.getDocumentCount()));

            assertEquals(List.of("in-title", "in-body"), searchKeys(client,
                    new SearchOptions().setSearchText("harbor").setScoringProfile("titleBoost")));
            assertEquals(List.of("in-title", "in-body"), searchKeys(client,
                    new SearchOptions().setSearchText("harbor")), "the default scoring profile applies");
            assertEquals(List.of("high", "low"), searchKeys(client,
                    new SearchOptions().setSearchText("cabin").setScoringProfile("ratingBoost")));
            assertEquals(400, statusOf(() -> searchKeys(client,
                    new SearchOptions().setSearchText("cabin").setScoringProfile("no-such-profile"))));
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, indexName);
        }
    }

    // ── Index aliases ────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/search-how-to-alias ("You can use an alias for all document operations,
    // including querying, indexing"; "An update to an alias might take up to 10 seconds to propagate"; "If you attempt
    // to delete an index that's currently mapped to an alias, the operation fails with 400 (Bad Request)")
    // https://learn.microsoft.com/rest/api/searchservice/aliases/create (201 Created)
    @Test
    void aliasesRouteDocumentOperationsAndCanBeRepointed() {
        String v1 = SearchTestSupport.name("alias-v1");
        String v2 = SearchTestSupport.name("alias-v2");
        String aliasName = SearchTestSupport.name("alias");
        for (String name : List.of(v1, v2)) {
            indexClient.createIndex(new SearchIndex(name,
                    new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                    new SearchField("version", SearchFieldDataType.STRING).setSearchable(true)));
        }
        try {
            SearchTestSupport.upload(SearchTestSupport.searchClient(service, v1, ADMIN_KEY),
                    List.of(doc("id", "from-v1", "version", "one")));
            SearchTestSupport.upload(SearchTestSupport.searchClient(service, v2, ADMIN_KEY),
                    List.of(doc("id", "from-v2", "version", "two")));

            SearchAlias created = indexClient.createAlias(new SearchAlias(aliasName, v1));
            assertEquals(aliasName, created.getName());
            assertEquals(List.of(v1), created.getIndexes());
            assertEquals(List.of(v1), indexClient.getAlias(aliasName).getIndexes());
            List<String> aliases = new ArrayList<>();
            indexClient.listAliases().forEach(alias -> aliases.add(alias.getName()));
            assertTrue(aliases.contains(aliasName), aliases.toString());

            SearchClient viaAlias = SearchTestSupport.searchClient(service, aliasName, ADMIN_KEY);
            eventually(() -> assertEquals(List.of("from-v1"),
                    searchKeys(viaAlias, new SearchOptions().setSearchText("*"))));
            SearchTestSupport.upload(viaAlias, List.of(doc("id", "written-through-alias", "version", "one")));
            SearchClient v1Client = SearchTestSupport.searchClient(service, v1, ADMIN_KEY);
            eventually(() -> assertEquals("one", v1Client.getDocument("written-through-alias")
                    .getAdditionalProperties().get("version")));

            indexClient.createOrUpdateAlias(new SearchAlias(aliasName, v2));
            eventually(Duration.ofSeconds(30), () -> assertEquals(List.of("from-v2"),
                    searchKeys(viaAlias, new SearchOptions().setSearchText("*"))));

            assertEquals(400, statusOf(() -> indexClient.deleteIndex(v2)), "an index an alias maps to is protected");

            indexClient.deleteAlias(aliasName);
            eventually(Duration.ofSeconds(30), () -> assertEquals(404, statusOf(() -> searchKeys(viaAlias,
                    new SearchOptions().setSearchText("*")))));
        } finally {
            try {
                indexClient.deleteAlias(aliasName);
            } catch (HttpResponseException ignored) {
                // Cleanup only: the test deletes the alias itself when it gets that far.
            }
            SearchTestSupport.deleteIndexQuietly(indexClient, v1);
            SearchTestSupport.deleteIndexQuietly(indexClient, v2);
        }
    }

    // ── Analyzers ────────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/rest/api/searchservice/indexes/analyze (sample response: "Text to analyze" gives
    // text 0-4 position 0, to 5-7 position 1, analyze 8-15 position 2)
    // https://learn.microsoft.com/azure/search/index-add-custom-analyzers (predefined analyzers: standard is "the
    // standard tokenizer, lowercase filter, and stop filter" with an empty default stopword list; keyword "Treats the
    // entire content of a field as a single token"; whitespace; stop "applies the lowercase and stopword token filters"
    // with "a predefined list for English")
    @Test
    void analyzeApiTokenizesWithPredefinedAnalyzers() {
        List<AnalyzedTokenInfo> standard = indexClient.analyzeText(hotelsIndex,
                new AnalyzeTextOptions("Text to analyze").setAnalyzerName(LexicalAnalyzerName.STANDARD_LUCENE))
                .getTokens();
        assertEquals(List.of("text", "to", "analyze"), standard.stream().map(AnalyzedTokenInfo::getToken).toList());
        assertEquals(List.of(0, 5, 8), standard.stream().map(AnalyzedTokenInfo::getStartOffset).toList());
        assertEquals(List.of(4, 7, 15), standard.stream().map(AnalyzedTokenInfo::getEndOffset).toList());
        assertEquals(List.of(0, 1, 2), standard.stream().map(AnalyzedTokenInfo::getPosition).toList());

        String text = "The Quick-Brown fox";
        assertEquals(List.of("the", "quick", "brown", "fox"), tokens(LexicalAnalyzerName.STANDARD_LUCENE, text));
        assertEquals(List.of("The Quick-Brown fox"), tokens(LexicalAnalyzerName.KEYWORD, text));
        assertEquals(List.of("The", "Quick-Brown", "fox"), tokens(LexicalAnalyzerName.WHITESPACE, text));
        assertEquals(List.of("quick", "brown", "fox"), tokens(LexicalAnalyzerName.STOP, text));

        List<String> tokenizerAndFilter = indexClient.analyzeText(hotelsIndex, new AnalyzeTextOptions(text)
                .setTokenizerName(LexicalTokenizerName.WHITESPACE).setTokenFilters(TokenFilterName.LOWERCASE))
                .getTokens().stream().map(AnalyzedTokenInfo::getToken).toList();
        assertEquals(List.of("the", "quick-brown", "fox"), tokenizerAndFilter);
    }

    // https://learn.microsoft.com/azure/search/index-add-custom-analyzers (a custom analyzer is a tokenizer, token
    // filters and char filters: html_strip "attempts to strip out HTML constructs", asciifolding "Converts ...
    // characters which aren't in the first 127 ASCII characters ... into their ASCII equivalents", lowercase)
    @Test
    void customAnalyzersApplyToAnalyzeAndToIndexedFields() {
        String indexName = SearchTestSupport.name("custom");
        indexClient.createIndex(new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("note", SearchFieldDataType.STRING).setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.fromString("folding")))
                .setAnalyzers(new CustomAnalyzer("folding", LexicalTokenizerName.STANDARD)
                        .setTokenFilters(TokenFilterName.LOWERCASE, TokenFilterName.ASCII_FOLDING)
                        .setCharFilters(CharFilterName.HTML_STRIP)));
        try {
            assertTrue(indexClient.getIndex(indexName).getAnalyzers().stream()
                    .anyMatch(analyzer -> "folding".equals(analyzer.getName())));
            List<String> folded = indexClient.analyzeText(indexName, new AnalyzeTextOptions("<p>Café DÉJÀ vu</p>")
                    .setAnalyzerName(LexicalAnalyzerName.fromString("folding"))).getTokens().stream()
                    .map(AnalyzedTokenInfo::getToken).toList();
            assertEquals(List.of("cafe", "deja", "vu"), folded);

            SearchClient client = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
            SearchTestSupport.upload(client, List.of(doc("id", "dessert", "note", "Crème brûlée at the <b>Café</b>")));
            eventually(() -> assertEquals(List.of("dessert"),
                    searchKeys(client, new SearchOptions().setSearchText("creme brulee").setSearchMode(SearchMode.ALL))));
            assertEquals(List.of(), searchKeys(client, new SearchOptions().setSearchText("b")),
                    "html_strip removed the markup before tokenizing");
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, indexName);
        }
    }

    private List<String> lucene(String query) {
        return searchKeys(hotels, new SearchOptions().setSearchText(query).setQueryType(QueryType.FULL));
    }

    private List<String> tokens(LexicalAnalyzerName analyzer, String text) {
        return indexClient.analyzeText(hotelsIndex, new AnalyzeTextOptions(text).setAnalyzerName(analyzer))
                .getTokens().stream().map(AnalyzedTokenInfo::getToken).toList();
    }

    private static Double number(FacetResult facet, String property) {
        Object value = facet.getAdditionalProperties().get(property);
        assertNotNull(value, property + " missing from facet " + facet.getAdditionalProperties());
        return ((Number) value).doubleValue();
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }
}
