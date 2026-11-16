package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CosmosReaderTest {

    private static final String ACCOUNT = "readeracct";
    private static final String DATABASE = "catalog";
    private static final String CONTAINER = "products";
    private static final String DBS = "/" + ACCOUNT + "-cosmos/dbs";
    private static final String DOCS = DBS + "/" + DATABASE + "/colls/" + CONTAINER + "/docs";
    private static final String HIGH_WATER_MARK_QUERY =
            "SELECT * FROM c WHERE c._ts >= @HighWaterMark ORDER BY c._ts";

    @Inject
    CosmosReader reader;

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(Map.of("id", DATABASE)).post(DBS).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"" + CONTAINER + "\",\"partitionKey\":{\"paths\":[\"/category\"],\"kind\":\"Hash\"}}")
                .post(DBS + "/" + DATABASE + "/colls").then().statusCode(201);
    }

    @Test
    void highWaterMarkQueryReturnsDocumentsChangedSinceTheMarkWithSystemProperties() throws InterruptedException {
        insert("older", "books");
        long olderTs = timestampOf("older");
        while (Instant.now().getEpochSecond() <= olderTs) {
            Thread.sleep(50);
        }
        insert("newer", "games");
        long newerTs = timestampOf("newer");

        List<Object> all = reader.query(ACCOUNT, DATABASE, CONTAINER, HIGH_WATER_MARK_QUERY,
                Map.of("@HighWaterMark", olderTs)).orElseThrow();
        List<Object> changed = reader.query(ACCOUNT, DATABASE, CONTAINER, HIGH_WATER_MARK_QUERY,
                Map.of("@HighWaterMark", newerTs)).orElseThrow();

        assertThat(ids(all), contains("older", "newer"));
        assertThat(ids(changed), contains("newer"));
        Map<?, ?> document = (Map<?, ?>) changed.getFirst();
        assertEquals(newerTs, ((Number) document.get("_ts")).longValue());
        assertTrue(document.containsKey("_rid"));
        assertTrue(document.containsKey("_etag"));
        assertTrue(document.containsKey("_self"));
        assertEquals(List.of(), reader.query(ACCOUNT, DATABASE, CONTAINER, HIGH_WATER_MARK_QUERY,
                Map.of("@HighWaterMark", newerTs + 1)).orElseThrow());
    }

    @Test
    void runsProjectionsWithStringParametersAndDefaultsToEveryDocument() {
        insert("b1", "books");
        insert("g1", "games");
        insert("b2", "books");

        List<Object> projected = reader.query(ACCOUNT, DATABASE, CONTAINER,
                "SELECT c.id, c.category FROM c WHERE c.category = @category ORDER BY c.id",
                Map.of("@category", "books")).orElseThrow();

        assertEquals(List.of(Map.of("id", "b1", "category", "books"), Map.of("id", "b2", "category", "books")),
                projected);
        assertEquals(3, reader.query(ACCOUNT, DATABASE, CONTAINER, null, null).orElseThrow().size());
    }

    @Test
    void refusesAMultiPropertyOrderByWithoutACompositeIndex() {
        insert("b1", "books");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reader.query(ACCOUNT, DATABASE, CONTAINER, "SELECT * FROM c ORDER BY c.category, c.id", null));
        assertTrue(refused.getMessage().startsWith("SC2104"));
    }

    @Test
    void listsDatabasesAndContainersAndReportsMissingOnesAsEmpty() {
        given().contentType("application/json").body(Map.of("id", "archive")).post(DBS).then().statusCode(201);

        assertEquals(List.of("archive", DATABASE), reader.listDatabases(ACCOUNT));
        assertEquals(List.of(CONTAINER), reader.listContainers(ACCOUNT, DATABASE).orElseThrow());
        assertEquals(List.of(), reader.listContainers(ACCOUNT, "archive").orElseThrow());
        assertEquals(Optional.empty(), reader.listContainers(ACCOUNT, "missing"));
        assertEquals(Optional.empty(), reader.query(ACCOUNT, DATABASE, "missing", null, null));
        assertEquals(List.of(), reader.listDatabases("otheracct"));
    }

    private static void insert(String id, String category) {
        given().contentType("application/json").body(Map.of("id", id, "category", category))
                .post(DOCS).then().statusCode(201);
    }

    private static long timestampOf(String id) {
        Number ts = given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .body(Map.of("query", "SELECT VALUE c._ts FROM c WHERE c.id = '" + id + "'"))
                .post(DOCS).then().statusCode(200).extract().path("Documents[0]");
        return ts.longValue();
    }

    private static List<String> ids(List<Object> documents) {
        return documents.stream().map(document -> (String) ((Map<?, ?>) document).get("id")).toList();
    }
}
