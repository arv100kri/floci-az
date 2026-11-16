package io.floci.az.services.search.indexer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.az.services.search.indexer.IndexerTestSupport.API;
import static io.floci.az.services.search.indexer.IndexerTestSupport.BLOB_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.PREVIEW_API;
import static io.floci.az.services.search.indexer.IndexerTestSupport.admin;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.base;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createDataSource;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndex;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndexer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.putBlob;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Search data plane: indexer definitions, runs and status")
class SearchIndexersTest {

    private static final String SERVICE = "ixsvc";
    private static final String INDEXERS = base(SERVICE) + "/indexers";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createContainer("docs");
        createIndex(SERVICE, """
            {"name": "docs", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "content", "type": "Edm.String", "searchable": true},
              {"name": "title", "type": "Edm.String"},
              {"name": "address", "type": "Edm.ComplexType", "fields": [{"name": "city", "type": "Edm.String"}]}]}
            """);
        createDataSource(SERVICE, "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"docs\"}}");
    }

    @Test
    void createReturnsTheNormalizedDefinitionAndRunsTheIndexer() {
        admin().body("""
                {"name": "docs-indexer", "dataSourceName": "docs-ds", "targetIndexName": "docs",
                 "schedule": {"interval": "PT5M"},
                 "parameters": {"batchSize": 5, "configuration": {"parsingMode": "text"}},
                 "fieldMappings": [{"sourceFieldName": "metadata_storage_name", "targetFieldName": "title"}]}
                """)
            .post(INDEXERS + API).then().statusCode(201)
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/ixsvc-search/indexers('docs-indexer')?api-version=2024-07-01"))
            .body("'@odata.context'", endsWith("/$metadata#indexers/$entity"))
            .body("'@odata.etag'", startsWith("\"0x"))
            .body("name", equalTo("docs-indexer"))
            .body("description", nullValue())
            .body("skillsetName", nullValue())
            .body("disabled", nullValue())
            .body("schedule.interval", equalTo("PT5M"))
            .body("schedule.startTime", notNullValue())
            .body("parameters.batchSize", equalTo(5))
            .body("parameters.configuration.parsingMode", equalTo("text"))
            .body("fieldMappings[0].sourceFieldName", equalTo("metadata_storage_name"))
            .body("fieldMappings[0].mappingFunction", nullValue())
            .body("outputFieldMappings", empty())
            .body("cache", nullValue())
            .body("encryptionKey", nullValue());
        JsonPath run = awaitRun(SERVICE, "docs-indexer", 0);
        assertEquals("success", run.getString("status"));
        assertEquals(0, run.getInt("itemsProcessed"));

        admin().get(INDEXERS + "('docs-indexer')" + API).then().statusCode(200)
            .header("ETag", startsWith("\"0x"))
            .body("parameters.configuration.parsingMode", equalTo("text"));
        admin().get(INDEXERS + API + "&$select=name").then().statusCode(200)
            .body("'@odata.context'", endsWith("/$metadata#indexers(name)"))
            .body("value.name", contains("docs-indexer"))
            .body("value[0]", not(hasKey("dataSourceName")));
        admin().get(base(SERVICE) + "/servicestats" + API).then().statusCode(200)
            .body("counters.indexersCount.usage", equalTo(1));
        admin().body("{\"name\": \"docs-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\"}")
            .post(INDEXERS + API).then().statusCode(409);
    }

    @Test
    void referencesFieldMappingsAndParametersAreValidated() {
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"nope\", \"targetIndexName\": \"docs\"}",
            "the data source 'nope' does not exist");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"nope\"}",
            "the target index 'nope' does not exist");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
            + "\"skillsetName\": \"nope\"}", "the skillset 'nope' does not exist");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"fieldMappings\": [{\"sourceFieldName\": \"a\", \"targetFieldName\": \"missing\"}]}",
            "Field mapping specifies target field 'missing' that doesn't exist in the index.");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"fieldMappings\": [{\"sourceFieldName\": \"a\", \"targetFieldName\": \"address\"}]}",
            "Field mapping specifies target field 'address' that doesn't exist in the index.");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"fieldMappings\": [{\"sourceFieldName\": \"a\", \"mappingFunction\": {\"name\": \"rot13\"}}]}",
            "uses the mapping function 'rot13', which is not supported");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"fieldMappings\": [{\"sourceFieldName\": \"title\", \"mappingFunction\": "
                + "{\"name\": \"extractTokenAtPosition\", \"parameters\": {\"delimiter\": \" \"}}}]}",
            "extractTokenAtPosition requires the parameter 'position'");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
            + "\"parameters\": {\"configuration\": {\"parsingMode\": \"yaml\"}}}", "parsingMode 'yaml' is not supported");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
                + "\"parameters\": {\"configuration\": {\"imageAction\": \"generateNormalizedImages\"}}}",
            "requires a skillset");
        assertInvalid("{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
            + "\"parameters\": {\"maxFailedItems\": -2}}", "parameters.maxFailedItems '-2' is invalid");
        assertInvalid("{\"name\": \"Upper\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\"}",
            "Indexer name 'Upper' is invalid");
    }

    @Test
    void scheduleIntervalsMustLieBetweenTheFloorAndOneDay() {
        assertInvalid(scheduled("PT1M"), "the interval must be at least PT5M and at most PT24H");
        assertInvalid(scheduled("P2D"), "the interval must be at least PT5M and at most PT24H");
        assertInvalid(scheduled("every hour"), "is not a valid duration");
        admin().body(scheduled("P1D")).post(INDEXERS + API).then().statusCode(201)
            .body("schedule.interval", equalTo("P1D"));
        admin().body(scheduled("PT2H").replace("\"name\": \"x\"", "\"name\": \"y\"")
                .replace("}}", ", \"startTime\": \"2024-01-01T00:00:00Z\"}}"))
            .post(INDEXERS + API).then().statusCode(201)
            .body("schedule.startTime", equalTo("2024-01-01T00:00:00Z"));
    }

    @Test
    void aDisabledIndexerRunsOnlyOnDemand() {
        createIndexer(SERVICE, "{\"name\": \"lazy\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", "
            + "\"disabled\": true}");
        admin().get(INDEXERS + "('lazy')/search.status" + API).then().statusCode(200)
            .body("name", equalTo("lazy"))
            .body("status", equalTo("running"))
            .body("lastResult", nullValue())
            .body("executionHistory", empty());
        putBlob("docs", "a.txt", "alpha", Map.of());
        JsonPath run = runAndAwait(SERVICE, "lazy");
        assertEquals(1, run.getInt("itemsProcessed"));
        admin().post(INDEXERS + "('missing')/search.run" + API).then().statusCode(404);
        admin().get(INDEXERS + "('missing')/search.status" + API).then().statusCode(404)
            .body("error.message", equalTo("Indexer 'missing' was not found."));
    }

    @Test
    void statusReportsTheRunResultsAndTheLimits() {
        putBlob("docs", "a.txt", "alpha", Map.of());
        createIndexer(SERVICE, "{\"name\": \"docs-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\"}");
        awaitRun(SERVICE, "docs-indexer", 0);
        admin().get(INDEXERS + "('docs-indexer')/search.status" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#Microsoft.Azure.Search.V2019_05_06.IndexerExecutionInfo"))
            .body("status", equalTo("running"))
            .body("lastResult.status", equalTo("success"))
            .body("lastResult.errorMessage", nullValue())
            .body("lastResult.startTime", notNullValue())
            .body("lastResult.endTime", notNullValue())
            .body("lastResult.itemsProcessed", equalTo(1))
            .body("lastResult.itemsFailed", equalTo(0))
            .body("lastResult.errors", empty())
            .body("lastResult.warnings", empty())
            .body("lastResult.initialTrackingState", nullValue())
            .body("lastResult.finalTrackingState", containsString("highWaterMark"))
            .body("lastResult", not(hasKey("mode")))
            .body("executionHistory", hasSize(1))
            .body("limits.maxRunTime", equalTo("PT2H"))
            .body("limits.maxDocumentExtractionSize", equalTo(134217728))
            .body("limits.maxDocumentContentCharactersToExtract", equalTo(4000000))
            .body("$", not(hasKey("currentState")));
        admin().get(INDEXERS + "('docs-indexer')/search.status" + PREVIEW_API).then().statusCode(200)
            .body("lastResult.mode", equalTo("indexingAllDocs"))
            .body("currentState.mode", equalTo("indexingAllDocs"))
            .body("currentState.resetDocumentKeys", empty());
    }

    @Test
    void etagsGuardIndexerUpdatesAndDeletes() {
        String etag = admin().body("{\"name\": \"guarded\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
                + "\"docs\", \"disabled\": true}")
            .post(INDEXERS + API).then().statusCode(201).extract().header("ETag");
        admin().header("If-Match", "\"0x1\"").body("{\"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\"}")
            .put(INDEXERS + "('guarded')" + API).then().statusCode(412);
        admin().header("If-Match", etag).header("Prefer", "return=representation")
            .body("{\"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", \"disabled\": true, "
                + "\"description\": \"updated\"}")
            .put(INDEXERS + "('guarded')" + API).then().statusCode(200)
            .body("name", equalTo("guarded"))
            .body("description", equalTo("updated"));
        admin().header("If-Match", etag).delete(INDEXERS + "('guarded')" + API).then().statusCode(412);
        admin().delete(INDEXERS + "('guarded')" + API).then().statusCode(204);
        admin().get(INDEXERS + "('guarded')" + API).then().statusCode(404);
    }

    @Test
    void resetDocumentsAndResyncValidateTheirBodies() {
        createIndexer(SERVICE, "{\"name\": \"docs-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\", \"disabled\": true}");
        String indexer = INDEXERS + "('docs-indexer')";
        admin().body("{}").post(indexer + "/search.resetdocs" + PREVIEW_API).then().statusCode(400);
        admin().body("{\"documentKeys\": [\"a\"], \"datasourceDocumentIds\": [\"b\"]}")
            .post(indexer + "/search.resetdocs" + PREVIEW_API).then().statusCode(400);
        admin().body("{\"datasourceDocumentIds\": [\"b\"]}").post(indexer + "/search.resetdocs" + PREVIEW_API)
            .then().statusCode(204);
        admin().body("{\"documentKeys\": [\"a\"]}").post(indexer + "/search.resetdocs" + PREVIEW_API + "&overwrite=true")
            .then().statusCode(204);
        admin().get(indexer + "/search.status" + PREVIEW_API).then().statusCode(200)
            .body("currentState.resetDocumentKeys", contains("a"))
            .body("currentState.resetDatasourceDocumentIds", empty());
        admin().body("{\"options\": [\"permissions\"]}").post(indexer + "/search.resync" + PREVIEW_API)
            .then().statusCode(204);
        admin().body("{\"options\": [\"content\"]}").post(indexer + "/search.resync" + PREVIEW_API)
            .then().statusCode(400).body("error.message", containsString("'content' is not supported"));
        admin().body("{}").post(indexer + "/search.resync" + PREVIEW_API).then().statusCode(400);
    }

    @Test
    void aRunFailsWhenTheTargetIndexIsGone() {
        createIndexer(SERVICE, "{\"name\": \"docs-indexer\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": "
            + "\"docs\", \"disabled\": true}");
        admin().delete(base(SERVICE) + "/indexes('docs')" + API).then().statusCode(204);
        JsonPath run = runAndAwait(SERVICE, "docs-indexer");
        assertEquals("transientFailure", run.getString("status"));
        assertEquals("The target index 'docs' does not exist.", run.getString("errorMessage"));
    }

    private static String scheduled(String interval) {
        return "{\"name\": \"x\", \"dataSourceName\": \"docs-ds\", \"targetIndexName\": \"docs\", \"disabled\": true, "
            + "\"schedule\": {\"interval\": \"" + interval + "\"}}";
    }

    private static void assertInvalid(String body, String message) {
        admin().body(body).post(INDEXERS + API).then().statusCode(400)
            .body("error.message", containsString(message));
    }
}
