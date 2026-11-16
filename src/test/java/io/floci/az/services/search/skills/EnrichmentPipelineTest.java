package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.indexer.SkillsetRunner.EnrichmentResult;
import io.floci.az.services.search.indexer.SkillsetRunner.ProjectedDocument;
import io.floci.az.services.search.model.SearchJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

class EnrichmentPipelineTest {

    private static final String SENTENCES = "The quick brown fox jumps over the lazy dog near the river bank today. ";

    private final EnrichmentPipeline pipeline = new EnrichmentPipeline(new SkillHttp(), null,
        (service, index) -> index.equals("missing") ? null : "chunk_id");

    private static ObjectNode json(String text) {
        return (ObjectNode) SearchJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ArrayNode array(String text) {
        return (ArrayNode) SearchJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private EnrichmentResult run(String skills, ObjectNode document, String mappings) {
        return pipeline.run("svc", json("{\"skills\": " + skills + "}"), document, array(mappings));
    }

    private EnrichmentResult run(String skills, String document, String mappings) {
        return run(skills, json(document), mappings);
    }

    private static String split(String parameters) {
        return "[{\"@odata.type\": \"#Microsoft.Skills.Text.SplitSkill\", " + parameters
            + " \"inputs\": [{\"name\": \"text\", \"source\": \"/document/content\"},"
            + " {\"name\": \"languageCode\", \"source\": \"/document/language\"}],"
            + " \"outputs\": [{\"name\": \"textItems\", \"targetName\": \"pages\"}, {\"name\": \"offsets\"},"
            + " {\"name\": \"lengths\"}, {\"name\": \"ordinalPositions\"}]}]";
    }

    private static ObjectNode content(String text) {
        ObjectNode document = SearchJson.object();
        document.put("content", text);
        return document;
    }

    private static final String SPLIT_MAPPINGS = """
        [{"sourceFieldName": "/document/pages", "targetFieldName": "pages"},
         {"sourceFieldName": "/document/offsets/*/utf16", "targetFieldName": "offsets"},
         {"sourceFieldName": "/document/lengths/*/utf16", "targetFieldName": "lengths"},
         {"sourceFieldName": "/document/ordinalPositions"}]
        """;

    @Test
    void pagesBreakOnSentencesAndStayWithinTheMaximum() {
        String text = SENTENCES.repeat(10);
        EnrichmentResult result = run(split("\"textSplitMode\": \"pages\", \"maximumPageLength\": 300,"),
            content(text), SPLIT_MAPPINGS);
        assertThat(result.errors(), empty());
        List<String> pages = strings(result.indexFields().get("pages"));
        assertThat(pages, hasSize(3));
        assertThat(pages.stream().map(String::length).toList(), everyItem(lessThanOrEqualTo(300)));
        assertThat(pages.get(0), equalTo(SENTENCES.repeat(4)));
        assertThat(String.join("", pages), equalTo(text));
        assertThat(result.indexFields().get("offsets").toString(), equalTo("[0,284,568]"));
        assertThat(result.indexFields().get("lengths").toString(), equalTo("[284,284,142]"));
        assertThat(result.indexFields().get("ordinalPositions").toString(), equalTo("[1,2,3]"));
    }

    @Test
    void pagesOverlapFromAWordStartAndMaximumPagesToTakeStopsEarly() {
        StringBuilder numbered = new StringBuilder();
        for (int sentence = 1; sentence <= 10; sentence++) {
            numbered.append("This is sentence number ").append(sentence).append(" of the test document. ");
        }
        String text = numbered.toString();
        EnrichmentResult overlapped = run(split("\"maximumPageLength\": 300, \"pageOverlapLength\": 30,"),
            content(text), SPLIT_MAPPINGS);
        List<String> pages = strings(overlapped.indexFields().get("pages"));
        assertThat(pages, hasSize(2));
        assertThat(pages.get(0), equalTo(text.substring(0, 288)));
        assertThat(pages.get(1), equalTo(text.substring(264)));
        assertThat(pages.get(1), startsWith("6 of the test document. This is sentence number 7"));
        assertThat(overlap(pages.get(0), pages.get(1)), is(both(greaterThan(0)).and(lessThanOrEqualTo(30))));
        assertThat(overlapped.indexFields().get("offsets").toString(), equalTo("[0,264]"));
        EnrichmentResult first = run(split("\"maximumPageLength\": 300, \"maximumPagesToTake\": 1,"),
            content(SENTENCES.repeat(10)), SPLIT_MAPPINGS);
        assertThat(strings(first.indexFields().get("pages")), contains(SENTENCES.repeat(4)));
    }

    @Test
    void sentencesAreTrimmedAndOffsetsCountEveryEncoding() {
        EnrichmentResult result = run(split("\"textSplitMode\": \"sentences\","),
            content("Héllo wörld.  How are you? Fine 😀 thanks!"), """
                [{"sourceFieldName": "/document/pages", "targetFieldName": "pages"},
                 {"sourceFieldName": "/document/offsets/2", "targetFieldName": "third"},
                 {"sourceFieldName": "/document/lengths/2", "targetFieldName": "thirdLength"}]
                """);
        assertThat(strings(result.indexFields().get("pages")),
            contains("Héllo wörld.", "How are you?", "Fine 😀 thanks!"));
        assertThat(result.indexFields().get("third").toString(),
            equalTo("{\"utf8\":29,\"utf16\":27,\"codePoint\":27}"));
        assertThat(result.indexFields().get("thirdLength").toString(),
            equalTo("{\"utf8\":17,\"utf16\":15,\"codePoint\":14}"));
    }

    @Test
    void tokensAreApproximatedAndAnUnsupportedLanguageLeavesTheTextWhole() {
        String text = SENTENCES.repeat(10);
        EnrichmentResult tokens = run(split("\"maximumPageLength\": 75, \"unit\": \"azureOpenAITokens\","),
            content(text), SPLIT_MAPPINGS);
        assertThat(strings(tokens.indexFields().get("pages")).getFirst(), equalTo(SENTENCES.repeat(4)));

        ObjectNode klingon = content(text);
        klingon.put("language", "tlh");
        EnrichmentResult unsplit = run(split(""), klingon, SPLIT_MAPPINGS);
        assertThat(strings(unsplit.indexFields().get("pages")), contains(text));
        assertThat(unsplit.warnings(), contains("Enrichment.SplitSkill.#1: Skill input 'languageCode' has the "
            + "following language codes 'tlh', at least one of which is invalid. The text was not split."));
    }

    @Test
    void mergeInsertsAtOffsetsOrAppends() {
        String merge = """
            [{"@odata.type": "#Microsoft.Skills.Text.MergeSkill", "insertPreTag": " ", "insertPostTag": "",
              "inputs": [{"name": "text", "source": "/document/text"},
                         {"name": "itemsToInsert", "source": "/document/items"},
                         {"name": "offsets", "source": "/document/offsets"}],
              "outputs": [{"name": "mergedText", "targetName": "merged"}, {"name": "mergedOffsets"}]}]
            """;
        String mappings = "[{\"sourceFieldName\": \"/document/merged\"}, {\"sourceFieldName\": "
            + "\"/document/mergedOffsets\"}]";
        EnrichmentResult result = run(merge, "{\"text\": \"The brown fox jumps over the dog\", "
            + "\"items\": [\"quick\", \"lazy\"], \"offsets\": [3, 28]}", mappings);
        assertThat(result.indexFields().get("merged").asText(), equalTo("The quick brown fox jumps over the lazy dog"));
        assertThat(result.indexFields().get("mergedOffsets").toString(), equalTo("[3,34]"));

        EnrichmentResult invalid = run(merge, "{\"text\": \"Doc\", \"items\": [\"a\", \"b\"], \"offsets\": [1, "
            + "\"x\"]}", mappings);
        assertThat(invalid.indexFields().get("merged").asText(), equalTo("Doc a b"));
        assertThat(invalid.warnings().getFirst(), containsString("One or more offset values were invalid"));

        EnrichmentResult concatenated = run(merge.replace("{\"name\": \"text\", \"source\": \"/document/text\"},",
            "").replace("\"insertPostTag\": \"\"", "\"insertPostTag\": \"]\"").replace("\"insertPreTag\": \" \"",
            "\"insertPreTag\": \"[\""), "{\"items\": [\"a\", \"b\"]}", mappings);
        assertThat(concatenated.indexFields().get("merged").asText(), equalTo("[a][b]"));
    }

    @Test
    void shaperBuildsTheDocumentedShapes() {
        String document = """
            {"title": "How to be happy", "phrases": ["awesome movie", "bad end"],
             "pages": [{"chapterTitles": [{"title": "Start young", "number": 1},
                                          {"title": "Laugh often", "number": 2}]},
                       {"chapterTitles": [{"title": "Eat, sleep and exercise", "number": 3}]}]}
            """;
        String skills = """
            [{"@odata.type": "#Microsoft.Skills.Util.ShaperSkill", "context": "/document/phrases/*",
              "inputs": [{"name": "text", "source": "/document/phrases/*"},
                         {"name": "sentiment", "source": "/document/phrases/*/sentiment"}],
              "outputs": [{"name": "output", "targetName": "analyzedText"}]},
             {"@odata.type": "#Microsoft.Skills.Util.ShaperSkill",
              "inputs": [{"name": "title", "source": "/document/title"},
                         {"name": "chapterTitles", "source": "/document/pages/*/chapterTitles/*/title"}],
              "outputs": [{"name": "output", "targetName": "flat"}]},
             {"@odata.type": "#Microsoft.Skills.Util.ShaperSkill",
              "inputs": [{"name": "title", "source": "/document/title"},
                         {"name": "chapterTitles", "sourceContext": "/document/pages/*/chapterTitles/*",
                          "inputs": [{"name": "title", "source": "/document/pages/*/chapterTitles/*/title"},
                                     {"name": "number", "source": "/document/pages/*/chapterTitles/*/number"}]}],
              "outputs": [{"name": "output", "targetName": "nested"}]}]
            """;
        EnrichmentResult result = run(skills, document, """
            [{"sourceFieldName": "/document/phrases/*/analyzedText", "targetFieldName": "analyzed"},
             {"sourceFieldName": "/document/flat"}, {"sourceFieldName": "/document/nested"}]
            """);
        assertThat(result.errors(), empty());
        assertThat(result.indexFields().get("analyzed").toString(),
            equalTo("[{\"text\":\"awesome movie\"},{\"text\":\"bad end\"}]"));
        assertThat(result.indexFields().get("flat").toString(), equalTo("{\"title\":\"How to be happy\","
            + "\"chapterTitles\":[\"Start young\",\"Laugh often\",\"Eat, sleep and exercise\"]}"));
        assertThat(result.indexFields().get("nested").toString(), equalTo("{\"title\":\"How to be happy\","
            + "\"chapterTitles\":[{\"title\":\"Start young\",\"number\":1},{\"title\":\"Laugh often\",\"number\":2},"
            + "{\"title\":\"Eat, sleep and exercise\",\"number\":3}]}"));
    }

    @Test
    void conditionalSetsDefaultsAndANullOutputStopsDependentSkills() {
        String skills = """
            [{"@odata.type": "#Microsoft.Skills.Util.ConditionalSkill", "name": "default",
              "inputs": [{"name": "condition", "source": "= $(/document/language) == null"},
                         {"name": "whenTrue", "source": "= 'es'"},
                         {"name": "whenFalse", "source": "= $(/document/language)"}],
              "outputs": [{"name": "output", "targetName": "languageWithDefault"}]},
             {"@odata.type": "#Microsoft.Skills.Util.ConditionalSkill", "name": "french",
              "inputs": [{"name": "condition", "source": "= $(/document/languageWithDefault) == 'fr'"},
                         {"name": "whenTrue", "source": "/document/sentences"},
                         {"name": "whenFalse", "source": "= null"}],
              "outputs": [{"name": "output", "targetName": "frenchSentences"}]},
             {"@odata.type": "#Microsoft.Skills.Util.ShaperSkill", "name": "shape",
              "context": "/document/frenchSentences",
              "inputs": [{"name": "s", "source": "/document/frenchSentences"}],
              "outputs": [{"name": "output", "targetName": "shaped"}]},
             {"@odata.type": "#Microsoft.Skills.Util.ConditionalSkill", "name": "broken",
              "inputs": [{"name": "condition", "source": "/document/sentences"}],
              "outputs": [{"name": "output", "targetName": "nothing"}]}]
            """;
        EnrichmentResult spanish = run(skills, "{\"sentences\": [\"Hola.\"]}",
            "[{\"sourceFieldName\": \"/document/languageWithDefault\"}, "
                + "{\"sourceFieldName\": \"/document/frenchSentences/shaped\"}]");
        assertThat(spanish.indexFields().get("languageWithDefault").asText(), equalTo("es"));
        assertThat(spanish.indexFields().has("shaped"), is(false));
        assertThat(spanish.warnings(), contains(
            "Enrichment.ConditionalSkill.broken: Could not execute skill because one or more skill input was "
                + "invalid. Required skill input was not of the expected type 'Boolean'. Name: 'condition', "
                + "Source: '/document/sentences'.",
            "Projection.SearchIndex.OutputFieldMapping.shaped: Could not map output field 'shaped' to search "
                + "index. Missing value /document/frenchSentences/shaped."));
        EnrichmentResult french = run(skills, "{\"language\": \"fr\", \"sentences\": [\"Bonjour.\"]}",
            "[{\"sourceFieldName\": \"/document/frenchSentences/shaped/s\", \"targetFieldName\": \"shaped\"}]");
        assertThat(french.indexFields().get("shaped").toString(), equalTo("[\"Bonjour.\"]"));
    }

    @Test
    void documentExtractionReadsTextFormatsAndWarnsAboutOthers() {
        String skills = """
            [{"@odata.type": "#Microsoft.Skills.Util.DocumentExtractionSkill", "context": "/document/files/*",
              "inputs": [{"name": "file_data", "source": "/document/files/*"}],
              "outputs": [{"name": "content", "targetName": "text"}, {"name": "normalized_images"}]}]
            """;
        String document = "{\"files\": [" + file("hello") + ", "
            + file("<html><head><style>p{}</style></head><body><h1>Title</h1><p>A &amp; B</p></body></html>") + ", "
            + file("%PDF-1.7 binary") + "]}";
        EnrichmentResult result = run(skills, document, "[{\"sourceFieldName\": \"/document/files/*/text\", "
            + "\"targetFieldName\": \"texts\"}, {\"sourceFieldName\": \"/document/files/0/normalized_images\", "
            + "\"targetFieldName\": \"images\"}]");
        assertThat(strings(result.indexFields().get("texts")), contains("hello", "Title\nA & B"));
        assertThat(result.indexFields().get("images").toString(), equalTo("[]"));
        assertThat(result.warnings(), contains("Enrichment.DocumentExtractionSkill.#1: Could not extract content or "
            + "metadata from your document. Document has unsupported content type 'application/pdf'. floci-az "
            + "extracts text formats only."));
    }

    private static String file(String content) {
        return "{\"$type\": \"file\", \"data\": \"" + Base64.getEncoder().encodeToString(
            content.getBytes(StandardCharsets.UTF_8)) + "\"}";
    }

    @Test
    void skillsTheEmulatorCannotRunFailTheDocumentAndMissingInputsAreWarnings() {
        String skills = """
            [{"@odata.type": "#Microsoft.Skills.Text.V3.EntityRecognitionSkill", "name": "entities",
              "inputs": [{"name": "text", "source": "/document/content"}], "outputs": [{"name": "persons"}]},
             {"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "name": "split",
              "inputs": [{"name": "text", "source": "/document/missing"}], "outputs": [{"name": "textItems"}]},
             {"@odata.type": "#Microsoft.Skills.Vision.OcrSkill", "name": "ocr", "context": "/document/images/*",
              "inputs": [{"name": "image", "source": "/document/images/*"}], "outputs": [{"name": "text"}]}]
            """;
        EnrichmentResult result = run(skills, "{\"content\": \"Satya Nadella\"}",
            "[{\"sourceFieldName\": \"/document/content\"}]");
        assertThat(result.errors(), contains("Enrichment.EntityRecognitionSkill.entities: Could not execute skill "
            + "because the skill 'entities' of type '#Microsoft.Skills.Text.V3.EntityRecognitionSkill' is not "
            + "emulated by floci-az: it needs an Azure AI services model."));
        assertThat(result.warnings(), contains("Enrichment.SplitSkill.split: Could not execute skill because one or "
            + "more skill input was invalid. Required skill input is missing. Name: text, Source: /document/missing."
            + " Missing value /document/missing."));
        assertThat(result.indexFields().size(), is(0));
        assertThat(result.projections(), empty());
    }

    @Test
    void outputFieldMappingsApplyTheMappingFunctions() {
        String document = """
            {"path": "00>00?00", "encoded": "MDA-MDA_MDA1", "name": "Jane Doe", "tags": "[\\"red\\", \\"blue\\"]",
             "key": "<hello>", "orgs": [{"description": "QQ"}, {"description": "Qg"}], "doc": "{\\"a\\": 1}"}
            """;
        EnrichmentResult result = run("[]", document, """
            [{"sourceFieldName": "/document/path", "targetFieldName": "token",
              "mappingFunction": {"name": "base64Encode"}},
             {"sourceFieldName": "/document/path", "targetFieldName": "plain",
              "mappingFunction": {"name": "base64Encode", "parameters": {"useHttpServerUtilityUrlTokenEncode": false}}},
             {"sourceFieldName": "/document/encoded", "targetFieldName": "decoded",
              "mappingFunction": {"name": "BASE64DECODE"}},
             {"sourceFieldName": "/document/name", "targetFieldName": "last",
              "mappingFunction": {"name": "extractTokenAtPosition", "parameters": {"delimiter": " ", "position": 1}}},
             {"sourceFieldName": "/document/tags", "mappingFunction": {"name": "jsonArrayToStringCollection"}},
             {"sourceFieldName": "/document/key", "targetFieldName": "url",
              "mappingFunction": {"name": "urlEncode"}},
             {"sourceFieldName": "/document/orgs/*/description", "targetFieldName": "descriptions",
              "mappingFunction": {"name": "base64Decode", "parameters": {"useHttpServerUtilityUrlTokenDecode": false}}},
             {"sourceFieldName": "/document/key", "targetFieldName": "fixed", "mappingFunction": {"name": "fixedLengthEncode"}},
             {"sourceFieldName": "/document/doc", "targetFieldName": "json", "mappingFunction": {"name": "toJson"}}]
            """);
        ObjectNode fields = result.indexFields();
        assertThat(result.errors(), hasSize(0));
        assertThat(fields.get("token").asText(), equalTo("MDA-MDA_MDA1"));
        assertThat(fields.get("plain").asText(), equalTo("MDA-MDA_MDA"));
        assertThat(fields.get("decoded").asText(), equalTo("00>00?00"));
        assertThat(fields.get("last").asText(), equalTo("Doe"));
        assertThat(fields.get("tags").toString(), equalTo("[\"red\",\"blue\"]"));
        assertThat(fields.get("url").asText(), equalTo("%3chello%3e"));
        assertThat(fields.get("descriptions").toString(), equalTo("[\"A\",\"B\"]"));
        assertThat(fields.get("fixed").asText(), matchesPattern("[0-9a-f]{64}"));
        assertThat(fields.get("json").toString(), equalTo("{\"a\":1}"));
    }

    @Test
    void aMappingFunctionThatCannotApplyIsAnError() {
        EnrichmentResult result = run("[]", "{\"name\": \"Jane\"}", "[{\"sourceFieldName\": \"/document/name\", "
            + "\"mappingFunction\": {\"name\": \"extractTokenAtPosition\", \"parameters\": {\"delimiter\": \" \", "
            + "\"position\": 3}}}]");
        assertThat(result.errors(), contains("Projection.SearchIndex.OutputFieldMapping.name: Could not map output "
            + "field 'name' to search index due to deserialization problem while applying mapping function "
            + "'extractTokenAtPosition'. there is no token at position 3."));
    }

    private static final String PROJECTED_SKILLSET = """
        {"skills": [{"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "sentences",
                     "inputs": [{"name": "text", "source": "/document/content"}],
                     "outputs": [{"name": "textItems", "targetName": "pages"}]}],
         "indexProjections": {"selectors": [{"targetIndexName": "chunks", "parentKeyFieldName": "parent_id",
            "sourceContext": "/document/pages/*",
            "mappings": [{"name": "chunk", "source": "/document/pages/*"},
                         {"name": "title", "source": "/document/title"},
                         {"name": "meta", "sourceContext": "/document/pages/*",
                          "inputs": [{"name": "text", "source": "/document/pages/*"}]}]}],
          "parameters": {"projectionMode": "skipIndexingParentDocuments"}}}
        """;

    @Test
    void indexProjectionsProjectEachSourceContextNodeWithDocumentedKeys() {
        ObjectNode document = json("{\"AzureSearch_DocumentKey\": \"aa1b22c33\", \"title\": \"Doc\", "
            + "\"content\": \"One. Two.\"}");
        EnrichmentResult result = pipeline.run("svc", json(PROJECTED_SKILLSET), document, array("[]"));
        assertThat(result.errors(), empty());
        assertThat(result.skipParentDocument(), is(true));
        List<ProjectedDocument> projections = result.projections();
        assertThat(projections, hasSize(2));
        assertThat(projections.get(0).targetIndexName(), equalTo("chunks"));
        ObjectNode first = projections.get(0).document();
        assertThat(first.get("chunk_id").asText(), matchesPattern("[0-9a-f]{12}_aa1b22c33_pages_0"));
        assertThat(projections.get(1).document().get("chunk_id").asText(), matchesPattern(
            "[0-9a-f]{12}_aa1b22c33_pages_1"));
        assertThat(first.get("parent_id").asText(), equalTo("aa1b22c33"));
        assertThat(first.get("chunk").asText(), equalTo("One."));
        assertThat(first.get("title").asText(), equalTo("Doc"));
        assertThat(first.get("meta").toString(), equalTo("{\"text\":\"One.\"}"));

        EnrichmentResult again = pipeline.run("svc", json(PROJECTED_SKILLSET), document.deepCopy(), array("[]"));
        assertThat(again.projections().get(0).document(), equalTo(first));
        ObjectNode changed = document.deepCopy();
        changed.put("content", "One. Three.");
        String changedKey = pipeline.run("svc", json(PROJECTED_SKILLSET), changed, array("[]")).projections().get(0)
            .document().get("chunk_id").asText();
        assertThat(changedKey.equals(first.get("chunk_id").asText()), is(false));
        assertThat(changedKey.substring(12), equalTo("_aa1b22c33_pages_0"));
    }

    @Test
    void theParentKeyFallsBackToTheTargetKeyFieldAndMissingIndexesFail() {
        ObjectNode document = json("{\"chunk_id\": \"aHR0cHM6Ly9ibG9i0\", \"content\": \"One.\"}");
        EnrichmentResult result = pipeline.run("svc", json(PROJECTED_SKILLSET.replace(
            "skipIndexingParentDocuments", "includeIndexingParentDocuments")), document, array("[]"));
        assertThat(result.skipParentDocument(), is(false));
        assertThat(result.projections().get(0).document().get("parent_id").asText(), equalTo("aHR0cHM6Ly9ibG9i0"));

        EnrichmentResult missing = pipeline.run("svc", json(PROJECTED_SKILLSET.replace("\"chunks\"", "\"missing\"")),
            document, array("[]"));
        assertThat(missing.errors(), contains("Projection.SearchIndex.IndexProjection.missing: Could not project "
            + "document. The index projection's target index 'missing' does not exist."));
        assertThat(missing.projections(), empty());
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    /** How many characters {@code page} starts with from the end of {@code previous}. */
    private static int overlap(String previous, String page) {
        for (int length = Math.min(previous.length(), page.length()); length > 0; length--) {
            if (previous.endsWith(page.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }
}
