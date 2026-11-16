package io.floci.az.services.cosmos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only, in-process access to the databases, containers and documents of floci-az's Azure Cosmos DB for NoSQL
 * service, for emulated services that pull documents from it, such as the Azure AI Search Cosmos DB indexer.
 * Nothing here goes over HTTP or checks a credential: the caller resolves the account it reads (see
 * {@link io.floci.az.core.storage.CosmosConnectionStrings}) and owns any authorization decision. An account is the
 * one floci-az serves at {@code /{account}-cosmos/} (and {@code /{account}-cosmos-nosql/}); the Java SDK's
 * root-path requests use the account {@code devstoreaccount1}.
 *
 * <p><b>Consistency.</b> A query reads a snapshot of the container taken under the monitor every document write,
 * transactional batch and delete holds, so none of them is seen half done. Like every Cosmos DB read, it leaves
 * out documents whose time to live has passed and purges them.
 *
 * <p><b>Change detection.</b> Every write stamps a document's {@code _ts} (epoch seconds) and {@code _etag}, so the
 * indexer's {@code SELECT * FROM c WHERE c._ts >= @HighWaterMark ORDER BY c._ts} runs as it does against Azure.
 */
@ApplicationScoped
public class CosmosReader {

    private static final String DEFAULT_QUERY = "SELECT * FROM c";

    private final CosmosHandler cosmosHandler;
    private final CosmosQueryEngine queryEngine = new CosmosQueryEngine();

    @Inject
    public CosmosReader(CosmosHandler cosmosHandler) {
        this.cosmosHandler = cosmosHandler;
    }

    /** The ids of the account's databases, in id order. */
    public List<String> listDatabases(String accountName) {
        return cosmosHandler.databaseIds(accountName);
    }

    /** The ids of a database's containers, in id order; empty when the database does not exist. */
    public Optional<List<String>> listContainers(String accountName, String databaseId) {
        return cosmosHandler.containerIds(accountName, databaseId);
    }

    /**
     * Runs a NoSQL query against one container across all its partitions, as the REST query does when it sends
     * no partition key and no page size, with the same engine, ordering and composite-index rule.
     *
     * @param sql        the query; {@code null} or blank runs {@code SELECT * FROM c}
     * @param parameters the query's named parameters, each name with its {@code @} as in the REST API's
     *                   {@code parameters} array ({@code "@HighWaterMark"}), each value a JSON-compatible Java value
     *                   (String, Number, Boolean, {@code null}, List, Map); {@code null} or empty for none
     * @return the result items in order, each a fresh copy the caller may keep: a document as a
     *         {@code Map<String, Object>}, system properties ({@code _rid}, {@code _self}, {@code _etag},
     *         {@code _ts}, {@code _attachments}) included, for {@code SELECT *}; a {@code Map} for a projection;
     *         whatever the expression yields for {@code SELECT VALUE}. Empty when the container does not exist
     * @throws IllegalArgumentException when Cosmos DB would refuse the query: an {@code ORDER BY} over two or more
     *                                  properties that no composite index of the container serves (SC2104), or a
     *                                  query the engine cannot parse or run
     */
    public Optional<List<Object>> query(String accountName, String databaseId, String containerId, String sql,
                                        Map<String, Object> parameters) {
        Optional<CosmosHandler.ContainerSnapshot> snapshot =
                cosmosHandler.containerSnapshot(accountName, databaseId, containerId);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        String text = sql == null || sql.isBlank() ? DEFAULT_QUERY : sql;
        CosmosQueryEngine.ParsedQuery parsed;
        try {
            parsed = queryEngine.prepare(text, parameterList(parameters));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The query could not be parsed: " + e.getMessage(), e);
        }
        if (parsed.orderBy().size() > 1 && !CosmosIndexingPolicy.supportsOrderBy(
                snapshot.get().properties().get("indexingPolicy"), parsed.orderBy())) {
            throw new IllegalArgumentException("SC2104: The order by query does not have a corresponding "
                    + "composite index that it can be served from.");
        }
        try {
            List<Object> items = queryEngine.executePage(parsed, snapshot.get().documents(), null, -1)
                    .result().items();
            return Optional.of(Collections.unmodifiableList(new ArrayList<>(items)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The query could not be executed: " + e.getMessage(), e);
        }
    }

    /** The parameters in the REST API's shape, a list of {@code {"name": ..., "value": ...}} objects. */
    private static List<Map<String, Object>> parameterList(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        parameters.forEach((name, value) -> {
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", name);
            parameter.put("value", value);
            list.add(parameter);
        });
        return list;
    }
}
