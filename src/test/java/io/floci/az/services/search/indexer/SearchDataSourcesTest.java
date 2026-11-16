package io.floci.az.services.search.indexer;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.floci.az.services.search.indexer.IndexerTestSupport.API;
import static io.floci.az.services.search.indexer.IndexerTestSupport.BLOB_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.admin;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.base;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndex;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndexer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Search data plane: data sources")
class SearchDataSourcesTest {

    private static final String SERVICE = "dssvc";
    private static final String DATA_SOURCES = base(SERVICE) + "/datasources";
    private static final String BLOB_SOURCE = """
        {"name": "blobs", "description": "Blob documents", "type": "azureblob",
         "credentials": {"connectionString": "%s"},
         "container": {"name": "docs", "query": "folder"},
         "dataChangeDetectionPolicy": {"@odata.type": "#Microsoft.Azure.Search.HighWaterMarkChangeDetectionPolicy",
           "highWaterMarkColumnName": "metadata_storage_last_modified"},
         "dataDeletionDetectionPolicy": {"@odata.type": "#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy",
           "softDeleteColumnName": "isDeleted", "softDeleteMarkerValue": "true"},
         "encryptionKey": {"keyVaultUri": "https://vault.example", "keyVaultKeyName": "key",
           "keyVaultKeyVersion": "1", "accessCredentials": {"applicationId": "app", "applicationSecret": "secret"}}}
        """.formatted(BLOB_CONNECTION);

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void createReturnsTheDefinitionWithoutItsSecrets() {
        admin().body(BLOB_SOURCE).post(DATA_SOURCES + API).then().statusCode(201)
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/dssvc-search/datasources('blobs')?api-version=2024-07-01"))
            .body("'@odata.context'", endsWith("/$metadata#datasources/$entity"))
            .body("'@odata.etag'", startsWith("\"0x"))
            .body("name", equalTo("blobs"))
            .body("type", equalTo("azureblob"))
            .body("credentials.connectionString", nullValue())
            .body("container.name", equalTo("docs"))
            .body("container.query", equalTo("folder"))
            .body("dataChangeDetectionPolicy.highWaterMarkColumnName", equalTo("metadata_storage_last_modified"))
            .body("dataDeletionDetectionPolicy.'@odata.type'",
                equalTo("#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy"))
            .body("encryptionKey.keyVaultKeyName", equalTo("key"))
            .body("encryptionKey.accessCredentials.applicationSecret", nullValue())
            .body("identity", nullValue());
        admin().get(DATA_SOURCES + "('blobs')" + API).then().statusCode(200)
            .header("ETag", startsWith("\"0x"))
            .body("credentials.connectionString", nullValue())
            .body("description", equalTo("Blob documents"));
        admin().body(BLOB_SOURCE).post(DATA_SOURCES + API).then().statusCode(409);
    }

    @Test
    void listsWithSelectAndCountsInServiceStatistics() {
        admin().body(BLOB_SOURCE).post(DATA_SOURCES + API).then().statusCode(201);
        admin().body("{\"name\": \"sql\", \"type\": \"azuresql\", \"credentials\": {\"connectionString\": "
                + "\"Server=tcp:sql;Database=db\"}, \"container\": {\"name\": \"dbo.items\"}}")
            .put(DATA_SOURCES + "/sql" + API).then().statusCode(201);
        admin().get(DATA_SOURCES + API + "&$select=name").then().statusCode(200)
            .body("'@odata.context'", endsWith("/$metadata#datasources(name)"))
            .body("value.name", contains("blobs", "sql"))
            .body("value[0]", not(hasKey("type")));
        admin().get(base(SERVICE) + "/servicestats" + API).then().statusCode(200)
            .body("counters.dataSourcesCount.usage", equalTo(2));
    }

    @Test
    void anUpdateKeepsTheConnectionStringUnlessItIsRedacted() {
        createContainer("docs");
        createIndex(SERVICE, "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}]}");
        admin().body(BLOB_SOURCE).post(DATA_SOURCES + API).then().statusCode(201);
        String fetched = admin().get(DATA_SOURCES + "('blobs')" + API).then().extract().asString();
        admin().header("Prefer", "return=representation")
            .body(fetched.replace("\"connectionString\":null", "\"connectionString\":\"<unchanged>\""))
            .put(DATA_SOURCES + "('blobs')" + API).then().statusCode(200)
            .body("credentials.connectionString", nullValue())
            .body("encryptionKey.keyVaultKeyName", equalTo("key"));
        createIndexer(SERVICE, "{\"name\": \"docs-indexer\", \"dataSourceName\": \"blobs\", "
            + "\"targetIndexName\": \"docs\"}");
        assertEquals("success", awaitRun(SERVICE, "docs-indexer", 0).getString("status"),
            "the stored connection string still reaches the account");

        admin().body("{\"name\": \"blobs\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"<redacted>\"}, \"container\": {\"name\": \"docs\"}}")
            .put(DATA_SOURCES + "('blobs')" + API).then().statusCode(204);
        String error = runAndAwait(SERVICE, "docs-indexer").getString("errorMessage");
        assertEquals("Error with data source: The data source 'blobs' has no connection string. Set "
            + "credentials.connectionString.", error);
    }

