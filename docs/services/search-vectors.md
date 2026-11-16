# Azure AI Search: vector and hybrid search

This page covers vector fields, vector queries, hybrid queries and vectorizers in the floci-az Azure AI Search data
plane (`{service}.search.windows.net` or `/{service}-search/`). It follows the public REST API and is checked with
`azure-search-documents` for Java, Python and JavaScript (the `search-vector` tests of the compatibility suites).

Vector search runs in floci-az's own engine: no Lucene, no vector database, no sidecar. Every vector query is an exact
nearest-neighbour search, so results are deterministic.

## Index definitions

- **`vectorSearch` section**: `algorithms`, `profiles`, `compressions` and `vectorizers`, validated and returned with the
  documented defaults filled in (HNSW `m` 4, `efConstruction` 400, `efSearch` 500, metric `cosine`; rescoring enabled
  and `preserveOriginals`; an Azure OpenAI `resourceUri` without its trailing slash). The four arrays are always present
  once the section is.
- **Algorithms**: `hnsw` (`m` 4 to 10, `efConstruction` and `efSearch` 100 to 1000) and `exhaustiveKnn`, with the
  metrics `cosine`, `euclidean`, `dotProduct` and `hamming`. `hamming` is only valid for `Collection(Edm.Byte)` fields
  with `vectorEncoding: packedBit`, and those fields need it.
- **Profiles** name an algorithm and optionally a vectorizer and a compression; each name must exist.
- **Compressions**: `scalarQuantization` (`int8`) and `binaryQuantization`, with `rescoringOptions`
  (`enableRescoring`, `defaultOversampling` of at least 1 and only with rescoring, `rescoreStorageMethod`) and
  `truncationDimension`. The 2024-07-01 properties (`rerankWithOriginalVectors`, a top-level `defaultOversampling`) are
  accepted too. Compression applies to `Collection(Edm.Single)` and `Collection(Edm.Half)` fields.
- **Vectorizers**: `azureOpenAI`, `customWebApi`, `aml`, `aiServicesVision` and `openAICompatible`, each with its
  parameters object. An Azure OpenAI model name bounds the dimensions of the fields it serves.
- **Vector fields**: dimensions 2 to 4096, a profile, always searchable, never filterable, sortable or facetable;
  `retrievable` defaults to false and `stored` to true. `stored: false` needs `retrievable: false`, cannot be set on a
  field added by an update, and, as documented, a `merge` or `mergeOrUpload` that omits such a field loses its vector.
- **Updates** cannot change a compression's `rescoreStorageMethod`.
- **Secrets**: GET and LIST return `<redacted>` for vectorizer API keys, the `aml` key, `customWebApi` header values and
  the `sig` and `code` values of its URI, and leave out `openAICompatible` headers. An update can send the
  `<redacted>` or `<unchanged>` placeholders back (or omit the `openAICompatible` headers) to keep the stored secrets of
  the same vectorizer at the same endpoint; a placeholder without such a secret is a 400.
- **Documents**: vectors must have the field's length (`dimensions / 8`, rounded up, for packed bits) and element type;
  a mismatch is the documented "There's a mismatch in vector dimensions" error.

## Vector queries

- **Kinds**: `vector` (a raw vector), `text`, `imageUrl` and `imageBinary` (vectorized by the field's vectorizer).
- **Parameters**: `fields` (up to 10, comma-separated), `k` (50 when absent), `exhaustive`, `oversampling` (only on
  compressed fields), `weight`, `threshold` (`vectorSimilarity` or `searchScore`) and `filterOverride`.
- **Scores**: `@search.score` is `1 / (1 + cosine distance)` for `cosine`, as documented; `1 / (1 + distance)` for
  `euclidean` and `hamming`; and a 0 to 1 transform of the dot product for `dotProduct`.
- **Filters** follow `vectorFilterMode`: `preFilter` (default) picks the nearest neighbours among the filtered
  documents; `postFilter` and `strictPostFilter` filter the unfiltered top `k`, so fewer than `k` can come back.
- **Several lists** (several vector queries, several fields, or a text query beside them) are fused with Reciprocal Rank
  Fusion: each list adds `weight / (60 + rank)`. A single vector list keeps its similarity scores.
- **Hybrid queries**: the text list is the BM25 ranking cut to `hybridSearch.maxTextRecallSize` (1,000 by default).
  With `countAndFacetMode: countAllResults` (the default) `@odata.count` also counts the text matches beyond that window.
  `search: "*"` adds a text list that matches every document; an absent or empty `search` makes a pure vector query.
- **Compression**: on an HNSW field, candidates are compared on int8 or binary-quantized vectors (truncated to
  `truncationDimension`); with rescoring the best `k * oversampling` are rescored on the full-precision vectors, so the
  final scores are exact. Without rescoring the scores are the compressed comparison's. `exhaustiveKnn` fields are
  always scored at full precision.
- **Debug**: `debug: vector` (or `all`) returns `@search.documentDebugInfo.vectors.subscores` with the text score and
  each vector query's `searchScore` and `vectorSimilarity` per field.

## Vectorizers

A `text`, `imageUrl` or `imageBinary` query is vectorized before the query runs, once per vectorizer:

| Vectorizer | Call | Query kinds |
|---|---|---|
| `azureOpenAI` | `POST {resourceUri}/openai/deployments/{deploymentId}/embeddings` with `api-key` | `text` |
| `customWebApi` | the custom skill contract (`values[0].data.text`, `imageUrl` or `imageBinary`; the vector in `data.vector`) | all three |
| `aml` | `POST {uri}` with `{"input": [...], "input_type": "query"}` | `text`, `imageBinary` |
| `openAICompatible` | `POST {uri}` with an OpenAI embeddings body without `model` | `text` |
| `aiServicesVision` | not emulated (501) | |

floci-az never calls a model hosted in Azure. Set `model-endpoint` to an OpenAI-compatible embeddings endpoint (for
example a local Ollama or LM Studio server) and the Azure OpenAI vectorizer calls `POST {model-endpoint}/embeddings` with
`{"input": "...", "model": "<deploymentId or modelName>"}` instead; vectorizers of the other kinds whose endpoint is an
Azure host use it for text queries too. Without it, such a call fails with Azure's vectorization error (400, an empty
error code, "Could not complete vectorization action. Could not reach the vectorization endpoint."). Endpoints that are
not Azure hosts, such as a local stub, are called directly.

```yaml
floci-az:
  services:
    search:
      model-endpoint: http://localhost:11434/v1   # FLOCI_AZ_SERVICES_SEARCH_MODEL_ENDPOINT
```

## Deviations from Azure

- Every vector query is an exact nearest-neighbour search; HNSW parameters are stored but do not change results. On
  small indexes Azure's HNSW results are normally exact too.
- Each index is one shard, so `postFilter` and `strictPostFilter` behave the same.
- Azure documents the `cosine` score formula and only the 0 to 1 range of the other metrics; the `euclidean`,
  `dotProduct` and `hamming` formulas above are floci-az's.
- Scalar quantization uses the field's minimum and maximum values as the int8 range, where Azure uses a percentile
  range; binary quantization keeps one sign bit per dimension.
- The request and response bodies of the `aml` vectorizer are not documented; floci-az uses the Azure AI model inference
  embeddings shape and accepts the common response shapes.
- A vector query must name its `fields`; Azure documents the property as optional without saying what its absence does.
- `perDocumentVectorLimit` and topic graph options are accepted and ignored; a vector field inside a complex collection
  matches through its closest vector.
