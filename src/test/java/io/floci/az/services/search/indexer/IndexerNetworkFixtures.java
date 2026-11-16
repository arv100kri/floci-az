package io.floci.az.services.search.indexer;

import io.restassured.path.json.JsonPath;

import java.util.Map;

import static io.floci.az.services.search.indexer.IndexerTestSupport.ACCOUNT;
import static io.floci.az.services.search.indexer.IndexerTestSupport.API;
import static io.floci.az.services.search.indexer.IndexerTestSupport.admin;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.base;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.putBlob;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * The ARM resources the indexer network tests build on: a search service created through Microsoft.Search with a
 * system-assigned and a user-assigned identity, the storage account its indexers read, and shared private links.
 */
final class IndexerNetworkFixtures {

    static final String SEARCH_API = "?api-version=2026-11-01";
    static final String STORAGE_API = "?api-version=2023-01-01";
    static final String IDENTITY_API = "?api-version=2023-01-31";
    static final String GROUP = "/subscriptions/sub-srch-indexer-net/resourceGroups/rg-indexer-net";
    static final String SERVICE = "netsvc";
    static final String SERVICE_ID = GROUP + "/providers/Microsoft.Search/searchServices/" + SERVICE;
    static final String STORAGE_ID = GROUP + "/providers/Microsoft.Storage/storageAccounts/" + ACCOUNT;
    static final String USER_IDENTITY_ID = GROUP
        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/indexer-identity";
    /** A data source's {@code identity} that names the service's user-assigned identity. */
    static final String USER_ASSIGNED = "{\"@odata.type\": \"#Microsoft.Azure.Search.DataUserAssignedIdentity\", "
        + "\"userAssignedIdentity\": \"" + USER_IDENTITY_ID + "\"}";
    /** A managed-identity connection to the storage account: the system-assigned identity, unless one is named. */
    static final String MANAGED_IDENTITY_CONNECTION = "ResourceId=" + STORAGE_ID + ";";
    static final String PRIVATE = "\"parameters\": {\"configuration\": {\"executionEnvironment\": \"private\"}}";
    static final String DOCS_INDEX = "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
        + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}]}";

    private IndexerNetworkFixtures() {
    }

    /**
     * Creates the search service through ARM with both identities, the {@code docs} container with one blob, and the
     * {@code docs} index; returns the service's admin key.
     */
    static String createServiceAndDocs() {
        given().contentType("application/json").body("{\"location\":\"westus\"}")
            .put(USER_IDENTITY_ID + IDENTITY_API).then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard\"},\"identity\":{\"type\":"
                + "\"SystemAssigned, UserAssigned\",\"userAssignedIdentities\":{\"" + USER_IDENTITY_ID + "\":{}}}}")
            .put(SERVICE_ID + SEARCH_API).then().statusCode(201);
        String key = given().post(SERVICE_ID + "/listAdminKeys" + SEARCH_API).then().statusCode(200).extract()
            .path("primaryKey");
        createContainer("docs");
        putBlob("docs", "a.txt", "alpha", Map.of());
        admin(key).body(DOCS_INDEX).post(base(SERVICE) + "/indexes" + API).then().statusCode(201);
        return key;
    }

    /** Creates or replaces the storage account {@link #STORAGE_ID}, with the {@code properties} JSON or none. */
    static void storageAccount(String properties) {
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}"
                + (properties == null ? "" : ",\"properties\":" + properties) + "}")
            .put(STORAGE_ID + STORAGE_API).then().statusCode(200);
    }

    /** Creates a shared private link of the service to {@code groupId} of {@code target}; links start approved. */
    static void sharedPrivateLink(String name, String target, String groupId) {
        given().contentType("application/json")
            .body("{\"properties\":{\"privateLinkResourceId\":\"" + target + "\",\"groupId\":\"" + groupId + "\","
                + "\"requestMessage\":\"please approve\"}}")
            .put(SERVICE_ID + "/sharedPrivateLinkResources/" + name + SEARCH_API).then().statusCode(202);
    }

    /** Creates data source {@code name} of the service, with the {@code identity} JSON or none. */
    static void dataSource(String apiKey, String name, String type, String connection, String container,
                           String identity) {
        admin(apiKey).body("{\"name\": \"" + name + "\", \"type\": \"" + type + "\", \"credentials\": "
                + "{\"connectionString\": \"" + connection + "\"}, \"container\": {\"name\": \"" + container + "\"}"
                + (identity == null ? "" : ", \"identity\": " + identity) + "}")
            .post(base(SERVICE) + "/datasources" + API).then().statusCode(201);
    }

    /**
     * Creates indexer {@code name} of the service, from {@code dataSource} to the {@code docs} index with the
     * {@code parameters} JSON member or none, and waits for its first run.
     */
    static JsonPath firstRun(String apiKey, String name, String dataSource, String parameters) {
        admin(apiKey).body("{\"name\": \"" + name + "\", \"dataSourceName\": \"" + dataSource + "\", "
                + "\"targetIndexName\": \"docs\"" + (parameters == null ? "" : ", " + parameters) + "}")
            .post(base(SERVICE) + "/indexers" + API).then().statusCode(201);
        return awaitRun(SERVICE, name, 0, apiKey);
    }
}
