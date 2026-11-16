package io.floci.az.compat;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.BinaryData;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexerClient;
import com.azure.search.documents.indexes.models.BlobIndexerParsingMode;
import com.azure.search.documents.indexes.models.DataSourceCredentials;
import com.azure.search.documents.indexes.models.FieldMapping;
import com.azure.search.documents.indexes.models.FieldMappingFunction;
import com.azure.search.documents.indexes.models.HighWaterMarkChangeDetectionPolicy;
import com.azure.search.documents.indexes.models.IndexerExecutionResult;
import com.azure.search.documents.indexes.models.IndexerExecutionStatus;
import com.azure.search.documents.indexes.models.IndexerStatus;
import com.azure.search.documents.indexes.models.IndexingParameters;
import com.azure.search.documents.indexes.models.IndexingParametersConfiguration;
import com.azure.search.documents.indexes.models.InputFieldMappingEntry;
import com.azure.search.documents.indexes.models.OutputFieldMappingEntry;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.SearchIndexer;
import com.azure.search.documents.indexes.models.SearchIndexerDataContainer;
import com.azure.search.documents.indexes.models.SearchIndexerDataSourceConnection;
import com.azure.search.documents.indexes.models.SearchIndexerDataSourceType;
import com.azure.search.documents.indexes.models.SearchIndexerSkillset;
import com.azure.search.documents.indexes.models.SearchIndexerStatus;
import com.azure.search.documents.indexes.models.SplitSkill;
import com.azure.search.documents.indexes.models.TextSplitMode;
import com.azure.search.documents.indexes.models.WebApiSkill;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.floci.az.compat.SearchTestSupport.ADMIN_KEY;
import static io.floci.az.compat.SearchTestSupport.doc;
import static io.floci.az.compat.SearchTestSupport.eventually;
import static io.floci.az.compat.SearchTestSupport.searchAll;
import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search indexers through {@code azure-search-documents}: a blob data source on floci-az's own Blob service
 * (JSON and text blobs written with the Blob SDK against the same emulator), indexer runs and status, field mappings,
 * change detection on re-runs, a Cosmos DB data source on floci-az's Cosmos with the {@code _ts} high-water mark, and a
 * Split skill.
 *
 * <p>The data source connection strings are the ones these tests hand the Blob and Cosmos SDKs, so they point at the
 * emulator's own endpoint: the indexer runs inside floci-az, which reaches that endpoint as the tests do.</p>
 */
@Tag("search-indexer")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search Indexer Compatibility")
class SearchIndexerCompatibilityTest {

