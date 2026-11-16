package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.indexer.SkillsetRunner;
import io.floci.az.services.search.indexer.SkillsetRunner.EnrichmentResult;
import io.floci.az.services.search.model.SearchJson;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class EnrichmentSkillsetRunnerTest {

    private static final String API = "?api-version=2024-07-01";

    @Inject
    SkillsetRunner runner;

    @Inject
    SkillsetStore store;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    @Test
    void theEnrichmentPipelineIsTheSkillsetRunnerTheIndexerGets() {
        assertThat(runner, instanceOf(EnrichmentSkillsetRunner.class));
    }

    @Test
    void aStoredSkillsetRunsAndProjectsIntoTheTargetIndex() {
        admin().body("""
            {"name": "chunks", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "analyzer": "keyword"},
              {"name": "parent", "type": "Edm.String", "filterable": true},
              {"name": "text", "type": "Edm.String"}]}
            """).post("/runner-search/indexes" + API).then().statusCode(201);
        admin().body("""
            {"name": "rag", "skills": [
              {"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "sentences",
               "inputs": [{"name": "text", "source": "/document/content"}],
               "outputs": [{"name": "textItems", "targetName": "pages"}]}],
             "indexProjections": {"selectors": [{"targetIndexName": "chunks", "parentKeyFieldName": "parent",
               "sourceContext": "/document/pages/*", "mappings": [{"name": "text", "source": "/document/pages/*"}]}],
               "parameters": {"projectionMode": "skipIndexingParentDocuments"}}}
            """).post("/runner-search/skillsets" + API).then().statusCode(201);
        ObjectNode skillset = store.find("runner", "rag").orElseThrow().definition();
        ObjectNode document = SearchJson.object();
        document.put("AzureSearch_DocumentKey", "doc1");
        document.put("content", "First sentence. Second sentence.");

        EnrichmentResult result = runner.enrich("runner", skillset, document, SearchJson.NODES.arrayNode());

        assertThat(result.errors(), empty());
        assertThat(result.skipParentDocument(), is(true));
        assertThat(result.projections(), hasSize(2));
        ObjectNode second = result.projections().get(1).document();
        assertThat(second.get("id").asText(), matchesPattern("[0-9a-f]{12}_doc1_pages_1"));
        assertThat(second.get("parent").asText(), equalTo("doc1"));
        assertThat(second.get("text").asText(), equalTo("Second sentence."));
        assertThat(document.has("pages"), is(false));
    }
}
