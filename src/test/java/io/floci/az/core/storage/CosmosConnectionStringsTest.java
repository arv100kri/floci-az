package io.floci.az.core.storage;

import io.floci.az.core.storage.CosmosConnectionStrings.Credential;
import io.floci.az.core.storage.CosmosConnectionStrings.Resolution;
import io.floci.az.core.storage.CosmosConnectionStrings.Resolved;
import io.floci.az.core.storage.CosmosConnectionStrings.Unresolved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CosmosConnectionStringsTest {

    private static final String KEY = ";AccountKey=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGy"
            + "PMbIZnqyMsEcaGQy67XIw/Jw==;Database=catalog";

    @ParameterizedTest
    @CsvSource({
            "https://MyCosmos.documents.azure.com:443/, mycosmos",
            "https://mycosmos.documents.azure.com, mycosmos",
            "https://mycosmos.documents.azure.cn/, mycosmos",
            "http://localhost:4577/mycosmos-cosmos/, mycosmos",
            "https://floci-az:4577/mycosmos-cosmos, mycosmos",
            "http://localhost:4577/mycosmos-cosmos-nosql/, mycosmos",
            "https://localhost:4577/, devstoreaccount1",
            "https://localhost:4577, devstoreaccount1",
            "https://localhost:8081/, devstoreaccount1"
    })
    void resolvesTheAccountTheEndpointNames(String endpoint, String account) {
        assertEquals(new Resolved(account, "catalog", Credential.ACCOUNT_KEY),
                resolved("AccountEndpoint=" + endpoint + KEY));
    }

    @Test
    void readsTheManagedIdentityResourceId() {
        String resourceId = "ResourceId=/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/"
                + "providers/Microsoft.DocumentDB/databaseAccounts/mycosmos";
        assertEquals(new Resolved("mycosmos", "catalog", Credential.MANAGED_IDENTITY),
                resolved(resourceId + "/;Database=catalog;IdentityAuthType=AccessToken"));
        assertEquals(new Resolved("mycosmos", "catalog", Credential.MANAGED_IDENTITY),
                resolved(resourceId + ";Database=catalog;"));
        assertThat(unresolved("ResourceId=/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/"
                + "storageAccounts/blobs;Database=catalog"), containsString("does not name a Cosmos DB account"));
    }

    @Test
    void reportsAnEndpointWithoutAKey() {
        assertEquals(new Resolved("mycosmos", "catalog", Credential.NONE),
                resolved("AccountEndpoint=http://localhost:4577/mycosmos-cosmos/;Database=catalog"));
    }

    @Test
    void refusesEndpointsOtherThanFlociAzNoSql() {
        assertThat(unresolved("AccountEndpoint=http://localhost:4577/mycosmos-cosmos-mongo/" + KEY),
                containsString("Cosmos DB for mongo"));
        assertThat(unresolved("AccountEndpoint=https://mycosmos.mongo.cosmos.azure.com:10255/" + KEY),
                containsString("not an Azure Cosmos DB for NoSQL endpoint"));
        assertThat(unresolved("AccountEndpoint=https://mycosmos.table.cosmos.azure.com/" + KEY),
                containsString("not an Azure Cosmos DB for NoSQL endpoint"));
        assertThat(unresolved("AccountEndpoint=https://mystorage.blob.core.windows.net/" + KEY),
                containsString("not an Azure Cosmos DB for NoSQL endpoint"));
        assertThat(unresolved("AccountEndpoint=http://localhost:4577/devstoreaccount1-table/" + KEY),
                containsString("not a floci-az Cosmos DB endpoint"));
        assertThat(unresolved("AccountEndpoint=localhost:4577" + KEY), containsString("not an http(s) URL"));
    }

    @Test
    void refusesStringsThatNameNoNoSqlDatabase() {
        assertThat(unresolved("AccountEndpoint=https://mycosmos.documents.azure.com/;AccountKey=k"),
                containsString("names no Database"));
        assertThat(unresolved("AccountEndpoint=https://mycosmos.documents.azure.com/" + KEY + ";ApiKind=MongoDb"),
                containsString("ApiKind=MongoDb"));
        assertThat(unresolved("AccountKey=k;Database=catalog"), containsString("needs AccountEndpoint or ResourceId"));
        assertThat(unresolved(""), containsString("empty"));
        assertThat(unresolved("AccountEndpoint"), containsString("not a key=value pair"));
    }

    @Test
    void acceptsTheNoSqlApiKind() {
        assertEquals("mycosmos",
                resolved("AccountEndpoint=https://mycosmos.documents.azure.com/" + KEY + ";ApiKind=Sql").accountName());
    }

    private static Resolved resolved(String connectionString) {
        Resolution resolution = CosmosConnectionStrings.resolve(connectionString);
        assertThat(resolution, instanceOf(Resolved.class));
        return (Resolved) resolution;
    }

    private static String unresolved(String connectionString) {
        Resolution resolution = CosmosConnectionStrings.resolve(connectionString);
        assertThat(resolution, instanceOf(Unresolved.class));
        return ((Unresolved) resolution).reason();
    }
}
