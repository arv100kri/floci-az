package io.floci.az.services.search.indexer;

import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.model.SearchJson;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/** Requests the indexer tests share: search resources over REST, and data written to floci-az's own services. */
final class IndexerTestSupport {

    static final String API = "?api-version=2024-07-01";
    static final String PREVIEW_API = "?api-version=2025-11-01-preview";
    static final String ACCOUNT = "idxacct";
    static final String BLOB_CONNECTION = "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT
        + ";AccountKey=a2V5;BlobEndpoint=http://localhost:4577/" + ACCOUNT + ";";
    static final String TABLE_CONNECTION = "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT
        + ";AccountKey=a2V5;TableEndpoint=http://localhost:4577/" + ACCOUNT + "-table;";
    static final String COSMOS_CONNECTION = "AccountEndpoint=http://localhost:4577/" + ACCOUNT
        + "-cosmos/;AccountKey=a2V5;Database=catalog";

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(30);

    private IndexerTestSupport() {
    }

    static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    static String base(String service) {
        return "/" + service + "-search";
    }

    static void createIndex(String service, String definition) {
        admin().body(definition).post(base(service) + "/indexes" + API).then().statusCode(201);
    }

    static void createDataSource(String service, String definition) {
        admin().body(definition).post(base(service) + "/datasources" + API).then().statusCode(201);
    }

    static void createIndexer(String service, String definition) {
        admin().body(definition).post(base(service) + "/indexers" + API).then().statusCode(201);
    }

    static JsonPath status(String service, String indexer) {
        return admin().get(base(service) + "/indexers('" + indexer + "')/search.status" + API).then().statusCode(200)
            .extract().jsonPath();
    }

    static int historySize(String service, String indexer) {
        return status(service, indexer).getList("executionHistory").size();
    }

    /** Waits until the execution history holds more than {@code previousRuns} entries; returns the newest. */
    static JsonPath awaitRun(String service, String indexer, int previousRuns) {
        Instant deadline = Instant.now().plus(RUN_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonPath status = status(service, indexer);
            List<Object> history = status.getList("executionHistory");
            if (history.size() > previousRuns && !"inProgress".equals(status.getString("lastResult.status"))) {
                return JsonPath.from(SearchJson.write(SearchJson.MAPPER.valueToTree(history.getFirst())));
            }
            pause(Duration.ofMillis(50));
        }
        throw new AssertionError("indexer " + indexer + " did not finish a run: "
            + status(service, indexer).prettify());
    }

    /** Runs an indexer on demand and waits for that run. */
    static JsonPath runAndAwait(String service, String indexer) {
        int before = historySize(service, indexer);
        admin().post(base(service) + "/indexers('" + indexer + "')/search.run" + API).then().statusCode(202);
        return awaitRun(service, indexer, before);
    }

    static List<Map<String, Object>> searchAll(String service, String index) {
        return admin().get(base(service) + "/indexes('" + index + "')/docs" + API + "&search=*&$top=1000")
            .then().statusCode(200).extract().jsonPath().getList("value");
    }

    static Map<String, Object> lookup(String service, String index, String key) {
        return admin().get(base(service) + "/indexes('" + index + "')/docs('" + key + "')" + API)
            .then().statusCode(200).extract().jsonPath().getMap("$");
    }

    static void createContainer(String container) {
        given().put("/{account}/{container}?restype=container", ACCOUNT, container).then().statusCode(201);
    }

    static void putBlob(String container, String name, String content, Map<String, String> metadata) {
        putBlob(container, name, content.getBytes(StandardCharsets.UTF_8), null, metadata);
    }

    static void putBlob(String container, String name, byte[] content, String contentType,
                        Map<String, String> metadata) {
        RequestSpecification request = given().header("x-ms-blob-type", "BlockBlob").body(content);
        if (contentType != null) {
            request.header("x-ms-blob-content-type", contentType);
        }
        metadata.forEach((key, value) -> request.header("x-ms-meta-" + key, value));
        request.put("/{account}/{container}/{blob}", ACCOUNT, container, name).then().statusCode(201);
    }

    static void createTable(String table) {
        given().contentType("application/json").body("{\"TableName\":\"" + table + "\"}")
            .post("/{account}/Tables", ACCOUNT + "-table").then().statusCode(201);
    }

    static void insertEntity(String table, String json) {
        given().contentType("application/json").body(json).post("/{account}/{table}", ACCOUNT + "-table", table)
            .then().statusCode(201);
    }

    static void mergeEntity(String table, String partitionKey, String rowKey, String json) {
        given().contentType("application/json").header("If-Match", "*").body(json)
            .request("MERGE", "/{account}/{table}(PartitionKey='" + partitionKey + "',RowKey='" + rowKey + "')",
                ACCOUNT + "-table", table)
            .then().statusCode(204);
    }

    static void createCosmosContainer(String container) {
        String databases = "/" + ACCOUNT + "-cosmos/dbs";
        given().contentType("application/json").body(Map.of("id", "catalog")).post(databases);
        given().contentType("application/json")
            .body("{\"id\":\"" + container + "\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
            .post(databases + "/catalog/colls").then().statusCode(201);
    }

    static void upsertCosmos(String container, String json) {
        given().contentType("application/json").header("x-ms-documentdb-is-upsert", "true").body(json)
            .post("/" + ACCOUNT + "-cosmos/dbs/catalog/colls/" + container + "/docs")
            .then().statusCode(anyOf(is(200), is(201)));
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
