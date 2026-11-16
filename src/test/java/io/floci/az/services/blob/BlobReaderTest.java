package io.floci.az.services.blob;

import io.floci.az.services.blob.BlobReader.BlobContent;
import io.floci.az.services.blob.BlobReader.BlobEntry;
import io.floci.az.services.blob.BlobReader.BlobListing;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BlobReaderTest {

    /** Not in {@code hierarchical-namespace-accounts}: a flat Blob Storage account. */
    private static final String FLAT_ACCOUNT = "readeracct";
    /** In {@code hierarchical-namespace-accounts} by default: an ADLS Gen2 account. */
    private static final String HNS_ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "docs";

    @Inject
    BlobReader reader;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void listsBlobsWrittenThroughRestWithTheirProperties() {
        createContainer(FLAT_ACCOUNT, CONTAINER);
        Response first = given()
                .header("x-ms-blob-type", "BlockBlob")
                .header("x-ms-blob-content-type", "application/json")
                .header("x-ms-blob-content-md5", "u2y1xo30ZSlByvZSo2by2A==")
                .header("x-ms-meta-AzureSearch_Skip", "true")
                .header("x-ms-meta-category", "guides")
                .body("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
                .put("/{account}/{container}/{blob}", FLAT_ACCOUNT, CONTAINER, "folder/b.json");
        first.then().statusCode(201);
        putBlob(FLAT_ACCOUNT, CONTAINER, "a.txt", "alpha");

        BlobListing listing = reader.list(FLAT_ACCOUNT, CONTAINER, null).orElseThrow();

        assertFalse(listing.hierarchicalNamespace());
        assertThat(listing.entries().stream().map(BlobEntry::name).toList(), contains("a.txt", "folder/b.json"));
        BlobEntry json = listing.entries().get(1);
        assertFalse(json.directory());
        assertEquals(7, json.contentLength());
        assertEquals("application/json", json.contentType());
        assertEquals("u2y1xo30ZSlByvZSo2by2A==", json.contentMd5());
        assertEquals(first.header("ETag"), json.etag());
        assertEquals(httpDate(first.header("Last-Modified")), json.lastModified().truncatedTo(ChronoUnit.SECONDS));
        assertEquals("true", json.metadata().get("AzureSearch_Skip"));
        assertEquals("guides", json.metadata().get("Category"));
        BlobEntry text = listing.entries().get(0);
        assertNull(text.contentMd5(), "no MD5 is stored unless the client sends one");
        assertEquals(Map.of(), text.metadata());
    }

    @Test
    void listsOnlyNamesUnderThePrefix() {
        createContainer(FLAT_ACCOUNT, CONTAINER);
        putBlob(FLAT_ACCOUNT, CONTAINER, "folder/one.txt", "1");
        putBlob(FLAT_ACCOUNT, CONTAINER, "folder/sub/two.txt", "2");
        putBlob(FLAT_ACCOUNT, CONTAINER, "folder-sibling.txt", "3");
        putBlob(FLAT_ACCOUNT, CONTAINER, "other.txt", "4");

        List<String> names = reader.list(FLAT_ACCOUNT, CONTAINER, "folder/").orElseThrow().entries().stream()
                .map(BlobEntry::name)
                .toList();

        assertThat(names, contains("folder/one.txt", "folder/sub/two.txt"));
    }

    @Test
    void reportsAMissingContainerAsEmpty() {
        assertFalse(reader.containerExists(FLAT_ACCOUNT, CONTAINER));
        assertEquals(Optional.empty(), reader.list(FLAT_ACCOUNT, CONTAINER, ""));
        createContainer(FLAT_ACCOUNT, CONTAINER);
        assertTrue(reader.containerExists(FLAT_ACCOUNT, CONTAINER));
        assertEquals(List.of(), reader.list(FLAT_ACCOUNT, CONTAINER, "").orElseThrow().entries());
        assertEquals(Optional.empty(), reader.read(FLAT_ACCOUNT, CONTAINER, "missing.txt"));
    }

    @Test
    void lastModifiedAdvancesOnlyForTheBlobThatChanged() {
        createContainer(FLAT_ACCOUNT, CONTAINER);
        putBlob(FLAT_ACCOUNT, CONTAINER, "changed.txt", "v1");
        putBlob(FLAT_ACCOUNT, CONTAINER, "untouched.txt", "v1");
        BlobEntry changedBefore = reader.properties(FLAT_ACCOUNT, CONTAINER, "changed.txt").orElseThrow();
        BlobEntry untouchedBefore = reader.properties(FLAT_ACCOUNT, CONTAINER, "untouched.txt").orElseThrow();

        given().header("x-ms-meta-reviewed", "yes")
                .put("/{account}/{container}/{blob}?comp=metadata", FLAT_ACCOUNT, CONTAINER, "changed.txt")
                .then().statusCode(200);

        BlobEntry changedAfter = reader.properties(FLAT_ACCOUNT, CONTAINER, "changed.txt").orElseThrow();
        assertThat(changedAfter.lastModified(), greaterThan(changedBefore.lastModified()));
        assertFalse(changedAfter.etag().equals(changedBefore.etag()));
        assertEquals("yes", changedAfter.metadata().get("reviewed"));
        assertEquals(untouchedBefore, reader.properties(FLAT_ACCOUNT, CONTAINER, "untouched.txt").orElseThrow());
    }

    @Test
    void readsContentAsACopyTheCallerMayChange() {
        createContainer(FLAT_ACCOUNT, CONTAINER);
        putBlob(FLAT_ACCOUNT, CONTAINER, "data.bin", "payload");

        BlobContent content = reader.read(FLAT_ACCOUNT, CONTAINER, "data.bin").orElseThrow();
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), content.content());
        assertEquals(7, content.properties().contentLength());
        content.content()[0] = 'X';

        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8),
                reader.read(FLAT_ACCOUNT, CONTAINER, "data.bin").orElseThrow().content());
        given().get("/{account}/{container}/{blob}", FLAT_ACCOUNT, CONTAINER, "data.bin")
                .then().statusCode(200).body(equalTo("payload"));
    }

    @Test
    void listsDataLakePathsOfAHierarchicalNamespaceAccount() {
        createContainer(HNS_ACCOUNT, CONTAINER);
        dfs().put("/{filesystem}/guides?resource=directory", CONTAINER).then().statusCode(201);
        dfs().put("/{filesystem}/guides/a.txt?resource=file", CONTAINER).then().statusCode(201);
        dfs().body("hello").patch("/{filesystem}/guides/a.txt?action=append&position=0", CONTAINER)
                .then().statusCode(202);
        dfs().patch("/{filesystem}/guides/a.txt?action=flush&position=5", CONTAINER).then().statusCode(200);
        dfs().put("/{filesystem}/guides/pending.txt?resource=file", CONTAINER).then().statusCode(201);
        dfs().body("unflushed").patch("/{filesystem}/guides/pending.txt?action=append&position=0", CONTAINER)
                .then().statusCode(202);
        putBlob(HNS_ACCOUNT, CONTAINER, "top.txt", "blob api");

        BlobListing listing = reader.list(HNS_ACCOUNT, CONTAINER, "").orElseThrow();

        assertTrue(listing.hierarchicalNamespace());
        assertThat(listing.entries().stream().map(BlobEntry::name).toList(),
                contains("guides", "guides/a.txt", "guides/pending.txt", "top.txt"));
        BlobEntry directory = listing.entries().get(0);
        assertTrue(directory.directory());
        assertEquals(0, directory.contentLength());
        assertEquals("true", directory.metadata().get("hdi_isfolder"));
        assertFalse(listing.entries().get(1).directory());
        assertEquals(5, listing.entries().get(1).contentLength());
        assertEquals(0, listing.entries().get(2).contentLength(), "appended data is invisible until flushed");
        assertFalse(listing.entries().get(3).directory());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                reader.read(HNS_ACCOUNT, CONTAINER, "guides/a.txt").orElseThrow().content());
        assertTrue(reader.properties(HNS_ACCOUNT, CONTAINER, "guides").orElseThrow().directory());
    }

    private static RequestSpecification dfs() {
        return given().header("Host", HNS_ACCOUNT + ".dfs.core.windows.net");
    }

    private static void createContainer(String account, String container) {
        given().put("/{account}/{container}?restype=container", account, container).then().statusCode(201);
    }

    private static void putBlob(String account, String container, String blob, String content) {
        given().header("x-ms-blob-type", "BlockBlob")
                .body(content.getBytes(StandardCharsets.UTF_8))
                .put("/{account}/{container}/{blob}", account, container, blob)
                .then().statusCode(201);
    }

    private static Instant httpDate(String value) {
        return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }
}
