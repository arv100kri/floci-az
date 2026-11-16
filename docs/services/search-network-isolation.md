# Azure AI Search: network isolation

floci-az models the network isolation of an [Azure AI Search](search.md) service from ARM state and transport
addresses only, with no new endpoints: public network access, IP firewall rules, private endpoint connections, the
service's private link resource, shared private links for outbound access, and an empty list of network security
perimeter configurations.

The settings are always stored and answered. They are **enforced on the data plane only under `strict-network`**
(`parity: strict`, or `parity-overrides.strict-network: true`), so a default emulator accepts every caller, as before.
Network checks run before any credential is read; keys, tokens and roles are on [Security](search-security.md).

## Configuration

```yaml
floci-az:
  parity: compat                    # strict turns strict-network on
  parity-overrides:
    strict-network: true            # enforce public network access, IP rules and private endpoints
  services:
    search:
      trust-client-ip-header: false # test hook: IP rules read the caller from x-floci-az-client-ip
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `parity-overrides.strict-network` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_NETWORK` | on from `strict` | `true`: the search data plane refuses callers its network settings exclude, with `403 Forbidden` |
| `services.search.trust-client-ip-header` | `FLOCI_AZ_SERVICES_SEARCH_TRUST_CLIENT_IP_HEADER` | `false` | Test hook: IP firewall rules match the address in the `x-floci-az-client-ip` header instead of the transport peer address, so a rule that names a public address can be tested from one machine. The header never makes a caller private |

## Management plane

Every operation of the 2026-11-01 spec for these children of `Microsoft.Search/searchServices` is served, for every
api-version:

| Resource | Operations | Notes |
|---|---|---|
| `privateEndpointConnections` | list, get, update, delete | The connections Microsoft.Network private endpoints make to the service, named `{endpoint}.{guid}`. An update approves or rejects one (`Pending`, `Approved`, `Rejected`, `Disconnected`); a delete answers `200` with the connection `Disconnected` and removes it from the service |
| `privateLinkResources` | list | One resource, `searchService`, with `requiredZoneNames` `privatelink.` plus the search suffix of the cloud (`privatelink.search.windows.net`), and the shareable resource types of the spec's example |
| `sharedPrivateLinkResources` | create or update, get, list, delete | Create and delete answer `202` with `Azure-AsyncOperation` and `Location` naming an `operationStatuses` monitor; the work is done before the answer, so the monitor reports `Succeeded` on its first poll |
| `networkSecurityPerimeterConfigurations` | list, get, reconcile | floci-az models no network security perimeter: the list is empty, and a get or a reconcile is `404` |

The service's own `properties.privateEndpointConnections` and `properties.sharedPrivateLinkResources` list the same
objects.

### Private endpoint connections

A connection is created by creating a [`Microsoft.Network/privateEndpoints`](network.md) resource whose
`privateLinkServiceId` is the search service: one in `privateLinkServiceConnections` starts `Approved`, one in
`manualPrivateLinkServiceConnections` starts `Pending`. Both sides show one state: an approval, a rejection or a
removal made on the search service is written onto the private endpoint, whose own GET shows it, and a later PUT of
the endpoint keeps it. Deleting the endpoint removes the connection; deleting the search service leaves its endpoints
`Disconnected`, as Private Link leaves the endpoints of a removed resource.

### Shared private links

A link is checked against Learn's limits before it is written:

- Free, Serverless and S3 in high-density hosting mode have none (`400 BadRequest`).
- The most links and distinct group IDs per tier: Basic 30 and 4, S1 100 and 7, S2 and S3 400 and 15, L1 and L2 20
  and 4. Learn gives Basic "10 or 30" depending on when the service was created; floci-az takes 30.
- One link for each resource and group ID: a second one is `409 Conflict`.

A create names the resource and the group ID; a PUT to an existing link keeps whatever it omits (azurerm's update of
`request_message` sends the message alone).

A link is `Approved` and `Succeeded` once its PUT answers, as floci-az's private endpoints are approved. Under
`strict-network`, a link to a resource whose own private endpoint connections floci-az models stays `Pending` until
that resource's owner approves it there. No floci-az service models those connections yet (Storage, Cosmos DB and
Key Vault would, through the `SharedPrivateLinkTarget` interface), so today every link is approved.

Indexers read floci-az's Blob, Table and Cosmos DB services in process. Without `strict-network`, an indexer reads its
data source whatever its `executionEnvironment` and the service's links. Under `strict-network`, before a run reads
anything, it checks that the storage account it reads admits it: the account a `ResourceId=` connection string names,
else the `Microsoft.Storage/storageAccounts` resource that owns the account name. The account's public endpoint is
closed when its `publicNetworkAccess` is `Disabled` or `SecuredByPerimeter`, or its `networkAcls.defaultAction` is
`Deny`. An open account admits indexers in both execution environments, with or without links. A closed one admits
only:

