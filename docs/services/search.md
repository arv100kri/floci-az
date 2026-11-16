# Azure AI Search

floci-az emulates Azure AI Search: the `Microsoft.Search/searchServices` management plane, and the data plane of
each service with its indexes, documents, queries, synonym maps, aliases, data sources, indexers and skillsets. It
works with `azure-search-documents` (Python, Java, JavaScript and .NET), `azure-mgmt-search` and
`azure-resourcemanager-search`, the `az search` commands, and the `azurerm_search_service` resource of Terraform and
OpenTofu. It follows the public [Azure AI Search REST API](https://learn.microsoft.com/rest/api/searchservice/) and
the [management REST API](https://learn.microsoft.com/rest/api/searchmanagement/).

> **In process, no Docker.** Search runs on floci-az's own engine, written in Java for the emulator: no Lucene, no
> Elasticsearch or OpenSearch sidecar and no vector database. Indexes are held in memory and rebuilt from the `search`
> store after a restart. Indexers read floci-az's own Blob, Data Lake, Table and Cosmos DB services in the same
> process.

| Page | Covers |
|---|---|
| [Security](search-security.md) | API keys, Microsoft Entra tokens, role assignments and document-level access |
| [Network isolation](search-network-isolation.md) | Public network access, IP firewall rules, private endpoints and shared private links |
| [Vector and hybrid search](search-vectors.md) | Vector fields and compression, vector and hybrid queries, vectorizers |
| [Skillsets and AI enrichment](search-skillsets.md) | Skills, output field mappings and index projections |

---

## Endpoints

### Management plane

`Microsoft.Search` is served through [Azure Resource Manager](arm.md). Every api-version is answered in the shape of
the 2026-11-01 spec:

```text
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices
GET    /subscriptions/{sub}/providers/Microsoft.Search/searchServices
POST   /subscriptions/{sub}/providers/Microsoft.Search/checkNameAvailability

POST   .../searchServices/{name}/listAdminKeys
POST   .../searchServices/{name}/regenerateAdminKey/{primary|secondary}
POST   .../searchServices/{name}/listQueryKeys            # also GET, as api-version 2015-08-19 sends it
POST   .../searchServices/{name}/createQueryKey/{keyName}
DELETE .../searchServices/{name}/deleteQueryKey/{key}

GET    .../searchServices/{name}/privateEndpointConnections[/{connection}]   # PUT and DELETE on one connection
GET    .../searchServices/{name}/privateLinkResources
GET    .../searchServices/{name}/sharedPrivateLinkResources[/{link}]         # PUT and DELETE on one link
GET    .../searchServices/{name}/networkSecurityPerimeterConfigurations      # always empty
```

- **Writes complete at once.** A create answers `201` and a replace `200`, with `provisioningState: succeeded` and
  `status: running`; there is no long-running operation to poll. A delete answers `200`, or `204` for a service that
  does not exist.
- **PUT and PATCH.** A PUT resets every property and the tags it omits to their defaults, and keeps the stored `sku`
  and `identity` when it omits them; a PATCH changes only what it names. Both keep the service's location and keys.
  Read-only properties echoed from a GET are ignored.
- **Tier rules** from Learn's service limits are checked: the eight SKUs of the spec (`free` to `serverless`);
  1 to 12 replicas and 1, 2, 3, 4, 6 or 12 partitions; at most 3 of each on `basic` and 36 search units on the
  Standard and Storage Optimized tiers; `highDensity` hosting on `standard3` only, with at most 3 partitions; no IP
  rules, managed identity, customer-managed key enforcement or `standard` semantic plan on `free`. A tier changes in
  place only between `basic`, `standard`, `standard2` and `standard3`, and the hosting mode never changes. A refused
  value is `400 BadRequest` naming the rule.
- **Keys**: two admin keys and one query key are generated on create, each 52 letters and digits. A service holds
  at most 50 query keys; the one created with the service has no name. Regenerating an admin key replaces it on the
  data plane at once.
- **Names**: 2 to 60 lowercase letters, digits and dashes, with no dash among the first two characters or at the
  end, and no consecutive dashes. A name is a global DNS label: a create of the same name in another subscription or
  resource group is `409 Conflict` until the owner deletes it. `checkNameAvailability` answers `Invalid` or
  `AlreadyExists`, and [`strict-names`](arm.md#name-syntax) refuses an invalid name on PUT with
  `400 InvalidResourceName`.
- **`properties.endpoint`** is `https://{name}.search.windows.net`, with the search suffix of the subscription's
  cloud. A path-style client uses `http://localhost:4577/{name}-search` instead (below).
- **Persistence**: services and their keys are kept in the `search` store, so they survive a restart under a
  persistent `storage.services.search.mode`. They appear in `GET .../resources`, and are deleted with their resource
  group, only under [`resource-group-delete`](arm.md#resource-groups).

### Data plane

```text
http://localhost:4577/{service}-search/...     # path style, the default
https://{service}.search.windows.net/...       # host style: needs TLS and a DNS name for floci-az
```

Every route of the data-plane REST API is served, under both of its URL forms (`indexes('hotels')/docs('1')` and
`indexes/hotels/docs/1`):

| Resource | Operations |
|---|---|
| Indexes | `POST /indexes`, `PUT`, `GET` and `DELETE /indexes/{index}`, `GET /indexes`, `GET .../search.stats`, `POST .../search.analyze` |
| Documents | Search (`GET .../docs`, `POST .../docs/search.post.search`), Index Documents (`POST .../docs/search.index`), Lookup (`GET .../docs/{key}`), Count (`GET .../docs/$count`), Suggest and Autocomplete (GET and POST) |
| Synonym maps, aliases, data sources | `POST` to create, `PUT` to create or update, `GET` one, `GET` the list, `DELETE` |
| Indexers | the same, plus `search.run`, `search.reset`, `search.resetdocs`, `search.resync` and `search.status` |
| Skillsets | the same, plus `search.resetskills` |
| Service | `GET /servicestats`, `GET /indexstats` |
| Knowledge bases, knowledge sources, graph queries (`docs/search.graph`) | routed and authorized, then answered `501 NotImplemented` |

- **`api-version`** is required: every stable and preview version published for the data plane in
  azure-rest-api-specs is accepted, from `2015-02-28` to `2026-10-01` and `2026-11-01-preview`. A missing or unknown
  one is `400`.
- **Concurrency**: definitions carry `@odata.etag` and honor `If-Match` and `If-None-Match` (`412` on a mismatch);
  `Prefer: return=representation` chooses between the definition and an empty `204` on update.
- **Responses** carry `request-id` (the caller's `client-request-id` or `x-ms-client-request-id` when it sends one),
  `elapsed-time` and `OData-Version: 4.0`. Errors use the Azure envelope `{"error":{"code":"...","message":"..."}}`.

### Host style and HTTPS

floci-az routes `{service}.search.windows.net` to the service its first label names, and does the same for any host
whose second label is `search`, such as a sovereign cloud's `{service}.search.azure.us` or `{service}.search.localhost`.
To call the host-style endpoint:

1. Enable TLS with `FLOCI_AZ_TLS_ENABLED=true`. The self-signed certificate already names `*.search.windows.net`,
   `*.search.azure.us` and `*.search.azure.cn`; clients trust the CA from `GET /_floci/tls-cert` or
   `{persistent-path}/tls/floci-az-selfsigned-ca.crt`.
2. Make the name resolve to floci-az, for example with an `/etc/hosts` entry or Docker's `--add-host`.
3. A portless `https://` URL targets port 443: publish or forward 443 as well as 4577 (floci-az binds
   `floci-az.tls.https-port`, 443 by default, and treats a failure as non-fatal), or put `:4577` in the URL.

Which SDKs need HTTPS:

| SDK | Plain HTTP | What to do |
|---|---|---|
| Python `azure-search-documents` | yes, with an API key | Nothing: its key policy does not insist on `https` |
| JavaScript `@azure/search-documents` | yes, with an API key | Add a policy that sets `allowInsecureConnection` on each request (below) |
| Java `azure-search-documents` | through a policy | Its key policy refuses `http`: give the client the `https://` form of the endpoint and add a policy that sends the request over `http` (below), or enable TLS |
| .NET `Azure.Search.Documents` | no | Enable TLS and trust floci-az's certificate |

Bearer tokens over plain HTTP need an SDK-specific opt-out: the Python and Java token policies refuse `http`, so give
the client the `https://` form of the endpoint and a transport or policy that sends it over `http`, as the identity
tests of the compatibility suites do, or enable TLS.

---

## Quick start

### 1. Get a service

**A data-plane-only service.** The first data-plane request that names a service provisions it, with the
development keys:

| Key | Value |
|---|---|
| Primary admin key | `FlociAzSearchDevPrimaryAdminKey0000000000000000000000` |
| Secondary admin key | `FlociAzSearchDevSecondaryAdminKey00000000000000000000` |
| Query key | `FlociAzSearchDevQueryKey000000000000000000000000000000` |

The query key may only read documents; any other key is accepted as an admin key. Such a service also accepts
Microsoft Entra tokens. It has no ARM resource, so it has no authentication options, network settings, role
assignments or semantic plan to configure. Under `parity: strict` there are no data-plane-only services: a request
for a service that was not created through `Microsoft.Search` is `404 ServiceNotFound`.

**A service created through ARM:**

```bash
SUB=00000000-0000-0000-0000-000000000001
ARM=http://localhost:4577/subscriptions/$SUB/resourceGroups/my-rg/providers/Microsoft.Search

curl -s -X PUT "http://localhost:4577/subscriptions/$SUB/resourceGroups/my-rg?api-version=2021-04-01" \
  -H "Content-Type: application/json" -d '{"location":"eastus"}'
curl -s -X PUT "$ARM/searchServices/mysearch?api-version=2025-05-01" \
  -H "Content-Type: application/json" -d '{"location":"eastus","sku":{"name":"basic"}}'
curl -s -X POST "$ARM/searchServices/mysearch/listAdminKeys?api-version=2025-05-01"
```

`az search service create -n mysearch -g my-rg -l eastus --sku basic` and `azurerm_search_service` do the same
once the CLI or the provider is pointed at floci-az (see [Cloud metadata](arm.md#cloud-metadata) and
[Terraform / OpenTofu](../terraform.md)). A service created through ARM accepts only its own keys, as Azure does: a
key it does not hold, the development keys included, is refused with `403`. Creating a service through ARM replaces a
data-plane-only service of the same name; its indexes and documents stay.

### 2. Create an index, upload documents and search

The examples use the service `mysearch` and the development admin key; for a service created through ARM, use a key
from `listAdminKeys`.

=== "Python"

    ```python
    from azure.core.credentials import AzureKeyCredential
    from azure.search.documents import SearchClient
    from azure.search.documents.indexes import SearchIndexClient
    from azure.search.documents.indexes.models import SearchField, SearchFieldDataType, SearchIndex

    endpoint = "http://localhost:4577/mysearch-search"
    credential = AzureKeyCredential("FlociAzSearchDevPrimaryAdminKey0000000000000000000000")

    SearchIndexClient(endpoint, credential).create_index(SearchIndex(name="hotels", fields=[
        SearchField(name="id", type=SearchFieldDataType.STRING, key=True),
        SearchField(name="name", type=SearchFieldDataType.STRING, searchable=True),
        SearchField(name="rating", type=SearchFieldDataType.INT32, filterable=True, sortable=True),
    ]))

    hotels = SearchClient(endpoint, "hotels", credential)
    hotels.upload_documents([
        {"id": "1", "name": "Ocean View Resort", "rating": 5},
        {"id": "2", "name": "Budget Inn", "rating": 2},
    ])
    for result in hotels.search(search_text="ocean", filter="rating ge 4"):
        print(result["id"], result["@search.score"])
    ```

=== ".NET"

    ```csharp
    using Azure;
    using Azure.Core.Pipeline;
    using Azure.Search.Documents;
    using Azure.Search.Documents.Indexes;
    using Azure.Search.Documents.Indexes.Models;
    using Azure.Search.Documents.Models;

    // Azure.Search.Documents requires https: start floci-az with FLOCI_AZ_TLS_ENABLED=true.
    var endpoint = new Uri("https://localhost:4577/mysearch-search");
    var credential = new AzureKeyCredential("FlociAzSearchDevPrimaryAdminKey0000000000000000000000");
    var options = new SearchClientOptions
    {
        // For a local test only: trust floci-az's self-signed certificate. Otherwise install its CA
        // (GET /_floci/tls-cert) in the trust store and leave the default transport.
        Transport = new HttpClientTransport(new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = HttpClientHandler.DangerousAcceptAnyServerCertificateValidator,
        }),
    };

    var indexes = new SearchIndexClient(endpoint, credential, options);
    await indexes.CreateIndexAsync(new SearchIndex("hotels")
    {
        Fields =
        {
            new SimpleField("id", SearchFieldDataType.String) { IsKey = true },
            new SearchableField("name"),
            new SimpleField("rating", SearchFieldDataType.Int32) { IsFilterable = true, IsSortable = true },
        },
    });

    var hotels = new SearchClient(endpoint, "hotels", credential, options);
    await hotels.UploadDocumentsAsync(new[]
    {
        new SearchDocument { ["id"] = "1", ["name"] = "Ocean View Resort", ["rating"] = 5 },
        new SearchDocument { ["id"] = "2", ["name"] = "Budget Inn", ["rating"] = 2 },
    });
    SearchResults<SearchDocument> results = await hotels.SearchAsync<SearchDocument>(
        "ocean", new SearchOptions { Filter = "rating ge 4" });
    await foreach (SearchResult<SearchDocument> result in results.GetResultsAsync())
    {
        Console.WriteLine($"{result.Document["id"]} {result.Score}");
    }
    ```

=== "Java"

    The key policy refuses `http`, so the client gets the `https` form of the endpoint, and `ForceHttpPolicy` (shown on
    the [Key Vault](key-vault.md#connection-string) page), a per-retry policy that runs after the key policy, sends
    each request over `http`.

    ```java
    String endpoint = "https://localhost:4577/mysearch-search";
    AzureKeyCredential credential = new AzureKeyCredential("FlociAzSearchDevPrimaryAdminKey0000000000000000000000");

    SearchIndexClient indexes = new SearchIndexClientBuilder()
            .endpoint(endpoint)
            .credential(credential)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    indexes.createIndex(new SearchIndex("hotels",
            new SearchField("id", SearchFieldDataType.STRING).setKey(true),
            new SearchField("name", SearchFieldDataType.STRING).setSearchable(true),
            new SearchField("rating", SearchFieldDataType.INT32).setFilterable(true).setSortable(true)));

    SearchClient hotels = new SearchClientBuilder()
            .endpoint(endpoint)
            .indexName("hotels")
            .credential(credential)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    hotels.uploadDocuments(List.of(
            Map.of("id", "1", "name", "Ocean View Resort", "rating", 5),
            Map.of("id", "2", "name", "Budget Inn", "rating", 2)));
    for (SearchResult result : hotels.search("ocean", new SearchOptions().setFilter("rating ge 4"), Context.NONE)) {
        System.out.println(result.getDocument(SearchDocument.class).get("id") + " " + result.getScore());
    }
    ```

=== "JavaScript"

    ```typescript
    import { AzureKeyCredential, SearchClient, SearchIndexClient } from "@azure/search-documents";
    import { PipelinePolicy } from "@azure/core-rest-pipeline";

    // The SDK refuses plain http unless each request allows it.
    const allowHttp: PipelinePolicy = {
      name: "allowHttp",
      sendRequest(request, next) {
        request.allowInsecureConnection = true;
        return next(request);
      },
    };
    const options = { additionalPolicies: [{ policy: allowHttp, position: "perCall" as const }] };
    const endpoint = "http://localhost:4577/mysearch-search";
    const credential = new AzureKeyCredential("FlociAzSearchDevPrimaryAdminKey0000000000000000000000");

    await new SearchIndexClient(endpoint, credential, options).createIndex({
      name: "hotels",
      fields: [
        { name: "id", type: "Edm.String", key: true },
        { name: "name", type: "Edm.String", searchable: true },
        { name: "rating", type: "Edm.Int32", filterable: true, sortable: true },
      ],
    });

    const hotels = new SearchClient(endpoint, "hotels", credential, options);
    await hotels.uploadDocuments([
      { id: "1", name: "Ocean View Resort", rating: 5 },
      { id: "2", name: "Budget Inn", rating: 2 },
    ]);
    const response = await hotels.search("ocean", { filter: "rating ge 4" });
    for await (const result of response.results) {
      console.log(result.document, result.score);
    }
    ```

Documents are searchable as soon as Index Documents answers.

---

## Configuration

```yaml
floci-az:
  services:
    search:
      enabled: true
      max-documents-per-index: 100000     # emulator guard on memory, not an Azure quota
      indexer-min-interval-seconds: 300   # the shortest indexer schedule; Azure's is five minutes
      semantic-ranker: stand-in           # stand-in | disabled
      # model-endpoint: http://localhost:11434/v1   # OpenAI-compatible embeddings endpoint
      trust-client-ip-header: false       # test hook for IP firewall rules
  storage:
    services:
      search:
        # mode: persistent                # default: storage.mode
        flush-interval-ms: 5000
  parity: compat                          # strict turns on every strict-* override below
  # parity-overrides:
  #   strict-tokens: true                 # validate bearer tokens
  #   strict-rbac: true                   # enforce role assignments
  #   strict-network: true                # enforce network isolation
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `services.search.enabled` | `FLOCI_AZ_SERVICES_SEARCH_ENABLED` | `true` | Turns the `Microsoft.Search` management plane and the data plane on or off together |
| `services.search.max-documents-per-index` | `FLOCI_AZ_SERVICES_SEARCH_MAX_DOCUMENTS_PER_INDEX` | `100000` | The most documents one index holds. A new document beyond it fails in its batch with `429` |
| `services.search.indexer-min-interval-seconds` | `FLOCI_AZ_SERVICES_SEARCH_INDEXER_MIN_INTERVAL_SECONDS` | `300` | The shortest `schedule.interval` an indexer may name, at least 1 second. Lower it so tests need not wait five minutes for a scheduled run |
| `services.search.semantic-ranker` | `FLOCI_AZ_SERVICES_SEARCH_SEMANTIC_RANKER` | `stand-in` | `stand-in`: semantic queries are answered by the deterministic stand-in. `disabled`: every service refuses them, as a service without semantic ranking does |
| `services.search.model-endpoint` | `FLOCI_AZ_SERVICES_SEARCH_MODEL_ENDPOINT` | unset | An OpenAI-compatible endpoint (`POST {endpoint}/embeddings`) that the Azure OpenAI embedding skill and vectorizers call instead of Azure; see [Skills and the model endpoint](#skills-and-the-model-endpoint) |
| `services.search.trust-client-ip-header` | `FLOCI_AZ_SERVICES_SEARCH_TRUST_CLIENT_IP_HEADER` | `false` | Test hook: IP firewall rules match the address in the `x-floci-az-client-ip` header instead of the caller's; see [Network isolation](search-network-isolation.md) |
| `storage.services.search.mode` | `FLOCI_AZ_STORAGE_SERVICES_SEARCH_MODE` | `storage.mode` | Storage mode of the `search` store |
| `storage.services.search.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_SEARCH_FLUSH_INTERVAL_MS` | `5000` | Flush interval in `hybrid` mode |
| `parity-overrides.strict-tokens` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_TOKENS` | on from `strict` or `entra.validate-tokens` | Bearer tokens must be valid floci-az tokens for a search audience |
| `parity-overrides.strict-rbac` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_RBAC` | on from `strict` | Role assignments are enforced for bearer callers and for indexers that read through a managed identity |
| `parity-overrides.strict-network` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_NETWORK` | on from `strict` | Public network access, IP rules and private endpoints decide who reaches the data plane |

The `search` store holds the services created through ARM with their keys and shared private links, and every
service's index definitions, documents, synonym maps, aliases, data sources, indexers with their execution
history, and skillsets. Under a persistent mode all of it survives a restart, and indexer schedules start again. A
data-plane-only service is not stored: it is provisioned again, with the same keys, by the next request that names
it, and its scheduled indexers resume from then. `POST /_admin/reset` clears every search service and everything in
it.

---

## Feature matrix

| Area | What works | Fidelity |
|---|---|---|
| Management plane | Services (create, update, get, list, delete), admin and query keys, `checkNameAvailability`, authentication options, identities, network settings and the network children | Writes complete at once; the tier rules are checked; codes for refused values are floci-az's |
| Indexes | Create, update, get, list, delete and statistics; field attributes with the documented defaults and rules, complex fields, scoring profiles, suggesters, analyzers, normalizers, semantic and vector configurations, CORS options; updates follow "Update an index schema": new analyzers, tokenizers, token filters and char filters need `allowIndexDowntime`, existing ones cannot be changed or deleted, and normalizer or `similarity` changes need `allowIndexDowntime` | Definitions validated as the REST API documents them; unknown sections round-trip as sent |
| Documents | Index Documents (`upload`, `merge`, `mergeOrUpload`, `delete`; `200`, or `207` with per-document results), Lookup with `$select`, Count | Writes are visible at once |
| Full-text queries | Simple and full Lucene syntax (fields, boolean operators, phrases with proximity, fuzzy, boosts, wildcards, regular expressions), `searchMode`, `searchFields`, BM25 with the index's `similarity` parameters, synonym maps | Exact BM25 field lengths; fuzzy expansions scored as one term |
| Filters and shaping | OData `$filter` (comparisons, `any`/`all`, `search.in`, `search.ismatch`, `search.ismatchscoring`, `geo.distance`, `geo.intersects`), `$orderby` (`search.score()`, `geo.distance`), `$select`, `$top`, `$skip`, `$count`, paging past 1,000 results | As documented |
| Facets and highlights | Value, `values` range and `interval` facets (dates with `timeoffset`); hit highlighting with custom tags and fragment counts, including the terms a query matched through a synonym map | Fragmenting and week boundaries are floci-az's |
| Scoring profiles | Text weights, magnitude, freshness, distance and tag functions, every interpolation and `functionAggregation` | Functions rescore every match |
| Suggestions | Suggest and Autocomplete (`oneTerm`, `twoTerms`, `oneTermWithContext`), fuzzy, highlights, filters | Completions are lowercase words ranked by document count |
| Analysis | Built-in analyzers and normalizers, custom analyzers, tokenizers, token filters and character filters, the Analyze API | Approximations listed under [Analysis](#analysis) |
| Aliases | Create, update, get, list, delete; document operations and index get and update through an alias | No quota |
| Vectors and hybrid | Vector fields, profiles, compression, vector, text and image queries, filter modes, hybrid queries with RRF, vectorizers | Exact nearest-neighbour search; see [Vector and hybrid search](search-vectors.md) |
| Semantic ranking | Semantic configurations, `queryType=semantic`, `semanticQuery`, reranker scores, captions, answers, debug output | A deterministic lexical stand-in, not the model; see [Semantic ranking](#semantic-ranking) |
| Indexers | Blob, ADLS Gen2, Table and Cosmos DB for NoSQL data sources on floci-az; parsing modes, field mappings, change and deletion detection, schedules, run, reset, reset documents, resync and status | Text formats only; see [Indexers](#indexers-over-floci-az-storage-and-cosmos-db) |
| Skillsets | Split, Merge, Shaper, Conditional, Document Extraction, Custom Web API, AML and Azure OpenAI embedding skills; output field mappings; index projections | Skills that need Azure AI services fail per document; see [Skillsets](search-skillsets.md) |
| Identity | API keys, `authOptions` and `disableLocalAuth`, Entra tokens, role assignments, document-level permission filters | Token validation and roles opt in; see [Security](search-security.md) |
| Network isolation | Public network access, IP rules, private endpoint connections, private link resources, shared private links | Enforced under `strict-network`; see [Network isolation](search-network-isolation.md) |
| Statistics | Service statistics (usage counters, per-index limits), index statistics and the index statistics summary | Counters have no quota |
| Not emulated | Knowledge bases and knowledge sources, graph queries, the `aiServicesVision` vectorizer, OneLake permission filters | `501 NotImplemented` |

### Analysis

`standard.lucene`, `standardasciifolding.lucene`, `keyword`, `pattern`, `simple`, `stop`, `whitespace` and
`en.lucene` (possessive removal, lowercase, English stop words and the Porter stemmer) are built from the components
Azure documents for them, and custom analyzers, tokenizers, token filters, character filters and normalizers are
composed from the documented components the same way. These components and analyzers are approximated:

| Component | floci-az |
|---|---|
| Standard tokenizer (`standard.lucene`, `standard_v2`, every language analyzer) | Unicode word boundaries from the JDK, split further at dashes, double quotes and ideographs as UAX #29 does; rare cases such as emoji sequences can differ |
| `*.lucene` analyzers other than `en.lucene` | Word boundaries and lowercase plus the language's stop words (the Snowball list, else Microsoft's published one, else none); no stemming; French elision; character bigrams for Chinese, Japanese and Korean; the JDK's dictionary-based word boundaries for Thai |
| `*.microsoft` analyzers | `en.microsoft` analyzes like `en.lucene` with Microsoft's published English stop words; the others like the Lucene approximation with Microsoft's stop words. No lemmatization, decompounding or entity recognition |
| `classic` and `uax_url_email` tokenizers | Regular-expression approximations of their grammars |
| `microsoft_language_tokenizer`, `microsoft_language_stemming_tokenizer` | Word boundaries whatever the language; the stemming tokenizer stems English only, with the Porter stemmer |
| `stemmer`, `snowball` and `kstem` token filters | English variants use the Porter stemmer or Harman's S stemmer; other languages pass tokens through unchanged |
| `phonetic` token filter | Soundex and Metaphone; the other encoders fall back to one of them |
| `hindi_normalization`, `indic_normalization`, `sorani_normalization` | Accepted; tokens pass through unchanged |
| `persian_normalization`, `arabic_normalization`, `german_normalization`, `scandinavian_normalization` | Written from the published descriptions; edge cases can differ |
| `classic` token filter, `cjk_bigram`, `word_delimiter`, `limit` | `classic` applies to every token; `cjk_bigram` reads scripts from the characters; `word_delimiter` splits case only from lower to upper; `limit` ignores `consumeAllTokens` |
| `unique` token filter | `onlyOnSamePosition` defaults to `false`, as the REST specification documents (Learn's table says `true`) |
| `synonym` token filter | Rule terms split on whitespace and matched longest first |
| `html_strip` character filter | Markup becomes spaces; numeric and common named entities are decoded |
| Predefined `stopwords` lists | `english` is the 33-word list of the `stop` analyzer; Snowball or Microsoft lists elsewhere; armenian, basque, galician, persian and sorani are empty |
| Token length | Tokens longer than 300 characters are indexed; Azure fails the document |

---

## Identity, network isolation and parity

A data-plane request goes through these steps, in order:

1. **Route and version.** A path no route matches is `404`, a method the route does not serve `405`, and a missing
   or unknown `api-version` `400`.
2. **Service.** Under `parity: strict` the service must have been created through `Microsoft.Search`
   (`404 ServiceNotFound` otherwise); in every other configuration a data-plane request provisions a service it does
   not find.
3. **Network**, under `strict-network` only: a caller the service's `publicNetworkAccess`, IP rules and private
   endpoints exclude is refused with `403 Forbidden`, before any credential is read. See
   [Network isolation](search-network-isolation.md).
4. **Credentials and roles.** An `api-key` header wins over a bearer token. The service's `authOptions` and
   `disableLocalAuth` decide which credentials it accepts, at every parity level. A query key only reads documents.
   Under `strict-tokens` a token must be valid for a search audience, and under `strict-rbac` a bearer caller needs
   a role assignment that grants the operation. See [Security](search-security.md).
5. **Document-level access.** An index with permission filters returns only the documents the end user of the
   `x-ms-query-source-authorization` token may see, unless `x-ms-enable-elevated-read: true` asks for an elevated
   read. See [Document-level access](search-security.md#document-level-access).

What each parity switch changes for Search:

| Switch | Default | What it changes |
|---|---|---|
| `parity: strict` itself | off | A data-plane request needs a service created through `Microsoft.Search`; a Custom Web API or AML skill must use `https`, even for a local endpoint. It also turns on every `strict-*` override below |
| `strict-tokens` | on from `strict` or `entra.validate-tokens` | Bearer tokens, and the token of `x-ms-query-source-authorization`, must be signed by floci-az's Entra, within their lifetime and issued for `https://search.azure.com` (or `search.azure.us`, `search.azure.cn`). Off: their claims are read without verification |
| `strict-rbac` | on from `strict` | A bearer caller needs a role assignment that grants the operation, checked at the service or, for a document operation, at the index, so an assignment there or above grants it; an elevated read needs its own data action; an indexer reading through a managed identity needs the source's reader role. Off: assignments are stored and answered only |
| `strict-network` | on from `strict` | Public network access, IP rules and private endpoints are enforced on the data plane; an indexer reads a storage account closed to public access only through an `Approved` shared private link from the private execution environment, or as a trusted service; a shared private link to a resource that models approval stays `Pending` until approved. Off: the settings are stored and answered only |
| `strict-names` | on from `strict` | A service name outside the naming rule is refused on PUT with `400 InvalidResourceName` |
| `resource-group-delete` | on from `azure` | Search services are listed by `GET .../resources` and deleted with their resource group |

API keys are never checked against role assignments, and a data-plane-only service has no ARM resource to assign
a role at, so under `strict-rbac` it refuses bearer callers and still accepts keys.

---

## Indexers over floci-az storage and Cosmos DB

An indexer reads a data source of floci-az's own, in process, and writes to an index of the same service:

| Data source `type` | Reads | Change detection | Deletion detection |
|---|---|---|---|
| `azureblob`, `adlsgen2` | The blobs of `container.name`, under the `container.query` virtual folder | Built in, by last-modified time | `SoftDeleteColumnDeletionDetectionPolicy` on a metadata property |
| `azuretable` | The entities of the table `container.name` matching the `container.query` OData filter; a `Key` field holds `PartitionKey` and `RowKey` concatenated | Built in, by `Timestamp` | `SoftDeleteColumnDeletionDetectionPolicy` |
| `cosmosdb` (NoSQL) | The documents the `container.query` returns, every document without one; an `rid` field holds `_rid` Base64-encoded | `HighWaterMarkChangeDetectionPolicy` on `_ts` (the default query is `SELECT * FROM c WHERE c._ts >= @HighWaterMark ORDER BY c._ts`) | `SoftDeleteColumnDeletionDetectionPolicy` |
| `azuresql`, `mysql`, `onelake`, `sharepoint`, `azurefile` | Accepted on create; a run fails, since floci-az cannot read them | | |

### Connection strings

Resolution is syntactic: the string only has to name a floci-az account, which floci-az creates on first use. No
account key or SAS token is verified; a managed-identity string is checked as described below.

**Blob, ADLS Gen2 and Table data sources**, the first form that applies:

| Form | Example |
|---|---|
| Development storage | `UseDevelopmentStorage=true` (the account `devstoreaccount1`) |
| Managed identity | `ResourceId=/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Storage/storageAccounts/{account};` |
| Container SAS URL (not for `adlsgen2`) | `ContainerSharedAccessUri=https://{account}.blob.core.windows.net/{container}?{sas}` |
| Account key or account SAS | `DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;BlobEndpoint=http://localhost:4577/devstoreaccount1;TableEndpoint=http://localhost:4577/devstoreaccount1-table;` |

The endpoint of the service read (`BlobEndpoint` or `TableEndpoint`) wins, then `AccountName`, then the other
service's endpoint. An endpoint names its account host-style (`{account}.blob.core.windows.net`,
`{account}.dfs.core.windows.net`, `{account}.table.core.windows.net`, a sovereign cloud's suffix, or floci-az's
`{account}.blob.localhost`) or path-style (`http://{host}:{port}/{account}` for Blob,
`http://{host}:{port}/{account}-table` for Table). floci-az's Cosmos DB for Table endpoint (`/{account}-cosmos-table`)
keeps its tables apart from Table Storage and is refused.

**Cosmos DB data sources** need `Database`, and read Azure Cosmos DB for NoSQL only (an `ApiKind` such as `MongoDb` or
`Gremlin` is refused):

| Form | Example |
|---|---|
| Account key | `AccountEndpoint=http://localhost:4577/{account}-cosmos/;AccountKey=...;Database={database}` |
| Managed identity | `ResourceId=/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.DocumentDB/databaseAccounts/{account};Database={database};IdentityAuthType=AccessToken` |

`AccountEndpoint` may also be `/{account}-cosmos-nosql/`, `https://{account}.documents.azure.com`, or the root of
the emulator (`https://localhost:4577/`, the account `devstoreaccount1` that floci-az serves there for the Java SDK).

**Managed identity.** A `ResourceId=` string reads through the search service's system-assigned identity, or the
user-assigned identity the data source's `identity` names, which must be assigned to the service; otherwise the run
fails, so a data-plane-only service, which has no identity, cannot use it. Under `strict-rbac` that identity also
needs a role on the source: Storage Blob Data Reader on the container, Storage Table Data Reader on the table, or
Cosmos DB Account Reader on the account. Cosmos DB's data-plane role assignments are not modelled, so that half of
Azure's check is not made.

### Parsing and runs

- **Parsing modes**: `default` (plain text, Markdown, CSV and JSON as text, HTML and XML stripped to text, with the
  content metadata), `text`, `json`, `jsonArray` (with `documentRoot`), `jsonLines`, `delimitedText` and `markdown`.
  Every blob document has the `metadata_storage_*` properties and the blob's metadata; `metadata_storage_path` is the
  blob's path-style URL on floci-az, and the default key is its Base64 encoding (`AzureSearch_DocumentKey` for the
  one-to-many parsing modes).
- **Unsupported content**: PDF, Office, OpenDocument, e-mail, archive, image and media files are not extracted. They
  are an error when `failOnUnsupportedContentType` is true, else a warning with the metadata still indexed.
- **Field mappings** take the documented mapping functions, and implicit mappings match names ignoring case.
- **Runs**: an indexer runs when it is created or updated unless it is disabled; `search.run` answers `202`, or `409`
  while a run is in progress. Get Indexer Status reports the execution history (the newest 50) and the tier's limits;
  the blob size and extracted-text limits are enforced, the run time limit is not. `maxFailedItems` and
  `maxFailedItemsPerBatch` stop a run as a transient failure.
- **Schedules**: `schedule.interval` must lie between `indexer-min-interval-seconds` (at least 1 second) and 24 hours.
  A scheduled start that finds a run still in progress is skipped, and a persistent failure stops the schedule until
  the indexer is updated or reset.
- **Execution environment**: `executionEnvironment` (`standard` or `private`) is validated and sets the run time limit
  Get Indexer Status reports. Under `strict-network`, a storage account whose public endpoint is closed admits only a
  `private` indexer with an `Approved` shared private link to it, or a blob or ADLS Gen2 indexer that connects as a
  trusted service; any other run ends as a `transientFailure`. See
  [Network isolation](search-network-isolation.md#shared-private-links). Without `strict-network`, an indexer reads
  floci-az's storage in process whatever its environment and links.

---

## Skills and the model endpoint

The Split, Merge, Shaper and Conditional skills, Document Extraction (text formats), the Custom Web API and AML
skills and the Azure OpenAI embedding skill run. Skills that need an Azure AI services model (OCR, image analysis,
entity recognition, key phrases, language detection, sentiment, PII detection, translation, custom entity lookup,
Document Intelligence layout, Content Understanding, vision vectorization, GenAI prompt) are accepted in definitions
and fail each document that reaches them. [Skillsets and AI enrichment](search-skillsets.md) has the details.

floci-az never calls a model hosted in Azure. Point `model-endpoint` at an OpenAI-compatible embeddings endpoint,
such as a local Ollama or LM Studio server:

```yaml
floci-az:
  services:
    search:
      model-endpoint: http://localhost:11434/v1   # FLOCI_AZ_SERVICES_SEARCH_MODEL_ENDPOINT
```

| Caller | What it calls |
|---|---|
| Azure OpenAI embedding skill | Always the model endpoint: `POST {model-endpoint}/embeddings` with `{"input": [...], "model": "<deploymentId>"}`, plus `dimensions` when the skill sets it. Without a model endpoint each document fails with `Could not execute skill because the Web Api request failed` |
| `azureOpenAI` vectorizer | The model endpoint when one is set, with the deployment name (else the model name) as `model`. Without one, `{resourceUri}/openai/deployments/{deploymentId}/embeddings` when `resourceUri` is not an Azure host, such as a local stub; an Azure host fails the query with Azure's vectorization error (`400`, "Could not reach the vectorization endpoint") |
| `customWebApi`, `aml` and `openAICompatible` vectorizers | Their own URI when it is not an Azure host. When it is an Azure host (or an `aml` vectorizer has no URI), the model endpoint, for text queries only; without one, the query fails as above |
| Custom Web API and AML skills | Their own `uri`, never the model endpoint: `https`, or plain `http` to a local endpoint (a loopback or private address, `localhost`, `host.docker.internal` or a single-label host such as a Compose service) outside `parity: strict` |

The model must produce vectors of the dimensions of the index field they fill. When floci-az runs in a container,
`localhost` is the container itself: use `http://host.docker.internal:11434/v1`, or a service name on the same
network.

---

## Vectors and semantic ranking

### Vector and hybrid search

Vector fields, profiles, compression, vector queries, filter modes, hybrid queries and vectorizers are on
[Vector and hybrid search](search-vectors.md). In short: every vector query, `hnsw` or `exhaustiveKnn`, is an exact
nearest-neighbour search, so results are deterministic; several ranked lists are fused with Reciprocal Rank Fusion,
with the documented constant of 60; a vector query without `k` returns 50 neighbours.

### Semantic ranking

Azure's semantic ranker is a proprietary model. floci-az answers semantic queries with a deterministic lexical
stand-in that keeps the documented request and response shapes:

- **Requests**: `queryType=semantic` or a `semanticQuery`, with `semanticConfiguration` (else the index's
  `defaultConfiguration`) and optionally `semanticFields`. `answers`, `captions`, `queryRewrites`, `debug`,
  `semanticErrorHandling` and `semanticMaxWaitInMilliseconds` are validated as the REST API documents them. A
  semantic query with `$orderby` is `400`.
- **Ranking**: the top 50 results of the L1 ranking (BM25 or fusion, after the filter and any scoring profile) get a
  `@search.rerankerScore` from 0 to 4 and come first in that order; the rest follow in L1 order. With a scoring profile
  the response also has `@search.rerankerBoostedScore`, which ranks unless the configuration's `rankingOrder` is
  `RerankerScore`.
- **How the stand-in scores**: the share of the query's terms (stop words left out, plural endings folded) that the
  prioritized fields hold: the title weighs 3, the content 2 and the keywords 1. Inputs are cut as Learn describes
  (128 tokens of title, 128 of keywords, 2,048 in all), counting words as tokens. A query of `*` is not reranked.
- **Captions and answers**: a caption is the best-matching passage of the content (else the title, else the
  keywords), highlighted with the request's tags. Answers are extractive and only come for question-like queries
  (ending with `?` or starting with a question word), scored by the share of the question's terms the passage holds.
- **Query rewrites**: none are generated, so a request for them reports
  `@search.semanticQueryRewritesResultType: originalQueryOnly`.
- **Marker**: every semantic response carries the header `x-floci-az-semantic-ranker: stand-in`.
- **Off switch**: `semantic-ranker: disabled` refuses semantic queries on every service with `400` ("Semantic search
  is not enabled for this service."). A service's `semanticSearch` plan does not: `disabled` is still accepted from
  clients that send it, and answers semantic queries as the `free` plan, as Learn documents ("Existing services with
  `semanticSearch` set to `disabled` are automatically treated as `free`"). A data-plane-only service counts as the
  default `free` plan.

---

## Limits and guards

| Limit | floci-az | Notes |
|---|---|---|
| Request body | 16 MB | `413 RequestEntityTooLarge`, Azure's documented maximum |
| Index Documents batch | 1 to 1,000 actions | More is `413`, none is `400` |
| Document key | Letters, digits, `_`, `-` and `=`; at most 1,024 characters; not starting with `_` | A bad key fails its action with `400` |
| Documents per index | `max-documents-per-index` (100,000) | An emulator guard on memory, not an Azure quota: a new document beyond it fails with `429` |
| Fields per index | 1,000 | |
| Page size | 50 by default, at most 1,000 per response | A larger `top`, or more results than a default page holds, continues through `@search.nextPageParameters` |
| `$skip` | 0 to 100,000 | |
| `$orderby` | 32 clauses | |
| Search text | 100,000 characters; prefix, wildcard and regular expression terms at most 1,000 | |
| Vector fields per vector query | 10 | |
| Synonym maps | 20,000 rules of at most 20 expansions each | |
| Query keys per service | 50 | |
| Indexer schedule | `indexer-min-interval-seconds` (300) to 24 hours | |
| Service quotas | Not enforced | No limit on the number of indexes, indexers, data sources, skillsets, synonym maps or aliases, or on storage; `/servicestats` reports a `null` quota for each counter |

---

## Differences from Azure

**General**

- Error responses use Azure's envelope and statuses. Where Learn documents only a status, the error code and message,
  and the text of the bearer challenge, are floci-az's own.
- Management-plane writes complete before they answer: no `provisioning` or `updating` state is ever seen, and a
  shared private link's long-running create or delete reports `Succeeded` on its first poll.
- An ARM-created service accepts only its own keys. A data-plane-only service accepts any key as an admin key, and
  exists only outside `parity: strict`.
- Writes are visible as soon as they answer; Azure indexes in near real time.
- Every index is a single in-memory shard: `scoringStatistics` and `sessionId` change nothing, and `postFilter` and
  `strictPostFilter` behave the same.
- `speller`, `moreLikeThis` and `agenticOptions` are accepted and have no effect.
- Service quotas are not enforced; `max-documents-per-index` is the only cap on size.

**Queries**

- Fuzzy terms expand to at most 50 index terms, and the expansions are scored together as one BM25 term.
- Scoring profile functions rescore every match; Azure's identify the
  [top 1,000 matches](https://learn.microsoft.com/azure/search/search-relevance-overview) of the BM25 ranking.
- BM25 uses exact field lengths; Lucene stores them lossily, so scores can differ in the third decimal place.
- Autocomplete completions are lowercase forms of the original words, ranked by the number of documents they occur
  in.
- A value of at most 200 characters is highlighted as one fragment; a longer one yields its matching sentences.
- Date `interval` facets with a `week` interval start weeks on Monday, in UTC unless `timeoffset` names another
  offset.
- Setting only one of `highlightPreTag` and `highlightPostTag` is `400`.
- Analysis approximations are listed under [Analysis](#analysis).

**Vectors and semantic ranking**

- HNSW parameters are stored but every vector search is exact; the score formulas beyond `cosine` are floci-az's;
  a vector query without `k` returns 50 neighbours; scalar quantization uses the field's minimum and maximum. See
  [Vector and hybrid search](search-vectors.md#differences-from-azure).
- Semantic ranking is the stand-in described above: scores, captions and answers come from term overlap, not a
  language model; answers are given for question-like queries only; no query rewrites are generated; a semantic
  response is never partial (no `206`).

**Indexers and skills**

- Only floci-az's own Blob, ADLS Gen2, Table and Cosmos DB for NoSQL data are read; PDF, Office and image content is
  not extracted.
- `NativeBlobSoftDeleteDeletionDetectionPolicy` has no effect, since floci-az Blob Storage keeps no soft-deleted
  blobs: a run warns about it.
- `initialTrackingState` and `finalTrackingState` in the execution history are floci-az's format
  (`{"highWaterMark": ...}`).
- `fixedLengthEncode`, whose algorithm is not documented, is the URL-safe Base64 SHA-256 of the value (43 characters),
  in field mappings and output field mappings alike.
- The shortest schedule is the larger of 1 second and `indexer-min-interval-seconds` (300 by default, Azure's five
  minutes); the maximum run time is reported but not enforced.
- Custom Web API and AML skills accept plain `http` to local endpoints outside `parity: strict`; Azure requires
  `https`.
- `azureOpenAITokens` lengths and the embedding skill's 8,000-token limit count one token per four characters.
- Projected document keys are deterministic: a hash of the parent document, the parent key and the source context
  path.
- See [Skillsets and AI enrichment](search-skillsets.md#differences-from-azure) for the rest.

**Identity and network**

- Owner, Contributor and Reader match the Search actions by Azure RBAC's wildcard rules; Learn's summary table says
  they cannot create search objects or read service statistics.
- The end-user and elevated-read headers are honored on every document read (count, suggest, autocomplete and GET
  search included), not only on POST search and lookup.
- `rbacScope` permission filters are stored but not enforced.
- Network refusals on the data plane are `403 Forbidden`; shared private links are approved at once unless, under
  `strict-network`, their target models approval; private callers are recognized by subnet addresses, floci-az's own
  emulation of Private Link. See [Security](search-security.md#parity) and
  [Network isolation](search-network-isolation.md#differences-from-azure).

---

## Compatibility tests

The Search tests are part of the SDK suites, plus the Terraform, OpenTofu and Azure CLI suites:

| Suite | Search tests |
|---|---|
| `sdk-test-python` | `tests/search/`, with the markers `search_mvp`, `search_query`, `search_vector`, `search_indexer`, `search_arm` and `search_identity` |
| `sdk-test-java` | `Search*CompatibilityTest`: data plane, queries, vectors, semantic ranking, indexers, management plane, identity and network |
| `sdk-test-node` | `tests/search-mvp.test.ts`, `search-query.test.ts`, `search-vector.test.ts` |
| `sdk-test-dotnet` | `SearchCompatibilityTests` (over HTTPS) |
| `compat-terraform`, `compat-opentofu` | `azurerm_search_service` with its admin and query keys |
| `compat-azcli` | `test/search.bats`: `az search service`, `admin-key` and `query-key` |

Run a whole suite in Docker, against an emulator started with TLS, as CI does:

```bash
make test-python-compat
make test-java-compat
make test-node-compat
make test-dotnet-compat
make test-iac-compat     # Terraform + OpenTofu
make test-azcli
```

Or run only the Search tests against a local emulator: `make run` starts one with TLS on port 4577, and the suites
default to `FLOCI_AZ_ENDPOINT=http://localhost:4577`.

```bash
cd compatibility-tests/sdk-test-python    # after pip install -r requirements.txt
pytest tests/search -v
pytest tests/search -m search_vector -v   # one area

cd compatibility-tests/sdk-test-java
mvn test -Dtest='Search*CompatibilityTest'

cd compatibility-tests/sdk-test-node      # after npm install
npx jest tests/search-

cd compatibility-tests/sdk-test-dotnet
dotnet run -- --treenode-filter "/*/*/SearchCompatibilityTests/*"
```

The suites run the emulator at the default parity, where tokens and role assignments are not checked. The emulator's
own tests in `src/test/java/io/floci/az/services/search` cover `parity: strict`, `strict-tokens`, `strict-rbac` and
`strict-network` (`SearchStrictParityTest`, `SearchStrictTokensTest`, `SearchStrictRbacTest`,
`SearchNetworkFirewallTest` and others): `./mvnw test -Dtest='Search*Test'`.