    /** Indexers run asynchronously; Azure documents no bound, so this only caps a hung run. */
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);

    private final String service = SearchTestSupport.serviceName("javaindexer");
    private final List<String> indexers = new ArrayList<>();
    private final List<String> dataSources = new ArrayList<>();
    private final List<String> skillsets = new ArrayList<>();
    private final List<String> indexes = new ArrayList<>();
    private final List<BlobContainerClient> containers = new ArrayList<>();

    private SearchIndexClient indexClient;
    private SearchIndexerClient indexerClient;
    private BlobServiceClient blobs;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        indexClient = SearchTestSupport.indexClient(service, ADMIN_KEY);
        indexerClient = SearchTestSupport.indexerClient(service, ADMIN_KEY);
        blobs = new BlobServiceClientBuilder().connectionString(EmulatorConfig.BLOB_CONN).buildClient();
    }

    @AfterAll
    void teardown() {
        if (indexerClient != null) {
            indexers.forEach(name -> quietly(() -> indexerClient.deleteIndexer(name)));
            skillsets.forEach(name -> quietly(() -> indexerClient.deleteSkillset(name)));
            dataSources.forEach(name -> quietly(() -> indexerClient.deleteDataSourceConnection(name)));
        }
        if (indexClient != null) {
            indexes.forEach(name -> SearchTestSupport.deleteIndexQuietly(indexClient, name));
        }
        containers.forEach(container -> quietly(container::delete));
    }

    // https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-storage (type azureblob, container and
    // query "to specify any subfolders"; "An indexer runs automatically when it's created")
    // https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-json ("parsingMode": "json"; source fields
    // in JSON Pointer notation lift nested properties into top-level fields)
    // https://learn.microsoft.com/azure/search/search-indexer-field-mappings (sourceFieldName/targetFieldName, the
    // base64Encode mapping function for document keys)
    // https://learn.microsoft.com/rest/api/searchservice/indexers/get-status (lastResult, itemsProcessed, itemsFailed)
    @Test
    void jsonBlobsAreIndexedThroughFieldMappings() {
        BlobContainerClient container = container();
        uploadBlob(container, "json/a.json", article("Lighthouse restoration finished", "2016-04-13",
                List.of("coast", "history")));
        uploadBlob(container, "json/b.json", article("Harbor festival schedule", "2017-06-01", List.of("events")));
        uploadBlob(container, "notes/ignored.txt", "outside the json folder");

        String indexName = jsonIndex();
        String dataSource = blobDataSource(container, "json");
        SearchIndexerDataSourceConnection fetchedSource = indexerClient.getDataSourceConnection(dataSource);
        assertEquals(SearchIndexerDataSourceType.AZURE_BLOB, fetchedSource.getType());
        assertEquals(container.getBlobContainerName(), fetchedSource.getContainer().getName());
        assertEquals("json", fetchedSource.getContainer().getQuery());

        String indexer = createIndexer(new SearchIndexer(SearchTestSupport.name("json-indexer"), dataSource, indexName)
                .setParameters(jsonParsing())
                .setFieldMappings(jsonFieldMappings()));

        IndexerExecutionResult first = awaitRun(indexer, null);
        assertEquals(IndexerExecutionStatus.SUCCESS, first.getStatus(), describe(first));
        assertEquals(2, first.getItemCount());
        assertEquals(0, first.getFailedItemCount());
        assertEquals(IndexerStatus.RUNNING, indexerClient.getIndexerStatus(indexer).getStatus(),
                "running: the indexer is operational");

        SearchClient search = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
        List<SearchResult> results = searchAll(search, new SearchOptions().setSearchText("*"));
        Map<String, Map<String, Object>> byFile = new HashMap<>();
        results.forEach(r -> byFile.put((String) r.getAdditionalProperties().get("fileName"),
                r.getAdditionalProperties()));
        assertEquals(Set.of("a.json", "b.json"), byFile.keySet());
        Map<String, Object> a = byFile.get("a.json");
        assertEquals("Lighthouse restoration finished", a.get("text"));
        assertEquals(List.of("coast", "history"), a.get("tags"));
        assertTrue(String.valueOf(a.get("date")).startsWith("2016-04-13"), String.valueOf(a.get("date")));
        assertEquals("Harbor festival schedule", byFile.get("b.json").get("text"));
        assertEquals("Lighthouse restoration finished",
                search.getDocument((String) a.get("id")).getAdditionalProperties().get("text"),
                "the base64-encoded metadata_storage_path is a usable document key");
        assertEquals(List.of("a.json"), keysOf(searchAll(search, new SearchOptions().setSearchText("lighthouse")),
                "fileName"));

        SearchIndexer fetched = indexerClient.getIndexer(indexer);
        assertEquals(5, fetched.getFieldMappings().size());
        assertTrue(fetched.getFieldMappings().stream().anyMatch(m -> "id".equals(m.getTargetFieldName())
                && m.getMappingFunction() != null && "base64Encode".equals(m.getMappingFunction().getName())));
        List<String> names = new ArrayList<>();
        indexerClient.listIndexerNames().forEach(names::add);
        assertTrue(names.contains(indexer), names.toString());
    }

    // https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-changed-deleted ("change detection occurs
    // automatically because indexers keep track of the last update using the built-in timestamps")
    // https://learn.microsoft.com/azure/search/search-howto-run-reset-indexers ("If the underlying content is
    // unchanged, a run operation has no effect. In this case, indexer execution history indicates 0\0 documents
    // processed"; "Reset Indexers clears the high-water mark and performs a full reindex of all documents")
    // https://learn.microsoft.com/rest/api/searchservice/indexers/run (202 Accepted)
    // https://learn.microsoft.com/rest/api/searchservice/indexers/reset (204 No Content)
    @Test
    void rerunsPickUpOnlyNewAndChangedBlobsUntilAReset() {
        BlobContainerClient container = container();
        uploadBlob(container, "json/a.json", article("first", "2020-01-01", List.of("one")));
        uploadBlob(container, "json/b.json", article("second", "2020-01-02", List.of("two")));
        String indexName = jsonIndex();
        String indexer = createIndexer(new SearchIndexer(SearchTestSupport.name("rerun-indexer"),
                blobDataSource(container, "json"), indexName)
                .setParameters(jsonParsing()).setFieldMappings(jsonFieldMappings()));
        IndexerExecutionResult initial = awaitRun(indexer, null);
        assertEquals(2, initial.getItemCount(), describe(initial));

        // Blob timestamps can be second-grained; keep each change clearly after the previous run's high-water mark.
        SearchTestSupport.pause(Duration.ofMillis(1100));
        uploadBlob(container, "json/c.json", article("third", "2020-01-03", List.of("three")));
        IndexerExecutionResult added = runAndAwait(indexer, initial);
        assertEquals(IndexerExecutionStatus.SUCCESS, added.getStatus(), describe(added));
        assertEquals(1, added.getItemCount(), "only the new blob is processed");

        IndexerExecutionResult unchanged = runAndAwait(indexer, added);
        assertEquals(IndexerExecutionStatus.SUCCESS, unchanged.getStatus(), describe(unchanged));
        assertEquals(0, unchanged.getItemCount(), "nothing changed since the last run");

        SearchTestSupport.pause(Duration.ofMillis(1100));
        uploadBlob(container, "json/b.json", article("second, revised", "2020-01-02", List.of("two")));
        IndexerExecutionResult changed = runAndAwait(indexer, unchanged);
        assertEquals(IndexerExecutionStatus.SUCCESS, changed.getStatus(), describe(changed));
        assertEquals(1, changed.getItemCount(), "only the overwritten blob is processed");

        SearchClient search = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
        eventually(() -> assertEquals(3L, search.getDocumentCount()));
        assertEquals(List.of("b.json"), keysOf(searchAll(search, new SearchOptions().setSearchText("revised")),
                "fileName"));

        assertEquals(204, indexerClient.resetIndexerWithResponse(indexer, null).getStatusCode());
        IndexerExecutionResult full = runAndAwait(indexer, changed);
        assertEquals(IndexerExecutionStatus.SUCCESS, full.getStatus(), describe(full));
        assertEquals(3, full.getItemCount(), "a reset reprocesses every blob");

        List<IndexerExecutionResult> history = indexerClient.getIndexerStatus(indexer).getExecutionHistory();
        assertTrue(history.stream().filter(run -> run.getStatus() == IndexerExecutionStatus.SUCCESS).count() >= 5,
                "the five runs are in the execution history: " + history.size());
    }
    // https://learn.microsoft.com/azure/search/search-how-to-index-azure-blob-storage (content and metadata properties
    // map to "similarly named and typed fields"; metadata_storage_name "is the file name of the blob";
    // indexedFileNameExtensions)
    // https://learn.microsoft.com/azure/search/search-indexer-field-mappings ("The blob storage indexer automatically
    // adds a field mapping from metadata_storage_path ... to the index key field if no field mapping is specified")
    @Test
    void textBlobsAreIndexedIntoContentWithTheDefaultKeyMapping() {
        BlobContainerClient container = container();
        uploadBlob(container, "notes/c.txt", "The lighthouse keeper wrote in the log every night.");
        uploadBlob(container, "notes/d.txt", "Tide tables for the harbor are posted daily.");
        uploadBlob(container, "notes/skip.json", article("not text", "2020-01-01", List.of()));

        String indexName = track(indexes, SearchTestSupport.name("blobs-text"));
        indexClient.createIndex(new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("content", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("metadata_storage_name", SearchFieldDataType.STRING).setFilterable(true)
                        .setSortable(true)));
        String indexer = createIndexer(new SearchIndexer(SearchTestSupport.name("text-indexer"),
                blobDataSource(container, null), indexName)
                .setParameters(new IndexingParameters().setConfiguration(new IndexingParametersConfiguration()
                        .setIndexedFileNameExtensions(".txt"))));

        IndexerExecutionResult run = awaitRun(indexer, null);
        assertEquals(IndexerExecutionStatus.SUCCESS, run.getStatus(), describe(run));
        assertEquals(2, run.getItemCount());

        SearchClient search = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
        assertEquals(Set.of("c.txt", "d.txt"), new HashSet<>(keysOf(searchAll(search,
                new SearchOptions().setSearchText("*")), "metadata_storage_name")));
        assertEquals(List.of("c.txt"), keysOf(searchAll(search, new SearchOptions().setSearchText("lighthouse")),
                "metadata_storage_name"));
        for (SearchResult result : searchAll(search, new SearchOptions().setSearchText("*"))) {
            String key = (String) result.getAdditionalProperties().get("id");
            assertNotNull(key, "the key is populated without a field mapping");
            assertEquals(result.getAdditionalProperties().get("metadata_storage_name"),
                    search.getDocument(key).getAdditionalProperties().get("metadata_storage_name"));
        }
    }

    // https://learn.microsoft.com/azure/search/search-howto-index-cosmosdb (type cosmosdb, "AccountEndpoint=...;
    // AccountKey=...;Database=..." connection string, container name, "Change detection is supported by default through
    // a _ts field" with a HighWaterMarkChangeDetectionPolicy on _ts)
    // The guide asks for an endpoint without a port; floci-az's Cosmos endpoint necessarily has one.
    @Test
    void cosmosDbDataSourceIndexesItemsAndPicksUpChangesThroughTheTimestamp() {
        CosmosClient cosmos = EmulatorConfig.buildCosmosClient();
        String databaseId = "searchdb" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        cosmos.createDatabase(databaseId);
        CosmosDatabase database = cosmos.getDatabase(databaseId);
        try {
            database.createContainerIfNotExists("items", "/pk");
            CosmosContainer items = database.getContainer("items");
            items.createItem(doc("id", "c1", "pk", "p", "name", "first item"), new PartitionKey("p"),
                    new CosmosItemRequestOptions());
            items.createItem(doc("id", "c2", "pk", "p", "name", "second item"), new PartitionKey("p"),
                    new CosmosItemRequestOptions());

            String indexName = track(indexes, SearchTestSupport.name("cosmos"));
            indexClient.createIndex(new SearchIndex(indexName,
                    new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                    new SearchField("name", SearchFieldDataType.STRING).setSearchable(true)));
            String connection = "AccountEndpoint=" + EmulatorConfig.httpBase() + "/" + EmulatorConfig.ACCOUNT
                    + "-cosmos;AccountKey=" + EmulatorConfig.COSMOS_KEY + ";Database=" + databaseId;
            String dataSource = track(dataSources, SearchTestSupport.name("cosmos-ds"));
            indexerClient.createDataSourceConnection(new SearchIndexerDataSourceConnection(dataSource,
                    SearchIndexerDataSourceType.COSMOS_DB, new DataSourceCredentials().setConnectionString(connection),
                    new SearchIndexerDataContainer("items"))
                    .setDataChangeDetectionPolicy(new HighWaterMarkChangeDetectionPolicy("_ts")));
            assertEquals("_ts", ((HighWaterMarkChangeDetectionPolicy) indexerClient
                    .getDataSourceConnection(dataSource).getDataChangeDetectionPolicy()).getHighWaterMarkColumnName());

            String indexer = createIndexer(new SearchIndexer(SearchTestSupport.name("cosmos-indexer"), dataSource,
                    indexName));
            IndexerExecutionResult first = awaitRun(indexer, null);
            assertEquals(IndexerExecutionStatus.SUCCESS, first.getStatus(), describe(first));
            assertEquals(2, first.getItemCount());

            SearchClient search = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
            eventually(() -> assertEquals(Set.of("c1", "c2"),
                    new HashSet<>(SearchTestSupport.searchKeys(search, new SearchOptions().setSearchText("*")))));

            // _ts has one-second resolution; step past the high-water mark of the first run.
            SearchTestSupport.pause(Duration.ofMillis(1100));
            items.upsertItem(doc("id", "c1", "pk", "p", "name", "first item renamed"));
            items.createItem(doc("id", "c3", "pk", "p", "name", "third item"), new PartitionKey("p"),
                    new CosmosItemRequestOptions());
            IndexerExecutionResult second = runAndAwait(indexer, first);
            assertEquals(IndexerExecutionStatus.SUCCESS, second.getStatus(), describe(second));
            assertTrue(second.getItemCount() >= 2, "the changed and the new item: " + second.getItemCount());

            eventually(() -> assertEquals(3L, search.getDocumentCount()));
            assertEquals("first item renamed", search.getDocument("c1").getAdditionalProperties().get("name"));
            assertEquals(List.of("c3"), SearchTestSupport.searchKeys(search, new SearchOptions().setSearchText("third")));
        } finally {
            quietly(database::delete);
            cosmos.close();
        }
    }

    // https://learn.microsoft.com/azure/search/cognitive-search-skill-textsplit (textSplitMode sentences: "a string that
    // terminates at sentence-ending punctuation, such as a period, question mark, or exclamation point"; output
    // textItems with a targetName)
    // https://learn.microsoft.com/azure/search/cognitive-search-output-field-mapping (outputFieldMappings send enriched
    // nodes such as /document/pages to index fields)
    @Test
    void splitSkillChunksBlobContentIntoSentences() {
        BlobContainerClient container = container();
        uploadBlob(container, "text/story.txt", "First sentence here. Second sentence follows? Third sentence ends!");

        String indexName = track(indexes, SearchTestSupport.name("split"));
        indexClient.createIndex(new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("content", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("sentences", SearchFieldDataType.collection(SearchFieldDataType.STRING))
                        .setSearchable(true)));
        String skillset = track(skillsets, SearchTestSupport.name("split-skills"));
        indexerClient.createSkillset(new SearchIndexerSkillset(skillset,
                new SplitSkill(List.of(new InputFieldMappingEntry("text").setSource("/document/content")),
                        List.of(new OutputFieldMappingEntry("textItems").setTargetName("sentences")))
                        .setTextSplitMode(TextSplitMode.SENTENCES)
                        .setContext("/document")));
        assertEquals(1, indexerClient.getSkillset(skillset).getSkills().size());

        String indexer = createIndexer(new SearchIndexer(SearchTestSupport.name("split-indexer"),
                blobDataSource(container, "text"), indexName)
                .setSkillsetName(skillset)
                .setOutputFieldMappings(new FieldMapping("/document/sentences").setTargetFieldName("sentences")));
        IndexerExecutionResult run = awaitRun(indexer, null);
        assertEquals(IndexerExecutionStatus.SUCCESS, run.getStatus(), describe(run));

        SearchClient search = SearchTestSupport.searchClient(service, indexName, ADMIN_KEY);
        List<SearchResult> results = searchAll(search, new SearchOptions().setSearchText("*"));
        assertEquals(1, results.size());
        @SuppressWarnings("unchecked")
        List<String> sentences = (List<String>) results.getFirst().getAdditionalProperties().get("sentences");
        assertEquals(3, sentences.size(), String.valueOf(sentences));
        assertTrue(sentences.get(0).trim().startsWith("First"), sentences.toString());
        assertTrue(sentences.get(1).trim().startsWith("Second"), sentences.toString());
        assertTrue(sentences.get(2).trim().startsWith("Third"), sentences.toString());
    }

    // https://learn.microsoft.com/azure/search/cognitive-search-custom-skill-web-api (uri: "Only the https URI scheme is
    // allowed")
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("400 Bad Request")
    @Test
    void webApiSkillRejectsAPlainHttpUri() {
        String skillset = SearchTestSupport.name("webapi-skills");
        WebApiSkill skill = new WebApiSkill(
                List.of(new InputFieldMappingEntry("text").setSource("/document/content")),
                List.of(new OutputFieldMappingEntry("label").setTargetName("label")),
                "http://example.invalid/api/label")
                .setHttpMethod("POST")
                .setContext("/document");
        int status = statusOf(() -> indexerClient.createSkillset(new SearchIndexerSkillset(skillset, skill)));
        if (status != 400) {
            quietly(() -> indexerClient.deleteSkillset(skillset));
        }
        assertEquals(400, status);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private BlobContainerClient container() {
        BlobContainerClient container = blobs.createBlobContainer(
                "search-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        containers.add(container);
        return container;
    }

    private static void uploadBlob(BlobContainerClient container, String name, String content) {
        container.getBlobClient(name).upload(BinaryData.fromString(content), true);
    }

    private static String article(String text, String datePublished, List<String> tags) {
        return BinaryData.fromObject(Map.of("article", Map.of("text", text, "datePublished", datePublished,
                "tags", tags))).toString();
    }

    private String jsonIndex() {
        String indexName = track(indexes, SearchTestSupport.name("blobs-json"));
        indexClient.createIndex(new SearchIndex(indexName,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("text", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("date", SearchFieldDataType.DATE_TIME_OFFSET).setFilterable(true).setSortable(true),
                new SearchField("tags", SearchFieldDataType.collection(SearchFieldDataType.STRING))
                        .setFilterable(true),
                new SearchField("fileName", SearchFieldDataType.STRING).setFilterable(true).setSortable(true)));
        return indexName;
    }

    private String blobDataSource(BlobContainerClient container, String folder) {
        String name = track(dataSources, SearchTestSupport.name("blob-ds"));
        SearchIndexerDataContainer dataContainer = new SearchIndexerDataContainer(container.getBlobContainerName());
        if (folder != null) {
            dataContainer.setQuery(folder);
        }
        indexerClient.createDataSourceConnection(new SearchIndexerDataSourceConnection(name,
                SearchIndexerDataSourceType.AZURE_BLOB,
                new DataSourceCredentials().setConnectionString(EmulatorConfig.BLOB_CONN), dataContainer));
        return name;
    }

    private static IndexingParameters jsonParsing() {
        return new IndexingParameters().setConfiguration(new IndexingParametersConfiguration()
                .setParsingMode(BlobIndexerParsingMode.JSON));
    }

    private static List<FieldMapping> jsonFieldMappings() {
        return List.of(
                new FieldMapping("metadata_storage_path").setTargetFieldName("id")
                        .setMappingFunction(new FieldMappingFunction("base64Encode")),
                new FieldMapping("metadata_storage_name").setTargetFieldName("fileName"),
                new FieldMapping("/article/text").setTargetFieldName("text"),
                new FieldMapping("/article/datePublished").setTargetFieldName("date"),
                new FieldMapping("/article/tags").setTargetFieldName("tags"));
    }

    private String createIndexer(SearchIndexer indexer) {
        indexerClient.createIndexer(indexer);
        return track(indexers, indexer.getName());
    }

    /**
     * Waits for a finished run newer than {@code previous} (any finished run when null) and returns its result. A run
     * is newer when its start time differs from the previous run's or the execution history has grown past
     * {@code previousRuns}. Only a run that ended counts: not {@code inProgress}, and not the {@code reset} entry a
     * reset leaves behind.
     */
    private IndexerExecutionResult awaitRun(String indexer, IndexerExecutionResult previous, int previousRuns) {
        OffsetDateTime previousStart = previous == null ? null : previous.getStartTime();
        SearchIndexerStatus status = SearchTestSupport.poll(() -> indexerClient.getIndexerStatus(indexer),
                s -> finishedAfter(s, previous == null, previousStart, previousRuns), RUN_TIMEOUT);
        assertTrue(finishedAfter(status, previous == null, previousStart, previousRuns),
                "indexer " + indexer + " did not finish a new run: " + describe(status.getLastResult()));
        return status.getLastResult();
    }

    private IndexerExecutionResult awaitRun(String indexer, IndexerExecutionResult previous) {
        return awaitRun(indexer, previous, 0);
    }

    /** Runs the indexer on demand (202 Accepted) and waits for that run to finish. */
    private IndexerExecutionResult runAndAwait(String indexer, IndexerExecutionResult previous) {
        int runsBefore = historySize(indexer);
        assertEquals(202, indexerClient.runIndexerWithResponse(indexer, null).getStatusCode());
        return awaitRun(indexer, previous, runsBefore);
    }

    private int historySize(String indexer) {
        List<IndexerExecutionResult> history = indexerClient.getIndexerStatus(indexer).getExecutionHistory();
        return history == null ? 0 : history.size();
    }

    private static boolean finishedAfter(SearchIndexerStatus status, boolean first, OffsetDateTime previousStart,
                                         int previousRuns) {
        IndexerExecutionResult last = status.getLastResult();
        if (last == null || last.getStatus() == IndexerExecutionStatus.IN_PROGRESS
                || last.getStatus() == IndexerExecutionStatus.RESET) {
            return false;
        }
        int runs = status.getExecutionHistory() == null ? 0 : status.getExecutionHistory().size();
        return first || !Objects.equals(last.getStartTime(), previousStart) || runs > previousRuns;
    }

    private static String describe(IndexerExecutionResult result) {
        if (result == null) {
            return "no run yet";
        }
        return "status=" + result.getStatus() + ", items=" + result.getItemCount() + ", failed="
                + result.getFailedItemCount() + ", error=" + result.getErrorMessage() + ", errors="
                + (result.getErrors() == null ? "[]" : result.getErrors().stream()
                .map(e -> e.getKey() + ": " + e.getErrorMessage()).toList());
    }

    private static List<String> keysOf(List<SearchResult> results, String field) {
        return SearchTestSupport.keys(results, field);
    }

    private static String track(List<String> names, String name) {
        names.add(name);
        return name;
    }

    private static void quietly(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (HttpResponseException | CosmosException ignored) {
            // Cleanup only: the resource may already be gone, or the test failed before creating it.
        }
    }
}
