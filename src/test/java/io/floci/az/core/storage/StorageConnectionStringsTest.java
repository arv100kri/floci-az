package io.floci.az.core.storage;

import io.floci.az.core.storage.StorageConnectionStrings.Credential;
import io.floci.az.core.storage.StorageConnectionStrings.Resolution;
import io.floci.az.core.storage.StorageConnectionStrings.Resolved;
import io.floci.az.core.storage.StorageConnectionStrings.Service;
import io.floci.az.core.storage.StorageConnectionStrings.Unresolved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageConnectionStringsTest {

    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    static Stream<Arguments> accountsNamedByTheConnectionString() {
        return Stream.of(
                Arguments.of("DefaultEndpointsProtocol=https;AccountName=myacct;AccountKey=" + KEY
                        + ";EndpointSuffix=core.windows.net", Service.BLOB, "myacct"),
                Arguments.of("defaultendpointsprotocol=https;accountname=myacct;accountkey=" + KEY, Service.TABLE,
                        "myacct"),
                Arguments.of("DefaultEndpointsProtocol=http;AccountName=ignored;AccountKey=" + KEY
                        + ";BlobEndpoint=http://localhost:4577/devstoreaccount1;", Service.BLOB, "devstoreaccount1"),
                Arguments.of("DefaultEndpointsProtocol=http;AccountName=ignored;AccountKey=" + KEY
                        + ";TableEndpoint=http://127.0.0.1:4577/tablesacct-table/;", Service.TABLE, "tablesacct"),
                Arguments.of("AccountName=myacct;AccountKey=" + KEY + ";BlobEndpoint=http://localhost:4577/other",
                        Service.TABLE, "myacct"),
                Arguments.of("BlobEndpoint=https://MyAcct.blob.core.windows.net/;SharedAccessSignature=sv=1",
                        Service.BLOB, "myacct"),
                Arguments.of("BlobEndpoint=https://myacct.dfs.core.windows.net", Service.BLOB, "myacct"),
                Arguments.of("TableEndpoint=https://myacct.table.core.windows.net/", Service.TABLE, "myacct"),
                Arguments.of("BlobEndpoint=https://myacct.blob.core.chinacloudapi.cn/", Service.BLOB, "myacct"),
                Arguments.of("BlobEndpoint=http://devstoreaccount1.blob.localhost:4577/", Service.BLOB,
                        "devstoreaccount1"),
                Arguments.of("BlobEndpoint=http://floci_az:4577/devstoreaccount1", Service.BLOB, "devstoreaccount1"),
                Arguments.of("BlobEndpoint=https://myacct.blob.core.windows.net/;SharedAccessSignature=?sv=1",
                        Service.TABLE, "myacct"));
    }

    @ParameterizedTest
    @MethodSource("accountsNamedByTheConnectionString")
    void resolvesTheAccountTheConnectionStringNames(String connectionString, Service service, String account) {
        assertEquals(account, resolved(connectionString, service).accountName());
    }

    @Test
    void reportsTheAccountKeyForm() {
        assertEquals(new Resolved("myacct", Credential.ACCOUNT_KEY, null, null),
                resolved("DefaultEndpointsProtocol=https;AccountName=myacct;AccountKey=" + KEY, Service.BLOB));
    }

    @Test
    void namesTheDevelopmentAccountForDevelopmentStorage() {
        assertEquals(new Resolved("devstoreaccount1", Credential.DEVELOPMENT_STORAGE, null, null),
                resolved("UseDevelopmentStorage=true", Service.TABLE));
        assertThat(unresolved("UseDevelopmentStorage=false", Service.BLOB),
                containsString("UseDevelopmentStorage=true"));
    }

    @Test
    void readsTheManagedIdentityResourceId() {
        assertEquals(new Resolved("mystorage", Credential.MANAGED_IDENTITY, null, null), resolved(
                "ResourceId=/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/providers/"
                        + "Microsoft.Storage/storageAccounts/mystorage/;", Service.BLOB));
        assertThat(unresolved("ResourceId=/subscriptions/s/resourceGroups/rg/providers/Microsoft.DocumentDB/"
                + "databaseAccounts/cosmos;", Service.BLOB), containsString("does not name a storage account"));
    }

    @Test
    void keepsTheSharedAccessSignatureWithoutItsQuestionMark() {
        assertEquals(new Resolved("myacct", Credential.SHARED_ACCESS_SIGNATURE, null, "sv=2016-05-31&sig=abc&sp=rl"),
                resolved("BlobEndpoint=https://myacct.blob.core.windows.net/;"
                        + "SharedAccessSignature=?sv=2016-05-31&sig=abc&sp=rl;", Service.BLOB));
    }

    @Test
    void readsTheContainerOfAContainerSharedAccessUri() {
        assertEquals(new Resolved("myacct", Credential.SHARED_ACCESS_SIGNATURE, "my-container", "sv=1&sr=c&sig=x"),
                resolved("ContainerSharedAccessUri=https://myacct.blob.core.windows.net/my-container"
                        + "?sv=1&sr=c&sig=x;", Service.BLOB));
        assertEquals(new Resolved("devstoreaccount1", Credential.SHARED_ACCESS_SIGNATURE, "people", "tn=people&sig=x"),
                resolved("ContainerSharedAccessUri=http://localhost:4577/devstoreaccount1-table/people?tn=people&sig=x",
                        Service.TABLE));
        assertThat(unresolved("ContainerSharedAccessUri=https://myacct.blob.core.windows.net/?sv=1", Service.BLOB),
                containsString("names no container"));
        assertThat(unresolved("ContainerSharedAccessUri=my-container?sv=1", Service.BLOB),
                containsString("not an http(s) URL"));
    }

    @Test
    void refusesEndpointsOutsideFlociAzStorage() {
        assertThat(unresolved("TableEndpoint=http://localhost:4577/devstoreaccount1-cosmos-table/", Service.TABLE),
                containsString("Cosmos DB for Table"));
        assertThat(unresolved("TableEndpoint=http://127.0.0.1:10002/devstoreaccount1", Service.TABLE),
                containsString("{account}-table"));
        assertThat(unresolved("BlobEndpoint=http://localhost:4577/devstoreaccount1-queue", Service.BLOB),
                containsString("Queue service"));
        assertThat(unresolved("BlobEndpoint=http://localhost:4577/devstoreaccount1-cosmos/", Service.BLOB),
                containsString("Cosmos DB endpoint"));
        assertThat(unresolved("BlobEndpoint=https://myacct.table.cosmos.azure.com/", Service.BLOB),
                containsString("Cosmos DB endpoint"));
        assertThat(unresolved("BlobEndpoint=https://myvault.vault.azure.net/secrets", Service.BLOB),
                containsString("not an Azure Storage endpoint"));
        assertThat(unresolved("BlobEndpoint=http://localhost:4577/", Service.BLOB), containsString("names no storage"));
        assertThat(unresolved("BlobEndpoint=ftp://myacct.blob.core.windows.net/", Service.BLOB),
                containsString("not an http(s) URL"));
    }

    @Test
    void refusesStringsThatNameNoAccount() {
        assertThat(unresolved(null, Service.BLOB), containsString("empty"));
        assertThat(unresolved("AccountName", Service.BLOB), containsString("not a key=value pair"));
        assertThat(unresolved("DefaultEndpointsProtocol=https;AccountKey=" + KEY, Service.BLOB),
                containsString("names no account"));
    }

    private static Resolved resolved(String connectionString, Service service) {
        Resolution resolution = StorageConnectionStrings.resolve(connectionString, service);
        assertThat(resolution, instanceOf(Resolved.class));
        return (Resolved) resolution;
    }

    private static String unresolved(String connectionString, Service service) {
        Resolution resolution = StorageConnectionStrings.resolve(connectionString, service);
        assertThat(resolution, instanceOf(Unresolved.class));
        return ((Unresolved) resolution).reason();
    }
}
