package io.floci.az.services.table;

import io.floci.az.services.table.TableReader.EdmType;
import io.floci.az.services.table.TableReader.TableEntity;
import io.floci.az.services.table.TableReader.TableProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TableReaderTest {

    private static final String ACCOUNT = "readeracct";
    private static final String TABLE_ACCOUNT = ACCOUNT + "-table";
    private static final String TABLE = "people";

    @Inject
    TableReader reader;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void readsEveryPropertyWithItsEdmType() {
        createTable(TABLE);
        Response inserted = insert(TABLE, """
                {"PartitionKey":"p1","RowKey":"r1","Name":"Ada","Age":36,"Score":9.5,"Active":true,
                 "Visits@odata.type":"Edm.Int64","Visits":"9007199254740993",
                 "Joined@odata.type":"Edm.DateTime","Joined":"2024-01-02T03:04:05.1234567Z",
                 "Born@odata.type":"Edm.DateTime","Born":"1815-12-10T00:00:00",
                 "Id@odata.type":"Edm.Guid","Id":"c9da6455-213d-42c9-9a79-3e9149a57833",
                 "Avatar@odata.type":"Edm.Binary","Avatar":"AQID",
                 "Ratio@odata.type":"Edm.Double","Ratio":"NaN",
                 "Broken@odata.type":"Edm.Int64","Broken":"not a number"}
                """);

        TableEntity entity = reader.query(ACCOUNT, TABLE, null, null).orElseThrow().getFirst();

        assertEquals("p1", entity.partitionKey());
        assertEquals("r1", entity.rowKey());
        assertEquals(inserted.header("ETag"), entity.etag());
        String restTimestamp = given().get("/{account}/{table}(PartitionKey='p1',RowKey='r1')", TABLE_ACCOUNT, TABLE)
                .then().statusCode(200).extract().path("Timestamp");
        assertEquals(Instant.parse(restTimestamp), entity.timestamp());
        assertThat(List.copyOf(entity.properties().keySet()), contains("PartitionKey", "RowKey", "Timestamp", "Name",
                "Age", "Score", "Active", "Visits", "Joined", "Born", "Id", "Avatar", "Ratio", "Broken"));
        assertProperty(entity, "Timestamp", EdmType.DATE_TIME, entity.timestamp());
        assertProperty(entity, "Name", EdmType.STRING, "Ada");
        assertProperty(entity, "Age", EdmType.INT32, 36);
        assertProperty(entity, "Score", EdmType.DOUBLE, 9.5);
        assertProperty(entity, "Active", EdmType.BOOLEAN, true);
        assertProperty(entity, "Visits", EdmType.INT64, 9007199254740993L);
        assertProperty(entity, "Joined", EdmType.DATE_TIME, Instant.parse("2024-01-02T03:04:05.1234567Z"));
        assertProperty(entity, "Born", EdmType.DATE_TIME, Instant.parse("1815-12-10T00:00:00Z"));
        assertProperty(entity, "Id", EdmType.GUID, "c9da6455-213d-42c9-9a79-3e9149a57833");
        assertEquals(EdmType.BINARY, entity.properties().get("Avatar").type());
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) entity.properties().get("Avatar").value());
        assertProperty(entity, "Ratio", EdmType.DOUBLE, Double.NaN);
        assertProperty(entity, "Broken", EdmType.STRING, "not a number");
    }

    @Test
    void appliesTheODataFilterAndTheTimestampHighWaterMark() throws InterruptedException {
        createTable(TABLE);
        insert(TABLE, "{\"PartitionKey\":\"p1\",\"RowKey\":\"r1\",\"v\":1}");
        insert(TABLE, "{\"PartitionKey\":\"p1\",\"RowKey\":\"r2\",\"v\":1}");
        Thread.sleep(20);
        Instant highWaterMark = Instant.now();
        Thread.sleep(20);
        given().contentType("application/json").header("If-Match", "*").body("{\"v\":2}")
                .request("MERGE", "/{account}/{table}(PartitionKey='p1',RowKey='r2')", TABLE_ACCOUNT, TABLE)
                .then().statusCode(204);
        insert(TABLE, "{\"PartitionKey\":\"p2\",\"RowKey\":\"r1\",\"v\":1}");

        assertThat(keys(reader.query(ACCOUNT, TABLE, null, null)), contains("p1/r1", "p1/r2", "p2/r1"));
        assertThat(keys(reader.query(ACCOUNT, TABLE, null, highWaterMark)), contains("p1/r2", "p2/r1"));
        assertThat(keys(reader.query(ACCOUNT, TABLE, "Timestamp ge datetime'" + highWaterMark + "'", null)),
                contains("p1/r2", "p2/r1"));
        assertThat(keys(reader.query(ACCOUNT, TABLE, "PartitionKey eq 'p1'", highWaterMark)), contains("p1/r2"));
        assertThat(keys(reader.query(ACCOUNT, TABLE, "v gt 1", null)), contains("p1/r2"));
        assertEquals(List.of(), reader.query(ACCOUNT, TABLE, null, Instant.now().plusSeconds(60)).orElseThrow());
    }

    @Test
    void reportsAMissingTableAsEmpty() {
        createTable("zebras");
        createTable("aardvarks");

        assertEquals(List.of("aardvarks", "zebras"), reader.listTables(ACCOUNT));
        assertTrue(reader.tableExists(ACCOUNT, "zebras"));
        assertFalse(reader.tableExists(ACCOUNT, TABLE));
        assertEquals(Optional.empty(), reader.query(ACCOUNT, TABLE, null, null));
        assertEquals(List.of(), reader.query(ACCOUNT, "zebras", null, null).orElseThrow());
    }

    private static void assertProperty(TableEntity entity, String name, EdmType type, Object value) {
        TableProperty property = entity.properties().get(name);
        assertEquals(type, property.type(), name);
        assertEquals(value, property.value(), name);
    }

    private static List<String> keys(Optional<List<TableEntity>> entities) {
        return entities.orElseThrow().stream()
                .map(entity -> entity.partitionKey() + "/" + entity.rowKey())
                .toList();
    }

    private static void createTable(String table) {
        given().contentType("application/json").body("{\"TableName\":\"" + table + "\"}")
                .post("/{account}/Tables", TABLE_ACCOUNT)
                .then().statusCode(201);
    }

    private static Response insert(String table, String json) {
        Response response = given().contentType("application/json").body(json)
                .post("/{account}/{table}", TABLE_ACCOUNT, table);
        response.then().statusCode(201);
        return response;
    }
}
