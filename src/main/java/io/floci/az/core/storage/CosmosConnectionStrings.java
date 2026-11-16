package io.floci.az.core.storage;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the connection string of an Azure AI Search {@code cosmosdb} data source to the floci-az Azure Cosmos DB
 * for NoSQL account and database it names, so an indexer can read them in process through the Cosmos service's
 * {@code CosmosReader}. Resolution is syntactic: floci-az creates a Cosmos DB account on first use, so a resolved
 * account or database need not exist yet, and no key or identity is verified.
 *
 * <p>Recognized forms, both of which need {@code Database}:
 * <ul>
 *   <li>{@code AccountEndpoint={endpoint};AccountKey={key};Database={database}}, the key form</li>
 *   <li>{@code ResourceId={account resource id};Database={database}[;IdentityAuthType=AccessToken|AccountKey]}, the
 *       managed-identity form, where the resource id is
 *       {@code /subscriptions/{id}/resourceGroups/{group}/providers/Microsoft.DocumentDB/databaseAccounts/{account}}
 *       with an optional trailing slash</li>
 * </ul>
 * An {@code ApiKind} other than the NoSQL API's ({@code MongoDb}, {@code Gremlin}) is refused: floci-az's MongoDB,
 * Gremlin, Cassandra, Table and PostgreSQL engines are separate and not readable in process.
 *
 * <p>The {@code AccountEndpoint} names its account the way floci-az routes Cosmos DB requests:
 * <ul>
 *   <li>host style {@code https://{account}.documents.{rest of the host}}, Azure's
 *       {@code {account}.documents.azure.com} or a sovereign cloud's</li>
 *   <li>path style on any other host: {@code http(s)://{host}:{port}/{account}-cosmos/}, or
 *       {@code /{account}-cosmos-nosql/} for the NoSQL engine route</li>
 *   <li>the root of any other host, {@code http(s)://{host}:{port}/}: the account {@value #ROOT_ACCOUNT}, which
 *       floci-az serves there for the Java SDK (and which the endpoint of the Cosmos DB emulator,
 *       {@code https://localhost:8081/}, therefore also names)</li>
 * </ul>
 * Anything else resolves to {@link Unresolved} with the reason: a malformed string, a missing database, another
 * Cosmos DB API's endpoint ({@code {account}.mongo.cosmos.azure.com}, {@code /{account}-cosmos-mongo}), an Azure host
 * that is not a NoSQL endpoint, or a path floci-az does not route to Cosmos DB.
 */
public final class CosmosConnectionStrings {

    /** The account floci-az serves at the root path, where the Java SDK sends its requests. */
    public static final String ROOT_ACCOUNT = "devstoreaccount1";

    private static final String COSMOS_RESOURCE_PROVIDER = "Microsoft.DocumentDB";
    private static final String COSMOS_RESOURCE_TYPE = "databaseAccounts";
    private static final Set<String> NOSQL_API_KINDS = Set.of("sql", "nosql");
    private static final List<String> NOSQL_SUFFIXES = List.of("-cosmos-nosql", "-cosmos");
    private static final String ENGINE_PREFIX = "-cosmos-";

    private CosmosConnectionStrings() {
    }

    /** The kind of credential a connection string carries. */
    public enum Credential {
        ACCOUNT_KEY,
        MANAGED_IDENTITY,
        NONE
    }

    /** The outcome of {@link #resolve}: {@link Resolved} or {@link Unresolved}. */
    public sealed interface Resolution permits Resolved, Unresolved {
    }

    /**
     * A connection string that names a floci-az Cosmos DB for NoSQL account and database.
     *
     * @param accountName  the account to read, as floci-az keys it (a host-style account in lower case)
     * @param databaseName the {@code Database} the string names
     * @param credential   the kind of credential the string carries
     */
    public record Resolved(String accountName, String databaseName, Credential credential) implements Resolution {
    }

    /** A connection string that names no floci-az Cosmos DB for NoSQL account and database, and why. */
    public record Unresolved(String reason) implements Resolution {
    }

    public static Resolution resolve(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            return new Unresolved("The Cosmos DB connection string is empty.");
        }
        Map<String, String> pairs;
        try {
            pairs = ConnectionStringParts.parse(connectionString);
        } catch (IllegalArgumentException e) {
            return new Unresolved("The Cosmos DB connection string is malformed: " + e.getMessage() + ".");
        }
        String apiKind = pairs.get("ApiKind");
        if (apiKind != null && !NOSQL_API_KINDS.contains(apiKind.toLowerCase(Locale.ROOT))) {
            return new Unresolved("ApiKind=" + apiKind + " is not readable in process: floci-az reads Azure Cosmos DB "
                    + "for NoSQL accounts only.");
        }
        String database = pairs.get("Database");
        if (database == null || database.isBlank()) {
            return new Unresolved("The Cosmos DB connection string names no Database.");
        }
        String resourceId = pairs.get("ResourceId");
        if (resourceId != null) {
            String account = ConnectionStringParts.armResourceName(
                    resourceId, COSMOS_RESOURCE_PROVIDER, COSMOS_RESOURCE_TYPE);
            return account == null
                    ? new Unresolved("ResourceId '" + resourceId + "' does not name a Cosmos DB account: expected "
                            + "/subscriptions/{id}/resourceGroups/{group}/providers/Microsoft.DocumentDB/"
                            + "databaseAccounts/{account}.")
                    : new Resolved(account, database, Credential.MANAGED_IDENTITY);
        }
        String endpoint = pairs.get("AccountEndpoint");
        if (endpoint == null) {
            return new Unresolved("The Cosmos DB connection string names no account: it needs AccountEndpoint or "
                    + "ResourceId.");
        }
        Credential credential = pairs.containsKey("AccountKey") ? Credential.ACCOUNT_KEY : Credential.NONE;
        return fromEndpoint(endpoint, database, credential);
    }

    private static Resolution fromEndpoint(String value, String database, Credential credential) {
        URI url = ConnectionStringParts.httpUrl(value);
        if (url == null) {
            return new Unresolved("AccountEndpoint '" + value + "' is not an http(s) URL.");
        }
        List<String> labels = ConnectionStringParts.hostLabels(url);
        if (labels.size() > 2 && "documents".equals(labels.get(1)) && !labels.get(0).isEmpty()) {
            return new Resolved(labels.get(0), database, credential);
        }
        if (ConnectionStringParts.azureHost(url)) {
            return new Unresolved("AccountEndpoint '" + value + "' is not an Azure Cosmos DB for NoSQL endpoint: "
                    + "a NoSQL account is reached at {account}.documents.azure.com."); // cloud-literal: example host in a refusal message
        }
        List<String> segments = ConnectionStringParts.pathSegments(url);
        if (segments.isEmpty()) {
            return new Resolved(ROOT_ACCOUNT, database, credential);
        }
        String segment = segments.get(0);
        for (String suffix : NOSQL_SUFFIXES) {
            if (segment.endsWith(suffix) && segment.length() > suffix.length()) {
                return new Resolved(segment.substring(0, segment.length() - suffix.length()), database, credential);
            }
        }
        int engine = segment.lastIndexOf(ENGINE_PREFIX);
        if (engine > 0 && engine + ENGINE_PREFIX.length() < segment.length()) {
            return new Unresolved("AccountEndpoint '" + value + "' is floci-az's Azure Cosmos DB for "
                    + segment.substring(engine + ENGINE_PREFIX.length()) + " endpoint, not the NoSQL API's.");
        }
        return new Unresolved("AccountEndpoint '" + value + "' is not a floci-az Cosmos DB endpoint: floci-az serves "
                + "Cosmos DB for NoSQL at http(s)://{host}:{port}/{account}-cosmos/, at http(s)://{host}:{port}/ "
                + "(the account " + ROOT_ACCOUNT + ") and host-style at {account}.documents.azure.com."); // cloud-literal: example host in a refusal message
    }
}
