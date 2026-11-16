# Azure AI Search: authentication, roles and document-level access

How floci-az's Azure AI Search data plane decides who may call it, and which documents a query returns. It follows
Learn's "Connect to Azure AI Search using keys", "Enable or disable role-based access control", "Connect using Azure
roles", "Document-level access control" and "Query-time ACL and RBAC enforcement", the `Microsoft.Search` permission
list, and the REST API (`specification/search` in azure-rest-api-specs).

Two things are always on, because they are the service's own settings: the authentication options of a service
(`authOptions`, `disableLocalAuth`) and an index's document-level permission filters (`permissionFilterOption`).
Two things are opt-in, like every new kind of enforcement in floci-az: validating Entra tokens
(`parity-overrides.strict-tokens`) and evaluating role assignments (`parity-overrides.strict-rbac`). Both are on under
`parity: strict`.

---

## Credentials

| Service setting | API keys | Entra tokens | A request that fails authentication |
|---|---|---|---|
| `authOptions: {"apiKeyOnly": {}}` (a new service's default) | accepted | **refused with `403`** | `401` |
| `authOptions: {"aadOrApiKey": {"aadAuthFailureMode": "http401WithBearerChallenge"}}` | accepted | accepted | `401` with a bearer challenge |
| `authOptions: {"aadOrApiKey": {"aadAuthFailureMode": "http403"}}` | accepted | accepted | `403` |
| `authOptions: {"aadOrApiKey": {}}` | accepted | accepted | `401` with a bearer challenge, the documented default |
| `disableLocalAuth: true` | **refused with `403`**, even next to a token | accepted | `401` with a bearer challenge |
| a service only a data-plane request created (no ARM resource) | accepted | accepted | `401` with a bearer challenge |

- **Both credentials on one request**: the `api-key` header wins, as Learn documents ("if you provide both in the
  same request, the API key is used").
- **Keys**: an admin key may call every operation; a query key only reads the documents of an index (search, lookup,
  count, suggest, autocomplete). A key the service does not know is accepted as an admin key, and refused with `403`
  under `parity: strict`.
- **Tokens**: with `strict-tokens` off (the default), a bearer token is accepted and its claims are read without
  verification, as everywhere in floci-az. With it on, the token must be signed by floci-az's Entra, within its
  lifetime, and issued for a search audience: `https://search.azure.com` (the `https://search.azure.com/.default` scope
  the SDKs request), or the sovereign-cloud audiences `https://search.azure.us` and `https://search.azure.cn`, with or
  without a trailing slash.
- **The challenge** is `WWW-Authenticate: Bearer authorization_uri="{floci-az}/{tenant}/oauth2/v2.0/authorize",
  resource="https://search.azure.com"`, plus `error="invalid_token"` when a token was refused. `{tenant}` is the tenant
  of the service's subscription. Learn documents the challenge, not its text: this text is floci-az's.

Get a token for the data plane from floci-az's Entra:

```bash
curl -s http://localhost:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token \
  -d grant_type=client_credentials \
  -d client_id=11111111-1111-1111-1111-111111111111 \
  -d client_secret=floci-az-dev-secret \
  -d scope=https://search.azure.com/.default
```

floci-az's v2.0 endpoint gives a delegated (user) token the client's application id as its audience, which
`strict-tokens` refuses. For a user token that passes, use the v1.0 endpoint with the search resource:
`POST /{tenant}/oauth2/token` with `grant_type=password`, `client_id`, `username`, `password` and
`resource=https://search.azure.com`.

## Roles

With `strict-rbac` on, a bearer caller also needs a role assignment that grants the operation's permission. Role
assignments are created as usual (`az role assignment create`, `azurerm_role_assignment`, or
`PUT .../providers/Microsoft.Authorization/roleAssignments/{id}`), and evaluated as described in
[Azure RBAC](authorization.md#evaluation): inherited from the resource group, subscription and management groups,
granted to the caller or to any group it belongs to, transitively.

| Operations | Permission |
|---|---|
| Search, lookup, count, suggest, autocomplete, graph queries, knowledge base retrieval | data action `Microsoft.Search/searchServices/indexes/documents/read` |
| Index Documents (upload, merge, mergeOrUpload, delete) | data action `Microsoft.Search/searchServices/indexes/documents/write` |
| Elevated read (`x-ms-enable-elevated-read: true`) | data action `Microsoft.Search/searchServices/indexes/contentSecurity/elevatedOperations/read`, in addition |
| List, get, statistics and analyze of indexes | `Microsoft.Search/searchServices/indexes/read` |
| Create, update and delete an index | `Microsoft.Search/searchServices/indexes/write`, `.../indexes/delete` |
| Synonym maps, aliases, data sources, indexers, skillsets, knowledge bases, knowledge sources | `Microsoft.Search/searchServices/{type}/read`, `/write`, `/delete` |
| Run, reset, reset documents and resync an indexer | `Microsoft.Search/searchServices/indexers/write` |
| Reset skills | `Microsoft.Search/searchServices/skillsets/write` |
| Service statistics | `Microsoft.Search/searchServices/read` |

- **Scope**: a document operation is checked at `{service id}/indexes/{index}`, where Learn documents index-scoped
  assignments, so an assignment on the service or on that index grants it. Every other operation is checked at the
  service's ARM id. An index named through an alias is checked under the alias name.
- **Built-in roles**: Search Index Data Reader queries; Search Index Data Contributor also uploads and may make
  elevated reads; Search Service Contributor (`Microsoft.Search/searchServices/*`) manages every object but reads no
  documents.
- **Keys bypass roles.** A request authenticated with an API key is never checked against role assignments.
- **No ARM resource, no roles**: a service only a data-plane request created has no ARM id to assign a role at, so
  a bearer caller is refused with `403` there under `strict-rbac`; create the service through `Microsoft.Search`, or
  use a key. (Under `parity: strict` such a service does not exist at all.)
- A denial is `403 Forbidden` naming the permission and the scope; the log line names the assignments considered.

## Document-level access

An index enforces document-level permission filters when it sets `permissionFilterOption` to `enabled` and has a
`userIds` or `groupIds` permission filter field:

```json
{
  "name": "docs",
  "permissionFilterOption": "enabled",
  "fields": [
    { "name": "id", "type": "Edm.String", "key": true },
    { "name": "UserIds", "type": "Collection(Edm.String)", "filterable": true, "permissionFilter": "userIds" },
    { "name": "GroupIds", "type": "Collection(Edm.String)", "filterable": true, "permissionFilter": "groupIds" },
    { "name": "RbacScope", "type": "Edm.String", "filterable": true, "permissionFilter": "rbacScope" }
  ]
}
```

- **Definition rules** (`400` otherwise): `permissionFilterOption` is `enabled`, `disabled` or null; `permissionFilter`
  is `userIds`, `groupIds` or `rbacScope`, on a filterable `Edm.String` or `Collection(Edm.String)` field; one field
  of each type per index. A permission filter can be added to an existing field by an index update.
- **The reader** is the end user of the `x-ms-query-source-authorization` header (a token, with or without the
  `Bearer ` prefix): its object id (`oid`) for `userIds`, and for `groupIds` the groups of its `groups` claim plus every
  group floci-az's Entra directory holds it in, directly or through nested groups. Under `strict-tokens` the token is
  validated like the caller's, and a refused one is a `401`.
- **What it sees**: the documents whose `userIds` hold its object id, whose `groupIds` hold one of its groups, or whose
  `userIds` or `groupIds` hold `all`. `none`, an empty array and a missing value grant nothing. Values compare as a
  filter on the field compares them.
- **No reader**: a read without the header sees only the documents granted to `all`, whatever the caller's key or
  role, as Azure answers since November 2025.
- **Elevated read**: `x-ms-enable-elevated-read: true` returns every document. An admin key may always make one, a
  query key never; a bearer caller needs the elevated-read data action when `strict-rbac` is on (Search Index Data
  Contributor holds it).
- **Which reads are trimmed**: search (with its count and facets), lookup (a hidden document is a `404`), count,
  suggest and autocomplete. The REST API defines the two headers on search (GET and POST) and lookup, and Learn says
  the elevated-read header "only works on Search POST actions"; floci-az reads both headers on every document read,
  count, suggest and autocomplete included, so no read reveals a hidden document. Suggest and autocomplete get the
  permission filter appended to their `filter`.

## Parity

| Behaviour | floci-az | Parity switch |
|---|---|---|
| Bearer token validation | claims read without verification; validated (signature, lifetime, search audience) when on | `strict-tokens` |
| Role assignments | not consulted; evaluated for bearer callers when on | `strict-rbac` |
| `authOptions`, `disableLocalAuth` | always enforced: they are the service's own settings | none |
| Owner and Contributor (`*`) and Reader (`*/read`) | **match the documented actions by Azure RBAC's wildcard rules**, so they can read (Reader) or manage (Owner, Contributor) search objects with a token. Learn's summary table says Owner and Contributor cannot create search objects and Reader cannot read service statistics | `strict-rbac` |
| An Index Documents batch with deletes | checked as `documents/write`: every built-in role that grants it also grants `documents/delete`, and floci-az has no custom roles | `strict-rbac` |
| `rbacScope` permission filters | **not evaluated**: stored and returned, but they add no clause, so an index whose only permission field is `rbacScope` is not trimmed, and a document Azure shows only through its `rbacScope` stays hidden | none |
| OneLake permission filters (`oneLakeParentFolders`, `oneLakeLakehouseId`) | `501 NotImplemented` | none |
| Permission filter fields inside complex fields | refused with `400`; floci-az accepts them on top-level fields only | none |
| Limits on permission values (1000 per field, five `rbacScope` values per index) | not enforced | none |
| The end-user and elevated-read headers on count, suggest, autocomplete and GET search | honoured, like on POST search and lookup | none |
| An elevated read with an admin key | allowed: an admin key holds every data-plane permission. Learn names the roles that may make one, not keys | none |
| The api-versions that define permission filters (2025-05-01-preview and later previews, 2026-10-01) | not checked: the properties and headers are honoured under every api-version | none |
| A document operation through an alias | role checked at `{service id}/indexes/{alias name}` | `strict-rbac` |
| Error texts and the challenge text | floci-az's own; Learn documents the statuses | none |
