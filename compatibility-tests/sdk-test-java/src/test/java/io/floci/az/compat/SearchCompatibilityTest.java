package io.floci.az.compat;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.MatchConditions;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.http.rest.Response;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexBatchException;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsOptions;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.IndexingResult;
import com.azure.search.documents.models.SearchMode;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedResponse;
import com.azure.search.documents.models.SearchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.az.compat.SearchTestSupport.ADMIN_KEY;
import static io.floci.az.compat.SearchTestSupport.QUERY_KEY;
import static io.floci.az.compat.SearchTestSupport.SEATTLE;
import static io.floci.az.compat.SearchTestSupport.action;
import static io.floci.az.compat.SearchTestSupport.doc;
import static io.floci.az.compat.SearchTestSupport.eventually;
import static io.floci.az.compat.SearchTestSupport.fields;
import static io.floci.az.compat.SearchTestSupport.searchKeys;
import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search MVP through {@code azure-search-documents}: index management with ETags, document batches, lookup,
 * count and the simple query syntax with filters, sorting and paging, plus query-key authorization.
 *
 * <p>Every assertion encodes behavior documented on Microsoft Learn; the URL above each test names the page.</p>
 */
@Tag("search-mvp")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search MVP Compatibility")
class SearchCompatibilityTest {

    private final String service = SearchTestSupport.serviceName("javamvp");
    private final String hotelsIndex = SearchTestSupport.name("hotels");

    private SearchIndexClient indexClient;
    private SearchClient hotels;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        indexClient = SearchTestSupport.indexClient(service, ADMIN_KEY);
        indexClient.createIndex(SearchTestSupport.hotelsIndex(hotelsIndex));
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

    // ── Index management ─────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/rest/api/searchservice/indexes/create (201 Created)
    // https://learn.microsoft.com/rest/api/searchservice/indexes/get
    // https://learn.microsoft.com/rest/api/searchservice/indexes/list
    // https://learn.microsoft.com/rest/api/searchservice/indexes/create-or-update (200 OK on update)
    // https://learn.microsoft.com/rest/api/searchservice/indexes/delete (204 No Content, 404 Not Found)
    // https://learn.microsoft.com/azure/search/search-howto-concurrency ("Every time you update a resource, its ETag
    // changes automatically")
    // https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/search/azure-search-documents/src/main/java/com/azure/search/documents/implementation/SearchIndexClientImpl.java
    // (deleteIndex is @ExpectedResponses({ 204, 404 }): deleting a missing index succeeds, and the response is a 404)
    @Test
    void indexLifecycleCreateGetListUpdateDelete() {
        String name = SearchTestSupport.name("lifecycle");
        SearchIndex created = indexClient.createIndex(new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("title", SearchFieldDataType.STRING).setSearchable(true)));
        try {
            assertEquals(name, created.getName());
            assertNotNull(created.getETag(), "a created index carries an ETag");

            SearchIndex fetched = indexClient.getIndex(name);
            assertEquals(List.of("id", "title"), fetched.getFields().stream().map(SearchField::getName).toList());
            assertEquals(Boolean.TRUE, fetched.getFields().getFirst().isKey());
            assertEquals(created.getETag(), fetched.getETag());

            List<String> names = new ArrayList<>();
            indexClient.listIndexes().forEach(index -> names.add(index.getName()));
            assertTrue(names.contains(name), "listIndexes: " + names);
            List<String> listedNames = new ArrayList<>();
            indexClient.listIndexNames().forEach(listedNames::add);
            assertTrue(listedNames.contains(name), "listIndexNames: " + listedNames);

            fetched.getFields().add(new SearchField("added", SearchFieldDataType.INT32).setFilterable(true));
            Response<SearchIndex> updated = indexClient.createOrUpdateIndexWithResponse(fetched, null);
            assertEquals(200, updated.getStatusCode());
            assertNotEquals(created.getETag(), updated.getValue().getETag(), "an update changes the ETag");
            assertTrue(indexClient.getIndex(name).getFields().stream().anyMatch(f -> "added".equals(f.getName())));
        } finally {
            indexClient.deleteIndex(name);
        }

