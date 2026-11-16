package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.model.SearchJson;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.floci.az.services.search.indexer.IndexerTestSupport.API;
import static io.floci.az.services.search.indexer.IndexerTestSupport.BLOB_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.admin;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.base;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createCosmosContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createDataSource;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndex;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndexer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createTable;
import static io.floci.az.services.search.indexer.IndexerTestSupport.historySize;
import static io.floci.az.services.search.indexer.IndexerTestSupport.insertEntity;
import static io.floci.az.services.search.indexer.IndexerTestSupport.pause;
import static io.floci.az.services.search.indexer.IndexerTestSupport.putBlob;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.floci.az.services.search.indexer.IndexerTestSupport.searchAll;
import static io.floci.az.services.search.indexer.IndexerTestSupport.upsertCosmos;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Indexer behaviour that needs its own configuration: managed-identity connections under
 * {@code parity-overrides.strict-rbac}, schedules with the interval floor lowered to one second, and the skillset path
 * through {@link FakeSkillsetRunner}.
 */
@QuarkusTest
@TestProfile(SearchIndexerProfileTest.Profile.class)
@DisplayName("Search indexers: managed identity, schedules and skillsets")
class SearchIndexerProfileTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-rbac", "true",
                "floci-az.services.search.indexer-min-interval-seconds", "1");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FakeSkillsetRunner.class);
        }
    }

    private static final String SERVICE = "misvc";
    private static final String SUBSCRIPTION = "eeeeeeee-1111-2222-3333-000000000009";
    private static final String GROUP = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/search-rg";
    private static final String STORAGE = GROUP + "/providers/Microsoft.Storage/storageAccounts/"
        + IndexerTestSupport.ACCOUNT;
    private static final String COSMOS = GROUP + "/providers/Microsoft.DocumentDB/databaseAccounts/"
        + IndexerTestSupport.ACCOUNT;
    private static final String USER_IDENTITY = GROUP
        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/indexer-identity";
    private static final String SYSTEM_PRINCIPAL = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String USER_PRINCIPAL = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String STORAGE_BLOB_DATA_READER = "2a2b9908-6ea1-4ae2-8e65-a410df84e7d1";
    private static final String STORAGE_TABLE_DATA_READER = "76199698-9eea-4c19-bc75-cec21354c6b6";
    private static final String COSMOS_ACCOUNT_READER = "fbdf93bf-df7d-467e-a4d2-9458aa1360c8";

    @Inject
    SearchServiceRegistry registry;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        FakeSkillsetRunner.release();
    }

    @AfterEach
    void releaseRunner() {
        FakeSkillsetRunner.release();
    }

    private void registerService(String identityJson) {
        registry.put(new SearchServiceInfo(SERVICE, GROUP + "/providers/Microsoft.Search/searchServices/" + SERVICE,
            SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY, SearchServiceRegistry.DEV_SECONDARY_ADMIN_KEY,
            List.of(new SearchQueryKey("", SearchServiceRegistry.DEV_QUERY_KEY)), false, null, "enabled", null,
            identityJson == null ? null : SearchJson.parse(identityJson.getBytes(StandardCharsets.UTF_8)), "standard"));
    }

    private static void assign(String scope, String roleGuid, String principal) {
        String id = scope + "/providers/Microsoft.Authorization/roleAssignments/" + UUID.randomUUID();
        given().contentType("application/json").queryParam("api-version", "2022-04-01")
            .body("{\"properties\":{\"roleDefinitionId\":\"/subscriptions/" + SUBSCRIPTION
                + "/providers/Microsoft.Authorization/roleDefinitions/" + roleGuid + "\",\"principalId\":\""
                + principal + "\",\"principalType\":\"ServicePrincipal\"}}")
            .when().put(id).then().statusCode(201);
    }

    private void storeSkillset(String name, String fake) {
        String key = "dp/skillset/" + SERVICE + "/" + name;
        ObjectNode skillset = (ObjectNode) SearchJson.parse(("{\"name\": \"" + name + "\", \"skills\": [], \"fake\": "
            + fake + "}").getBytes(StandardCharsets.UTF_8));
        storageFactory.create("search").put(key, new StoredObject(key, SearchJson.writeBytes(skillset), Map.of(),
            Instant.now(), "\"0x1\""));
    }

    private static void blobIndexAndSource(String connection, String identity) {
        createContainer("docs");
        putBlob("docs", "a.txt", "alpha", Map.of());
        createIndex(SERVICE, "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}, {\"name\": \"title\", "
            + "\"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + connection + "\"}, \"container\": {\"name\": \"docs\"}"
            + (identity == null ? "" : ", \"identity\": " + identity) + "}");
    }

    @Test
    void aBlobIndexerWithAManagedIdentityNeedsTheIdentityAndItsRole() {
        registerService(null);
        blobIndexAndSource("ResourceId=" + STORAGE + "/;", null);
        createIndexer(SERVICE, "{\"name\": \"mi-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\"}");
        JsonPath noIdentity = awaitRun(SERVICE, "mi-indexer", 0);
        assertEquals("transientFailure", noIdentity.getString("status"));
        assertThat(noIdentity.getString("errorMessage"), containsString("has no system-assigned managed identity"));

        registerService("{\"type\": \"SystemAssigned\", \"principalId\": \"" + SYSTEM_PRINCIPAL + "\", \"tenantId\": "
            + "\"00000000-0000-0000-0000-000000000001\"}");
        JsonPath noRole = runAndAwait(SERVICE, "mi-indexer");
        assertEquals("transientFailure", noRole.getString("status"));
        assertThat(noRole.getString("errorMessage"), containsString("is not authorized to read '" + STORAGE
            + "/blobServices/default/containers/docs'"));
        assertThat(noRole.getString("errorMessage"), containsString("Storage Blob Data Reader"));

        assign(STORAGE, STORAGE_BLOB_DATA_READER, SYSTEM_PRINCIPAL);
        JsonPath granted = runAndAwait(SERVICE, "mi-indexer");
        assertEquals("success", granted.getString("status"), granted.prettify());
        assertEquals(1, granted.getInt("itemsProcessed"));
    }

    @Test
    void aUserAssignedIdentityMustBeAssignedToTheService() {
        registerService("{\"type\": \"SystemAssigned\", \"principalId\": \"" + SYSTEM_PRINCIPAL + "\", \"tenantId\": "
            + "\"00000000-0000-0000-0000-000000000001\"}");
        blobIndexAndSource("ResourceId=" + STORAGE + ";", "{\"@odata.type\": "
            + "\"#Microsoft.Azure.Search.DataUserAssignedIdentity\", \"userAssignedIdentity\": \"" + USER_IDENTITY
            + "\"}");
        assign(STORAGE + "/blobServices/default/containers/docs", STORAGE_BLOB_DATA_READER, USER_PRINCIPAL);
        createIndexer(SERVICE, "{\"name\": \"uai-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\"}");
        JsonPath unassigned = awaitRun(SERVICE, "uai-indexer", 0);
        assertThat(unassigned.getString("errorMessage"), containsString("which is not assigned to the search service"));

        registerService("{\"type\": \"SystemAssigned, UserAssigned\", \"principalId\": \"" + SYSTEM_PRINCIPAL
            + "\", \"tenantId\": \"00000000-0000-0000-0000-000000000001\", \"userAssignedIdentities\": {\""
            + USER_IDENTITY.toUpperCase(Locale.ROOT) + "\": {\"principalId\": \"" + USER_PRINCIPAL
            + "\", \"clientId\": \"c\"}}}");
        JsonPath assigned = runAndAwait(SERVICE, "uai-indexer");
        assertEquals("success", assigned.getString("status"), assigned.prettify());
    }

    @Test
    void tableAndCosmosIndexersCheckTheirDocumentedRoles() {
        registerService("{\"type\": \"SystemAssigned\", \"principalId\": \"" + SYSTEM_PRINCIPAL + "\", \"tenantId\": "
            + "\"00000000-0000-0000-0000-000000000001\"}");
        createTable("rows");
        insertEntity("rows", "{\"PartitionKey\": \"p\", \"RowKey\": \"1\", \"Name\": \"one\"}");
        createIndex(SERVICE, "{\"name\": \"rows\", \"fields\": [{\"name\": \"Key\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"Name\", \"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"rows-ds\", \"type\": \"azuretable\", \"credentials\": "
            + "{\"connectionString\": \"ResourceId=" + STORAGE + ";\"}, \"container\": {\"name\": \"rows\"}}");
        createIndexer(SERVICE, "{\"name\": \"rows-indexer\", \"dataSourceName\": \"rows-ds\", \"targetIndexName\": "
            + "\"rows\"}");
        assertThat(awaitRun(SERVICE, "rows-indexer", 0).getString("errorMessage"),
            containsString("Storage Table Data Reader"));
        assign(STORAGE, STORAGE_TABLE_DATA_READER, SYSTEM_PRINCIPAL);
        assertEquals("success", runAndAwait(SERVICE, "rows-indexer").getString("status"));

        createCosmosContainer("items");
        upsertCosmos("items", "{\"id\": \"i1\", \"pk\": \"a\", \"name\": \"item\"}");
        createIndex(SERVICE, "{\"name\": \"items\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"name\", \"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"items-ds\", \"type\": \"cosmosdb\", \"credentials\": "
            + "{\"connectionString\": \"ResourceId=" + COSMOS + ";Database=catalog;IdentityAuthType=AccessToken\"}, "
            + "\"container\": {\"name\": \"items\"}}");
        createIndexer(SERVICE, "{\"name\": \"items-indexer\", \"dataSourceName\": \"items-ds\", \"targetIndexName\": "
            + "\"items\"}");
        assertThat(awaitRun(SERVICE, "items-indexer", 0).getString("errorMessage"),
            containsString("Microsoft.DocumentDB/databaseAccounts/read"));
        assign(COSMOS, COSMOS_ACCOUNT_READER, SYSTEM_PRINCIPAL);
        assertEquals("success", runAndAwait(SERVICE, "items-indexer").getString("status"));
    }

    @Test
    void aScheduledIndexerRunsAtItsIntervalAboveTheConfiguredFloor() {
        createContainer("docs");
        createIndex(SERVICE, "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}]}");
        createDataSource(SERVICE, "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"docs\"}}");
        admin().body("{\"name\": \"fast\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"schedule\": {\"interval\": \"PT0.5S\"}}")
            .post(base(SERVICE) + "/indexers" + API).then().statusCode(400);
        createIndexer(SERVICE, "{\"name\": \"scheduled\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\", \"schedule\": {\"interval\": \"PT1S\"}}");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (historySize(SERVICE, "scheduled") < 3 && Instant.now().isBefore(deadline)) {
            pause(Duration.ofMillis(100));
        }
        assertThat(historySize(SERVICE, "scheduled"), greaterThanOrEqualTo(3));

        admin().body("{\"name\": \"scheduled\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"disabled\": true, \"schedule\": {\"interval\": \"PT1S\"}}")
            .put(base(SERVICE) + "/indexers('scheduled')" + API).then().statusCode(204);
        pause(Duration.ofMillis(300));
        int afterDisable = historySize(SERVICE, "scheduled");
        pause(Duration.ofMillis(2500));
        assertEquals(afterDisable, historySize(SERVICE, "scheduled"), "a disabled indexer is not scheduled");
    }

    @Test
    void theSkillsetEnrichesProjectsAndReportsThroughTheRunner() {
        createContainer("docs");
        putBlob("docs", "a.txt", "alpha beta", Map.of());
        putBlob("docs", "b.txt", "please fail here", Map.of());
        createIndex(SERVICE, "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}, {\"name\": \"title\", "
            + "\"type\": \"Edm.String\"}, {\"name\": \"shout\", \"type\": \"Edm.String\"}]}");
        createIndex(SERVICE, "{\"name\": \"chunks\", \"fields\": [{\"name\": \"chunkId\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"parentId\", \"type\": \"Edm.String\"}, {\"name\": \"chunk\", "
            + "\"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"docs\"}}");
        storeSkillset("enrich", "{\"upperCaseInto\": \"shout\", \"projectInto\": \"chunks\", \"warn\": true, "
            + "\"failOn\": \"fail\"}");
        createIndexer(SERVICE, """
            {"name": "enrich-indexer", "dataSourceName": "docs-ds", "targetIndexName": "docs", "skillsetName": "enrich",
             "parameters": {"maxFailedItems": -1},
             "fieldMappings": [{"sourceFieldName": "metadata_storage_name", "targetFieldName": "title"}],
             "outputFieldMappings": [{"sourceFieldName": "/document/shout", "targetFieldName": "shout"}]}
            """);
        JsonPath run = awaitRun(SERVICE, "enrich-indexer", 0);
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(2, run.getInt("itemsProcessed"));
        assertEquals(1, run.getInt("itemsFailed"));
        assertEquals("Fake skill failed on 'fail'.", run.getString("errors[0].errorMessage"));
        assertEquals("Enrichment.Skillset.enrich", run.getString("errors[0].name"));
        assertEquals(List.of("Fake warning.", "Fake warning."), run.getList("warnings.message"));

        List<Map<String, Object>> documents = searchAll(SERVICE, "docs");
        assertEquals(1, documents.size(), "a document with skill errors is not indexed");
        assertEquals("ALPHA BETA", documents.getFirst().get("shout"));
        assertEquals("a.txt", documents.getFirst().get("title"));
        assertEquals(Set.of("alpha", "beta"), searchAll(SERVICE, "chunks").stream()
            .map(chunk -> (String) chunk.get("chunk")).collect(Collectors.toSet()));
        ObjectNode seen = FakeSkillsetRunner.lastDocument();
        assertEquals("b.txt", seen.path("title").asText(), "the /document node carries the field mapping targets");
        assertTrue(seen.has("metadata_storage_path"), "and the cracked source fields");
        assertEquals("/document/shout", FakeSkillsetRunner.lastOutputFieldMappings().path(0).path("sourceFieldName")
            .asText());

        storeSkillset("replace", "{\"projectInto\": \"chunks\", \"skipParent\": true}");
        createIndex(SERVICE, "{\"name\": \"parents\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}]}");
        createIndexer(SERVICE, "{\"name\": \"replace-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"parents\", \"skillsetName\": \"replace\"}");
        assertEquals("success", awaitRun(SERVICE, "replace-indexer", 0).getString("status"));
        assertTrue(searchAll(SERVICE, "parents").isEmpty(), "the projections replace the parent documents");
    }

    @Test
    void aMissingSkillsetFailsTheRunAndARunInProgressRefusesAnother() {
        createContainer("docs");
        putBlob("docs", "a.txt", "alpha", Map.of());
        createIndex(SERVICE, "{\"name\": \"docs\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"docs\"}}");
        storeSkillset("slow", "{\"block\": true}");
        FakeSkillsetRunner.block();
        createIndexer(SERVICE, "{\"name\": \"slow-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\", \"skillsetName\": \"slow\"}");
        admin().get(base(SERVICE) + "/indexers('slow-indexer')/search.status" + API).then().statusCode(200)
            .body("lastResult.status", equalTo("inProgress"));
        admin().post(base(SERVICE) + "/indexers('slow-indexer')/search.run" + API).then().statusCode(409)
            .body("error.message", containsString("Another indexer invocation is currently in progress; concurrent "
                + "invocations are not allowed."));
        FakeSkillsetRunner.release();
        assertEquals("success", awaitRun(SERVICE, "slow-indexer", 0).getString("status"));

        String skillsetKey = "dp/skillset/" + SERVICE + "/slow";
        storageFactory.create("search").delete(skillsetKey);
        JsonPath missing = runAndAwait(SERVICE, "slow-indexer");
        assertEquals("transientFailure", missing.getString("status"));
        assertEquals("The skillset 'slow' that the indexer references was not found.",
            missing.getString("errorMessage"));
    }
}
