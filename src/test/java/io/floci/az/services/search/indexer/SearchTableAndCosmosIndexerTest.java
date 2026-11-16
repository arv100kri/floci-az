package io.floci.az.services.search.indexer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.floci.az.services.search.indexer.IndexerTestSupport.COSMOS_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.TABLE_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createCosmosContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createDataSource;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndex;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndexer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createTable;
import static io.floci.az.services.search.indexer.IndexerTestSupport.insertEntity;
import static io.floci.az.services.search.indexer.IndexerTestSupport.lookup;
import static io.floci.az.services.search.indexer.IndexerTestSupport.mergeEntity;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.floci.az.services.search.indexer.IndexerTestSupport.searchAll;
import static io.floci.az.services.search.indexer.IndexerTestSupport.upsertCosmos;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@DisplayName("Search indexers over floci-az Table Storage and Azure Cosmos DB")
class SearchTableAndCosmosIndexerTest {

    private static final String SERVICE = "tcidx";
    private static final String SOFT_DELETE = "\"dataDeletionDetectionPolicy\": {\"@odata.type\": "
        + "\"#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy\", \"softDeleteColumnName\": "
        + "\"IsDeleted\", \"softDeleteMarkerValue\": \"true\"}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void tableEntitiesAreIndexedByPartitionAndRowKeyWithTimestampChangeDetection() {
        createTable("hotels");
        insertEntity("hotels", "{\"PartitionKey\": \"p1\", \"RowKey\": \"1\", \"HotelName\": \"Fancy\", \"Rating\": "
            + "4.5, \"Opened@odata.type\": \"Edm.DateTime\", \"Opened\": \"2020-01-02T03:04:05Z\", \"IsDeleted\": "
            + "false}");
        insertEntity("hotels", "{\"PartitionKey\": \"p1\", \"RowKey\": \"2\", \"HotelName\": \"Plain\", \"Rating\": "
            + "3, \"IsDeleted\": false}");
        insertEntity("hotels", "{\"PartitionKey\": \"p2\", \"RowKey\": \"1\", \"HotelName\": \"Elsewhere\"}");
        createIndex(SERVICE, """
            {"name": "hotels", "fields": [
              {"name": "Key", "type": "Edm.String", "key": true},
              {"name": "HotelName", "type": "Edm.String", "searchable": true},
              {"name": "Rating", "type": "Edm.Double"},
              {"name": "Opened", "type": "Edm.DateTimeOffset"},
              {"name": "PartitionKey", "type": "Edm.String"}]}
            """);
        createDataSource(SERVICE, "{\"name\": \"hotels-ds\", \"type\": \"azuretable\", \"credentials\": "
            + "{\"connectionString\": \"" + TABLE_CONNECTION + "\"}, \"container\": {\"name\": \"hotels\", \"query\": "
            + "\"PartitionKey eq 'p1'\"}, " + SOFT_DELETE + "}");
        createIndexer(SERVICE, "{\"name\": \"hotels-indexer\", \"dataSourceName\": \"hotels-ds\", "
            + "\"targetIndexName\": \"hotels\"}");

        JsonPath first = awaitRun(SERVICE, "hotels-indexer", 0);
        assertEquals("success", first.getString("status"), first.prettify());
        assertEquals(2, first.getInt("itemsProcessed"));
        Map<String, Object> fancy = lookup(SERVICE, "hotels", "p11");
        assertEquals("Fancy", fancy.get("HotelName"));
        assertEquals(4.5, ((Number) fancy.get("Rating")).doubleValue());
        assertEquals("2020-01-02T03:04:05Z", fancy.get("Opened"));
        assertEquals("p1", fancy.get("PartitionKey"));
        assertEquals(3.0, ((Number) lookup(SERVICE, "hotels", "p12").get("Rating")).doubleValue());

        assertEquals(0, runAndAwait(SERVICE, "hotels-indexer").getInt("itemsProcessed"));