- an indexer with `executionEnvironment: private`, when the service holds an `Approved` link to the account for the
  group of the data source's type: `blob` (`azureblob`), `dfs` (`adlsgen2`) or `table` (`azuretable`). Only the private
  execution environment can use a link, and Azure does not guarantee to pick it unless the indexer asks for it, so any
  other indexer is refused even when a link exists.
- an `azureblob` or `adlsgen2` indexer that connects as a trusted service: a `ResourceId=` connection string read with
  the search service's system-assigned identity (the data source names no user-assigned `identity`), to an account
  whose `networkAcls.bypass` includes `AzureServices`, its default. As in Azure, the exception stays in effect when
  `publicNetworkAccess` is `Disabled`; `"bypass": "None"` withdraws it. `SecuredByPerimeter` never honors it: no
  network security perimeter is modelled, so the account behaves as if in an enforced perimeter with no access rule.

A refused run ends as a `transientFailure` whose message names the account, why it refused and the ways in. A source
floci-az holds no network rules for is reachable: an Azure Cosmos DB account (floci-az serves no
`Microsoft.DocumentDB` accounts) and a storage account that was never created through ARM. The account's IP, virtual
network and resource instance rules are not evaluated, since floci-az's indexers have no outbound address, so
`defaultAction: Deny` refuses every other indexer. floci-az's Storage data plane does not enforce these settings on its
own callers. `executionEnvironment: private` also sets the run time limit the indexer's status reports.

## Data plane enforcement

Under `strict-network`, before any credential is looked at:

| `publicNetworkAccess` | `networkRuleSet.ipRules` | Admitted |
|---|---|---|
| `Enabled` | none | everyone |
| `Enabled` | IPv4 addresses or CIDR ranges | private callers, and public callers whose address a rule contains |
| `Disabled` | ignored | private callers only |
| `SecuredByPerimeter` | ignored | private callers only: no perimeter is modelled, so the service behaves as if in a perimeter with no inbound access rule |

Everyone else gets `403` with the error code `Forbidden`. `networkRuleSet.bypass` (`AzureServices`, `AzurePortal`)
admits no one: no request reaches floci-az from Azure's trusted services or the portal. A service a data-plane request
provisioned (no ARM resource) is open.

### How floci-az recognizes a private caller

This is floci-az's own emulation, not Azure's mechanism. A request is private when its **transport peer address** (never
a forwarded header) falls inside the address prefix (`addressPrefix` or `addressPrefixes`, IPv4 or IPv6) of the subnet
that hosts a private endpoint whose connection to the service is `Approved`. The subnet addresses Azure reserves for
the network, its default gateway (the first address) and, for IPv4, its broadcast are never private callers. A private
endpoint whose subnet floci-az does not hold admits no one.

Locally, give floci-az and the application containers a Docker network with the same CIDR as the subnet the private
endpoint sits in. Docker gives the network's first address to its gateway, through which requests from the host
arrive, so they stay public and are refused when public network access is disabled:

```bash
docker network create --subnet 10.0.1.0/24 search-private
docker run -d --name floci-az --network search-private --ip 10.0.1.2 \
  -e FLOCI_AZ_PARITY_OVERRIDES_STRICT_NETWORK=true floci/floci-az:latest
docker run --rm --network search-private my-app   # 10.0.1.x: a private caller
```

with a subnet such as:

```bicep
resource subnet 'Microsoft.Network/virtualNetworks/subnets@2024-05-01' = {
  name: 'search-pe'
  parent: vnet
  properties: { addressPrefix: '10.0.1.0/24' }
}
```

With `floci-az.tls.enabled`, HTTP and HTTPS share the port through a TCP proxy, which hands each connection to the
emulator with a PROXY protocol header naming the caller: private callers and IP rules see the caller's own address,
with TLS on as with it off.

## HTTPS host names

The self-signed certificate names `*.search.windows.net`, `*.search.azure.us` and `*.search.azure.cn` (the search
suffixes of the cloud catalog), so an HTTPS client can call `https://{service}.search.windows.net` once that name
resolves to floci-az (see [Host style and HTTPS](search.md#host-style-and-https)).

## Differences from Azure

- The error codes and messages of a refused request or write are not public: the data plane answers `403 Forbidden`,
  and the management plane `400 BadRequest` naming the rule, or `409 Conflict` for a second link to one resource and
  group.
- Creates and deletes complete before they answer; no `Updating` or `Deleting` state is ever seen.
- Any connection status can be set by an update; Azure may refuse some transitions.
- A private endpoint to a Free tier service is not refused.
- The connection descriptions a private endpoint starts with (`Auto-approved`, `Awaiting approval`) are
  Microsoft.Network's in floci-az.
