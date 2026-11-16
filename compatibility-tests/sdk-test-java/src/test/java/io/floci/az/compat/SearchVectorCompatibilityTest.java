package io.floci.az.compat;

import com.azure.core.exception.HttpResponseException;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.AzureOpenAIModelName;
import com.azure.search.documents.indexes.models.AzureOpenAIVectorizer;
import com.azure.search.documents.indexes.models.AzureOpenAIVectorizerParameters;
import com.azure.search.documents.indexes.models.BinaryQuantizationCompression;
import com.azure.search.documents.indexes.models.ExhaustiveKnnAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.ExhaustiveKnnParameters;
import com.azure.search.documents.indexes.models.HnswAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.HnswParameters;
import com.azure.search.documents.indexes.models.RescoringOptions;
import com.azure.search.documents.indexes.models.ScalarQuantizationCompression;
import com.azure.search.documents.indexes.models.ScalarQuantizationParameters;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.VectorSearchAlgorithmMetric;
import com.azure.search.documents.indexes.models.VectorSearchCompression;
import com.azure.search.documents.indexes.models.VectorSearchCompressionRescoreStorageMethod;
import com.azure.search.documents.indexes.models.VectorSearchCompressionTarget;
import com.azure.search.documents.indexes.models.VectorSearchProfile;
import com.azure.search.documents.indexes.models.VectorSearchVectorizer;
import com.azure.search.documents.indexes.models.WebApiVectorizer;
import com.azure.search.documents.indexes.models.WebApiVectorizerParameters;
import com.azure.search.documents.models.QueryDebugMode;
import com.azure.search.documents.models.QueryResultDocumentSubscores;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.SingleVectorFieldResult;
import com.azure.search.documents.models.VectorFilterMode;
import com.azure.search.documents.models.VectorizableTextQuery;
import com.azure.search.documents.models.VectorizedQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.floci.az.compat.SearchTestSupport.ADMIN_KEY;
import static io.floci.az.compat.SearchTestSupport.doc;
import static io.floci.az.compat.SearchTestSupport.eventually;
import static io.floci.az.compat.SearchTestSupport.keys;
import static io.floci.az.compat.SearchTestSupport.searchAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search vector search through {@code azure-search-documents}: vector fields with exhaustive KNN and HNSW
 * profiles, k-nearest-neighbour queries, filtered vector queries, hybrid queries and multiple vector queries.
 *
 * <p>The vectors are three-dimensional and chosen so the cosine similarities to the query vectors are distinct, so the
 * nearest-neighbour order has one right answer. Approximate (HNSW) fields are queried with {@code exhaustive: true},
 * which the docs define as an exact search.</p>
 */
@Tag("search-vector")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search Vector Compatibility")
class SearchVectorCompatibilityTest {

    private static final List<Float> X_AXIS = List.of(1f, 0f, 0f);

    private final String service = SearchTestSupport.serviceName("javavector");
    private final String indexName = SearchTestSupport.name("vectors");

