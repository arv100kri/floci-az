# Azure RBAC (role definitions and role assignments)

Compatible with the `Microsoft.Authorization` role-based access control plane used by the `azurerm`
Terraform provider (`azurerm_role_assignment`, `data "azurerm_role_definition"`), the Azure CLI
(`az role assignment ...`, `az role definition list`, `az aks create --attach-acr`), the Azure SDKs
(`azure-mgmt-authorization`) and ARM template or Bicep `Microsoft.Authorization/roleAssignments` resources.
Covers Azure's **built-in role definitions** and **role assignments** at every scope: the tenant root, a
management group, a subscription, a resource group and any resource.

> **Stored; evaluated only where a data plane asks.** Role assignments are kept and answered the way Azure answers
> them. floci-az can also evaluate them the way Azure RBAC does (see [Evaluation](#evaluation)), for a data plane to
> consult under `parity-overrides.strict-rbac` (on from `parity: strict`). The [Azure AI Search](search.md) data plane
> consults it for bearer callers (see [Azure AI Search security](search-security.md#roles)), and for the managed
> identity an indexer reads its data source through (Storage Blob Data Reader, Storage Table Data Reader or Cosmos DB
> Account Reader on the source); no other service does yet, so elsewhere an assignment grants nothing and its absence
> denies nothing.

> **HTTP-only, no Docker.** Assignments are kept in the `authorization` store, which follows the global
> `storage.mode`, so they survive a restart in a persistent mode. They are keyed by their lowercased id.

---

## Features

- **Built-in role definitions**: all of Azure's built-in roles (AcrPull, Contributor, Key Vault Secrets User,
  Owner, Reader, Storage Blob Data Contributor and the rest), generated from Azure's own role list, with their
  GUIDs, descriptions, permissions and assignable scopes. List and Get at any scope:
    - `$filter=roleName eq '<name>'` matches the name ignoring case, as Azure does; `type eq 'BuiltInRole'`
      and `type eq 'CustomRole'` filter by type.
    - Inside a subscription a role's `id` is
      `/subscriptions/{sub}/providers/Microsoft.Authorization/roleDefinitions/{guid}`, whatever scope it was
      listed at. At the tenant root and a management group it is `/providers/Microsoft.Authorization/roleDefinitions/{guid}`.
    - `permissions[].condition` and `conditionVersion` appear from api-version `2022-05-01-preview`.
- **Role assignments**: Create (`201`, `200` on a re-PUT), Get, Delete (`200` with the deleted assignment,
  `204` when there is none) and List, at any scope.
    - Every property is written, the absent ones as `null`, as Azure writes them.
    - `roleDefinitionId` is echoed in the form it was sent; `scope` is the scope of the request.
    - An omitted `principalType` is resolved from the directory: `User`, `Group` or `ServicePrincipal` for a
      principal floci-az knows (an Entra user, group or service principal, or a user-assigned identity).
    - `createdBy` and `updatedBy` are the caller's object id when the request carries a bearer token.
- **List filters**: `atScope()` lists at or above the scope; no filter lists at, above and below;
  `principalId eq '{id}'` and `assignedTo('{id}')` keep one principal's assignments at, above and below
  (`assignedTo` does not expand group membership). Other terms are ignored.
- **Azure's refusals**:
    - the same principal, role and scope under a second name: `409 RoleAssignmentExists`;
    - a name reused with another principal, or at another scope (names are unique everywhere):
      `400 RoleAssignmentUpdateNotPermitted`;
    - an unknown role: `400 RoleDefinitionDoesNotExist`.
- **Resource group delete**: the group's assignments, and those on its resources, go with it.

## Evaluation

The RBAC evaluator (`RbacEvaluator`) decides whether a principal may perform an action or a data action at a scope,
following Azure's documented steps ("How Azure RBAC determines if a user has access to a resource"):

- **Scope**: an assignment applies at its own scope and every scope below it. The scope asked about can be any ARM
  id, including a data plane child path such as `.../searchServices/{service}/indexes/{index}`, where Azure AI Search
  documents index-scoped assignments. Above a subscription come its management group, that group's ancestors and
  the tenant root `/`, in the subscription's tenant. Scopes compare ignoring case.
- **Principal**: the caller's object id (a token's `oid`), the groups of the token's `groups` claim when it has one,
  and every group the Entra directory holds the caller in, transitively through nested groups.
- **Permissions**: within each permission entry, `actions` minus `notActions`, or for a data action `dataActions`
  minus `notDataActions`. A control plane action never grants a data action. `*` matches any run of characters,
  `/` included, and matching ignores case. Assignments add up: a `notActions` entry excludes nothing another
  assignment grants.
- **Roles**: Azure's built-in roles; floci-az has no custom roles.

Each decision names the assignments that grant the operation, or says why none does, for logs and for the data
plane's own `403`.

## Parity

| Behaviour | floci-az | Parity switch |
|---|---|---|
| Unknown principal | **accepted at every level.** Azure answers `400 PrincipalNotFound`. floci-az cannot resolve every principal Azure would (a system-assigned identity's object id is derived, not stored), and `azurerm` retries that error for 30 minutes | none |
| Assignment on a resource that does not exist | `404 ResourceNotFound`, read through the resource's own handler. A type floci-az does not serve is accepted, since its existence cannot be checked | `strict-resource-groups` |
| Assignment name that is not a GUID | `400 InvalidResourceName` | `strict-names` |
| Custom role definitions (PUT, DELETE) | `501 NotImplemented` | none |
| Role definition dates (`createdOn`, `updatedOn`) | `null`: Azure's role list carries none | none |
| Role assignment conditions (Azure ABAC), when evaluated | **not evaluated: an assignment with a `condition`, or a built-in role permission entry with one, grants nothing.** Conservative: floci-az never allows what Azure might refuse, though it may refuse what Azure would allow | none |
| Deny assignments, when evaluated | none: floci-az keeps none, and Azure lets no client create one | none |

Not every error code is documented by Azure. `RoleAssignmentNotFound` (a missing assignment) and
`RoleDefinitionDoesNotExist` (an unknown role) are not in the spec, the RBAC docs or the SDKs; clients act on the
status, which is Azure's.

## Configuration

```yaml
floci-az:
  services:
    authorization:
      enabled: true   # FLOCI_AZ_SERVICES_AUTHORIZATION_ENABLED
  # parity-overrides:
  #   strict-rbac: true   # FLOCI_AZ_PARITY_OVERRIDES_STRICT_RBAC; default on from parity: strict
```

## Examples

```bash
# Look a role up by name, then assign it to a managed identity on a Key Vault
az role definition list --name "Key Vault Secrets User" --query "[0].id" -o tsv
az role assignment create --assignee-object-id <principal-id> --assignee-principal-type ServicePrincipal \
  --role "Key Vault Secrets User" --scope <vault-id>
az role assignment list --scope <vault-id>

# Let a user query one Azure AI Search index; enforced under parity-overrides.strict-rbac
az role assignment create --assignee-object-id <user-object-id> --assignee-principal-type User \
  --role "Search Index Data Reader" --scope <search-service-id>/indexes/hotels
```

```hcl
resource "azurerm_role_assignment" "kv_reader" {
  scope                = azurerm_key_vault.example.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.example.principal_id
}
```
