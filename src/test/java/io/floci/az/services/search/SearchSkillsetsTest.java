package io.floci.az.services.search;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.skills.SkillsetStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: skillsets")
class SearchSkillsetsTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/skills-search";
    private static final String SPLIT_SKILLSET = """
        {"name": "chunker", "description": "split and shape", "skills": [
          {"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "pages", "maximumPageLength": 2000,
           "inputs": [{"name": "text", "source": "/document/content"}],
           "outputs": [{"name": "textItems", "targetName": "pages"}]},
          {"@odata.type": "#Microsoft.Skills.Util.ShaperSkill", "name": "shape", "context": "/document/pages/*",
           "inputs": [{"name": "text", "source": "/document/pages/*"}],
           "outputs": [{"name": "output", "targetName": "shaped"}]}
        ]}
        """;
    private static final String WEB_API_SKILLSET = """
        {"name": "custom", "skills": [
          {"@odata.type": "#Microsoft.Skills.Custom.WebApiSkill", "name": "lookup",
           "uri": "https://fn.example.net/api/lookup?code=s3cret&mode=fast", "httpHeaders": {"x-api-key": "k1"},
           "inputs": [{"name": "text", "source": "/document/content"}], "outputs": [{"name": "result"}]}
        ]}
        """;

    @Inject
    SkillsetStore store;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse create(String body) {
        return admin().body(body).post(BASE + "/skillsets" + API).then();
    }

    private static ValidatableResponse rejected(String body) {
        return create(body).statusCode(400);
    }

    @Test
    void createAnswersTheDefinitionAsSentAndGetAddsTheServiceDefaults() {
        create(SPLIT_SKILLSET).statusCode(201)
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/skills-search/skillsets('chunker')?api-version=2024-07-01"))
            .body("'@odata.context'", endsWith("/skills-search/$metadata#skillsets/$entity"))
            .body("'@odata.etag'", startsWith("\"0x"))
            .body("name", equalTo("chunker"))
            .body("skills[0].name", nullValue())
            .body("skills[0].context", nullValue())
            .body("skills[0].defaultLanguageCode", nullValue())
            .body("skills[0].inputs[0].sourceContext", nullValue())
            .body("skills[0].inputs[0].inputs", empty())
            .body("cognitiveServices", nullValue())
            .body("knowledgeStore", nullValue())
            .body("indexProjections", nullValue())
            .body("encryptionKey", nullValue());
        admin().get(BASE + "/skillsets('chunker')" + API).then().statusCode(200)
            .header("ETag", startsWith("\"0x"))
            .body("skills[0].name", equalTo("#1"))
            .body("skills[0].context", equalTo("/document"))
            .body("skills[0].defaultLanguageCode", equalTo("en"))
            .body("skills[0].maximumPageLength", equalTo(2000))
            .body("skills[1].name", equalTo("shape"))
            .body("skills[1].context", equalTo("/document/pages/*"));
        admin().get(BASE + "/skillsets/chunker" + API).then().statusCode(200).body("name", equalTo("chunker"));
    }

    @Test
    void listAnswersEverySkillsetAndHonoursSelect() {
        create(SPLIT_SKILLSET).statusCode(201);
        create(WEB_API_SKILLSET).statusCode(201);
        admin().get(BASE + "/skillsets" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#skillsets"))
            .body("value.name", contains("chunker", "custom"))
            .body("value[0]", hasKey("@odata.etag"));
        admin().get(BASE + "/skillsets" + API + "&$select=name").then().statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#skillsets(name)"))
            .body("value[0]", not(hasKey("@odata.etag")))
            .body("value[1].name", equalTo("custom"));
    }

    @Test
    void putCreatesThenUpdatesWithEtagsAndPrefer() {
        String etag = admin().body(SPLIT_SKILLSET).put(BASE + "/skillsets('chunker')" + API).then().statusCode(201)
            .extract().header("ETag");
        String changed = SPLIT_SKILLSET.replace("split and shape", "changed");
        admin().header("If-Match", "\"0x1\"").body(changed).put(BASE + "/skillsets('chunker')" + API).then()
            .statusCode(412).body("error.message", startsWith("The precondition given in one of the request headers"));
        admin().header("If-None-Match", "*").body(changed).put(BASE + "/skillsets('chunker')" + API).then()
            .statusCode(412);
        String updated = admin().header("If-Match", etag).header("Prefer", "return=representation").body(changed)
            .put(BASE + "/skillsets('chunker')" + API).then().statusCode(200)
            .body("description", equalTo("changed"))
            .extract().header("ETag");
        assertThat(updated.equals(etag), is(false));
        admin().body(changed).put(BASE + "/skillsets('chunker')" + API).then().statusCode(204);
        admin().header("If-Match", etag).delete(BASE + "/skillsets('chunker')" + API).then().statusCode(412);
        admin().delete(BASE + "/skillsets('chunker')" + API).then().statusCode(204);
        admin().delete(BASE + "/skillsets('chunker')" + API).then().statusCode(404)
            .body("error.message", equalTo("No skillset with the name 'chunker' was found in a service named "
                + "'skills'."));
        admin().get(BASE + "/skillsets('chunker')" + API).then().statusCode(404);
    }

    @Test
    void creatingAnExistingSkillsetWithPostIs409() {
        create(SPLIT_SKILLSET).statusCode(201);
        create(SPLIT_SKILLSET).statusCode(409);
    }

    @Test
    void definitionsAreValidatedPerTheSpecification() {
        rejected("{\"name\": \"Upper\", \"skills\": [" + split("") + "]}")
            .body("error.message", containsString("Skillset name 'Upper' is invalid"));
        rejected("{\"name\": \"none\", \"skills\": []}")
            .body("error.message", containsString("at least one skill"));
        rejected("{\"name\": \"unknown\", \"skills\": [{\"@odata.type\": \"#Microsoft.Skills.Text.Nope\"}]}")
            .body("error.message", equalTo("One or more skills are invalid. Details: Error in skill '#1': "
                + "'#Microsoft.Skills.Text.Nope' is not a known skill type."));
        rejected("{\"name\": \"dupes\", \"skills\": [" + split("\"name\": \"s\",") + "," + split("\"name\": \"s\",")
            + "]}").body("error.message", containsString("the name 's' is used more than once"));
        rejected("{\"name\": \"outputs\", \"skills\": [{\"@odata.type\": \"#Microsoft.Skills.Text.SplitSkill\", "
            + "\"inputs\": [{\"name\": \"text\", \"source\": \"/document/content\"}], "
            + "\"outputs\": [{\"name\": \"pages\"}]}]}")
            .body("error.message", equalTo("One or more skills are invalid. Details: Error in skill '#1': Outputs "
                + "are not supported by skill: pages"));
        rejected("{\"name\": \"context\", \"skills\": [" + split("\"context\": \"/doc/pages\",") + "]}")
            .body("error.message", containsString("context '/doc/pages' is invalid"));
        rejected("{\"name\": \"expression\", \"skills\": [{\"@odata.type\": \"#Microsoft.Skills.Util.ConditionalSkill\","
            + " \"inputs\": [{\"name\": \"condition\", \"source\": \"= $(/document/a) ==\"}],"
            + " \"outputs\": [{\"name\": \"output\"}]}]}")
            .body("error.message", containsString("has an invalid source"));
        rejected("{\"name\": \"pagelength\", \"skills\": [" + split("\"maximumPageLength\": 100,") + "]}")
            .body("error.message", containsString("'maximumPageLength' must be between 300 and 50000"));
        rejected(WEB_API_SKILLSET.replace("\"uri\": \"https://fn.example.net/api/lookup?code=s3cret&mode=fast\",",
            "")).body("error.message", containsString("'uri' is required"));
        rejected(WEB_API_SKILLSET.replace("x-api-key", "Content-Type"))
            .body("error.message", containsString("The header 'Content-Type' cannot be set"));
        rejected(WEB_API_SKILLSET.replace("\"name\": \"lookup\",", "\"name\": \"lookup\", \"timeout\": \"PT300S\","))
            .body("error.message", containsString("'timeout' must be between 1 and 230 seconds"));
        rejected(WEB_API_SKILLSET.replace("https://fn.example.net/api/lookup?code=s3cret&mode=fast",
            "http://example.invalid/api/label"))
            .body("error.message", containsString("only the https URI scheme is allowed"));
        rejected("{\"name\": \"cycle\", \"skills\": ["
            + "{\"@odata.type\": \"#Microsoft.Skills.Util.ShaperSkill\", \"name\": \"a\","
            + " \"inputs\": [{\"name\": \"x\", \"source\": \"/document/b\"}], \"outputs\": [{\"name\": \"output\","
            + " \"targetName\": \"a\"}]},"
            + "{\"@odata.type\": \"#Microsoft.Skills.Util.ShaperSkill\", \"name\": \"b\","
            + " \"inputs\": [{\"name\": \"x\", \"source\": \"/document/a\"}], \"outputs\": [{\"name\": \"output\","
            + " \"targetName\": \"b\"}]}]}")
            .body("error.message", containsString("in a cycle"));
        admin().body(SPLIT_SKILLSET).put(BASE + "/skillsets('other')" + API).then().statusCode(400)
            .body("error.message", containsString("does not match the name 'other'"));
    }

    @Test
    void skillsTheEmulatorCannotRunAreAcceptedAndNormalised() {
        create("""
            {"name": "vision", "skills": [{"@odata.type": "#Microsoft.Skills.Vision.OcrSkill",
              "context": "/document/normalized_images/*",
              "inputs": [{"name": "image", "source": "/document/normalized_images/*"}],
              "outputs": [{"name": "text"}]}],
             "cognitiveServices": {"@odata.type": "#Microsoft.Azure.Search.CognitiveServicesByKey", "key": "abc"},
             "knowledgeStore": {"storageConnectionString": "UseDevelopmentStorage=true", "projections": []},
             "encryptionKey": {"keyVaultKeyName": "k", "keyVaultKeyVersion": "1",
               "keyVaultUri": "https://vault.vault.azure.net"}}
            """).statusCode(201)
            .body("skills[0].lineEnding", nullValue())
            .body("skills[0].detectOrientation", nullValue())
            .body("cognitiveServices.description", nullValue())
            .body("cognitiveServices.key", equalTo("abc"))
            .body("knowledgeStore.storageConnectionString", equalTo("UseDevelopmentStorage=true"));
        admin().body("""
            {"name": "vision", "skills": [{"@odata.type": "#Microsoft.Skills.Vision.OcrSkill",
              "inputs": [{"name": "image", "source": "/document/normalized_images/*"}],
              "outputs": [{"name": "text"}]}], "encryptionKey": null}
            """).put(BASE + "/skillsets('vision')" + API).then().statusCode(204);
        admin().get(BASE + "/skillsets('vision')" + API).then().statusCode(200)
            .body("encryptionKey.keyVaultKeyName", equalTo("k"))
            .body("cognitiveServices", nullValue())
            .body("skills[0].defaultLanguageCode", equalTo("en"));
        rejected("{\"name\": \"cs\", \"skills\": [" + split("") + "], \"cognitiveServices\": "
            + "{\"@odata.type\": \"#Microsoft.Azure.Search.CognitiveServicesByKey\"}}")
            .body("error.message", containsString("requires 'key'"));
    }

    @Test
    void getAndListRedactWebApiSecretsAndPlaceholdersKeepThem() {
        create(WEB_API_SKILLSET).statusCode(201)
            .body("skills[0].uri", equalTo("https://fn.example.net/api/lookup?code=s3cret&mode=fast"));
        String redacted = admin().get(BASE + "/skillsets('custom')" + API).then().statusCode(200)
            .body("skills[0].uri", equalTo("https://fn.example.net/api/lookup?code=<redacted>&mode=fast"))
            .body("skills[0].httpHeaders.x-api-key", equalTo("<redacted>"))
            .extract().asString();
        admin().get(BASE + "/skillsets" + API).then().statusCode(200)
            .body("value[0].skills[0].httpHeaders.x-api-key", equalTo("<redacted>"));

        admin().body(redacted).put(BASE + "/skillsets('custom')" + API).then().statusCode(204);
        ObjectNode stored = store.find("skills", "custom").orElseThrow().definition();
        assertThat(stored.at("/skills/0/uri").asText(), equalTo("https://fn.example.net/api/lookup?code=s3cret"
            + "&mode=fast"));
        assertThat(stored.at("/skills/0/httpHeaders/x-api-key").asText(), equalTo("k1"));

        admin().body(redacted.replace("mode=fast", "mode=slow").replace("https://fn.example.net", "https://other.net"))
            .put(BASE + "/skillsets('custom')" + API).then().statusCode(400)
            .body("error.message", containsString("Provide the actual secret value"));
        admin().body(redacted.replace("\"custom\"", "\"copy\"")).put(BASE + "/skillsets('copy')" + API).then()
            .statusCode(400);
        admin().body(WEB_API_SKILLSET.replace("https://fn.example.net/api/lookup?code=s3cret&mode=fast",
            "<unchanged>").replace("\"k1\"", "\"<unchanged>\"")).put(BASE + "/skillsets('custom')" + API).then()
            .statusCode(204);
        assertThat(store.find("skills", "custom").orElseThrow().definition().at("/skills/0/uri").asText(),
            startsWith("https://fn.example.net/api/lookup?code=s3cret"));
    }

    @Test
    void indexProjectionsAreValidatedAgainstTheTargetIndex() {
        admin().body("""
            {"name": "chunks", "fields": [
              {"name": "chunk_id", "type": "Edm.String", "key": true, "analyzer": "keyword"},
              {"name": "parent_id", "type": "Edm.String", "filterable": true},
              {"name": "title", "type": "Edm.String", "filterable": false},
              {"name": "chunk", "type": "Edm.String"}]}
            """).post(BASE + "/indexes" + API).then().statusCode(201);
        String projected = """
            {"name": "rag", "skills": [%s],
             "indexProjections": {"selectors": [{"targetIndexName": "chunks", "parentKeyFieldName": "%s",
               "sourceContext": "/document/pages/*",
               "mappings": [{"name": "chunk", "source": "/document/pages/*"},
                            {"name": "title", "source": "/document/title"}]}],
               "parameters": {"projectionMode": "skipIndexingParentDocuments"}}}
            """;
        String skill = split("\"name\": \"split\",");
        create(projected.formatted(skill, "parent_id")).statusCode(201)
            .body("indexProjections.selectors[0].mappings[0].sourceContext", nullValue())
            .body("indexProjections.parameters.projectionMode", equalTo("skipIndexingParentDocuments"));
        rejected(projected.formatted(skill, "title").replace("\"rag\"", "\"rag2\""))
            .body("error.message", containsString("must be a filterable Edm.String field"));
        rejected(projected.formatted(skill, "parent_id").replace("\"chunks\"", "\"missing\"")
            .replace("\"rag\"", "\"rag3\""))
            .body("error.message", startsWith("One or more index projection selectors are invalid. Details: The "
                + "target index 'missing' does not exist"));
    }

    @Test
    void resetSkillsIsAcknowledgedAndServiceStatisticsCountSkillsets() {
        create(SPLIT_SKILLSET).statusCode(201);
        create(WEB_API_SKILLSET).statusCode(201);
        admin().body("{\"skillNames\": [\"#1\"]}").post(BASE + "/skillsets('chunker')/search.resetskills" + API)
            .then().statusCode(204);
        admin().body("{\"skillNames\": [\"#1\"]}").post(BASE + "/skillsets('none')/search.resetskills" + API)
            .then().statusCode(404);
        admin().body("{\"skillNames\": \"#1\"}").post(BASE + "/skillsets('chunker')/search.resetskills" + API)
            .then().statusCode(400);
        admin().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.skillsetCount.usage", equalTo(2));
    }

    @Test
    void skillsetsAreKeptUnderTheirDataPlaneKey() {
        create(SPLIT_SKILLSET).statusCode(201);
        assertThat(store.list("SKILLS"), hasSize(1));
        assertThat(store.find("skills", "chunker").orElseThrow().definition().at("/skills/0/name").asText(),
            equalTo("#1"));
        assertThat(SkillsetStore.key("Skills", "chunker"), equalTo("dp/skillset/skills/chunker"));
    }

    private static String split(String extra) {
        return "{\"@odata.type\": \"#Microsoft.Skills.Text.SplitSkill\", " + extra
            + " \"inputs\": [{\"name\": \"text\", \"source\": \"/document/content\"}],"
            + " \"outputs\": [{\"name\": \"textItems\", \"targetName\": \"pages\"}]}";
    }
}
