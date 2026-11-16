package io.floci.az.services.search.indexer;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.DOCS_INDEX;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.MANAGED_IDENTITY_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.PRIVATE;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.SERVICE;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.STORAGE_ID;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.USER_ASSIGNED;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.createServiceAndDocs;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.dataSource;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.firstRun;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.sharedPrivateLink;
import static io.floci.az.services.search.indexer.IndexerNetworkFixtures.storageAccount;
import static io.floci.az.services.search.indexer.IndexerTestSupport.BLOB_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.COSMOS_CONNECTION;
import static io.floci.az.services.search.indexer.IndexerTestSupport.awaitRun;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createCosmosContainer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createDataSource;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndex;
import static io.floci.az.services.search.indexer.IndexerTestSupport.createIndexer;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.floci.az.services.search.indexer.IndexerTestSupport.upsertCosmos;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Under {@code strict-network}, an indexer reads a storage account whose public endpoint is closed only from the
 * private execution environment through an approved shared private link, or as a trusted service; an open account,
 * and a source floci-az holds no network rules for, admit both environments.
 */
@QuarkusTest
@TestProfile(SearchIndexerStrictNetworkTest.StrictNetwork.class)
@DisplayName("Search indexers under strict-network: the network rules of the data source's storage account")
class SearchIndexerStrictNetworkTest {

