package io.floci.az.services.search.indexer;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.PRIVATE;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.createServiceAndDocs;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.dataSource;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.firstRun;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.storageAccount;
import static io.floci.az.services.search.indexer.IndexerTestSupport.BLOB_CONNECTION;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Without {@code strict-network}, the network rules of a data source's storage account are stored and answered only;
 * {@link SearchIndexerStrictNetworkTest} covers them under it.
 */
@QuarkusTest
@DisplayName("Search indexers in either execution environment without strict-network")
class SearchIndexerPrivateEnvironmentTest {

    private String key;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        key = createServiceAndDocs();
    }

    @Test
    void aClosedStorageAccountIsReadInEitherEnvironmentWithoutALink() {
        storageAccount("{\"publicNetworkAccess\":\"Disabled\",\"networkAcls\":{\"defaultAction\":\"Deny\","
            + "\"bypass\":\"None\"}}");
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);

        JsonPath privateRun = firstRun(key, "private-indexer", "docs-ds", PRIVATE);
        assertEquals("success", privateRun.getString("status"), privateRun.prettify());
        assertEquals(1, privateRun.getInt("itemsProcessed"));

        JsonPath standardRun = firstRun(key, "standard-indexer", "docs-ds", null);
        assertEquals("success", standardRun.getString("status"), standardRun.prettify());
        assertEquals(1, standardRun.getInt("itemsProcessed"));
    }
}