        mergeEntity("hotels", "p1", "1", "{\"HotelName\": \"Fancier\"}");
        mergeEntity("hotels", "p1", "2", "{\"IsDeleted\": true}");
        JsonPath changed = runAndAwait(SERVICE, "hotels-indexer");
        assertEquals(2, changed.getInt("itemsProcessed"), changed.prettify());
        assertEquals(List.of("Fancier"), searchAll(SERVICE, "hotels").stream()
            .map(document -> document.get("HotelName")).toList());
    }

    @Test
    void cosmosDocumentsAreIndexedWithTheTimestampHighWaterMark() {
        createCosmosContainer("items");
        upsertCosmos("items", "{\"id\": \"h1\", \"pk\": \"a\", \"name\": \"first item\"}");
        upsertCosmos("items", "{\"id\": \"h2\", \"pk\": \"a\", \"name\": \"second item\"}");
        createIndex(SERVICE, """
            {"name": "items", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "name", "type": "Edm.String", "searchable": true}]}
            """);
        createIndex(SERVICE, """
            {"name": "byrid", "fields": [
              {"name": "rid", "type": "Edm.String", "key": true},
              {"name": "name", "type": "Edm.String"}]}
            """);
        createDataSource(SERVICE, "{\"name\": \"items-ds\", \"type\": \"cosmosdb\", \"credentials\": "
            + "{\"connectionString\": \"" + COSMOS_CONNECTION + "\"}, \"container\": {\"name\": \"items\"}, "
            + "\"dataChangeDetectionPolicy\": {\"@odata.type\": "
            + "\"#Microsoft.Azure.Search.HighWaterMarkChangeDetectionPolicy\", \"highWaterMarkColumnName\": \"_ts\"}, "
            + SOFT_DELETE + "}");
        createIndexer(SERVICE, "{\"name\": \"items-indexer\", \"dataSourceName\": \"items-ds\", "
            + "\"targetIndexName\": \"items\"}");
        JsonPath first = awaitRun(SERVICE, "items-indexer", 0);
        assertEquals("success", first.getString("status"), first.prettify());
        assertEquals(2, first.getInt("itemsProcessed"));
        assertThat(first.getString("finalTrackingState"), containsString("highWaterMark"));
        assertEquals(0, runAndAwait(SERVICE, "items-indexer").getInt("itemsProcessed"),
            "documents at the high-water mark are not indexed twice");

        upsertCosmos("items", "{\"id\": \"h1\", \"pk\": \"a\", \"name\": \"first item renamed\"}");
        upsertCosmos("items", "{\"id\": \"h3\", \"pk\": \"a\", \"name\": \"third item\"}");
        assertEquals(2, runAndAwait(SERVICE, "items-indexer").getInt("itemsProcessed"),
            "a document written in the same second as the high-water mark is still picked up");
        assertEquals("first item renamed", lookup(SERVICE, "items", "h1").get("name"));

        upsertCosmos("items", "{\"id\": \"h2\", \"pk\": \"a\", \"name\": \"second item\", \"IsDeleted\": true}");
        runAndAwait(SERVICE, "items-indexer");
        assertEquals(Set.of("h1", "h3"), searchAll(SERVICE, "items").stream()
            .map(document -> (String) document.get("id")).collect(Collectors.toSet()));

        createIndexer(SERVICE, "{\"name\": \"rid-indexer\", \"dataSourceName\": \"items-ds\", "
            + "\"targetIndexName\": \"byrid\"}");
        assertEquals("success", awaitRun(SERVICE, "rid-indexer", 0).getString("status"));
        for (Map<String, Object> document : searchAll(SERVICE, "byrid")) {
            String rid = (String) document.get("rid");
            String encoded = rid.substring(0, rid.length() - 1).replace('-', '+').replace('_', '/')
                + "=".repeat(rid.charAt(rid.length() - 1) - '0');
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            assertFalse(decoded.isEmpty(), "the key is the Base64-encoded _rid");
        }
        assertEquals(2, searchAll(SERVICE, "byrid").size());
    }

    @Test
    void aCustomCosmosQueryGetsTheHighWaterMarkParameter() {
        createCosmosContainer("orders");
        upsertCosmos("orders", "{\"id\": \"o1\", \"pk\": \"x\", \"status\": \"open\", \"total\": 10}");
        upsertCosmos("orders", "{\"id\": \"o2\", \"pk\": \"x\", \"status\": \"closed\", \"total\": 20}");
        createIndex(SERVICE, """
            {"name": "orders", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "amount", "type": "Edm.Int32"}]}
            """);
        createDataSource(SERVICE, "{\"name\": \"orders-ds\", \"type\": \"cosmosdb\", \"credentials\": "
            + "{\"connectionString\": \"" + COSMOS_CONNECTION + "\"}, \"container\": {\"name\": \"orders\", \"query\": "
            + "\"SELECT c.id, c.total AS amount, c._ts FROM c WHERE c.status = 'open' AND c._ts >= @HighWaterMark "
            + "ORDER BY c._ts\"}, \"dataChangeDetectionPolicy\": {\"@odata.type\": "
            + "\"#Microsoft.Azure.Search.HighWaterMarkChangeDetectionPolicy\", \"highWaterMarkColumnName\": \"_ts\"}}");
        createIndexer(SERVICE, "{\"name\": \"orders-indexer\", \"dataSourceName\": \"orders-ds\", "
            + "\"targetIndexName\": \"orders\"}");
        JsonPath run = awaitRun(SERVICE, "orders-indexer", 0);
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(List.of(Map.of("id", "o1", "amount", 10)), searchAll(SERVICE, "orders").stream()
            .map(document -> Map.of("id", document.get("id"), "amount", document.get("amount"))).toList());
    }
}
