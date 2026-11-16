package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.auth.RbacDecision;
import io.floci.az.core.auth.RbacEvaluator;
import io.floci.az.core.auth.RbacOperation;
import io.floci.az.core.auth.RbacPrincipal;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * The checks a run makes before it reads a data source through a managed identity (a {@code ResourceId=} connection
 * string), as "Connect to a data source using a managed identity" describes: the search service must have the identity
 * the connection uses (its system-assigned identity, or the user-assigned identity the data source's {@code identity}
 * names, which "should have been assigned to the search service"), and, when {@code floci-az.parity-overrides.strict-rbac}
 * is on, that identity's principal must hold the documented role on the source:
 * <ul>
 *   <li>Blob Storage and ADLS Gen2: Storage Blob Data Reader, checked as its data action
 *       {@code .../blobServices/containers/blobs/read} at the container;</li>
 *   <li>Table Storage: Storage Table Data Reader, as {@code .../tableServices/tables/entities/read} at the table;</li>
 *   <li>Azure Cosmos DB, the modern {@code IdentityAuthType=AccessToken} connection: Cosmos DB Account Reader on the
 *       control plane, as {@code Microsoft.DocumentDB/databaseAccounts/read} at the account. The Cosmos DB Built-in Data
 *       Reader role it also needs is a Cosmos DB data-plane role assignment ({@code sqlRoleAssignments}), which floci-az
 *       does not model, so that half is not checked;</li>
 *   <li>Azure Cosmos DB, the legacy connection (no {@code IdentityAuthType}, or {@code AccountKey}): the account keys
 *       are fetched with the identity, checked as {@code Microsoft.DocumentDB/databaseAccounts/readonlykeys/action}
 *       (Cosmos DB Account Reader grants it).</li>
 * </ul>
 */
@ApplicationScoped
public class ManagedIdentityAccess {

    static final RbacOperation BLOB_READ =
        RbacOperation.dataAction("Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read");
    static final RbacOperation TABLE_READ =
        RbacOperation.dataAction("Microsoft.Storage/storageAccounts/tableServices/tables/entities/read");
    static final RbacOperation COSMOS_ACCOUNT_READ = RbacOperation.action("Microsoft.DocumentDB/databaseAccounts/read");
    static final RbacOperation COSMOS_READ_KEYS =
        RbacOperation.action("Microsoft.DocumentDB/databaseAccounts/readonlykeys/action");

    private final EmulatorConfig config;
    private final RbacEvaluator rbac;

    @Inject
    public ManagedIdentityAccess(EmulatorConfig config, RbacEvaluator rbac) {
        this.config = config;
        this.rbac = rbac;
    }

    /**
     * Checks that the run may read through the identity.
     *
     * @param service    the search service
     * @param dataSource the data source
     * @param scope      the ARM scope of what is read (a container, a table, an account)
     * @param operation  the action or data action reading needs
     * @param roleName   the role that grants it, for the error message
     * @throws IndexerRunException when the service lacks the identity or the identity lacks the role
     */
    public void check(SearchServiceInfo service, DataSourceDefinition dataSource, String scope,
                      RbacOperation operation, String roleName) throws IndexerRunException {
        String principal = principal(service, dataSource);
        if (!config.strictRbac()) {
            return;
        }
        RbacDecision decision = rbac.evaluate(RbacPrincipal.of(principal), scope, operation);
        if (!decision.allowed()) {
            throw new IndexerRunException("Error with data source: The managed identity of the search service '"
                + service.name() + "' (principal '" + principal + "') is not authorized to read '" + scope + "'. "
                + decision.reason() + " Assign it the " + roleName + " role.");
        }
    }

    /** The principal id of the identity the data source connects with. */
    private static String principal(SearchServiceInfo service, DataSourceDefinition dataSource)
            throws IndexerRunException {
        JsonNode identity = service.identity();
        String userAssigned = dataSource.userAssignedIdentity();
        String type = identity == null ? "" : String.valueOf(SearchJson.text(identity, "type"))
            .toLowerCase(Locale.ROOT);
        if (userAssigned == null) {
            String principal = SearchJson.text(identity, "principalId");
            if (!type.contains("systemassigned") || principal == null) {
                throw new IndexerRunException("Error with data source: The data source '" + dataSource.name()
                    + "' connects with a managed identity (a ResourceId connection string), but the search service '"
                    + service.name() + "' has no system-assigned managed identity. Turn on the service's "
                    + "system-assigned identity, or name one of its user-assigned identities in the data source's "
                    + "identity property.");
            }
            return principal;
        }
        JsonNode assigned = identity == null ? null : identity.get("userAssignedIdentities");
        if (assigned != null && type.contains("userassigned")) {
            Iterator<Map.Entry<String, JsonNode>> entries = assigned.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String principal = SearchJson.text(entry.getValue(), "principalId");
                if (trimSlashes(entry.getKey()).equalsIgnoreCase(trimSlashes(userAssigned)) && principal != null) {
                    return principal;
                }
            }
        }
        throw new IndexerRunException("Error with data source: The data source '" + dataSource.name() + "' names the "
            + "user-assigned identity '" + userAssigned + "', which is not assigned to the search service '"
            + service.name() + "'. Assign the identity to the service first.");
    }

    private static String trimSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
