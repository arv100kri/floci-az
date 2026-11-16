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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import static io.floci.az.services.search.indexer.IndexerTestSupport.lookup;
import static io.floci.az.services.search.indexer.IndexerTestSupport.putBlob;
import static io.floci.az.services.search.indexer.IndexerTestSupport.runAndAwait;
import static io.floci.az.services.search.indexer.IndexerTestSupport.searchAll;
import static io.floci.az.services.search.indexer.IndexerTestSupport.status;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Search indexers over floci-az Blob Storage")
class SearchBlobIndexerTest {

    private static final String SERVICE = "blobidx";
    private static final String CONTAINER = "docs";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createContainer(CONTAINER);
    }

    private static void dataSource(String name, String query) {
        createDataSource(SERVICE, "{\"name\":\"" + name + "\",\"type\":\"azureblob\",\"credentials\":"
            + "{\"connectionString\":\"" + BLOB_CONNECTION + "\"},\"container\":{\"name\":\"" + CONTAINER + "\""
            + (query == null ? "" : ",\"query\":\"" + query + "\"") + "}}");
    }

    private static String article(String text, String date, String tags) {
        return "{\"article\":{\"text\":\"" + text + "\",\"datePublished\":\"" + date + "\",\"tags\":[" + tags + "]}}";
    }

    private static String decodeKey(String key) {
        String encoded = key.substring(0, key.length() - 1).replace('-', '+').replace('_', '/')
            + "=".repeat(key.charAt(key.length() - 1) - '0');
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void jsonBlobsAreIndexedThroughFieldMappingsWithEncodedKeys() {
        putBlob(CONTAINER, "json/a.json", article("Lighthouse restoration finished", "2016-04-13",
            "\"coast\",\"history\""), Map.of());
        putBlob(CONTAINER, "json/b.json", article("Harbor festival schedule", "2017-06-01", "\"events\""), Map.of());
        putBlob(CONTAINER, "notes/ignored.txt", "outside the json folder", Map.of());
        createIndex(SERVICE, """
            {"name": "articles", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "text", "type": "Edm.String", "searchable": true},
              {"name": "date", "type": "Edm.DateTimeOffset", "filterable": true},
              {"name": "tags", "type": "Collection(Edm.String)", "filterable": true},
              {"name": "fileName", "type": "Edm.String", "filterable": true}]}
            """);
        dataSource("articles-ds", "json");
        createIndexer(SERVICE, """
            {"name": "articles-indexer", "dataSourceName": "articles-ds", "targetIndexName": "articles",
             "parameters": {"configuration": {"parsingMode": "json"}},
             "fieldMappings": [
               {"sourceFieldName": "metadata_storage_path", "targetFieldName": "id",
                "mappingFunction": {"name": "base64Encode"}},
               {"sourceFieldName": "metadata_storage_name", "targetFieldName": "fileName"},
               {"sourceFieldName": "/article/text", "targetFieldName": "text"},
               {"sourceFieldName": "/article/datePublished", "targetFieldName": "date"},
               {"sourceFieldName": "/article/tags", "targetFieldName": "tags"}]}
            """);

        JsonPath run = awaitRun(SERVICE, "articles-indexer", 0);
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(2, run.getInt("itemsProcessed"));
        assertEquals(0, run.getInt("itemsFailed"));
        assertEquals("running", status(SERVICE, "articles-indexer").getString("status"));

        Map<String, Map<String, Object>> byFile = searchAll(SERVICE, "articles").stream()
            .collect(Collectors.toMap(document -> (String) document.get("fileName"), document -> document));
        assertEquals(Set.of("a.json", "b.json"), byFile.keySet());
        Map<String, Object> first = byFile.get("a.json");
        assertEquals("Lighthouse restoration finished", first.get("text"));
        assertEquals("2016-04-13T00:00:00Z", first.get("date"));
        assertEquals(List.of("coast", "history"), first.get("tags"));
        String key = (String) first.get("id");
        assertThat(decodeKey(key), endsWith("/idxacct/docs/json/a.json"));
        assertEquals("Lighthouse restoration finished", lookup(SERVICE, "articles", key).get("text"));
    }

    @Test
    void defaultParsingExtractsTextStripsHtmlAndReportsUnsupportedContent() {
        putBlob(CONTAINER, "c.txt", "The lighthouse keeper wrote in the log every night.", Map.of("category",
            "logs"));
        putBlob(CONTAINER, "page.html", "<html><head><title>Harbor &amp; Coast</title><script>var x = 1;</script>"
            + "</head><body><h1>Tide tables</h1><p>Posted <b>daily</b>.</p></body></html>", Map.of());
        putBlob(CONTAINER, "skipped.txt", "never indexed", Map.of("AzureSearch_Skip", "true"));
        putBlob(CONTAINER, "report.pdf", "%PDF-1.7 binary".getBytes(StandardCharsets.UTF_8), "application/pdf",
            Map.of());
        createIndex(SERVICE, """
            {"name": "files", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "content", "type": "Edm.String", "searchable": true},
              {"name": "metadata_storage_name", "type": "Edm.String", "filterable": true},
              {"name": "metadata_storage_size", "type": "Edm.Int64"},
              {"name": "metadata_storage_last_modified", "type": "Edm.DateTimeOffset"},
              {"name": "metadata_content_type", "type": "Edm.String"},
              {"name": "metadata_title", "type": "Edm.String"},
              {"name": "Category", "type": "Edm.String"}]}
            """);
        dataSource("files-ds", null);
        createIndexer(SERVICE, "{\"name\": \"files-indexer\", \"dataSourceName\": \"files-ds\", "
            + "\"targetIndexName\": \"files\"}");

        JsonPath run = awaitRun(SERVICE, "files-indexer", 0);
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(3, run.getInt("itemsProcessed"), "the skipped blob is not processed");
        assertThat(run.getString("warnings[0].message"), containsString("unsupported content type 'application/pdf'"));
        assertThat(run.getString("warnings[0].key"), endsWith("/report.pdf"));

        Map<String, Map<String, Object>> byName = searchAll(SERVICE, "files").stream()
            .collect(Collectors.toMap(document -> (String) document.get("metadata_storage_name"),
                document -> document));
        assertEquals(Set.of("c.txt", "page.html", "report.pdf"), byName.keySet());
        assertEquals("The lighthouse keeper wrote in the log every night.", byName.get("c.txt").get("content"));
        assertEquals("logs", byName.get("c.txt").get("Category"), "user metadata maps by name, ignoring case");
        assertEquals(51, ((Number) byName.get("c.txt").get("metadata_storage_size")).intValue());
        assertEquals("text/plain", byName.get("c.txt").get("metadata_content_type"));
        assertEquals("Tide tables\nPosted daily.", byName.get("page.html").get("content"));
        assertEquals("Harbor & Coast", byName.get("page.html").get("metadata_title"));
        assertNull(byName.get("report.pdf").get("content"), "only the metadata of a PDF is indexed");
        assertThat(decodeKey((String) byName.get("c.txt").get("id")), endsWith("/idxacct/docs/c.txt"));
    }

    @Test
    void jsonArraysJsonLinesAndCsvGiveOneDocumentPerElement() {
        putBlob(CONTAINER, "array/levels.json", """
            {"level1": {"level2": [
              {"id": "1", "text": "Use the documentRoot property"},
              {"id": "2", "text": "to pluck the array you want to index"}]}}
            """, Map.of());
        putBlob(CONTAINER, "lines/readings.jsonl", "{\"text\": \"first reading\"}\n\n{\"text\": \"second reading\"}\n",
            Map.of());
        putBlob(CONTAINER, "csv/tags.csv", """
            id, datePublished, tags
            1, 2016-01-12, "azure-search,azure,cloud"
            2, 2020-01-05, "tags,with,""quoted text\"""
            """, Map.of());
        createIndex(SERVICE, """
            {"name": "parts", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "text", "type": "Edm.String", "searchable": true},
              {"name": "datePublished", "type": "Edm.DateTimeOffset"},
              {"name": "tags", "type": "Edm.String"}]}
            """);
        dataSource("array-ds", "array");
        dataSource("lines-ds", "lines");
        dataSource("csv-ds", "csv");
        createIndexer(SERVICE, "{\"name\": \"array-indexer\", \"dataSourceName\": \"array-ds\", \"targetIndexName\": "
            + "\"parts\", \"parameters\": {\"configuration\": {\"parsingMode\": \"jsonArray\", \"documentRoot\": "
            + "\"/level1/level2\"}}}");
        assertEquals(2, awaitRun(SERVICE, "array-indexer", 0).getInt("itemsProcessed"));
        assertEquals("to pluck the array you want to index", lookup(SERVICE, "parts", "2").get("text"));

        createIndexer(SERVICE, "{\"name\": \"lines-indexer\", \"dataSourceName\": \"lines-ds\", \"targetIndexName\": "
            + "\"parts\", \"parameters\": {\"configuration\": {\"parsingMode\": \"jsonLines\"}}}");
        assertEquals(2, awaitRun(SERVICE, "lines-indexer", 0).getInt("itemsProcessed"));
        Map<String, String> lineKeys = searchAll(SERVICE, "parts").stream()
            .filter(document -> ((String) document.get("text")).endsWith("reading"))
            .collect(Collectors.toMap(document -> (String) document.get("text"),
                document -> decodeKey((String) document.get("id"))));
        assertThat(lineKeys.get("first reading"), endsWith("/lines/readings.jsonl;1"));
        assertThat(lineKeys.get("second reading"), endsWith("/lines/readings.jsonl;2"));

        admin().body("{\"name\": \"csv-indexer\", \"dataSourceName\": \"csv-ds\", \"targetIndexName\": \"parts\", "
                + "\"parameters\": {\"configuration\": {\"parsingMode\": \"delimitedText\", "
                + "\"firstLineContainsHeaders\": true}}, \"fieldMappings\": [{\"sourceFieldName\": \"id\", "
                + "\"targetFieldName\": \"text\"}]}")
            .post(base(SERVICE) + "/indexers" + API).then().statusCode(201);
        JsonPath csvRun = awaitRun(SERVICE, "csv-indexer", 0);
        assertEquals("success", csvRun.getString("status"), csvRun.prettify());
        assertEquals(2, csvRun.getInt("itemsProcessed"));
        Map<String, Map<String, Object>> rows = searchAll(SERVICE, "parts").stream()
            .filter(document -> document.get("tags") != null)
            .collect(Collectors.toMap(document -> (String) document.get("text"), document -> document));
        assertEquals("azure-search,azure,cloud", rows.get("1").get("tags"));
        assertEquals("tags,with,\"quoted text\"", rows.get("2").get("tags"));
        assertEquals("2016-01-12T00:00:00Z", rows.get("1").get("datePublished"));
    }

    @Test
    void markdownIsParsedOneToManyAndOneToOne() {
        String markdown = "# Section 1\nContent for section 1.\n\n## Subsection 1.1\nContent for subsection 1.1.\n\n"
            + "# Section 2\nContent for section 2.\n";
        putBlob(CONTAINER, "md/sample.md", markdown, Map.of());
        createIndex(SERVICE, """
            {"name": "sections", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "content", "type": "Edm.String", "searchable": true},
              {"name": "ordinal_position", "type": "Edm.Int32"},
              {"name": "sections", "type": "Edm.ComplexType", "fields": [
                {"name": "h1", "type": "Edm.String"}, {"name": "h2", "type": "Edm.String"}]}]}
            """);
        createIndex(SERVICE, """
            {"name": "documents", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "document_content", "type": "Edm.String"},
              {"name": "document_title", "type": "Edm.String"},
              {"name": "sections", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "header_level", "type": "Edm.String"},
                {"name": "header_name", "type": "Edm.String"},
                {"name": "content", "type": "Edm.String"},
                {"name": "ordinal_position", "type": "Edm.Int32"},
                {"name": "sections", "type": "Collection(Edm.ComplexType)", "fields": [
                  {"name": "header_level", "type": "Edm.String"},
                  {"name": "header_name", "type": "Edm.String"},
                  {"name": "content", "type": "Edm.String"},
                  {"name": "ordinal_position", "type": "Edm.Int32"}]}]}]}
            """);
        dataSource("md-ds", "md");
        createIndexer(SERVICE, "{\"name\": \"many-indexer\", \"dataSourceName\": \"md-ds\", \"targetIndexName\": "
            + "\"sections\", \"parameters\": {\"configuration\": {\"parsingMode\": \"markdown\", "
            + "\"markdownHeaderDepth\": \"h2\"}}}");
        JsonPath many = awaitRun(SERVICE, "many-indexer", 0);
        assertEquals("success", many.getString("status"), many.prettify());
        Map<Integer, Map<String, Object>> byOrdinal = searchAll(SERVICE, "sections").stream()
            .collect(Collectors.toMap(document -> (Integer) document.get("ordinal_position"), document -> document));
        assertEquals(3, byOrdinal.size());
        assertEquals("Content for subsection 1.1.\r\n", byOrdinal.get(2).get("content"));
        assertEquals(Map.of("h1", "Section 1", "h2", "Subsection 1.1"), byOrdinal.get(2).get("sections"));
        assertEquals(Map.of("h1", "Section 2", "h2", ""), byOrdinal.get(3).get("sections"));

        createIndexer(SERVICE, """
            {"name": "one-indexer", "dataSourceName": "md-ds", "targetIndexName": "documents",
             "parameters": {"configuration": {"parsingMode": "markdown", "markdownParsingSubmode": "oneToOne"}},
             "fieldMappings": [{"sourceFieldName": "/sections/0/header_name", "targetFieldName": "document_title"}]}
            """);
        JsonPath one = awaitRun(SERVICE, "one-indexer", 0);
        assertEquals("success", one.getString("status"), one.prettify());
        Map<String, Object> document = searchAll(SERVICE, "documents").getFirst();
        assertEquals("Section 1", document.get("document_title"));
        assertEquals("# Section 1\r\nContent for section 1.\r\n## Subsection 1.1\r\nContent for subsection 1.1.\r\n"
            + "# Section 2\r\nContent for section 2.\r\n", document.get("document_content"));
        List<Map<String, Object>> sections = castList(document.get("sections"));
        assertEquals(2, sections.size());
        assertEquals("Content for section 1.", sections.getFirst().get("content"));
        assertEquals("Subsection 1.1", castList(sections.getFirst().get("sections")).getFirst().get("header_name"));
        assertEquals(3, sections.get(1).get("ordinal_position"));
    }

    @Test
    void rerunsIndexOnlyChangedBlobsUntilAResetReindexesEverything() {
        putBlob(CONTAINER, "a.txt", "alpha", Map.of());
        putBlob(CONTAINER, "b.txt", "bravo", Map.of());
        createIndex(SERVICE, "{\"name\": \"letters\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\", \"searchable\": true}]}");
        dataSource("letters-ds", null);
        createIndexer(SERVICE, "{\"name\": \"letters-indexer\", \"dataSourceName\": \"letters-ds\", "
            + "\"targetIndexName\": \"letters\"}");
        assertEquals(2, awaitRun(SERVICE, "letters-indexer", 0).getInt("itemsProcessed"));

        JsonPath unchanged = runAndAwait(SERVICE, "letters-indexer");
        assertEquals("success", unchanged.getString("status"));
        assertEquals(0, unchanged.getInt("itemsProcessed"), "nothing changed since the last run");

        putBlob(CONTAINER, "b.txt", "bravo revised", Map.of());
        putBlob(CONTAINER, "c.txt", "charlie", Map.of());
        JsonPath changed = runAndAwait(SERVICE, "letters-indexer");
        assertEquals(2, changed.getInt("itemsProcessed"), "the overwritten and the new blob");
        assertThat(changed.getString("initialTrackingState"), containsString("highWaterMark"));

        admin().post(base(SERVICE) + "/indexers('letters-indexer')/search.reset" + API).then().statusCode(204);
        JsonPath afterReset = status(SERVICE, "letters-indexer");
        assertEquals("reset", afterReset.getString("lastResult.status"));
        assertEquals("reset", afterReset.getString("executionHistory[0].status"));
        assertEquals(4, afterReset.getList("executionHistory").size());

        JsonPath full = runAndAwait(SERVICE, "letters-indexer");
        assertEquals(3, full.getInt("itemsProcessed"), "a reset reprocesses every blob");
        JsonPath history = status(SERVICE, "letters-indexer");
        assertEquals(List.of("success", "reset", "success", "success", "success"),
            history.getList("executionHistory.status"));
        assertEquals(List.of(3, 0, 2, 0, 2), history.getList("executionHistory.itemsProcessed"));
        assertEquals("bravo revised", searchAll(SERVICE, "letters").stream()
            .filter(document -> ((String) document.get("content")).startsWith("bravo")).findFirst().orElseThrow()
            .get("content"));
    }

    @Test
    void aSoftDeleteMetadataPropertyDeletesTheDocument() {
        putBlob(CONTAINER, "keep.txt", "kept", Map.of("IsDeleted", "false"));
        putBlob(CONTAINER, "drop.txt", "dropped", Map.of("IsDeleted", "false"));
        createIndex(SERVICE, "{\"name\": \"soft\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\", \"searchable\": true}]}");
        createDataSource(SERVICE, "{\"name\": \"soft-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"" + CONTAINER + "\"}, "
            + "\"dataDeletionDetectionPolicy\": {\"@odata.type\": "
            + "\"#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy\", \"softDeleteColumnName\": "
            + "\"IsDeleted\", \"softDeleteMarkerValue\": \"true\"}}");
        createIndexer(SERVICE, "{\"name\": \"soft-indexer\", \"dataSourceName\": \"soft-ds\", "
            + "\"targetIndexName\": \"soft\"}");
        awaitRun(SERVICE, "soft-indexer", 0);
        assertEquals(2, searchAll(SERVICE, "soft").size());

        given().header("x-ms-meta-IsDeleted", "true").put("/{account}/{container}/{blob}?comp=metadata",
            IndexerTestSupport.ACCOUNT, CONTAINER, "drop.txt").then().statusCode(200);
        JsonPath run = runAndAwait(SERVICE, "soft-indexer");
        assertEquals("success", run.getString("status"), run.prettify());
        assertEquals(1, run.getInt("itemsProcessed"));
        assertEquals(List.of("kept"), searchAll(SERVICE, "soft").stream().map(document -> document.get("content"))
            .toList());
    }

    @Test
    void indexProjectionsFollowTheirParentsContentAndDeletion() {
        putBlob(CONTAINER, "a.txt", "The keeper lit the lamp. The ship saw the light. The crew cheered.",
            Map.of("IsDeleted", "false"));
        putBlob(CONTAINER, "b.txt", "Gulls circled the pier. Fog rolled in.", Map.of("IsDeleted", "false"));
        createIndex(SERVICE, """
            {"name": "chunks", "fields": [
              {"name": "chunk_id", "type": "Edm.String", "key": true, "analyzer": "keyword"},
              {"name": "parent_id", "type": "Edm.String", "filterable": true},
              {"name": "chunk", "type": "Edm.String", "searchable": true}]}
            """);
        createIndex(SERVICE, "{\"name\": \"parents\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}]}");
        createDataSource(SERVICE, "{\"name\": \"chunks-ds\", \"type\": \"azureblob\", \"credentials\": "
            + "{\"connectionString\": \"" + BLOB_CONNECTION + "\"}, \"container\": {\"name\": \"" + CONTAINER + "\"}, "
            + "\"dataDeletionDetectionPolicy\": {\"@odata.type\": "
            + "\"#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy\", \"softDeleteColumnName\": "
            + "\"IsDeleted\", \"softDeleteMarkerValue\": \"true\"}}");
        admin().body("""
            {"name": "chunker", "skills": [
              {"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "sentences",
               "inputs": [{"name": "text", "source": "/document/content"}],
               "outputs": [{"name": "textItems", "targetName": "pages"}]}],
             "indexProjections": {"selectors": [{"targetIndexName": "chunks", "parentKeyFieldName": "parent_id",
               "sourceContext": "/document/pages/*", "mappings": [{"name": "chunk", "source": "/document/pages/*"}]}]}}
            """).post(base(SERVICE) + "/skillsets" + API).then().statusCode(201);
        createIndexer(SERVICE, "{\"name\": \"chunks-indexer\", \"dataSourceName\": \"chunks-ds\", "
            + "\"targetIndexName\": \"parents\", \"skillsetName\": \"chunker\"}");

        JsonPath first = awaitRun(SERVICE, "chunks-indexer", 0);
        assertEquals("success", first.getString("status"), first.prettify());
        Map<String, Set<String>> initial = chunksByParent();
        assertEquals(2, initial.size());
        String parentA = parentOf(initial, "a.txt");
        String parentB = parentOf(initial, "b.txt");
        assertEquals(Set.of(parentA, parentB), searchAll(SERVICE, "parents").stream()
            .map(parent -> (String) parent.get("id")).collect(Collectors.toSet()),
            "the parent key is the document key");
        assertEquals(3, initial.get(parentA).size(), "one chunk per sentence: " + initial);
        assertEquals(2, initial.get(parentB).size());
        for (String chunk : initial.get(parentA)) {
            assertTrue(chunk.matches("[0-9a-f]{12}_" + Pattern.quote(parentA) + "_pages_[0-2]"), chunk);
        }

        putBlob(CONTAINER, "a.txt", "The lamp went dark.", Map.of("IsDeleted", "false"));
        JsonPath changed = runAndAwait(SERVICE, "chunks-indexer");
        assertEquals("success", changed.getString("status"), changed.prettify());
        Map<String, Set<String>> afterChange = chunksByParent();
        assertEquals(1, afterChange.get(parentA).size(), "the chunks of the old content are deleted: " + afterChange);
        assertFalse(initial.get(parentA).containsAll(afterChange.get(parentA)), "a new hash keys the new chunk");
        assertEquals(initial.get(parentB), afterChange.get(parentB), "another parent's chunks are left alone");
        assertEquals(List.of("The lamp went dark."), searchAll(SERVICE, "chunks").stream()
            .filter(chunk -> parentA.equals(chunk.get("parent_id"))).map(chunk -> chunk.get("chunk")).toList());

        given().header("x-ms-meta-IsDeleted", "true").put("/{account}/{container}/{blob}?comp=metadata",
            IndexerTestSupport.ACCOUNT, CONTAINER, "b.txt").then().statusCode(200);
        JsonPath deleted = runAndAwait(SERVICE, "chunks-indexer");
        assertEquals("success", deleted.getString("status"), deleted.prettify());
        Map<String, Set<String>> afterDelete = chunksByParent();
        assertEquals(Set.of(parentA), afterDelete.keySet(), "a soft-deleted parent takes its chunks with it");
        assertEquals(afterChange.get(parentA), afterDelete.get(parentA));
        assertEquals(List.of(parentA), searchAll(SERVICE, "parents").stream().map(parent -> parent.get("id"))
            .toList());
    }

    /** The keys of the {@code chunks} index, by {@code parent_id}. */
    private static Map<String, Set<String>> chunksByParent() {
        return searchAll(SERVICE, "chunks").stream().collect(Collectors.groupingBy(
            chunk -> (String) chunk.get("parent_id"),
            Collectors.mapping(chunk -> (String) chunk.get("chunk_id"), Collectors.toSet())));
    }

    /** The parent key of the blob {@code name}: its encoded {@code metadata_storage_path}. */
    private static String parentOf(Map<String, Set<String>> chunks, String name) {
        return chunks.keySet().stream().filter(parent -> decodeKey(parent).endsWith("/" + CONTAINER + "/" + name))
            .findFirst().orElseThrow(() -> new AssertionError("no chunks for " + name + ": " + chunks));
    }

    @Test
    void failedItemsStopTheRunUnlessMaxFailedItemsToleratesThem() {
        putBlob(CONTAINER, "good.txt", "fine", Map.of("docid", "good"));
        putBlob(CONTAINER, "bad.txt", "broken key", Map.of("docid", "bad key!"));
        createIndex(SERVICE, "{\"name\": \"strict\", \"fields\": [{\"name\": \"docid\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\", \"searchable\": true}]}");
        dataSource("strict-ds", null);
        createIndexer(SERVICE, "{\"name\": \"strict-indexer\", \"dataSourceName\": \"strict-ds\", "
            + "\"targetIndexName\": \"strict\"}");
        JsonPath failed = awaitRun(SERVICE, "strict-indexer", 0);
        assertEquals("transientFailure", failed.getString("status"), failed.prettify());
        assertEquals(1, failed.getInt("itemsFailed"));
        assertThat(failed.getString("errorMessage"), containsString("maxFailedItems"));
        assertEquals("bad key!", failed.getString("errors[0].key"));
        assertEquals(400, failed.getInt("errors[0].statusCode"));
        assertThat(failed.getString("errors[0].errorMessage"), containsString("Invalid document key"));

        admin().body("{\"name\": \"strict-indexer\", \"dataSourceName\": \"strict-ds\", \"targetIndexName\": "
                + "\"strict\", \"parameters\": {\"maxFailedItems\": -1}}")
            .put(base(SERVICE) + "/indexers('strict-indexer')" + API).then().statusCode(204);
        JsonPath tolerated = awaitRun(SERVICE, "strict-indexer", 1);
        assertEquals("success", tolerated.getString("status"), tolerated.prettify());
        assertEquals(2, tolerated.getInt("itemsProcessed"));
        assertEquals(1, tolerated.getInt("itemsFailed"));
        assertEquals(List.of("good"), searchAll(SERVICE, "strict").stream().map(document -> document.get("docid"))
            .toList());
    }

    @Test
    void unsupportedContentFailsWhenTheIndexerSaysSo() {
        putBlob(CONTAINER, "image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13}, "image/png", Map.of());
        createIndex(SERVICE, "{\"name\": \"images\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}]}");
        dataSource("images-ds", null);
        createIndexer(SERVICE, "{\"name\": \"images-indexer\", \"dataSourceName\": \"images-ds\", "
            + "\"targetIndexName\": \"images\", \"parameters\": {\"configuration\": "
            + "{\"failOnUnsupportedContentType\": true}}}");
        JsonPath run = awaitRun(SERVICE, "images-indexer", 0);
        assertEquals("transientFailure", run.getString("status"), run.prettify());
        assertEquals("Document has unsupported content type 'image/png'.", run.getString("errors[0].errorMessage"));
        assertEquals("DocumentExtraction.azureblob.images-ds", run.getString("errors[0].name"));
        assertTrue(searchAll(SERVICE, "images").isEmpty());
    }

    @Test
    void resetDocumentsReprocessesOnlyTheNamedDocuments() {
        putBlob(CONTAINER, "one.txt", "one", Map.of("docid", "one"));
        putBlob(CONTAINER, "two.txt", "two", Map.of("docid", "two"));
        createIndex(SERVICE, "{\"name\": \"numbers\", \"fields\": [{\"name\": \"docid\", \"type\": \"Edm.String\", "
            + "\"key\": true}, {\"name\": \"content\", \"type\": \"Edm.String\"}]}");
        dataSource("numbers-ds", null);
        createIndexer(SERVICE, "{\"name\": \"numbers-indexer\", \"dataSourceName\": \"numbers-ds\", "
            + "\"targetIndexName\": \"numbers\"}");
        awaitRun(SERVICE, "numbers-indexer", 0);
        putBlob(CONTAINER, "one.txt", "one, revised", Map.of("docid", "one"));
        putBlob(CONTAINER, "two.txt", "two, revised", Map.of("docid", "two"));

        admin().body("{\"documentKeys\": [\"one\", \"no-such-key\"]}")
            .post(base(SERVICE) + "/indexers('numbers-indexer')/search.resetdocs" + PREVIEW_API)
            .then().statusCode(204);
        JsonPath pending = admin().get(base(SERVICE) + "/indexers('numbers-indexer')/search.status" + PREVIEW_API)
            .then().statusCode(200).extract().jsonPath();
        assertEquals("indexingResetDocs", pending.getString("currentState.mode"));
        assertThat(pending.getList("currentState.resetDocumentKeys"), hasItem("one"));

        int before = IndexerTestSupport.historySize(SERVICE, "numbers-indexer");
        admin().post(base(SERVICE) + "/indexers('numbers-indexer')/search.run" + API).then().statusCode(202);
        awaitRun(SERVICE, "numbers-indexer", before);
        JsonPath resetRun = admin().get(base(SERVICE) + "/indexers('numbers-indexer')/search.status" + PREVIEW_API)
            .then().extract().jsonPath();
        assertEquals("indexingResetDocs", resetRun.getString("lastResult.mode"));
        assertEquals("resetDocs", resetRun.getString("lastResult.statusDetail"));
        assertEquals(1, resetRun.getInt("lastResult.itemsProcessed"));
        assertEquals("indexingAllDocs", resetRun.getString("currentState.mode"));
        assertEquals("one, revised", lookup(SERVICE, "numbers", "one").get("content"));
        assertEquals("two", lookup(SERVICE, "numbers", "two").get("content"));

        JsonPath regular = runAndAwait(SERVICE, "numbers-indexer");
        assertEquals(2, regular.getInt("itemsProcessed"), "the high-water mark did not move during the reset run");
        assertEquals("two, revised", lookup(SERVICE, "numbers", "two").get("content"));
        assertFalse(regular.getMap("$").containsKey("mode"), "mode is a preview property");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
