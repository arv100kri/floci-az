package io.floci.az.core.storage;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the Azure Storage connection string of an Azure AI Search data source ({@code azureblob},
 * {@code adlsgen2}, {@code azuretable}) to the floci-az storage account it names, so an indexer can read that
 * account in process through the Blob service's {@code BlobReader} or the Table service's {@code TableReader}.
 * Resolution is syntactic: floci-az creates a storage account on first use, so a resolved account need not hold
 * anything yet, and no key, SAS token or identity is verified.
 *
 * <p>Recognized forms, the first that applies wins:
 * <ol>
 *   <li>{@code UseDevelopmentStorage=true}: the development account {@value #DEVELOPMENT_ACCOUNT}</li>
 *   <li>{@code ResourceId={account resource id}}, the managed-identity form, where the resource id is
 *       {@code /subscriptions/{id}/resourceGroups/{group}/providers/Microsoft.Storage/storageAccounts/{account}}
 *       with an optional trailing slash</li>
 *   <li>{@code ContainerSharedAccessUri={container URL}?{SAS}}, the container SAS form, which also names the
 *       container (for a table data source, the table)</li>
 *   <li>the endpoint of the service read ({@code BlobEndpoint} or {@code TableEndpoint}), else {@code AccountName},
 *       else the other service's endpoint (Azure's table indexer documentation shows a {@code BlobEndpoint} SAS
 *       string), with {@code AccountKey} or {@code SharedAccessSignature} as the credential: the account key form
 *       {@code DefaultEndpointsProtocol=https;AccountName={account};AccountKey={key}}, optionally with
 *       {@code BlobEndpoint} and {@code TableEndpoint}, and the account SAS form</li>
 * </ol>
 *
 * <p>An endpoint URL names its account the way floci-az routes it:
 * <ul>
 *   <li>host style, {@code {account}.{blob|dfs|table|queue|file}.{rest of the host}}: Azure's
 *       {@code {account}.blob.core.windows.net} and {@code {account}.table.core.windows.net}, a sovereign cloud's
 *       suffix, or floci-az's own {@code {account}.blob.localhost}</li>
 *   <li>path style on any other host: {@code http(s)://{host}:{port}/{account}} for Blob Storage and
 *       {@code http(s)://{host}:{port}/{account}-table} for Table Storage</li>
 * </ul>
 *
 * <p>Anything else resolves to {@link Unresolved} with the reason: a malformed string, an endpoint that names no
 * account, an Azure Cosmos DB endpoint, floci-az's Cosmos DB for Table endpoint ({@code /{account}-cosmos-table}),
 * which keeps its tables apart from Table Storage, a path-style endpoint floci-az routes to another service, or an
 * Azure host that is not a storage endpoint.
 */
public final class StorageConnectionStrings {

    /** The account {@code UseDevelopmentStorage=true} names, the one Azurite and floci-az preconfigure. */
    public static final String DEVELOPMENT_ACCOUNT = "devstoreaccount1";

    private static final String STORAGE_RESOURCE_PROVIDER = "Microsoft.Storage";
    private static final String STORAGE_RESOURCE_TYPE = "storageAccounts";
    private static final Set<String> STORAGE_HOST_MARKERS = Set.of("blob", "dfs", "table", "queue", "file");
    private static final String TABLE_SUFFIX = "-table";
    private static final String COSMOS_TABLE_SUFFIX = "-cosmos-table";
    private static final String QUEUE_SUFFIX = "-queue";
    private static final String COSMOS_MARKER = "-cosmos";

    private StorageConnectionStrings() {
    }

    /** The storage service an indexer reads, which decides the endpoint consulted first. */
    public enum Service {
        BLOB("BlobEndpoint"),
        TABLE("TableEndpoint");

        private final String endpointKey;

        Service(String endpointKey) {
            this.endpointKey = endpointKey;
        }

        private Service other() {
            return this == BLOB ? TABLE : BLOB;
        }
    }

    /** The kind of credential a connection string carries. */
    public enum Credential {
        ACCOUNT_KEY,
        SHARED_ACCESS_SIGNATURE,
        MANAGED_IDENTITY,
        DEVELOPMENT_STORAGE,
        NONE
    }

    /** The outcome of {@link #resolve}: {@link Resolved} or {@link Unresolved}. */
    public sealed interface Resolution permits Resolved, Unresolved {
    }

    /**
     * A connection string that names a floci-az storage account.
     *
     * @param accountName           the account to read, as floci-az keys it (a host-style account in lower case)
     * @param credential            the kind of credential the string carries
     * @param containerName         the container, or table, a {@code ContainerSharedAccessUri} names; {@code null}
     *                              for the other forms
     * @param sharedAccessSignature the SAS token without its leading {@code ?}, when the string carries one;
     *                              {@code null} otherwise
     */
    public record Resolved(String accountName, Credential credential, String containerName,
                           String sharedAccessSignature) implements Resolution {
    }

    /** A connection string that names no floci-az storage account, and why. */
    public record Unresolved(String reason) implements Resolution {
    }

    /** Resolves {@code connectionString} for an indexer that reads {@code service}. */
    public static Resolution resolve(String connectionString, Service service) {
        if (connectionString == null || connectionString.isBlank()) {
            return new Unresolved("The storage connection string is empty.");
        }
        Map<String, String> pairs;
        try {
            pairs = ConnectionStringParts.parse(connectionString);
        } catch (IllegalArgumentException e) {
            return new Unresolved("The storage connection string is malformed: " + e.getMessage() + ".");
        }
        String development = pairs.get("UseDevelopmentStorage");
        if (development != null) {
            return "true".equalsIgnoreCase(development)
                    ? new Resolved(DEVELOPMENT_ACCOUNT, Credential.DEVELOPMENT_STORAGE, null, null)
                    : new Unresolved("UseDevelopmentStorage=" + development
                            + " names no account; only UseDevelopmentStorage=true does.");
        }
        String resourceId = pairs.get("ResourceId");
        if (resourceId != null) {
            return fromResourceId(resourceId);
        }
        String containerUri = pairs.get("ContainerSharedAccessUri");
        if (containerUri != null) {
            return fromContainerUri(containerUri, service);
        }
        Credential credential = pairs.containsKey("AccountKey") ? Credential.ACCOUNT_KEY
                : pairs.containsKey("SharedAccessSignature") ? Credential.SHARED_ACCESS_SIGNATURE
                : Credential.NONE;
        String sas = sasToken(pairs.get("SharedAccessSignature"));
        String endpoint = pairs.get(service.endpointKey);
        if (endpoint != null) {
            return fromEndpoint(service.endpointKey, endpoint, service, credential, sas);
        }
        String accountName = pairs.get("AccountName");
        if (accountName != null && !accountName.isBlank()) {
            return new Resolved(accountName, credential, null, sas);
        }
        String otherEndpoint = pairs.get(service.other().endpointKey);
        if (otherEndpoint != null) {
            return fromEndpoint(service.other().endpointKey, otherEndpoint, service.other(), credential, sas);
        }
        return new Unresolved("The storage connection string names no account: it needs AccountName, "
                + "UseDevelopmentStorage=true, ResourceId, BlobEndpoint, TableEndpoint or ContainerSharedAccessUri.");
    }

    private static Resolution fromResourceId(String resourceId) {
        String account = ConnectionStringParts.armResourceName(
                resourceId, STORAGE_RESOURCE_PROVIDER, STORAGE_RESOURCE_TYPE);
        if (account == null) {
            return new Unresolved("ResourceId '" + resourceId + "' does not name a storage account: expected "
                    + "/subscriptions/{id}/resourceGroups/{group}/providers/Microsoft.Storage/storageAccounts/"
                    + "{account}.");
        }
        return new Resolved(account, Credential.MANAGED_IDENTITY, null, null);
    }

    private static Resolution fromContainerUri(String value, Service service) {
        URI url = ConnectionStringParts.httpUrl(value);
        if (url == null) {
            return new Unresolved("ContainerSharedAccessUri '" + value + "' is not an http(s) URL.");
        }
        String sas = sasToken(url.getRawQuery());
        Resolution resolution = fromUrl("ContainerSharedAccessUri", value, url, service,
                sas == null ? Credential.NONE : Credential.SHARED_ACCESS_SIGNATURE, sas, true);
        if (resolution instanceof Resolved resolved && resolved.containerName() == null) {
            return new Unresolved("ContainerSharedAccessUri '" + value + "' names no container.");
        }
        return resolution;
    }

    private static Resolution fromEndpoint(String key, String value, Service service, Credential credential,
                                           String sas) {
        URI url = ConnectionStringParts.httpUrl(value);
        if (url == null) {
            return new Unresolved(key + " '" + value + "' is not an http(s) URL.");
        }
        return fromUrl(key, value, url, service, credential, sas, false);
    }

    /**
     * The account an endpoint URL names, and for a container URL the container after it, read host-style or
     * path-style as floci-az routes the URL.
     */
    private static Resolution fromUrl(String key, String value, URI url, Service service, Credential credential,
                                      String sas, boolean containerUrl) {
        List<String> labels = ConnectionStringParts.hostLabels(url);
        List<String> segments = ConnectionStringParts.pathSegments(url);
        if (labels.contains("cosmos") || (labels.size() > 2 && "documents".equals(labels.get(1)))) {
            return new Unresolved(key + " '" + value + "' is an Azure Cosmos DB endpoint, not an Azure Storage one.");
        }
        if (labels.size() > 2 && STORAGE_HOST_MARKERS.contains(labels.get(1)) && !labels.get(0).isEmpty()) {
            String container = containerUrl && !segments.isEmpty() ? segments.get(0) : null;
            return new Resolved(labels.get(0), credential, container, sas);
        }
        if (ConnectionStringParts.azureHost(url)) {
            return new Unresolved(key + " '" + value + "' is not an Azure Storage endpoint: a storage account is "
                    + "reached at {account}.blob.core.windows.net or {account}.table.core.windows.net."); // cloud-literal: example hosts in a refusal message
        }
        if (segments.isEmpty()) {
            return new Unresolved(key + " '" + value + "' names no storage account: floci-az serves storage "
                    + "path-style at http(s)://{host}:{port}/{account} (Blob) and "
                    + "http(s)://{host}:{port}/{account}-table (Table), or host-style at {account}.blob.{host}.");
        }
        String reason = pathStyleProblem(segments.get(0), service);
        if (reason != null) {
            return new Unresolved(key + " '" + value + "' " + reason);
        }
        String account = service == Service.TABLE
                ? segments.get(0).substring(0, segments.get(0).length() - TABLE_SUFFIX.length())
                : segments.get(0);
        String container = containerUrl && segments.size() > 1 ? segments.get(1) : null;
        return new Resolved(account, credential, container, sas);
    }

    /** Why floci-az does not route the path-style account segment to {@code service}, or {@code null}. */
    private static String pathStyleProblem(String segment, Service service) {
        if (segment.endsWith(COSMOS_TABLE_SUFFIX)) {
            return "is floci-az's Azure Cosmos DB for Table endpoint, which keeps its tables apart from Table Storage.";
        }
        if (segment.contains(COSMOS_MARKER)) {
            return "is a floci-az Azure Cosmos DB endpoint, not an Azure Storage one.";
        }
        if (service == Service.TABLE) {
            return segment.endsWith(TABLE_SUFFIX) && segment.length() > TABLE_SUFFIX.length() ? null
                    : "is not a floci-az Table Storage endpoint: floci-az serves Table Storage path-style at "
                            + "http(s)://{host}:{port}/{account}-table.";
        }
        if (segment.endsWith(TABLE_SUFFIX) || segment.endsWith(QUEUE_SUFFIX)) {
            return "is not a floci-az Blob Storage endpoint: floci-az routes /" + segment + "/ to its "
                    + (segment.endsWith(TABLE_SUFFIX) ? "Table" : "Queue") + " service.";
        }
        return null;
    }

    private static String sasToken(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.startsWith("?") ? raw.substring(1) : raw;
        return token.isBlank() ? null : token;
    }
}