    private SearchIndexClient indexClient;
    private SearchClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        indexClient = SearchTestSupport.indexClient(service, ADMIN_KEY);
        indexClient.createIndex(new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("title", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("category", SearchFieldDataType.STRING).setFilterable(true),
                new SearchField("titleVector", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                        .setSearchable(true).setVectorSearchDimensions(3)
                        .setVectorSearchProfileName("exhaustive-profile"),
                new SearchField("contentVector", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                        .setSearchable(true).setVectorSearchDimensions(3)
                        .setVectorSearchProfileName("hnsw-profile"))
                .setVectorSearch(new VectorSearch()
                        .setAlgorithms(
                                new ExhaustiveKnnAlgorithmConfiguration("eknn").setParameters(
                                        new ExhaustiveKnnParameters().setMetric(VectorSearchAlgorithmMetric.COSINE)),
                                new HnswAlgorithmConfiguration("hnsw").setParameters(new HnswParameters()
                                        .setMetric(VectorSearchAlgorithmMetric.COSINE)
                                        .setM(4).setEfConstruction(400).setEfSearch(500)))
                        .setProfiles(
                                new VectorSearchProfile("exhaustive-profile", "eknn"),
                                new VectorSearchProfile("hnsw-profile", "hnsw"))));
        client = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
        SearchTestSupport.upload(client, List.of(
                vectorDoc("1", "red apple", "fruit", List.of(1.0, 0.0, 0.0), List.of(1.0, 0.0, 0.0)),
                vectorDoc("2", "green apple", "fruit", List.of(0.9, 0.1, 0.0), List.of(0.0, 1.0, 0.0)),
                vectorDoc("3", "blue car", "vehicle", List.of(0.2, 1.0, 0.0), List.of(0.9, 0.1, 0.0)),
                vectorDoc("4", "red car", "vehicle", List.of(0.0, 0.0, 1.0), List.of(0.0, 0.0, 1.0)),
                vectorDoc("5", "yellow banana", "fruit", List.of(0.5, 0.5, 0.0), List.of(0.3, 0.3, 0.9))));
        eventually(() -> assertEquals(5L, client.getDocumentCount()));
    }

    @AfterAll
    void teardown() {
        if (indexClient != null) {
            SearchTestSupport.deleteIndexQuietly(indexClient, indexName);
        }
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-create-index (vector fields are
    // Collection(Edm.Single) with dimensions and a vectorSearchProfile; profiles name an hnsw or exhaustiveKnn
    // algorithm configuration with a metric)
    @Test
    void vectorFieldsAndProfilesRoundTrip() {
        SearchIndex index = indexClient.getIndex(indexName);
        Map<String, VectorSearchAlgorithmConfiguration> algorithms = new LinkedHashMap<>();
        index.getVectorSearch().getAlgorithms().forEach(a -> algorithms.put(a.getName(), a));
        assertInstanceOf(ExhaustiveKnnAlgorithmConfiguration.class, algorithms.get("eknn"));
        assertInstanceOf(HnswAlgorithmConfiguration.class, algorithms.get("hnsw"));
        assertEquals(VectorSearchAlgorithmMetric.COSINE,
                ((ExhaustiveKnnAlgorithmConfiguration) algorithms.get("eknn")).getParameters().getMetric());
        HnswParameters hnsw = ((HnswAlgorithmConfiguration) algorithms.get("hnsw")).getParameters();
        assertEquals(VectorSearchAlgorithmMetric.COSINE, hnsw.getMetric());
        assertEquals(4, hnsw.getM());

        Map<String, String> profiles = new LinkedHashMap<>();
        index.getVectorSearch().getProfiles().forEach(p -> profiles.put(p.getName(), p.getAlgorithmConfigurationName()));
        assertEquals(Map.of("exhaustive-profile", "eknn", "hnsw-profile", "hnsw"), profiles);

        SearchField titleVector = index.getFields().stream().filter(f -> "titleVector".equals(f.getName()))
                .findFirst().orElseThrow();
        assertEquals(3, titleVector.getVectorSearchDimensions());
        assertEquals("exhaustive-profile", titleVector.getVectorSearchProfileName());
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("k determines how many nearest neighbor
    // results are returned"; "Vector queries always return k results, assuming at least k documents exist")
    // https://learn.microsoft.com/azure/search/vector-search-ranking (cosine @search.score range "0.333 - 1.00"; "Score
    // values always decrease as the similarity gets worse")
    @Test
    void vectorQueryReturnsKNearestNeighboursInSimilarityOrder() {
        List<SearchResult> results = vectorSearch(new VectorizedQuery(X_AXIS).setFields("titleVector")
                .setKNearestNeighbors(3).setExhaustive(true));
        assertEquals(List.of("1", "2", "5"), keys(results, "id"));
        double previous = Double.MAX_VALUE;
        for (SearchResult result : results) {
            assertTrue(result.getScore() >= 0.333 && result.getScore() <= 1.0 + 1e-6,
                    "cosine score out of the documented range: " + result.getScore());
            assertTrue(result.getScore() <= previous, "scores decrease as similarity drops");
            previous = result.getScore();
        }
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("in vector search, the search operation
    // identifies nearest neighbors and always return k results, even if the nearest neighbors aren't that similar";
    // "If k is larger than the number of documents in the index, the number of documents determines the upper limit")
    @Test
    void vectorQueryAlwaysReturnsKResultsUpToTheDocumentCount() {
        List<String> dissimilar = keys(vectorSearch(new VectorizedQuery(List.of(0f, 0f, 1f)).setFields("titleVector")
                .setKNearestNeighbors(2).setExhaustive(true)), "id");
        assertEquals(2, dissimilar.size());
        assertEquals("4", dissimilar.getFirst());

        List<String> all = keys(vectorSearch(new VectorizedQuery(X_AXIS).setFields("titleVector")
                .setKNearestNeighbors(50).setExhaustive(true)), "id");
        assertEquals(Set.of("1", "2", "3", "4", "5"), new HashSet<>(all));
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (VectorizedQuery.exhaustive: "When true,
    // triggers an exhaustive k-nearest neighbor search across all vectors within the vector index")
    // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("vectorQueries.exhaustive (optional) invokes
    // exhaustive KNN at query time, even if the field is indexed for HNSW")
    @Test
    void exhaustiveQueryOnAnHnswFieldIsExact() {
        assertEquals(List.of("1", "3"), keys(vectorSearch(new VectorizedQuery(X_AXIS).setFields("contentVector")
                .setKNearestNeighbors(2).setExhaustive(true)), "id"));
    }

    // https://learn.microsoft.com/azure/search/vector-search-filters ("Because the preFilter mode is used, the filter is
    // applied before the search engine runs the query, so only documents in the Databases category are considered")
    @Test
    void filtersRestrictVectorCandidates() {
        List<SearchResult> preFiltered = searchAll(client, new SearchOptions()
                .setVectorQueries(new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(2)
                        .setExhaustive(true))
                .setFilter("category eq 'vehicle'").setVectorFilterMode(VectorFilterMode.PRE_FILTER));
        assertEquals(List.of("3", "4"), keys(preFiltered, "id"));

        List<SearchResult> postFiltered = searchAll(client, new SearchOptions()
                .setVectorQueries(new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(5)
                        .setExhaustive(true))
                .setFilter("category eq 'fruit'").setVectorFilterMode(VectorFilterMode.POST_FILTER));
        assertTrue(Set.of("1", "2", "5").containsAll(keys(postFiltered, "id")),
                "post-filtered results still satisfy the filter: " + keys(postFiltered, "id"));
    }

    // https://learn.microsoft.com/azure/search/hybrid-search-ranking ("documents appearing in the top positions across
    // multiple search methods are likely to be more relevant and should be ranked higher"; the score is the sum of
    // 1/(rank + k) over the result sets)
    // https://learn.microsoft.com/azure/search/hybrid-search-how-to-query
    @Test
    void hybridQueryFusesTextAndVectorResults() {
        List<String> hybrid = keys(searchAll(client, new SearchOptions().setSearchText("red apple")
                .setSearchFields("title")
                .setVectorQueries(new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(3)
                        .setExhaustive(true))), "id");
        assertEquals(List.of("1", "2"), hybrid.subList(0, 2),
                "first in both lists, then the only other document in both lists: " + hybrid);
        assertEquals(Set.of("1", "2", "4", "5"), new HashSet<>(hybrid), "the union of the text and vector matches");
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-query ("Multiple vector queries ... The result of
    // this query is scored using reciprocal rank fusion"; "The vector query executes against each vector field that
    // you provide in the fields list")
    @Test
    void multipleVectorQueriesAndFieldsAreFused() {
        List<String> twoQueries = keys(searchAll(client, new SearchOptions().setVectorQueries(
                new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(3).setExhaustive(true),
                new VectorizedQuery(X_AXIS).setFields("contentVector").setKNearestNeighbors(3).setExhaustive(true))),
                "id");
        assertEquals(List.of("1", "5"), twoQueries.subList(0, 2), "ranked in both lists first: " + twoQueries);
        assertEquals(Set.of("1", "2", "3", "5"), new HashSet<>(twoQueries));

        List<String> twoFields = keys(searchAll(client, new SearchOptions().setVectorQueries(
                new VectorizedQuery(X_AXIS).setFields("titleVector,contentVector").setKNearestNeighbors(3)
                        .setExhaustive(true))), "id");
        assertEquals(List.of("1", "5"), twoFields.subList(0, 2), twoFields.toString());
        assertEquals(Set.of("1", "2", "3", "5"), new HashSet<>(twoFields));
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-query#vector-weighting ("Weights are used when
    // calculating the RRF scores of each document. The calculation is a multiplier of the weight value against the
    // rank score of the document within its respective result set")
    @Test
    void weightsMultiplyEachListsReciprocalRankScore() {
        List<String> weighted = keys(searchAll(client, new SearchOptions().setVectorQueries(
                new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(2).setExhaustive(true),
                new VectorizedQuery(X_AXIS).setFields("contentVector").setKNearestNeighbors(2).setExhaustive(true)
                        .setWeight(3f))), "id");
        assertEquals(List.of("1", "3", "2"), weighted, "3 is second in the list weighted 3, 2 in the list weighted 1");
    }

    // https://learn.microsoft.com/azure/search/hybrid-search-ranking#unpack-a-search-score-into-subscores ("adding a
    // new debug parameter set to either vector, semantic if using semantic ranker, or all")
    // https://learn.microsoft.com/azure/search/vector-search-ranking (cosine @search.score is 1 / (1 + cosine
    // distance), so an identical vector scores 1 with a cosine similarity of 1)
    @Test
    void debugVectorUnpacksTheHybridScoreIntoSubscores() {
        List<SearchResult> results = searchAll(client, new SearchOptions().setSearchText("apple")
                .setSearchFields("title").setDebug(QueryDebugMode.VECTOR)
                .setVectorQueries(new VectorizedQuery(X_AXIS).setFields("titleVector").setKNearestNeighbors(1)
                        .setExhaustive(true)));
        SearchResult first = results.getFirst();
        assertEquals("1", first.getAdditionalProperties().get("id"));
        QueryResultDocumentSubscores subscores = first.getDocumentDebugInfo().getVectors().getSubscores();
        assertNotNull(subscores.getText().getSearchScore());
        SingleVectorFieldResult titleVector = subscores.getVectors().getFirst().get("titleVector");
        assertEquals(1.0, titleVector.getVectorSimilarity(), 1e-6);
        assertEquals(1.0, titleVector.getSearchScore(), 1e-6);
    }

    // https://learn.microsoft.com/azure/search/vector-search-how-to-quantization (compressions with rescoring options,
    // assigned through profiles), https://learn.microsoft.com/azure/search/vector-search-how-to-configure-vectorizer
    // (vectorizers assigned through profiles) and the REST spec's HnswParameters defaults (m 4, efConstruction 400,
    // efSearch 500, which the spec's Create Index example returns filled in)
    @Test
    void compressionsAndVectorizersRoundTripThroughProfiles() {
        String name = SearchTestSupport.name("vectorconfig");
        try {
            indexClient.createIndex(new SearchIndex(name,
                    new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                    new SearchField("embedding", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                            .setSearchable(true).setVectorSearchDimensions(3).setVectorSearchProfileName("compressed"))
                    .setVectorSearch(new VectorSearch()
                            .setAlgorithms(new HnswAlgorithmConfiguration("graph").setParameters(
                                    new HnswParameters().setMetric(VectorSearchAlgorithmMetric.COSINE)))
                            .setCompressions(
                                    new ScalarQuantizationCompression("sq").setParameters(new ScalarQuantizationParameters()
                                                    .setQuantizedDataType(VectorSearchCompressionTarget.INT8))
                                            .setRescoringOptions(new RescoringOptions().setEnableRescoring(true)
                                                    .setDefaultOversampling(10.0)
                                                    .setRescoreStorageMethod(
                                                            VectorSearchCompressionRescoreStorageMethod.PRESERVE_ORIGINALS)),
                                    new BinaryQuantizationCompression("bq").setTruncationDimension(2))
                            .setVectorizers(
                                    new AzureOpenAIVectorizer("aoai").setParameters(new AzureOpenAIVectorizerParameters()
                                            .setResourceUrl(unreachableOpenAIResource()).setDeploymentName("embed")
                                            .setModelName(AzureOpenAIModelName.TEXT_EMBEDDING3SMALL)),
                                    new WebApiVectorizer("custom").setWebApiParameters(new WebApiVectorizerParameters()
                                            .setUrl("https://contoso.example/embed").setHttpMethod("POST")))
                            .setProfiles(
                                    new VectorSearchProfile("compressed", "graph").setCompressionName("sq")
                                            .setVectorizerName("aoai"),
                                    new VectorSearchProfile("binary", "graph").setCompressionName("bq")
                                            .setVectorizerName("custom"))));
            VectorSearch vectorSearch = indexClient.getIndex(name).getVectorSearch();
            HnswParameters hnsw = ((HnswAlgorithmConfiguration) vectorSearch.getAlgorithms().getFirst()).getParameters();
            assertEquals(List.of(4, 400, 500), List.of(hnsw.getM(), hnsw.getEfConstruction(), hnsw.getEfSearch()));

            Map<String, VectorSearchCompression> compressions = new LinkedHashMap<>();
            vectorSearch.getCompressions().forEach(c -> compressions.put(c.getCompressionName(), c));
            ScalarQuantizationCompression scalar = assertInstanceOf(ScalarQuantizationCompression.class,
                    compressions.get("sq"));
            assertEquals(VectorSearchCompressionTarget.INT8, scalar.getParameters().getQuantizedDataType());
            assertEquals(10.0, scalar.getRescoringOptions().getDefaultOversampling());
            assertEquals(VectorSearchCompressionRescoreStorageMethod.PRESERVE_ORIGINALS,
                    scalar.getRescoringOptions().getRescoreStorageMethod());
            assertEquals(2, assertInstanceOf(BinaryQuantizationCompression.class, compressions.get("bq"))
                    .getTruncationDimension());

            Map<String, VectorSearchVectorizer> vectorizers = new LinkedHashMap<>();
            vectorSearch.getVectorizers().forEach(v -> vectorizers.put(v.getVectorizerName(), v));
            AzureOpenAIVectorizerParameters openAI = assertInstanceOf(AzureOpenAIVectorizer.class,
                    vectorizers.get("aoai")).getParameters();
            assertEquals("embed", openAI.getDeploymentName());
            assertEquals(AzureOpenAIModelName.TEXT_EMBEDDING3SMALL, openAI.getModelName());
            assertEquals("https://contoso.example/embed", assertInstanceOf(WebApiVectorizer.class,
                    vectorizers.get("custom")).getWebApiParameters().getUrl());

            Map<String, String> profiles = new LinkedHashMap<>();
            vectorSearch.getProfiles().forEach(p -> profiles.put(p.getName(),
                    p.getCompressionName() + "/" + p.getVectorizerName()));
            assertEquals(Map.of("compressed", "sq/aoai", "binary", "bq/custom"), profiles);

            // A text query goes through the field's vectorizer; one that cannot reach its model fails the query with
            // the message Azure reports (https://github.com/Azure/azure-search-vector-samples/issues/133).
            SearchClient texts = SearchTestSupport.searchClient(service, name, ADMIN_KEY);
            HttpResponseException failure = assertThrows(HttpResponseException.class, () -> searchAll(texts,
                    new SearchOptions().setVectorQueries(new VectorizableTextQuery("red fruit")
                            .setFields("embedding").setKNearestNeighbors(1))));
            assertTrue(failure.getMessage().contains("Could not complete vectorization action"), failure.getMessage());
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, name);
        }
    }

    /** An Azure OpenAI endpoint no resource answers on, so vectorizing through it fails. */
    private static String unreachableOpenAIResource() {
        return "https://floci" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".openai.azure.com";
    }

    private List<SearchResult> vectorSearch(VectorizedQuery query) {
        return searchAll(client, new SearchOptions().setVectorQueries(query));
    }

    private static Map<String, Object> vectorDoc(String id, String title, String category, List<Double> titleVector,
                                                 List<Double> contentVector) {
        return doc("id", id, "title", title, "category", category, "titleVector", titleVector,
                "contentVector", contentVector);
    }
}