        assertEquals(404, statusOf(() -> indexClient.getIndex(name)));
        indexClient.deleteIndex(name);
        assertEquals(404, indexClient.deleteIndexWithResponse(name, new RequestOptions()).getStatusCode());
    }

    // https://learn.microsoft.com/azure/search/search-howto-concurrency (an update whose ETag no longer matches fails)
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("412 Precondition Failed: Returned when an
    // If-Match or If-None-Match header's condition evaluates to false")
    @Test
    void staleEtagsAreRejectedWithPreconditionFailed() {
        String name = SearchTestSupport.name("etag");
        SearchIndex original = indexClient.createIndex(new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true)));
        try {
            String staleEtag = original.getETag();
            SearchIndex clientA = indexClient.getIndex(name);
            clientA.getFields().add(new SearchField("a", SearchFieldDataType.INT32));
            Response<SearchIndex> updatedByA = indexClient.createOrUpdateIndexWithResponse(clientA,
                    new RequestOptions().setHeader(HttpHeaderName.IF_MATCH, staleEtag));
            assertEquals(200, updatedByA.getStatusCode(), "the first conditional update holds the current ETag");

            SearchIndex clientB = indexClient.getIndex(name);
            clientB.getFields().add(new SearchField("b", SearchFieldDataType.BOOLEAN));
            assertEquals(412, statusOf(() -> indexClient.createOrUpdateIndexWithResponse(clientB,
                    new RequestOptions().setHeader(HttpHeaderName.IF_MATCH, staleEtag))));

            assertEquals(412, statusOf(() -> indexClient.createOrUpdateIndexWithResponse(clientB,
                    new RequestOptions().setHeader(HttpHeaderName.IF_NONE_MATCH, "*"))),
                    "If-None-Match: * only creates, so it fails for an index that exists");

            assertEquals(412, statusOf(() -> indexClient.deleteIndex(name,
                    new MatchConditions().setIfMatch(staleEtag))));
            assertTrue(indexClient.getIndex(name).getFields().stream().noneMatch(f -> "b".equals(f.getName())));
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, name);
        }
    }

    // ── Documents ────────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/rest/api/searchservice/documents/index (IndexActionType: upload replaces all fields,
    // merge updates an existing document, mergeOrUpload, delete; IndexingResult.statusCode: 200 update or delete,
    // 201 created)
    // https://learn.microsoft.com/azure/search/search-how-to-load-search-index ("Delete operations are idempotent ...
    // even if a document key doesn't exist in the index ... results in a 200 status code")
    @Test
    void uploadMergeMergeOrUploadAndDeleteActions() {
        String name = SearchTestSupport.name("actions");
        indexClient.createIndex(new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("name", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("category", SearchFieldDataType.STRING).setFilterable(true),
                new SearchField("rating", SearchFieldDataType.INT32).setFilterable(true)));
        try {
            SearchClient client = SearchTestSupport.searchClient(service, name, ADMIN_KEY);

            IndexingResult created = single(client.indexDocuments(new IndexDocumentsBatch(action(IndexActionType.UPLOAD,
                    doc("id", "a", "name", "Alpha", "category", "One", "rating", 1)))));
            assertTrue(created.isSucceeded());
            assertEquals(201, created.getStatusCode());

            IndexingResult merged = single(client.indexDocuments(new IndexDocumentsBatch(
                    action(IndexActionType.MERGE, doc("id", "a", "rating", 5)))));
            assertEquals(200, merged.getStatusCode());
            Map<String, Object> afterMerge = client.getDocument("a").getAdditionalProperties();
            assertEquals("Alpha", afterMerge.get("name"), "merge keeps the fields it does not name");
            assertEquals(5, ((Number) afterMerge.get("rating")).intValue());

            IndexDocumentsResult upserts = client.indexDocuments(new IndexDocumentsBatch(
                    action(IndexActionType.MERGE_OR_UPLOAD, doc("id", "b", "name", "Beta")),
                    action(IndexActionType.MERGE_OR_UPLOAD, doc("id", "a", "category", "Two"))));
            assertEquals(201, statusFor(upserts, "b"), "mergeOrUpload of a new key uploads it");
            assertEquals(200, statusFor(upserts, "a"), "mergeOrUpload of an existing key merges it");
            Map<String, Object> afterMergeOrUpload = client.getDocument("a").getAdditionalProperties();
            assertEquals("Alpha", afterMergeOrUpload.get("name"));
            assertEquals("Two", afterMergeOrUpload.get("category"));

            IndexingResult replaced = single(client.indexDocuments(new IndexDocumentsBatch(
                    action(IndexActionType.UPLOAD, doc("id", "a", "name", "Alpha 2")))));
            assertEquals(200, replaced.getStatusCode(), "upload of an existing key is an update");
            Map<String, Object> afterReplace = client.getDocument("a").getAdditionalProperties();
            assertEquals("Alpha 2", afterReplace.get("name"));
            assertNull(afterReplace.get("category"), "upload replaces every field, so unnamed fields become null");
            assertNull(afterReplace.get("rating"));

            IndexDocumentsResult deletes = client.indexDocuments(new IndexDocumentsBatch(
                    action(IndexActionType.DELETE, doc("id", "b")),
                    action(IndexActionType.DELETE, doc("id", "never-existed"))));
            assertEquals(200, statusFor(deletes, "b"));
            assertEquals(200, statusFor(deletes, "never-existed"), "delete is idempotent");
            assertTrue(deletes.getResults().stream().allMatch(IndexingResult::isSucceeded));

            eventually(() -> assertEquals(1L, client.getDocumentCount()));
            assertEquals(404, statusOf(() -> client.getDocument("b")));
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, name);
        }
    }

    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("207 Multi-Status: Partial success ...
    // Some documents succeeded, but at least one failed")
    // https://learn.microsoft.com/azure/search/search-how-to-load-search-index (per-document 404: "The document
    // couldn't be merged because the given key doesn't exist in the index")
    @Test
    void partialBatchFailureReturnsMultiStatusWithPerDocumentResults() {
        String name = SearchTestSupport.name("partial");
        indexClient.createIndex(new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true),
                new SearchField("name", SearchFieldDataType.STRING).setSearchable(true)));
        try {
            SearchClient client = SearchTestSupport.searchClient(service, name, ADMIN_KEY);
            IndexDocumentsBatch batch = new IndexDocumentsBatch(
                    action(IndexActionType.UPLOAD, doc("id", "ok", "name", "fine")),
                    action(IndexActionType.MERGE, doc("id", "missing", "name", "nothing to merge into")));

            Response<IndexDocumentsResult> response = client.indexDocumentsWithResponse(batch,
                    new IndexDocumentsOptions().setThrowOnAnyError(false), null);
            assertEquals(207, response.getStatusCode());
            IndexingResult ok = resultFor(response.getValue(), "ok");
            IndexingResult missing = resultFor(response.getValue(), "missing");
            assertTrue(ok.isSucceeded());
            assertEquals(201, ok.getStatusCode());
            assertFalse(missing.isSucceeded());
            assertEquals(404, missing.getStatusCode());
            assertNotNull(missing.getErrorMessage(), "a failed action explains why");

            // The SDK's default surfaces the 207 as an IndexBatchException carrying the same per-document results.
            IndexBatchException thrown = assertThrows(IndexBatchException.class, () -> client.indexDocuments(
                    new IndexDocumentsBatch(action(IndexActionType.MERGE, doc("id", "missing", "name", "x")),
                            action(IndexActionType.UPLOAD, doc("id", "ok2", "name", "fine")))));
            assertEquals(404, thrown.getIndexingResults().stream()
                    .filter(result -> "missing".equals(result.getKey())).findFirst().orElseThrow().getStatusCode());
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, name);
        }
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/get ($select: "Any field not retrieved will be
    // missing from the returned document")
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("404 Not Found: Returned when a resource
    // doesn't exist")
    @Test
    void getDocumentReturnsTheDocumentAndHonoursSelect() {
        Map<String, Object> full = fields(hotels.getDocument("3").getAdditionalProperties());
        assertEquals("City Center Hotel", full.get("name"));
        assertEquals(List.of("pool", "gym"), full.get("tags"));
        assertEquals(4, ((Number) full.get("rating")).intValue());

        Map<String, Object> selected = fields(hotels.getDocument("3", List.of("id", "name")).getAdditionalProperties());
        assertEquals(Set.of("id", "name"), selected.keySet());

        assertEquals(404, statusOf(() -> hotels.getDocument("no-such-hotel")));
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/count
    // https://learn.microsoft.com/azure/search/search-pagination-page-layout ("Count is accurate when the index is
    // stable")
    @Test
    void documentCountAndTotalCountMatchTheIndexedDocuments() {
        assertEquals(6L, hotels.getDocumentCount());
        SearchPagedResponse page = hotels.search(new SearchOptions().setSearchText("*").setIncludeTotalCount(true))
                .iterableByPage().iterator().next();
        assertEquals(6L, page.getCount());
    }

    // ── Simple query syntax ──────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (SearchMode: "any: Any of the search
    // terms must be matched ... all: All of the search terms must be matched")
    @Test
    void searchModeAnyMatchesEitherTermAndAllRequiresEvery() {
        SearchOptions any = new SearchOptions().setSearchText("pool view").setSearchMode(SearchMode.ANY)
                .setSearchFields("description");
        assertEquals(Set.of("1", "3", "6"), new HashSet<>(searchKeys(hotels, any)));

        SearchOptions all = new SearchOptions().setSearchText("pool view").setSearchMode(SearchMode.ALL)
                .setSearchFields("description");
        assertEquals(List.of("1"), searchKeys(hotels, all));
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (searchFields: "The comma-separated
    // list of field names to which to scope the full-text search")
    // https://learn.microsoft.com/azure/search/query-simple-syntax (phrase search and prefix queries)
    @Test
    void searchFieldsScopeFullTextSearch() {
        assertEquals(Set.of("2", "5"), new HashSet<>(searchKeys(hotels, new SearchOptions().setSearchText("airport"))));
        assertEquals(List.of("5"), searchKeys(hotels,
                new SearchOptions().setSearchText("airport").setSearchFields("name")));
        assertEquals(List.of("1"), searchKeys(hotels, new SearchOptions().setSearchText("\"ocean view\"")));
        assertEquals(Set.of("2", "5"), new HashSet<>(searchKeys(hotels,
                new SearchOptions().setSearchText("air*").setSearchFields("description"))));
    }

    // https://learn.microsoft.com/azure/search/search-query-odata-comparison-operators
    // https://learn.microsoft.com/azure/search/search-query-odata-logical-operators ("When a Boolean field b appears
    // by itself in a filter expression, it behaves as if it had been written b eq true")
    @Test
    void filtersWithComparisonAndLogicalOperators() {
        assertEquals(Set.of("1", "3", "6"), filtered("rating ge 4"));
        assertEquals(Set.of("2", "5"), filtered("price lt 100"));
        assertEquals(Set.of("1", "4"), filtered("category eq 'Resort'"));
        assertEquals(Set.of("1", "2", "3", "6"), filtered("rating ne 3"));
        assertEquals(Set.of("1"), filtered("rating ge 4 and parking eq true"));
        assertEquals(Set.of("2", "5", "6"), filtered("category eq 'Budget' or category eq 'Boutique'"));
        assertEquals(Set.of("2", "3", "5", "6"), filtered("not (category eq 'Resort')"));
        assertEquals(Set.of("1", "2", "4"), filtered("parking"));
    }

    // https://learn.microsoft.com/azure/search/search-query-odata-search-in-function ("search.in(variable,
    // valueList)" and "search.in(variable, valueList, delimiters)")
    @Test
    void filtersWithSearchIn() {
        assertEquals(Set.of("2", "5", "6"), filtered("search.in(category, 'Budget,Boutique')"));
        assertEquals(Set.of("2", "6"), filtered("search.in(name, 'Budget Inn|Harbor Boutique', '|')"));
    }

    // https://learn.microsoft.com/azure/search/search-query-odata-collection-operators ("An expression using any
    // returns true if the predicate is true for at least one element"; all: true for every element)
    @Test
    void filtersWithAnyAndAllOnCollections() {
        assertEquals(Set.of("1", "3"), filtered("tags/any(t: t eq 'pool')"));
        assertEquals(Set.of("3", "4", "6"), filtered("tags/all(t: t ne 'wifi')"));
        assertEquals(Set.of("1", "2", "5"), filtered("tags/any(t: search.in(t, 'wifi,shuttle'))"));
    }

    // https://learn.microsoft.com/azure/search/search-query-odata-geo-spatial-functions ("The geo.distance function
    // ... returns an Edm.Double value that is the distance between them in kilometers"; usable in $orderby)
    @Test
    void geoDistanceFiltersInKilometresAndSorts() {
        assertEquals(Set.of("1", "3", "6"), filtered("geo.distance(location, " + SEATTLE + ") le 10"));
        assertEquals(Set.of("1", "3", "6", "2", "5"), filtered("geo.distance(location, " + SEATTLE + ") lt 100"));
        assertEquals(List.of("1", "3", "6", "2", "5", "4"), searchKeys(hotels, new SearchOptions().setSearchText("*")
                .setOrderBy("geo.distance(location, " + SEATTLE + ") asc")));
    }

    // https://learn.microsoft.com/azure/search/search-query-odata-orderby (multiple sort criteria, search.score())
    // https://learn.microsoft.com/azure/search/index-similarity-and-scoring ("A @search.score = 1 indicates an
    // un-scored or un-ranked result set ... an empty search (search=*)")
    @Test
    void orderByFieldsAndSearchScore() {
        assertEquals(List.of("2", "5", "4", "6", "3", "1"), searchKeys(hotels,
                new SearchOptions().setSearchText("*").setOrderBy("price asc")));
        assertEquals(List.of("1", "3", "6", "4", "5", "2"), searchKeys(hotels, new SearchOptions().setSearchText("*")
                .setOrderBy("search.score() desc", "rating desc", "id asc")));
        assertEquals(List.of("2", "5", "4", "6", "3", "1"), searchKeys(hotels, new SearchOptions().setSearchText("*")
                .setOrderBy("rating asc", "price asc")));
        for (SearchResult result : SearchTestSupport.searchAll(hotels, new SearchOptions().setSearchText("*"))) {
            assertEquals(1.0, result.getScore(), 1e-9, "search=* is un-scored");
        }
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (select, top, skip, count: "the count
    // may be greater than the number of results in this response ... if you use the $top or $skip parameters")
    @Test
    void selectTopSkipAndIncludeTotalCount() {
        SearchOptions options = new SearchOptions().setSearchText("*").setOrderBy("id asc")
                .setSelect("id", "name").setTop(2).setSkip(1).setIncludeTotalCount(true);
        SearchPagedResponse page = hotels.search(options).iterableByPage().iterator().next();
        assertEquals(6L, page.getCount());
        List<String> ids = new ArrayList<>();
        for (SearchResult result : page.getValue()) {
            Map<String, Object> fields = fields(result.getAdditionalProperties());
            assertEquals(Set.of("id", "name"), fields.keySet());
            ids.add((String) fields.get("id"));
        }
        assertEquals(List.of("2", "3"), ids);
    }

    // https://learn.microsoft.com/azure/search/search-pagination-page-layout ("The default page size is 50 ... If the
    // number of matches exceed the page size, the response includes information to retrieve the next page")
    @Test
    void pagingFollowsContinuationBeyondFiftyResults() {
        String name = SearchTestSupport.name("paging");
        indexClient.createIndex(new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true).setSortable(true),
                new SearchField("n", SearchFieldDataType.INT32).setSortable(true).setFilterable(true)));
        try {
            SearchClient client = SearchTestSupport.searchClient(service, name, ADMIN_KEY);
            List<Map<String, Object>> documents = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                documents.add(doc("id", String.format("doc-%03d", i), "n", i));
            }
            SearchTestSupport.upload(client, documents);
            eventually(() -> assertEquals(60L, client.getDocumentCount()));

            Iterator<SearchPagedResponse> pages = client.search(new SearchOptions().setSearchText("*")
                    .setIncludeTotalCount(true)).iterableByPage().iterator();
            SearchPagedResponse first = pages.next();
            assertEquals(50, first.getValue().size(), "the default page size is 50");
            assertEquals(60L, first.getCount());
            assertTrue(pages.hasNext(), "60 matches do not fit one page");

            List<String> all = searchKeys(client, new SearchOptions().setSearchText("*"));
            assertEquals(60, all.size());
            assertEquals(60, new HashSet<>(all).size(), "pages do not repeat documents");
        } finally {
            SearchTestSupport.deleteIndexQuietly(indexClient, name);
        }
    }

    // https://learn.microsoft.com/azure/search/search-filters ("Filters apply to ... fields that are attributed as
    // filterable")
    // https://learn.microsoft.com/azure/search/search-query-odata-orderby ("The sort criteria can either be the path of
    // a sortable field" or a function)
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("400 Bad Request: Returned when there's an
    // error in the request URI, headers, or body")
    @Test
    void filteringOrSortingOnAFieldWithoutTheAttributeIsABadRequest() {
        assertEquals(400, statusOf(() -> searchKeys(hotels,
                new SearchOptions().setSearchText("*").setFilter("description eq 'x'"))));
        assertEquals(400, statusOf(() -> searchKeys(hotels,
                new SearchOptions().setSearchText("*").setOrderBy("description asc"))));
    }

    // ── API keys ─────────────────────────────────────────────────────────────────────────────────────────────

    // https://learn.microsoft.com/azure/search/search-security-api-keys (query keys: "Read-only access, scoped to the
    // documents collection of a search index"; "Any read-only operation that returns system data or object
    // definitions, such as an index definition ... requires an admin key")
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden: Returned when
    // authorization fails")
    @Test
    void queryKeyCanSearchButCannotWriteOrReadDefinitions() {
        SearchClient readOnly = SearchTestSupport.searchClient(service, hotelsIndex, QUERY_KEY);
        assertEquals(Set.of("1", "3"), new HashSet<>(searchKeys(readOnly, new SearchOptions().setSearchText("pool"))));
        assertEquals("Budget Inn", readOnly.getDocument("2").getAdditionalProperties().get("name"));

        assertEquals(403, statusOf(() -> readOnly.indexDocuments(new IndexDocumentsBatch(
                action(IndexActionType.UPLOAD, doc("id", "99", "name", "Not allowed"))))));
        SearchIndexClient readOnlyIndexes = SearchTestSupport.indexClient(service, QUERY_KEY);
        assertEquals(403, statusOf(() -> readOnlyIndexes.getIndex(hotelsIndex)));
        assertEquals(404, statusOf(() -> hotels.getDocument("99")), "the rejected upload wrote nothing");
    }

    private Set<String> filtered(String filter) {
        return new HashSet<>(searchKeys(hotels, new SearchOptions().setSearchText("*").setFilter(filter)));
    }

    private static IndexingResult single(IndexDocumentsResult result) {
        assertEquals(1, result.getResults().size());
        return result.getResults().getFirst();
    }

    private static IndexingResult resultFor(IndexDocumentsResult result, String key) {
        return result.getResults().stream().filter(r -> key.equals(r.getKey())).findFirst()
                .orElseThrow(() -> new AssertionError("no indexing result for key " + key));
    }

    private static int statusFor(IndexDocumentsResult result, String key) {
        return resultFor(result, key).getStatusCode();
    }
}
