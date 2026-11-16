package io.floci.az.compat;

import com.azure.core.http.HttpHeaderName;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.SemanticConfiguration;
import com.azure.search.documents.indexes.models.SemanticField;
import com.azure.search.documents.indexes.models.SemanticPrioritizedFields;
import com.azure.search.documents.indexes.models.SemanticSearch;
import com.azure.search.documents.models.QueryAnswerResult;
import com.azure.search.documents.models.QueryAnswerType;
import com.azure.search.documents.models.QueryCaptionResult;
import com.azure.search.documents.models.QueryCaptionType;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedResponse;
import com.azure.search.documents.models.SearchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static io.floci.az.compat.SearchTestSupport.ADMIN_KEY;
import static io.floci.az.compat.SearchTestSupport.eventually;
import static io.floci.az.compat.SearchTestSupport.keys;
import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search semantic ranking through {@code azure-search-documents}. floci-az answers semantic queries with a
 * deterministic stand-in for the semantic ranker's model, so these tests check the documented shapes (reranker scores
 * from 0 to 4, extractive captions and answers, the semantic configuration round trip, the 400s) and the stand-in's
 * documented order, not Azure's own scores.
 */
@Tag("search-semantic")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search Semantic Ranking Compatibility")
class SearchSemanticCompatibilityTest {

    private static final String CONFIGURATION = "hotel-config";

    private final String service = SearchTestSupport.serviceName("javasemantic");
    private final String hotelsIndex = SearchTestSupport.name("semantic");

    private SearchIndexClient indexClient;
    private SearchClient hotels;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        indexClient = SearchTestSupport.indexClient(service, ADMIN_KEY);
        indexClient.createIndex(SearchTestSupport.hotelsIndex(hotelsIndex).setSemanticSearch(new SemanticSearch()
                .setDefaultConfigurationName(CONFIGURATION)
                .setConfigurations(new SemanticConfiguration(CONFIGURATION, new SemanticPrioritizedFields()
                        .setTitleField(new SemanticField("name"))
                        .setContentFields(new SemanticField("description"))
                        .setKeywordsFields(new SemanticField("tags"))))));
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

    private static SearchOptions semantic(String searchText) {
        return new SearchOptions().setSearchText(searchText).setQueryType(QueryType.SEMANTIC)
                .setSemanticConfigurationName(CONFIGURATION);
    }

    private SearchPagedResponse firstPage(SearchOptions options) {
        return hotels.search(options).iterableByPage().iterator().next();
    }

    // https://learn.microsoft.com/azure/search/semantic-how-to-configure (title, content and keyword fields, and an
    // optional default configuration)
    @Test
    void theSemanticConfigurationRoundTrips() {
        SemanticSearch semantic = indexClient.getIndex(hotelsIndex).getSemanticSearch();
        assertEquals(CONFIGURATION, semantic.getDefaultConfigurationName());
        SemanticPrioritizedFields fields = semantic.getConfigurations().get(0).getPrioritizedFields();
        assertEquals("name", fields.getTitleField().getFieldName());
        assertEquals("description", fields.getContentFields().get(0).getFieldName());
        assertEquals("tags", fields.getKeywordsFields().get(0).getFieldName());
    }

    // https://learn.microsoft.com/azure/search/semantic-search-overview (@search.rerankerScore from 4 to 0, results in
    // descending order by it); https://learn.microsoft.com/azure/search/semantic-answers (key, text, highlights, score)
    @Test
    void semanticQueriesRerankWithCaptionsAndAnswers() {
        SearchPagedResponse page = firstPage(semantic("which hotel has a rooftop pool?")
                .setCaptions(QueryCaptionType.EXTRACTIVE)
                .setAnswers(QueryAnswerType.fromString("extractive|count-3")));
        assertEquals("stand-in", page.getHeaders().getValue(HttpHeaderName.fromString("x-floci-az-semantic-ranker")));
        List<SearchResult> results = page.getValue();
        assertEquals(List.of("3", "1", "6"), keys(results, "id").subList(0, 3));
        double previous = 4.0;
        for (SearchResult result : results) {
            Double score = result.getRerankerScore();
            assertNotNull(score);
            assertTrue(score >= 0 && score <= previous, "reranker scores descend from at most 4: " + score);
            previous = score;
        }

        QueryCaptionResult caption = results.get(0).getCaptions().get(0);
        assertEquals("Modern hotel in the city center with a rooftop pool", caption.getText());
        assertTrue(caption.getHighlights().contains("<em>rooftop pool</em>"), caption.getHighlights());

        List<QueryAnswerResult> answers = page.getAnswers();
        assertEquals(1, answers.size());
        assertEquals("3", answers.get(0).getKey());
        assertEquals("Modern hotel in the city center with a rooftop pool", answers.get(0).getText());
        assertTrue(answers.get(0).getScore() >= 0.7);
    }

    // https://learn.microsoft.com/azure/search/semantic-how-to-query-request (semanticQuery reranks a simple or full
    // query with its own plain text)
    @Test
    void aSemanticQueryReranksASimpleQuery() {
        List<SearchResult> results = firstPage(new SearchOptions().setSearchText("pool")
                .setSemanticQuery("rooftop pool in the city").setSemanticConfigurationName(CONFIGURATION)).getValue();
        assertEquals(List.of("3", "1"), keys(results, "id"));
        assertNotNull(results.get(1).getRerankerScore());
    }

    // https://learn.microsoft.com/azure/search/semantic-how-to-query-request ("adding an orderby clause results in an
    // HTTP 400 error if you apply semantic ranking over ordered results")
    @Test
    void invalidSemanticQueriesAreRejected() {
        assertEquals(400, statusOf(() -> firstPage(semantic("pool").setOrderBy("rating desc"))));
        assertEquals(400, statusOf(() -> firstPage(semantic("pool").setSemanticConfigurationName("missing"))));
    }
}
