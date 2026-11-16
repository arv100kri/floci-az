using Azure;
using Azure.Core.Pipeline;
using Azure.Search.Documents;
using Azure.Search.Documents.Indexes;
using Azure.Search.Documents.Indexes.Models;
using Azure.Search.Documents.Models;

namespace FlociAz.Compatibility;

// Azure AI Search MVP through Azure.Search.Documents: index management with ETags, document batches with a 207
// partial failure, lookup with select, count, simple queries with filters, sorting and paging, and query-key
// authorization. Every assertion encodes behavior documented on Microsoft Learn; the URL above each test names the
// page.
//
// The .NET SDK refuses a non-HTTPS endpoint, so these tests use the HTTPS listener floci-az serves on the same port
// (compat sessions start it with FLOCI_AZ_TLS_ENABLED=true) through a transport that accepts its self-signed
// certificate. The data plane is path style, /{service}-search, and a data-plane request provisions the service
// with floci-az's development keys.
public sealed class SearchCompatibilityTests
{
    private const string AdminKey = "FlociAzSearchDevPrimaryAdminKey0000000000000000000000";
    private const string QueryKey = "FlociAzSearchDevQueryKey000000000000000000000000000000";
    private const string Seattle = "geography'POINT(-122.335 47.608)'";

    private static readonly Uri EmulatorEndpoint = new(
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    // Letters and digits only, so the -search path suffix stays unambiguous.
    private static readonly string Service = "dotnetmvp" + Guid.NewGuid().ToString("N")[..10];

    // https://learn.microsoft.com/rest/api/searchservice/indexes/create (201 Created)
    // https://learn.microsoft.com/rest/api/searchservice/indexes/create-or-update (200 OK on update)
    // https://learn.microsoft.com/rest/api/searchservice/indexes/delete (204 No Content, 404 Not Found)
    // https://learn.microsoft.com/azure/search/search-howto-concurrency ("Every time you update a resource, its ETag
    // changes automatically"; an update whose ETag no longer matches fails)
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("412 Precondition Failed: Returned when an
    // If-Match or If-None-Match header's condition evaluates to false")
    // https://github.com/Azure/azure-sdk-for-net/blob/Azure.Search.Documents_12.0.0/sdk/search/Azure.Search.Documents/src/Generated/SearchIndexClient.RestClient.cs
    // (12.0.0 builds Delete Index with PipelineMessageClassifier204, so a 404 throws RequestFailedException; the Java,
    // Python and JS SDKs, and later .NET sources, classify a 404 as success)
    [Test]
    [Timeout(60_000)]
    public async Task IndexLifecycleWithETags(CancellationToken cancellationToken)
    {
        SearchIndexClient indexes = IndexClient(AdminKey);
        string name = UniqueName("lifecycle");
        SearchIndex created = (await indexes.CreateIndexAsync(new SearchIndex(name)
        {
            Fields =
            {
                new SimpleField("id", SearchFieldDataType.String) { IsKey = true },
                new SearchableField("title"),
            },
        }, cancellationToken)).Value;
        try
        {
            await Assert.That(created.Name).IsEqualTo(name);
            await Assert.That(created.ETag.HasValue).IsTrue();

            SearchIndex fetched = (await indexes.GetIndexAsync(name, cancellationToken)).Value;
            await Assert.That(string.Join(",", fetched.Fields.Select(f => f.Name))).IsEqualTo("id,title");
            await Assert.That(fetched.ETag).IsEqualTo(created.ETag);

            var names = new List<string>();
            await foreach (string indexName in indexes.GetIndexNamesAsync(cancellationToken))
            {
                names.Add(indexName);
            }
            await Assert.That(names.Contains(name)).IsTrue();

            fetched.Fields.Add(new SimpleField("added", SearchFieldDataType.Int32) { IsFilterable = true });
            SearchIndex updated = (await indexes.CreateOrUpdateIndexAsync(fetched, cancellationToken: cancellationToken)).Value;
            await Assert.That(updated.ETag).IsNotEqualTo(created.ETag);

            SearchIndex stale = (await indexes.GetIndexAsync(name, cancellationToken)).Value;
            stale.Fields.Add(new SimpleField("b", SearchFieldDataType.Boolean));
            stale.ETag = created.ETag;
            await Assert.That(await StatusOf(() => indexes.CreateOrUpdateIndexAsync(
                stale, onlyIfUnchanged: true, cancellationToken: cancellationToken))).IsEqualTo(412);
            await Assert.That(await StatusOf(() => indexes.DeleteIndexAsync(
                created, onlyIfUnchanged: true, cancellationToken))).IsEqualTo(412);
        }
        finally
        {
            await indexes.DeleteIndexAsync(name, cancellationToken);
        }
        await Assert.That(await StatusOf(() => indexes.GetIndexAsync(name, cancellationToken))).IsEqualTo(404);
        await Assert.That(await StatusOf(() => indexes.DeleteIndexAsync(name, cancellationToken))).IsEqualTo(404);
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/index (upload replaces all fields, merge updates an
    // existing document, mergeOrUpload, delete; statusCode 200 update or delete, 201 created)
    // https://learn.microsoft.com/azure/search/search-how-to-load-search-index ("Delete operations are idempotent";
    // per-document 404 for a merge of a key that doesn't exist)
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("207 Multi-Status")
    [Test]
    [Timeout(60_000)]
    public async Task DocumentActionsAndPartialFailure(CancellationToken cancellationToken)
    {
        SearchIndexClient indexes = IndexClient(AdminKey);
        string name = UniqueName("actions");
        await indexes.CreateIndexAsync(new SearchIndex(name)
        {
            Fields =
            {
                new SimpleField("id", SearchFieldDataType.String) { IsKey = true },
                new SearchableField("name"),
                new SimpleField("category", SearchFieldDataType.String) { IsFilterable = true },
                new SimpleField("rating", SearchFieldDataType.Int32) { IsFilterable = true },
            },
        }, cancellationToken);
        try
        {
            SearchClient client = SearchClient(name, AdminKey);
            IndexDocumentsResult created = (await client.UploadDocumentsAsync(
                [new SearchDocument { ["id"] = "a", ["name"] = "Alpha", ["category"] = "One", ["rating"] = 1 }],
                cancellationToken: cancellationToken)).Value;
            await Assert.That(created.Results[0].Status).IsEqualTo(201);

            IndexDocumentsResult merged = (await client.MergeDocumentsAsync(
                [new SearchDocument { ["id"] = "a", ["rating"] = 5 }], cancellationToken: cancellationToken)).Value;
            await Assert.That(merged.Results[0].Status).IsEqualTo(200);
            SearchDocument afterMerge = (await client.GetDocumentAsync<SearchDocument>("a", cancellationToken: cancellationToken)).Value;
            await Assert.That(afterMerge.GetString("name")).IsEqualTo("Alpha");
            await Assert.That(afterMerge.GetInt32("rating")).IsEqualTo(5);

            IndexDocumentsResult upserts = (await client.MergeOrUploadDocumentsAsync(
                [new SearchDocument { ["id"] = "b", ["name"] = "Beta" }, new SearchDocument { ["id"] = "a", ["category"] = "Two" }],
                cancellationToken: cancellationToken)).Value;
            await Assert.That(StatusFor(upserts, "b")).IsEqualTo(201);
            await Assert.That(StatusFor(upserts, "a")).IsEqualTo(200);

            IndexDocumentsResult replaced = (await client.UploadDocumentsAsync(
                [new SearchDocument { ["id"] = "a", ["name"] = "Alpha 2" }], cancellationToken: cancellationToken)).Value;
            await Assert.That(replaced.Results[0].Status).IsEqualTo(200);
            SearchDocument afterReplace = (await client.GetDocumentAsync<SearchDocument>("a", cancellationToken: cancellationToken)).Value;
            await Assert.That(afterReplace.TryGetValue("category", out object? category) ? category : null).IsNull();

            IndexDocumentsResult deletes = (await client.DeleteDocumentsAsync("id", ["b", "never-existed"],
                cancellationToken: cancellationToken)).Value;
            await Assert.That(StatusFor(deletes, "b")).IsEqualTo(200);
            await Assert.That(StatusFor(deletes, "never-existed")).IsEqualTo(200);

            IndexDocumentsBatch<SearchDocument> batch = IndexDocumentsBatch.Create(
                IndexDocumentsAction.Upload(new SearchDocument { ["id"] = "ok", ["name"] = "fine" }),
                IndexDocumentsAction.Merge(new SearchDocument { ["id"] = "missing", ["name"] = "nothing to merge into" }));
            Response<IndexDocumentsResult> partial = await client.IndexDocumentsAsync(batch,
                new IndexDocumentsOptions { ThrowOnAnyError = false }, cancellationToken);
            await Assert.That(partial.GetRawResponse().Status).IsEqualTo(207);
            IndexingResult ok = partial.Value.Results.Single(r => r.Key == "ok");
            IndexingResult missing = partial.Value.Results.Single(r => r.Key == "missing");
            await Assert.That(ok.Succeeded).IsTrue();
            await Assert.That(ok.Status).IsEqualTo(201);
            await Assert.That(missing.Succeeded).IsFalse();
            await Assert.That(missing.Status).IsEqualTo(404);
            await Assert.That(string.IsNullOrEmpty(missing.ErrorMessage)).IsFalse();
        }
        finally
        {
            await DeleteIndexQuietly(indexes, name, cancellationToken);
        }
    }

    // https://learn.microsoft.com/rest/api/searchservice/documents/get ($select: "Any field not retrieved will be
    // missing from the returned document")
    // https://learn.microsoft.com/rest/api/searchservice/documents/count
    // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (searchMode, searchFields, select, top,
    // skip, count)
    // https://learn.microsoft.com/azure/search/search-query-odata-filter, -comparison-operators, -logical-operators,
    // -search-in-function, -collection-operators, -geo-spatial-functions (distance in kilometers)
    // https://learn.microsoft.com/azure/search/search-query-odata-orderby and
    // https://learn.microsoft.com/azure/search/index-similarity-and-scoring (search=* is un-scored, so
    // search.score() desc, rating desc orders by rating)
    [Test]
    [Timeout(90_000)]
    public async Task SimpleQueriesFiltersSortingAndPaging(CancellationToken cancellationToken)
    {
        SearchIndexClient indexes = IndexClient(AdminKey);
        string name = UniqueName("hotels");
        await indexes.CreateIndexAsync(HotelsIndex(name), cancellationToken);
        try
        {
            SearchClient hotels = SearchClient(name, AdminKey);
            await hotels.UploadDocumentsAsync(Hotels(), cancellationToken: cancellationToken);
            await Eventually(async () => await hotels.GetDocumentCountAsync(cancellationToken) == 6, cancellationToken);

            SearchDocument selected = (await hotels.GetDocumentAsync<SearchDocument>("3",
                new GetDocumentOptions { SelectedFields = { "id", "name" } }, cancellationToken)).Value;
            await Assert.That(string.Join(",", selected.Keys.Where(k => !k.StartsWith('@')).Order())).IsEqualTo("id,name");
            await Assert.That(await StatusOf(() => hotels.GetDocumentAsync<SearchDocument>("no-such-hotel",
                cancellationToken: cancellationToken))).IsEqualTo(404);

            await Assert.That(await Keys(hotels, "pool view", new SearchOptions
            {
                SearchMode = SearchMode.Any, SearchFields = { "description" },
            }, sorted: true, cancellationToken)).IsEqualTo("1,3,6");
            await Assert.That(await Keys(hotels, "pool view", new SearchOptions
            {
                SearchMode = SearchMode.All, SearchFields = { "description" },
            }, sorted: false, cancellationToken)).IsEqualTo("1");
            await Assert.That(await Keys(hotels, "airport", new SearchOptions { SearchFields = { "name" } },
                sorted: false, cancellationToken)).IsEqualTo("5");

            foreach ((string filter, string expected) in new[]
                     {
                         ("rating ge 4 and parking eq true", "1"),
                         ("not (category eq 'Resort')", "2,3,5,6"),
                         ("search.in(category, 'Budget,Boutique')", "2,5,6"),
                         ("tags/any(t: t eq 'pool')", "1,3"),
                         ("tags/all(t: t ne 'wifi')", "3,4,6"),
                         ($"geo.distance(location, {Seattle}) le 10", "1,3,6"),
                     })
            {
                await Assert.That(await Keys(hotels, "*", new SearchOptions { Filter = filter }, sorted: true,
                    cancellationToken)).IsEqualTo(expected);
            }

            await Assert.That(await Keys(hotels, "*", new SearchOptions
            {
                OrderBy = { "search.score() desc", "rating desc", "id asc" },
            }, sorted: false, cancellationToken)).IsEqualTo("1,3,6,4,5,2");
            await Assert.That(await Keys(hotels, "*", new SearchOptions
            {
                OrderBy = { $"geo.distance(location, {Seattle}) asc" },
            }, sorted: false, cancellationToken)).IsEqualTo("1,3,6,2,5,4");

            SearchResults<SearchDocument> page = (await hotels.SearchAsync<SearchDocument>("*", new SearchOptions
            {
                OrderBy = { "id asc" }, Select = { "id", "name" }, Size = 2, Skip = 1, IncludeTotalCount = true,
            }, cancellationToken)).Value;
            await Assert.That(page.TotalCount).IsEqualTo(6L);
            var ids = new List<string>();
            await foreach (SearchResult<SearchDocument> result in page.GetResultsAsync())
            {
                ids.Add(result.Document.GetString("id"));
                await Assert.That(string.Join(",", result.Document.Keys.Where(k => !k.StartsWith('@')).Order()))
                    .IsEqualTo("id,name");
            }
            await Assert.That(string.Join(",", ids)).IsEqualTo("2,3");
        }
        finally
        {
            await DeleteIndexQuietly(indexes, name, cancellationToken);
        }
    }

    // https://learn.microsoft.com/azure/search/search-pagination-page-layout ("The default page size is 50 ... If the
    // number of matches exceed the page size, the response includes information to retrieve the next page")
    [Test]
    [Timeout(90_000)]
    public async Task PagingFollowsTheContinuationPastFiftyResults(CancellationToken cancellationToken)
    {
        SearchIndexClient indexes = IndexClient(AdminKey);
        string name = UniqueName("paging");
        await indexes.CreateIndexAsync(new SearchIndex(name)
        {
            Fields =
            {
                new SimpleField("id", SearchFieldDataType.String) { IsKey = true, IsSortable = true },
                new SimpleField("n", SearchFieldDataType.Int32) { IsSortable = true, IsFilterable = true },
            },
        }, cancellationToken);
        try
        {
            SearchClient client = SearchClient(name, AdminKey);
            await client.UploadDocumentsAsync(Enumerable.Range(0, 60)
                .Select(i => new SearchDocument { ["id"] = $"doc-{i:000}", ["n"] = i }), cancellationToken: cancellationToken);
            await Eventually(async () => await client.GetDocumentCountAsync(cancellationToken) == 60, cancellationToken);

            SearchResults<SearchDocument> results = (await client.SearchAsync<SearchDocument>("*",
                new SearchOptions { IncludeTotalCount = true }, cancellationToken)).Value;
            await Assert.That(results.TotalCount).IsEqualTo(60L);
            var pageSizes = new List<int>();
            var keys = new HashSet<string>();
            await foreach (Page<SearchResult<SearchDocument>> page in results.GetResultsAsync().AsPages())
            {
                pageSizes.Add(page.Values.Count);
                foreach (SearchResult<SearchDocument> result in page.Values)
                {
                    keys.Add(result.Document.GetString("id"));
                }
            }
            await Assert.That(pageSizes[0]).IsEqualTo(50);
            await Assert.That(pageSizes.Sum()).IsEqualTo(60);
            await Assert.That(keys.Count).IsEqualTo(60);
        }
        finally
        {
            await DeleteIndexQuietly(indexes, name, cancellationToken);
        }
    }

    // https://learn.microsoft.com/azure/search/search-security-api-keys (query keys: "Read-only access, scoped to the
    // documents collection of a search index"; index definitions require an admin key)
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden: Returned when
    // authorization fails")
    [Test]
    [Timeout(60_000)]
    public async Task QueryKeyCanSearchButCannotWrite(CancellationToken cancellationToken)
    {
        SearchIndexClient indexes = IndexClient(AdminKey);
        string name = UniqueName("keys");
        await indexes.CreateIndexAsync(HotelsIndex(name), cancellationToken);
        try
        {
            await SearchClient(name, AdminKey).UploadDocumentsAsync(Hotels(), cancellationToken: cancellationToken);
            SearchClient readOnly = SearchClient(name, QueryKey);
            await Eventually(async () => await Keys(readOnly, "pool", new SearchOptions(), sorted: true,
                cancellationToken) == "1,3", cancellationToken);

            await Assert.That(await StatusOf(() => readOnly.UploadDocumentsAsync(
                [new SearchDocument { ["id"] = "99", ["name"] = "Not allowed" }],
                cancellationToken: cancellationToken))).IsEqualTo(403);
            await Assert.That(await StatusOf(() => IndexClient(QueryKey).GetIndexAsync(name, cancellationToken)))
                .IsEqualTo(403);
        }
        finally
        {
            await DeleteIndexQuietly(indexes, name, cancellationToken);
        }
    }

    private static Uri SearchEndpoint() => new UriBuilder(EmulatorEndpoint)
    {
        Scheme = Uri.UriSchemeHttps,
        Port = EmulatorEndpoint.Port,
        Path = $"/{Service}-search",
    }.Uri;

    private static SearchClientOptions Options() => new()
    {
        // The emulator's certificate is self-signed; trust it for this test only.
        Transport = new HttpClientTransport(new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = HttpClientHandler.DangerousAcceptAnyServerCertificateValidator,
        }),
    };

    private static SearchIndexClient IndexClient(string key) =>
        new(SearchEndpoint(), new AzureKeyCredential(key), Options());

    private static SearchClient SearchClient(string index, string key) =>
        new(SearchEndpoint(), index, new AzureKeyCredential(key), Options());

    private static string UniqueName(string prefix) => $"{prefix}-{Guid.NewGuid():N}"[..(prefix.Length + 11)];

    private static int StatusFor(IndexDocumentsResult result, string key) =>
        result.Results.Single(r => r.Key == key).Status;

    private static async Task<int> StatusOf(Func<Task> call)
    {
        try
        {
            await call();
        }
        catch (RequestFailedException error)
        {
            return error.Status;
        }
        throw new InvalidOperationException("expected the call to fail");
    }

    private static async Task<string> Keys(SearchClient client, string text, SearchOptions options, bool sorted,
        CancellationToken cancellationToken)
    {
        SearchResults<SearchDocument> results = (await client.SearchAsync<SearchDocument>(text, options, cancellationToken)).Value;
        var keys = new List<string>();
        await foreach (SearchResult<SearchDocument> result in results.GetResultsAsync())
        {
            keys.Add(result.Document.GetString("id"));
        }
        return string.Join(",", sorted ? keys.Order() : keys);
    }

    private static async Task Eventually(Func<Task<bool>> condition, CancellationToken cancellationToken)
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(15);
        while (!await condition())
        {
            if (DateTime.UtcNow > deadline)
            {
                throw new TimeoutException("the condition did not hold within 15 seconds");
            }
            await Task.Delay(250, cancellationToken);
        }
    }

    private static async Task DeleteIndexQuietly(SearchIndexClient indexes, string name, CancellationToken cancellationToken)
    {
        try
        {
            await indexes.DeleteIndexAsync(name, cancellationToken);
        }
        catch (RequestFailedException)
        {
            // Cleanup only: the index may already be gone, or the test failed before creating it.
        }
    }

    private static SearchIndex HotelsIndex(string name) => new(name)
    {
        Fields =
        {
            new SimpleField("id", SearchFieldDataType.String) { IsKey = true, IsFilterable = true, IsSortable = true },
            new SearchableField("name") { IsFilterable = true, IsSortable = true },
            new SearchableField("description"),
            new SimpleField("category", SearchFieldDataType.String) { IsFilterable = true, IsSortable = true, IsFacetable = true },
            new SearchableField("tags", collection: true) { IsFilterable = true, IsFacetable = true },
            new SimpleField("rating", SearchFieldDataType.Int32) { IsFilterable = true, IsSortable = true, IsFacetable = true },
            new SimpleField("price", SearchFieldDataType.Double) { IsFilterable = true, IsSortable = true, IsFacetable = true },
            new SimpleField("parking", SearchFieldDataType.Boolean) { IsFilterable = true, IsFacetable = true },
            new SimpleField("location", SearchFieldDataType.GeographyPoint) { IsFilterable = true, IsSortable = true },
        },
    };

    // Six hotels whose filters, sorts and distances have one right answer (the Java suite uses the same corpus).
    private static SearchDocument[] Hotels() =>
    [
        Hotel("1", "Ocean View Resort", "Luxury resort with a pool and an ocean view", "Resort",
            ["pool", "view", "wifi"], 5, 250.0, true, -122.33, 47.61),
        Hotel("2", "Budget Inn", "Cheap motel near the airport with free parking", "Budget",
            ["parking", "wifi"], 2, 60.0, true, -122.30, 47.45),
        Hotel("3", "City Center Hotel", "Modern hotel in the city center with a rooftop pool", "Luxury",
            ["pool", "gym"], 4, 180.0, false, -122.34, 47.60),
        Hotel("4", "Mountain Lodge", "Rustic lodge with hiking trails and a stone fireplace", "Resort",
            ["hiking", "fireplace"], 3, 120.0, true, -105.27, 40.01),
        Hotel("5", "Airport Suites", "Convenient suites next to the airport terminal", "Budget",
            ["wifi", "shuttle"], 3, 90.0, false, -122.31, 47.44),
        Hotel("6", "Harbor Boutique", "Small boutique hotel on the harbor with a view", "Boutique",
            ["boutique", "view"], 4, 150.0, false, -122.40, 47.65),
    ];

    private static SearchDocument Hotel(string id, string name, string description, string category, string[] tags,
        int rating, double price, bool parking, double longitude, double latitude) => new()
    {
        ["id"] = id,
        ["name"] = name,
        ["description"] = description,
        ["category"] = category,
        ["tags"] = tags,
        ["rating"] = rating,
        ["price"] = price,
        ["parking"] = parking,
        ["location"] = new Dictionary<string, object> { ["type"] = "Point", ["coordinates"] = new[] { longitude, latitude } },
    };
}
