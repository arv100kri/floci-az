import { IndexDocumentsBatch, SearchClient, SearchIndexClient } from "@azure/search-documents";
import {
  Hotel,
  QUERY_KEY,
  SEATTLE,
  createHotels,
  deleteIndexQuietly,
  eventually,
  indexClient,
  searchAll,
  searchClient,
  searchKeys,
  serviceName,
  statusOf,
  uniqueName,
} from "./searchSupport";

// Azure AI Search MVP through @azure/search-documents: index management with ETags, document batches,
// lookup, count, the simple query syntax with filters, sorting and paging, and query-key authorization.
// Every assertion encodes behavior documented on Microsoft Learn; the URL above each test names the page.

interface Doc {
  id: string;
  name?: string | null;
  category?: string | null;
  rating?: number | null;
  n?: number;
}

describe("search-mvp: indexes, documents and simple queries", () => {
  const service = serviceName("nodemvp");
  let indexes: SearchIndexClient;
  let hotelsName: string;
  let hotels: SearchClient<Hotel>;

  beforeAll(async () => {
    indexes = indexClient(service);
    ({ name: hotelsName, client: hotels } = await createHotels(service));
  });

  afterAll(async () => {
    if (hotelsName) {
      await deleteIndexQuietly(indexes, hotelsName);
    }
  });

  // https://learn.microsoft.com/rest/api/searchservice/indexes/create (201 Created)
  // https://learn.microsoft.com/rest/api/searchservice/indexes/create-or-update (200 OK on update)
  // https://learn.microsoft.com/rest/api/searchservice/indexes/delete (204 No Content, 404 Not Found)
  // https://learn.microsoft.com/azure/search/search-howto-concurrency ("Every time you update a resource, its ETag
  // changes automatically")
  // https://github.com/Azure/azure-sdk-for-js/blob/main/sdk/search/search-documents/src/searchIndex/api/operations.ts
  // (_deleteIndexDeserialize expects "204" or "404": deleting a missing index resolves)
  test("index lifecycle: create, get, list, update, delete", async () => {
    const name = uniqueName("lifecycle");
    const created = await indexes.createIndex({
      name,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "title", type: "Edm.String", searchable: true },
      ],
    });
    try {
      expect(created.name).toBe(name);
      expect(created.etag).toBeTruthy();

      const fetched = await indexes.getIndex(name);
      expect(fetched.fields.map((f) => f.name)).toEqual(["id", "title"]);
      expect(fetched.etag).toBe(created.etag);

      const listed: string[] = [];
      for await (const index of indexes.listIndexes()) {
        listed.push(index.name);
      }
      expect(listed).toContain(name);
      const names: string[] = [];
      for await (const indexName of indexes.listIndexesNames()) {
        names.push(indexName);
      }
      expect(names).toContain(name);

      fetched.fields.push({ name: "added", type: "Edm.Int32", filterable: true });
      const updated = await indexes.createOrUpdateIndex(fetched);
      expect(updated.etag).not.toBe(created.etag);
      expect((await indexes.getIndex(name)).fields.map((f) => f.name)).toContain("added");
    } finally {
      await indexes.deleteIndex(name);
    }
    expect(await statusOf(() => indexes.getIndex(name))).toBe(404);
    let deleteStatus: number | undefined;
    await indexes.deleteIndex(name, {
      onResponse: (response) => {
        deleteStatus = response.status;
      },
    });
    expect(deleteStatus).toBe(404);
  });

  // https://learn.microsoft.com/azure/search/search-howto-concurrency (an update whose ETag no longer matches fails)
  // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("412 Precondition Failed: Returned when an
  // If-Match or If-None-Match header's condition evaluates to false")
  test("stale ETags are rejected with 412 Precondition Failed", async () => {
    const name = uniqueName("etag");
    const original = await indexes.createIndex({ name, fields: [{ name: "id", type: "Edm.String", key: true }] });
    try {
      const clientA = await indexes.getIndex(name);
      clientA.fields.push({ name: "a", type: "Edm.Int32" });
      await indexes.createOrUpdateIndex(clientA, { onlyIfUnchanged: true });

      const clientB = await indexes.getIndex(name);
      clientB.fields.push({ name: "b", type: "Edm.Boolean" });
      clientB.etag = original.etag;
      expect(await statusOf(() => indexes.createOrUpdateIndex(clientB, { onlyIfUnchanged: true }))).toBe(412);
      expect(await statusOf(() => indexes.deleteIndex(original, { onlyIfUnchanged: true }))).toBe(412);
      expect((await indexes.getIndex(name)).fields.map((f) => f.name)).not.toContain("b");
    } finally {
      await deleteIndexQuietly(indexes, name);
    }
  });

  // https://learn.microsoft.com/rest/api/searchservice/documents/index (upload replaces all fields, merge updates an
  // existing document, mergeOrUpload, delete; statusCode 200 update or delete, 201 created)
  // https://learn.microsoft.com/azure/search/search-how-to-load-search-index ("Delete operations are idempotent")
  test("upload, merge, mergeOrUpload and delete actions", async () => {
    const name = uniqueName("actions");
    await indexes.createIndex({
      name,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "name", type: "Edm.String", searchable: true },
        { name: "category", type: "Edm.String", filterable: true },
        { name: "rating", type: "Edm.Int32", filterable: true },
      ],
    });
    try {
      const client = searchClient<Doc>(service, name);
      const created = await client.uploadDocuments([{ id: "a", name: "Alpha", category: "One", rating: 1 }]);
      expect(created.results.map((r) => [r.key, r.succeeded, r.statusCode])).toEqual([["a", true, 201]]);

      const merged = await client.mergeDocuments([{ id: "a", rating: 5 }]);
      expect(merged.results[0].statusCode).toBe(200);
      const afterMerge = await client.getDocument("a");
      expect(afterMerge.name).toBe("Alpha");
      expect(afterMerge.rating).toBe(5);

      const upserts = await client.mergeOrUploadDocuments([{ id: "b", name: "Beta" }, { id: "a", category: "Two" }]);
      expect(Object.fromEntries(upserts.results.map((r) => [r.key, r.statusCode]))).toEqual({ b: 201, a: 200 });
      const afterUpsert = await client.getDocument("a");
      expect([afterUpsert.name, afterUpsert.category]).toEqual(["Alpha", "Two"]);

      const replaced = await client.uploadDocuments([{ id: "a", name: "Alpha 2" }]);
      expect(replaced.results[0].statusCode).toBe(200);
      const afterReplace = await client.getDocument("a");
      expect(afterReplace.name).toBe("Alpha 2");
      expect(afterReplace.category ?? null).toBeNull();
      expect(afterReplace.rating ?? null).toBeNull();

      const deletes = await client.deleteDocuments("id", ["b", "never-existed"]);
      expect(Object.fromEntries(deletes.results.map((r) => [r.key, [r.succeeded, r.statusCode]]))).toEqual({
        b: [true, 200],
        "never-existed": [true, 200],
      });

      await eventually(async () => {
        expect(await client.getDocumentsCount()).toBe(1);
      });
      expect(await statusOf(() => client.getDocument("b"))).toBe(404);
    } finally {
      await deleteIndexQuietly(indexes, name);
    }
  });

  // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("207 Multi-Status ... Some documents
  // succeeded, but at least one failed")
  // https://learn.microsoft.com/azure/search/search-how-to-load-search-index (per-document 404 for a merge of a key
  // that doesn't exist)
  test("a partial batch failure returns 207 with per-document results", async () => {
    const name = uniqueName("partial");
    await indexes.createIndex({
      name,
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "name", type: "Edm.String", searchable: true },
      ],
    });
    try {
      const client = searchClient<Doc>(service, name);
      const batch = new IndexDocumentsBatch<Doc>();
      batch.upload([{ id: "ok", name: "fine" }]);
      batch.merge([{ id: "missing", name: "nothing to merge into" }]);
      let status: number | undefined;
      const result = await client.indexDocuments(batch, {
        throwOnAnyFailure: false,
        onResponse: (response) => {
          status = response.status;
        },
      });
      expect(status).toBe(207);
      const byKey = Object.fromEntries(result.results.map((r) => [r.key, r]));
      expect([byKey.ok.succeeded, byKey.ok.statusCode]).toEqual([true, 201]);
      expect([byKey.missing.succeeded, byKey.missing.statusCode]).toEqual([false, 404]);
      expect(byKey.missing.errorMessage).toBeTruthy();
    } finally {
      await deleteIndexQuietly(indexes, name);
    }
  });

  // https://learn.microsoft.com/rest/api/searchservice/documents/get ($select: "Any field not retrieved will be
  // missing from the returned document")
  // https://learn.microsoft.com/rest/api/searchservice/documents/count
  test("getDocument honours select, and the count matches the corpus", async () => {
    const full = await hotels.getDocument("3");
    expect(full.name).toBe("City Center Hotel");
    expect(full.tags).toEqual(["pool", "gym"]);
    const selected = await hotels.getDocument("3", { selectedFields: ["id", "name"] });
    expect(Object.keys(selected).filter((k) => !k.startsWith("@")).sort()).toEqual(["id", "name"]);
    expect(await statusOf(() => hotels.getDocument("no-such-hotel"))).toBe(404);

    expect(await hotels.getDocumentsCount()).toBe(6);
    const counted = await hotels.search("*", { includeTotalCount: true });
    expect(counted.count).toBe(6);
  });

  // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (searchMode any/all, searchFields)
  // https://learn.microsoft.com/azure/search/query-simple-syntax (phrase search, prefix queries)
  test("searchMode, searchFields, phrases and prefixes", async () => {
    expect(new Set(await searchKeys(hotels, "pool view", { searchMode: "any", searchFields: ["description"] })))
      .toEqual(new Set(["1", "3", "6"]));
    expect(await searchKeys(hotels, "pool view", { searchMode: "all", searchFields: ["description"] })).toEqual(["1"]);
    expect(new Set(await searchKeys(hotels, "airport"))).toEqual(new Set(["2", "5"]));
    expect(await searchKeys(hotels, "airport", { searchFields: ["name"] })).toEqual(["5"]);
    expect(await searchKeys(hotels, '"ocean view"')).toEqual(["1"]);
    expect(new Set(await searchKeys(hotels, "air*", { searchFields: ["description"] }))).toEqual(new Set(["2", "5"]));
  });

  // https://learn.microsoft.com/azure/search/search-query-odata-comparison-operators
  // https://learn.microsoft.com/azure/search/search-query-odata-logical-operators
  // https://learn.microsoft.com/azure/search/search-query-odata-search-in-function
  // https://learn.microsoft.com/azure/search/search-query-odata-collection-operators
  // https://learn.microsoft.com/azure/search/search-query-odata-geo-spatial-functions (distance in kilometers)
  test.each([
    ["rating ge 4", ["1", "3", "6"]],
    ["price lt 100", ["2", "5"]],
    ["category eq 'Resort'", ["1", "4"]],
    ["rating ge 4 and parking eq true", ["1"]],
    ["category eq 'Budget' or category eq 'Boutique'", ["2", "5", "6"]],
    ["not (category eq 'Resort')", ["2", "3", "5", "6"]],
    ["parking", ["1", "2", "4"]],
    ["search.in(category, 'Budget,Boutique')", ["2", "5", "6"]],
    ["search.in(name, 'Budget Inn|Harbor Boutique', '|')", ["2", "6"]],
    ["tags/any(t: t eq 'pool')", ["1", "3"]],
    ["tags/all(t: t ne 'wifi')", ["3", "4", "6"]],
    [`geo.distance(location, ${SEATTLE}) le 10`, ["1", "3", "6"]],
  ])("filter %s", async (filter, expected) => {
    expect(new Set(await searchKeys(hotels, "*", { filter }))).toEqual(new Set(expected));
  });

  // https://learn.microsoft.com/azure/search/search-query-odata-orderby (multiple sort criteria, search.score(),
  // geo.distance)
  // https://learn.microsoft.com/azure/search/index-similarity-and-scoring ("A @search.score = 1 indicates an un-scored
  // or un-ranked result set ... an empty search (search=*)")
  test("orderBy fields, distance and search.score()", async () => {
    expect(await searchKeys(hotels, "*", { orderBy: ["price asc"] })).toEqual(["2", "5", "4", "6", "3", "1"]);
    expect(await searchKeys(hotels, "*", { orderBy: ["search.score() desc", "rating desc", "id asc"] }))
      .toEqual(["1", "3", "6", "4", "5", "2"]);
    expect(await searchKeys(hotels, "*", { orderBy: [`geo.distance(location, ${SEATTLE}) asc`] }))
      .toEqual(["1", "3", "6", "2", "5", "4"]);
    for (const result of await searchAll(hotels, "*")) {
      expect(result.score).toBe(1);
    }
  });

  // https://learn.microsoft.com/rest/api/searchservice/documents/search-post (select, top, skip, count)
  test("select, top, skip and includeTotalCount", async () => {
    const response = await hotels.search("*", {
      orderBy: ["id asc"], select: ["id", "name"], top: 2, skip: 1, includeTotalCount: true,
    });
    expect(response.count).toBe(6);
    const documents: Record<string, unknown>[] = [];
    for await (const result of response.results) {
      documents.push(result.document as Record<string, unknown>);
    }
    expect(documents.map((d) => d.id)).toEqual(["2", "3"]);
    for (const document of documents) {
      expect(Object.keys(document).filter((k) => !k.startsWith("@")).sort()).toEqual(["id", "name"]);
    }
  });

  // https://learn.microsoft.com/azure/search/search-pagination-page-layout ("The default page size is 50 ... If the
  // number of matches exceed the page size, the response includes information to retrieve the next page")
  test("paging follows the continuation past fifty results", async () => {
    const name = uniqueName("paging");
    await indexes.createIndex({
      name,
      fields: [
        { name: "id", type: "Edm.String", key: true, sortable: true },
        { name: "n", type: "Edm.Int32", sortable: true, filterable: true },
      ],
    });
    try {
      const client = searchClient<Doc>(service, name);
      await client.uploadDocuments(Array.from({ length: 60 }, (_, i) => ({ id: `doc-${String(i).padStart(3, "0")}`, n: i })));
      await eventually(async () => {
        expect(await client.getDocumentsCount()).toBe(60);
      });

      const response = await client.search("*", { includeTotalCount: true });
      const sizes: number[] = [];
      for await (const page of response.results.byPage()) {
        sizes.push(page.results.length);
      }
      expect(sizes[0]).toBe(50);
      expect(sizes.reduce((a, b) => a + b, 0)).toBe(60);

      const keys = await searchKeys(client, "*");
      expect(new Set(keys).size).toBe(60);
    } finally {
      await deleteIndexQuietly(indexes, name);
    }
  });

  // https://learn.microsoft.com/azure/search/search-filters ("Filters apply to ... fields that are attributed as
  // filterable"); https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("400 Bad Request")
  test("filtering or sorting on a field without the attribute is a bad request", async () => {
    expect(await statusOf(() => searchKeys(hotels, "*", { filter: "description eq 'x'" }))).toBe(400);
    expect(await statusOf(() => searchKeys(hotels, "*", { orderBy: ["description asc"] }))).toBe(400);
  });

  // https://learn.microsoft.com/azure/search/search-security-api-keys (query keys: "Read-only access, scoped to the
  // documents collection of a search index"; index definitions require an admin key)
  // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden")
  test("a query key can search but cannot write or read definitions", async () => {
    const readOnly = searchClient<Hotel>(service, hotelsName, QUERY_KEY);
    expect(new Set(await searchKeys(readOnly, "pool"))).toEqual(new Set(["1", "3"]));
    expect((await readOnly.getDocument("2")).name).toBe("Budget Inn");
    expect(await statusOf(() => readOnly.uploadDocuments([{ id: "99", name: "Not allowed" }]))).toBe(403);
    expect(await statusOf(() => indexClient(service, QUERY_KEY).getIndex(hotelsName))).toBe(403);
    expect(await statusOf(() => hotels.getDocument("99"))).toBe(404);
  });
});
