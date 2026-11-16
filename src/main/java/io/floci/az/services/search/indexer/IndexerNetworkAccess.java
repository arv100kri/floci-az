package io.floci.az.services.search.indexer;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.network.SearchOutboundNetwork;
import io.floci.az.services.storageaccounts.StorageAccountArmService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whether the network rules of the resource a run reads admit the indexer: the check a run makes before it reads its
 * data source, only under {@link EmulatorConfig#strictNetwork()}. Without it network settings are stored and answered
 * only, so a run reads its source whatever its {@code executionEnvironment} and the search service's shared private
 * links.
 *
 * <p>Learn's "Indexer access to content protected by Azure network security" describes the private execution
 * environment as "Internal to a search service", and says "Only the private execution environment can use a shared
 * private link". Otherwise both environments connect over the resource's public endpoint, whose IP firewall must admit
 * "the IP address used by the search service, and the IP addresses used by the multitenant environment". So a resource
 * whose public endpoint is open admits a run in either environment, with or without links, and a closed one admits
 * only:
 * <ul>
 *   <li>a run in the private execution environment when the search service holds a usable shared private link
 *       ({@link SearchOutboundNetwork#allows}) to the resource, for the group id of the data source's type:
 *       {@code blob} (azureblob), {@code dfs} (adlsgen2), {@code table} (azuretable) or {@code Sql} (cosmosdb);</li>
 *   <li>a run that connects as a trusted service. Per Learn's "Connect as Trusted Service", "a trusted service
 *       connection is limited to blobs and ADLS Gen2 on Azure Storage. It's unsupported for indexer connections to
 *       Azure Table Storage and Azure Files", and "A trusted service connection must use a system-assigned managed
 *       identity": a {@code ResourceId} connection string whose data source names no user-assigned identity (the
 *       identity itself, and its role under strict-rbac, are checked first, by {@link ManagedIdentityAccess}). The
 *       account must "Allow trusted Microsoft services to access this storage account": {@code AzureServices} in its
 *       {@code networkAcls.bypass}, that property's default in the Microsoft.Storage REST spec. Public network access
 *       {@code Disabled} does not revoke it: per "Guidelines &amp; limitations: Azure Storage firewall", "access to a
 *       storage account from trusted services takes the highest precedence over other network access restrictions",
 *       and the exceptions configured before public network access is disabled "will remain in effect".</li>
 * </ul>
 *
 * <p>The resource is the storage account a {@code ResourceId} connection string names when floci-az's ARM holds it,
 * else the {@code Microsoft.Storage/storageAccounts} resource that owns the account name. Its public endpoint is closed
 * when its {@code publicNetworkAccess} is {@code Disabled} (Learn's "Set the default public network access rule":
 * "Traffic will be allowed only through a private endpoint") or its {@code networkAcls.defaultAction} is {@code Deny}.
 * Its IP, virtual network and resource instance rules are not evaluated, since floci-az's indexers have no outbound
 * address, so {@code Deny} refuses every other run. {@code SecuredByPerimeter} closes it too: floci-az models no
 * network security perimeter, so the account is as in an enforced perimeter without access rules, where Learn's
 * "Network security perimeter for Azure Blob, Data Lake, Queue, and Table Storage" says the trusted service exceptions
 * "aren't honored" and "Connections via private link always succeed".</p>
 *
 * <p>A resource floci-az holds no network rules for is publicly reachable: an Azure Cosmos DB account, since floci-az
 * serves no {@code Microsoft.DocumentDB} accounts, and a storage account that was never created through ARM. A refused
 * run fails as a transient failure that names the account, why it refuses, and the ways in.</p>
 */
@ApplicationScoped
public class IndexerNetworkAccess {

    /** The {@code networkAcls.bypass} flag that lets trusted Microsoft services in, and the property's default. */
    private static final String AZURE_SERVICES = "AzureServices";

    private final EmulatorConfig config;
    private final SearchOutboundNetwork outbound;
    private final GlobalNameRegistry names;
    private final AzureDirectory directory;
    private final StorageAccountArmService storageAccounts;

    @Inject
    public IndexerNetworkAccess(EmulatorConfig config, SearchOutboundNetwork outbound, GlobalNameRegistry names,
                                AzureDirectory directory, StorageAccountArmService storageAccounts) {
        this.config = config;
        this.outbound = outbound;
        this.names = names;
        this.directory = directory;
        this.storageAccounts = storageAccounts;
    }

    /**
     * Checks that the resource the run reads admits it.
     *
     * @param service          the search service
     * @param dataSource       the data source
     * @param privateExecution whether the indexer runs in the private execution environment
     * @param accountName      the storage or Cosmos DB account the connection string names
     * @param resourceId       the connection string's {@code ResourceId}, or null when it has none
     * @throws IndexerRunException when the resource's public endpoint is closed, and neither a shared private link nor
     *                             the trusted service exception admits the run
     */
    public void check(SearchServiceInfo service, DataSourceDefinition dataSource, boolean privateExecution,
                      String accountName, String resourceId) throws IndexerRunException {
        if (!config.strictNetwork()) {
            return;
        }
        Optional<AccountNetwork> found = storageAccount(service, dataSource, accountName, resourceId);
        if (found.isEmpty() || found.get().open()) {
            return;
        }
        AccountNetwork account = found.get();
        String groupId = groupId(dataSource.type());
        boolean linked = outbound.allows(service.name(), account.id(), groupId);
        if (privateExecution && linked) {
            return;
        }
        boolean trustedService = connectsAsTrustedService(dataSource, resourceId);
        if (trustedService && account.admitsTrustedServices()) {
            return;
        }
        throw new IndexerRunException(refusal(service, dataSource, account, groupId, privateExecution, linked,
            trustedService));
    }

    /** The group id (sub-resource) a shared private link to the data source names, per Learn's resource types. */
    private static String groupId(DataSourceType type) {
        return switch (type) {
            case AZURE_BLOB -> "blob";
            case ADLS_GEN2 -> "dfs";
            case AZURE_TABLE -> "table";
            case COSMOS_DB, DOCUMENT_DB -> "Sql";
            default -> throw new IllegalArgumentException("floci-az does not index data sources of type '"
                + type.wireName() + "'.");
        };
    }

    /**
     * Whether the run connects as a trusted service: a blob or ADLS Gen2 source read with the search service's
     * system-assigned identity, through a {@code ResourceId} connection string whose data source names no user-assigned
     * identity.
     */
    private static boolean connectsAsTrustedService(DataSourceDefinition dataSource, String resourceId) {
        return dataSource.type().isBlob() && resourceId != null && dataSource.userAssignedIdentity() == null;
    }

    /**
     * The network settings of the storage account the run reads: the one a {@code ResourceId} names when floci-az's
     * ARM holds it, else the one that owns the account name. Empty for an Azure Cosmos DB source, and for an account
     * floci-az's ARM does not hold.
     */
    private Optional<AccountNetwork> storageAccount(SearchServiceInfo service, DataSourceDefinition dataSource,
                                                    String accountName, String resourceId) {
        if (dataSource.type().isCosmos()) {
            return Optional.empty();
        }
        return ArmResourceId.parse(resourceId).flatMap(storageAccounts::account)
            .or(() -> owner(service, accountName).flatMap(storageAccounts::account))
            .map(AccountNetwork::of);
    }

    /** The ARM resource that owns the storage account name in the search service's cloud. */
    private Optional<ArmResourceId> owner(SearchServiceInfo service, String accountName) {
        if (accountName == null) {
            return Optional.empty();
        }
        return names.owner(cloudOf(service), CloudSuffix.BLOB, accountName);
    }

    /** The cloud of the search service's subscription; the default cloud for a service without an ARM resource. */
    private CloudProfile cloudOf(SearchServiceInfo service) {
        return ArmResourceId.parse(service.resourceId())
            .map(ArmResourceId::subscriptionId)
            .map(subscription -> directory.cloudOfTenant(directory.tenantOfSubscription(subscription)))
            .orElseGet(directory::defaultCloud);
    }

    /** Why the account refuses the run, and the ways in that apply to it. */
    private static String refusal(SearchServiceInfo service, DataSourceDefinition dataSource, AccountNetwork account,
                                  String groupId, boolean privateExecution, boolean linked, boolean trustedService) {
        StringBuilder message = new StringBuilder("Error with data source: The storage account '")
            .append(account.id()).append("' refuses the indexer, since ")
            .append(String.join(" and ", account.closures())).append('.');
        String link = "a shared private link resource to the group '" + groupId + "' of the account";
        List<String> ways = new ArrayList<>();
        if (privateExecution) {
            message.append(" The indexer runs in the private execution environment, but the search service '")
                .append(service.name()).append("' has no Approved shared private link to the group '").append(groupId)
                .append("' of the account.");
            ways.add("create " + link + " and approve its connection");
        } else if (linked) {
            message.append(" The search service '").append(service.name()).append("' has an Approved shared private ")
                .append("link to the group '").append(groupId).append("' of the account, but only the private ")
                .append("execution environment can use a shared private link, and the indexer runs in the standard ")
                .append("environment.");
            ways.add("set the indexer's executionEnvironment to private");
        } else {
            ways.add("create " + link + ", approve its connection and set the indexer's executionEnvironment to "
                + "private");
        }
        if (dataSource.type().isBlob() && !account.securedByPerimeter()) {
            ways.add(trustedService
                ? "add AzureServices to the account's networkAcls.bypass, so the search service connects as a "
                    + "trusted service"
                : "connect as a trusted service: a ResourceId connection string read with the search service's "
                    + "system-assigned identity, to an account whose networkAcls.bypass includes AzureServices");
        }
        ways.add("open the account to public network access (publicNetworkAccess Enabled, networkAcls.defaultAction "
            + "Allow)");
        return message.append(" To read it, ").append(String.join("; or ", ways)).append('.').toString();
    }

    /**
     * What decides whether a storage account admits an indexer.
     *
     * @param id                 the account's ARM id, in the casing it was created with
     * @param closures           why its public endpoint is closed; empty when it is open
     * @param bypass             whether its {@code networkAcls.bypass} names {@code AzureServices}
     * @param securedByPerimeter whether its {@code publicNetworkAccess} is {@code SecuredByPerimeter}
     */
    private record AccountNetwork(String id, List<String> closures, boolean bypass, boolean securedByPerimeter) {

        /** The settings of an account document, as StorageAccountArmService stores what its PUT sent. */
        static AccountNetwork of(Map<String, Object> account) {
            Map<String, Object> properties = ArmJson.cast(account.get("properties"));
            Map<String, Object> rules = ArmJson.cast(properties.get("networkAcls"));
            String publicNetworkAccess = ArmJson.string(properties, "publicNetworkAccess", "Enabled");
            boolean perimeter = "SecuredByPerimeter".equalsIgnoreCase(publicNetworkAccess);
            List<String> closures = new ArrayList<>();
            if ("Disabled".equalsIgnoreCase(publicNetworkAccess)) {
                closures.add("its publicNetworkAccess is Disabled");
            } else if (perimeter) {
                closures.add("its publicNetworkAccess is SecuredByPerimeter, and floci-az models no network security "
                    + "perimeter that could admit it");
            }
            if ("Deny".equalsIgnoreCase(ArmJson.string(rules, "defaultAction", "Allow"))) {
                closures.add("its networkAcls.defaultAction is Deny");
            }
            boolean bypass = Arrays.stream(ArmJson.string(rules, "bypass", AZURE_SERVICES).split(","))
                .map(String::trim)
                .anyMatch(AZURE_SERVICES::equalsIgnoreCase);
            return new AccountNetwork(String.valueOf(account.get("id")), List.copyOf(closures), bypass, perimeter);
        }

        boolean open() {
            return closures.isEmpty();
        }

        /** Whether trusted Microsoft services get in: an enforced perimeter does not honor the exception. */
        boolean admitsTrustedServices() {
            return bypass && !securedByPerimeter;
        }
    }
}