    @Test
    void etagsGuardUpdatesAndDeletes() {
        String etag = admin().body(BLOB_SOURCE).post(DATA_SOURCES + API).then().statusCode(201).extract()
            .header("ETag");
        admin().header("If-Match", "\"0x1\"").body(BLOB_SOURCE).put(DATA_SOURCES + "('blobs')" + API)
            .then().statusCode(412);
        admin().header("If-None-Match", "*").body(BLOB_SOURCE).put(DATA_SOURCES + "('blobs')" + API)
            .then().statusCode(412);
        String newEtag = admin().header("If-Match", etag).body(BLOB_SOURCE).put(DATA_SOURCES + "('blobs')" + API)
            .then().statusCode(204).extract().header("ETag");
        admin().header("If-Match", etag).delete(DATA_SOURCES + "('blobs')" + API).then().statusCode(412);
        admin().header("If-Match", newEtag).delete(DATA_SOURCES + "('blobs')" + API).then().statusCode(204);
        admin().get(DATA_SOURCES + "('blobs')" + API).then().statusCode(404)
            .body("error.message", equalTo("No data source with the name 'blobs' was found."));
        admin().delete(DATA_SOURCES + "('blobs')" + API).then().statusCode(404);
    }

    @Test
    void invalidDefinitionsAreRejected() {
        admin().body("{\"name\": \"x\", \"type\": \"ftp\", \"credentials\": {\"connectionString\": \"a=b\"}, "
                + "\"container\": {\"name\": \"c\"}}")
            .post(DATA_SOURCES + API).then().statusCode(400)
            .body("error.message", containsString("Data source type 'ftp' is not supported"));
        admin().body("{\"name\": \"x\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": \"a=b\"}}")
            .post(DATA_SOURCES + API).then().statusCode(400)
            .body("error.message", containsString("container"));
        admin().body("{\"name\": \"x\", \"type\": \"azureblob\", \"container\": {\"name\": \"c\"}}")
            .post(DATA_SOURCES + API).then().statusCode(400)
            .body("error.message", containsString("credentials.connectionString is required"));
        admin().body("{\"name\": \"Bad_Name\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"a=b\"}, \"container\": {\"name\": \"c\"}}")
            .post(DATA_SOURCES + API).then().statusCode(400)
            .body("error.message", containsString("Data source name 'Bad_Name' is invalid"));
        admin().body("{\"name\": \"x\", \"type\": \"cosmosdb\", \"credentials\": {\"connectionString\": \"a=b\"}, "
                + "\"container\": {\"name\": \"c\"}, \"dataChangeDetectionPolicy\": {\"@odata.type\": "
                + "\"#Microsoft.Azure.Search.HighWaterMarkChangeDetectionPolicy\"}}")
            .post(DATA_SOURCES + API).then().statusCode(400)
            .body("error.message", containsString("highWaterMarkColumnName is required"));
        admin().body("{\"name\": \"other\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"a=b\"}, \"container\": {\"name\": \"c\"}}")
            .put(DATA_SOURCES + "('mismatch')" + API).then().statusCode(400);
    }

    @Test
    void anOmittedIdentityKeepsTheStoredOne() {
        String identity = "/subscriptions/s/resourceGroups/r/providers/Microsoft.ManagedIdentity/"
            + "userAssignedIdentities/indexer";
        admin().body("{\"name\": \"mi\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"ResourceId=/subscriptions/s/resourceGroups/r/providers/Microsoft.Storage/storageAccounts/acct;\"},"
                + " \"container\": {\"name\": \"docs\"}, \"identity\": {\"@odata.type\": "
                + "\"#Microsoft.Azure.Search.DataUserAssignedIdentity\", \"userAssignedIdentity\": \"" + identity
                + "\"}}")
            .post(DATA_SOURCES + API).then().statusCode(201)
            .body("identity.userAssignedIdentity", equalTo(identity));
        admin().header("Prefer", "return=representation")
            .body("{\"name\": \"mi\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"<unchanged>\"}, \"container\": {\"name\": \"docs\", \"query\": \"sub\"}}")
            .put(DATA_SOURCES + "('mi')" + API).then().statusCode(200)
            .body("identity.userAssignedIdentity", equalTo(identity))
            .body("container.query", equalTo("sub"));
        admin().header("Prefer", "return=representation")
            .body("{\"name\": \"mi\", \"type\": \"azureblob\", \"credentials\": {\"connectionString\": "
                + "\"<unchanged>\"}, \"container\": {\"name\": \"docs\"}, \"identity\": {\"@odata.type\": "
                + "\"#Microsoft.Azure.Search.DataNoneIdentity\"}}")
            .put(DATA_SOURCES + "('mi')" + API).then().statusCode(200)
            .body("identity.'@odata.type'", equalTo("#Microsoft.Azure.Search.DataNoneIdentity"));
    }

    @Test
    void anUnsupportedTypeIsStoredButItsIndexerFailsPersistently() {
        createIndex(SERVICE, "{\"name\": \"rows\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}]}");
        admin().body("{\"name\": \"sql\", \"type\": \"azuresql\", \"credentials\": {\"connectionString\": "
                + "\"Server=tcp:sql;Database=db\"}, \"container\": {\"name\": \"dbo.items\"}, "
                + "\"dataChangeDetectionPolicy\": {\"@odata.type\": "
                + "\"#Microsoft.Azure.Search.SqlIntegratedChangeTrackingPolicy\"}}")
            .post(DATA_SOURCES + API).then().statusCode(201);
        createIndexer(SERVICE, "{\"name\": \"sql-indexer\", \"dataSourceName\": \"sql\", \"targetIndexName\": "
            + "\"rows\"}");
        assertEquals("persistentFailure", awaitRun(SERVICE, "sql-indexer", 0).getString("status"));
        admin().get(base(SERVICE) + "/indexers('sql-indexer')/search.status" + API).then().statusCode(200)
            .body("status", equalTo("error"))
            .body("lastResult.errorMessage", startsWith("floci-az cannot index data sources of type 'azuresql'"));
    }
}