    public static class StrictNetwork implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-network", "true");
        }
    }

    private static final String DENY = "{\"networkAcls\":{\"defaultAction\":\"Deny\",\"bypass\":\"None\"}}";
    private static final String DISABLED = "{\"publicNetworkAccess\":\"Disabled\",\"networkAcls\":{\"bypass\":"
        + "\"None\"}}";

    private String key;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        key = createServiceAndDocs();
    }

    private static void assertRead(JsonPath run) {
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(1, run.getInt("itemsProcessed"), run.prettify());
    }

    /** Asserts that the run was refused by the storage account, with {@code detail} in the message. */
    private static String assertRefused(JsonPath run, String detail) {
        assertEquals("transientFailure", run.getString("status"), run.prettify());
        String message = run.getString("errorMessage");
        assertThat(message, containsString("The storage account '" + STORAGE_ID + "' refuses the indexer, since "));
        assertThat(message, containsString(detail));
        return message;
    }

    @Test
    void anOpenAccountAdmitsBothEnvironmentsWithoutALink() {
        storageAccount("{\"publicNetworkAccess\":\"Enabled\",\"networkAcls\":{\"defaultAction\":\"Allow\"}}");
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);

        assertRead(firstRun(key, "standard-indexer", "docs-ds", null));
        assertRead(firstRun(key, "private-indexer", "docs-ds", PRIVATE));

        // A service a data-plane request provisioned has no ARM resource, so it can hold no link.
        createIndex("dpsvc", DOCS_INDEX);
        createDataSource("dpsvc", "{\"name\": \"docs-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"docs\"}}");
        createIndexer("dpsvc", "{\"name\": \"private-indexer\", \"dataSourceName\": \"docs-ds\", "
            + "\"targetIndexName\": \"docs\", " + PRIVATE + "}");
        assertRead(awaitRun("dpsvc", "private-indexer", 0));
    }

    @Test
    void aPrivateIndexerWithoutALinkIsRefusedByAClosedAccount() {
        storageAccount(DISABLED);
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);

        String message = assertRefused(firstRun(key, "private-indexer", "docs-ds", PRIVATE),
            "its publicNetworkAccess is Disabled.");
        assertThat(message, containsString("The indexer runs in the private execution environment, but the search "
            + "service '" + SERVICE + "' has no Approved shared private link to the group 'blob' of the account."));
        assertThat(message, containsString("To read it, create a shared private link resource to the group 'blob' "
            + "of the account and approve its connection; or connect as a trusted service"));
        assertThat(message, containsString("or open the account to public network access"));
    }

    @Test
    void aPrivateIndexerReadsAClosedAccountThroughAnApprovedLinkToTheGroupOfItsSource() {
        storageAccount(DENY);
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);
        dataSource(key, "lake-ds", "adlsgen2", MANAGED_IDENTITY_CONNECTION, "docs", null);
        sharedPrivateLink("table-link", STORAGE_ID, "table");

        assertRefused(firstRun(key, "blob-indexer", "docs-ds", PRIVATE), "its networkAcls.defaultAction is Deny.");
        sharedPrivateLink("blob-link", STORAGE_ID.toUpperCase(Locale.ROOT), "BLOB");
        assertRead(runAndAwait(SERVICE, "blob-indexer", key));

        assertRefused(firstRun(key, "lake-indexer", "lake-ds", PRIVATE), "no Approved shared private link to the "
            + "group 'dfs' of the account");
        sharedPrivateLink("dfs-link", STORAGE_ID, "dfs");
        assertRead(runAndAwait(SERVICE, "lake-indexer", key));
    }

    @Test
    void aStandardIndexerCannotUseTheLinkToAClosedAccount() {
        storageAccount(DENY);
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);
        sharedPrivateLink("blob-link", STORAGE_ID, "blob");

        String message = assertRefused(firstRun(key, "standard-indexer", "docs-ds", null),
            "has an Approved shared private link to the group 'blob' of the account, but only the private execution "
                + "environment can use a shared private link, and the indexer runs in the standard environment.");
        assertThat(message, containsString("To read it, set the indexer's executionEnvironment to private"));

        assertRead(firstRun(key, "private-indexer", "docs-ds", PRIVATE));
    }

    @Test
    void theTrustedServiceExceptionAdmitsBlobSourcesReadWithTheSystemAssignedIdentity() {
        storageAccount("{\"networkAcls\":{\"defaultAction\":\"Deny\",\"bypass\":\"AzureServices\"}}");
        dataSource(key, "system-ds", "azureblob", MANAGED_IDENTITY_CONNECTION, "docs", null);
        dataSource(key, "user-ds", "azureblob", MANAGED_IDENTITY_CONNECTION, "docs", USER_ASSIGNED);
        dataSource(key, "key-ds", "azureblob", BLOB_CONNECTION, "docs", null);
        dataSource(key, "table-ds", "azuretable", MANAGED_IDENTITY_CONNECTION, "rows", null);

        assertRead(firstRun(key, "system-indexer", "system-ds", null));
        assertRefused(firstRun(key, "user-indexer", "user-ds", null), "connect as a trusted service: a ResourceId "
            + "connection string read with the search service's system-assigned identity");
        assertRefused(firstRun(key, "key-indexer", "key-ds", null), "connect as a trusted service");
        String table = assertRefused(firstRun(key, "table-indexer", "table-ds", null), "group 'table'");
        assertThat(table, not(containsString("trusted service")));
    }

    @Test
    void theTrustedServiceExceptionFollowsTheBypassEvenWithPublicNetworkAccessDisabled() {
        storageAccount("{\"networkAcls\":{\"defaultAction\":\"Deny\",\"bypass\":\"Logging, Metrics\"}}");
        dataSource(key, "system-ds", "azureblob", MANAGED_IDENTITY_CONNECTION, "docs", null);
        assertRefused(firstRun(key, "system-indexer", "system-ds", null), "add AzureServices to the account's "
            + "networkAcls.bypass, so the search service connects as a trusted service");

        // bypass defaults to AzureServices, and disabling public network access leaves the exception in effect.
        storageAccount("{\"publicNetworkAccess\":\"Disabled\"}");
        assertRead(runAndAwait(SERVICE, "system-indexer", key));

        // floci-az models no perimeter: the account is as in an enforced one, which honors no trusted service.
        storageAccount("{\"publicNetworkAccess\":\"SecuredByPerimeter\"}");
        String perimeter = assertRefused(runAndAwait(SERVICE, "system-indexer", key),
            "its publicNetworkAccess is SecuredByPerimeter");
        assertThat(perimeter, not(containsString("trusted service")));
    }

    @Test
    void aSourceFlociAzHoldsNoNetworkRulesForIsReachable() {
        dataSource(key, "docs-ds", "azureblob", BLOB_CONNECTION, "docs", null);
        assertRead(firstRun(key, "blob-indexer", "docs-ds", PRIVATE));

        createCosmosContainer("items");
        upsertCosmos("items", "{\"id\": \"c1\", \"pk\": \"a\", \"content\": \"gamma\"}");
        dataSource(key, "cosmos-ds", "cosmosdb", COSMOS_CONNECTION, "items", null);
        // Closes the storage account of the same name, which a Cosmos DB source does not read.
        storageAccount(DISABLED);
        assertRead(firstRun(key, "cosmos-indexer", "cosmos-ds", PRIVATE));
    }
}
