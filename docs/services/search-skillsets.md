# Azure AI Search: skillsets and AI enrichment

floci-az runs Azure AI Search skillsets in process: an indexer that names a skillset sends every document
through the enrichment pipeline before it is indexed. Utility skills, Custom Web API skills, AML skills and the
Azure OpenAI embedding skill run; skills that need an Azure AI services model are accepted in definitions and fail
the documents that reach them with an error naming the skill.

---

## Skillset operations

```
POST   /skillsets                                  # Create Skillset
PUT    /skillsets('{name}')                        # Create or Update Skillset (If-Match, If-None-Match, Prefer)
GET    /skillsets('{name}')                        # Get Skillset
GET    /skillsets?$select=name,description         # List Skillsets
DELETE /skillsets('{name}')                        # Delete Skillset (If-Match)
POST   /skillsets('{name}')/search.resetskills     # Reset Skills: {"skillNames": [...]}
```

Both URL forms work (`skillsets('x')` and `skillsets/x`), on `{service}.search.windows.net` and on
`/{service}-search/`. Get Service Statistics reports the number of skillsets in `counters.skillsetCount`.

- **Validation** follows the REST specification: lowercase names of 1 to 128 letters, digits and dashes; 1 to 30
  skills of a known `@odata.type`; unique skill names; contexts, sources and source contexts that start at
  `/document`; input expressions that parse; the input and output names of the skills floci-az runs
  (`Outputs are not supported by skill: ...`); the documented parameter ranges (`maximumPageLength` 300 to 50000
  characters, `timeout` 1 to 230 seconds, `degreeOfParallelism` 1 to 10, the embedding model's `dimensions`);
  `cognitiveServices` of a known type; and `indexProjections` whose target index exists, has a key field with the
  `keyword` analyzer, and a filterable `Edm.String` parent key field.
- **Responses**: the answer to a create or update is the definition as sent, with every documented property present
  (`null` when not given). The stored definition, which Get, List and the indexer see, adds the defaults the
  service applies: skill names by position (`#1`, `#2`, ...), the `/document` context and the `en` default language.
- **Secrets**: Get and List answer `<redacted>` for the `sig` and `code` query values of a Custom Web API skill's
  `uri` and for its `httpHeaders` values. An update of the same skill and endpoint can send those placeholders, or
  `<unchanged>`, to keep the stored values; a placeholder that cannot be resolved is a 400.
- `knowledgeStore`, `cognitiveServices` and `encryptionKey` are stored and returned, not acted on. Setting
  `encryptionKey` to `null` keeps the existing key, as documented.

Skillsets are stored in the `search` storage backend under `dp/skillset/{service}/{name}`.

---

## Skills

| Skill | Behavior in floci-az |
|---|---|
| `#Microsoft.Skills.Text.SplitSkill` | Runs: `pages` and `sentences`, `maximumPageLength`, `pageOverlapLength`, `maximumPagesToTake`, `defaultLanguageCode` and `languageCode`; outputs `textItems`, `offsets`, `lengths` and `ordinalPositions` |
| `#Microsoft.Skills.Text.MergeSkill` | Runs: `insertPreTag`, `insertPostTag`, `offsets`; outputs `mergedText` and `mergedOffsets` |
| `#Microsoft.Skills.Util.ShaperSkill` | Runs, including nested inputs with `sourceContext` |
| `#Microsoft.Skills.Util.ConditionalSkill` | Runs, with the annotation language's expressions |
| `#Microsoft.Skills.Util.DocumentExtractionSkill` | Runs for text formats (plain text, Markdown, CSV, JSON, HTML and XML without markup); other formats are a warning |
| `#Microsoft.Skills.Custom.WebApiSkill` | Runs: calls `uri` with the documented request and response contract |
| `#Microsoft.Skills.Custom.AmlSkill` | Runs with key authentication: posts each record to `uri` |
| `#Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill` | Runs against the configured model endpoint (below) |
| OCR, Image Analysis, Entity Recognition, Entity Linking, Key Phrases, Language Detection, Sentiment, PII Detection, Translation, Custom Entity Lookup, Document Intelligence Layout, Content Understanding, Vision Vectorize, GenAI Prompt | Accepted; a document that reaches one fails with `Could not execute skill because the skill '...' of type '...' is not emulated by floci-az` |

Skills run in dependency order: a skill runs after every skill whose output it reads through its context, an input
path, an expression or a source context. Each skill runs once per node of its context, and its outputs become
annotations of that node under their `targetName`.

### Custom Web API skill

Records are posted as `{"values": [{"recordId": "0", "data": {...}}]}`, at most `batchSize` (1000) per call and
`degreeOfParallelism` (5) calls at a time, each within `timeout` (`PT30S`), with `httpHeaders`. 429, 502 and 503 are
retried twice. A failed call, a response that is not `application/json` or has no `values` array, and a record that
is missing or repeated in the response are errors; each record's `errors` and `warnings` are reported. Inputs whose
source selects nothing are sent as `null`.

Azure only accepts `https` URIs, and so does floci-az, with one exception outside `parity: strict`: plain `http` to
a local endpoint is accepted, so that a skill under development can be called. A local endpoint is a loopback,
private or link-local address (`127.0.0.1`, `10.x`, `192.168.x`, `[::1]`), `localhost` or `*.localhost`,
`host.docker.internal`, or a single-label host name such as a Docker Compose service (`http://my-skill:8080/api`).
Any other `http` URI, such as `http://example.invalid/api`, is a 400. The same applies to the AML skill.

### Azure OpenAI embedding skill

Azure OpenAI is not reachable from the emulator, so the skill calls an OpenAI-compatible endpoint instead of
`resourceUri`:

```yaml
floci-az:
  services:
    search:
      model-endpoint: http://localhost:11434/v1   # FLOCI_AZ_SERVICES_SEARCH_MODEL_ENDPOINT
```

The request is `POST {model-endpoint}/embeddings` with `{"input": [...], "model": "<deploymentId>"}`, plus
`dimensions` when the skill sets it and the `apiKey` as `api-key` and bearer token headers. Without a model endpoint,
documents fail with `Could not execute skill because the Web Api request failed`, as when the resource cannot be
reached.

---

## Output field mappings and index projections

`outputFieldMappings` map enrichment paths (`/document/pages/*/vector`) to index fields, with the mapping functions
`base64Encode`, `base64Decode`, `extractTokenAtPosition`, `jsonArrayToStringCollection`, `urlEncode`, `urlDecode`,
`fixedLengthEncode` and `toJson`. A path that selects nothing is the documented `Could not map output field` warning.

`indexProjections` turn each node of a selector's `sourceContext` into a document of its `targetIndexName`, with the
parent document's key in `parentKeyFieldName`. With `projectionMode` `skipIndexingParentDocuments` the parent
document itself is not indexed. A projected document's key has the documented segments: a hash of the parent
document (it changes when the parent changes), the parent key and the source context path, such as
`3f1c2a9b8d7e_aHR0cHM6Ly9ibG9i0_pages_0`.

---

## Differences from Azure

- `azureOpenAITokens` lengths in the Split skill, and the embedding skill's 8,000-token limit, count one token per
  four characters instead of running a tokenizer.
- Sentence boundaries come from the JDK's `BreakIterator`, so pages and sentences can break differently from Azure.
- A Split page after the first starts with up to `pageOverlapLength` characters of the previous page, from a word
  start.
- Joining strings with `+` in an expression puts a space between them, as the documented examples show.
- `fixedLengthEncode` is the hexadecimal SHA-256 of the value; its algorithm is not documented.
- Web API batches hold the records of one document: floci-az enriches documents one at a time.
- Document Extraction reads text formats only, and produces no `normalized_images`.
- Plain `http` custom skill URIs to local endpoints are accepted outside `parity: strict`.
- Merge skill offsets that cannot be read are a warning rather than an error, since the skill still produces its
  output with the items at the end.
- A missing optional skill input is not reported; a missing required input is the documented warning.
- The enrichment cache does not exist, so Reset Skills only validates the request.
- Knowledge store projections are stored, not written.
