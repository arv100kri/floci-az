package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@DisplayName("Search data plane: analyzers, normalizers and the Analyze API")
class SearchAnalysisTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/analysis-search";
    private static final String INDEX = """
        {"name": "notes", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "note", "type": "Edm.String", "searchable": true, "analyzer": "folding"},
          {"name": "body", "type": "Edm.String", "searchable": true, "analyzer": "en.lucene"},
          {"name": "city", "type": "Edm.String", "filterable": true, "sortable": true, "facetable": true,
           "normalizer": "lowercase"},
          {"name": "code", "type": "Edm.String", "filterable": true, "normalizer": "dashes"}
        ],
        "analyzers": [{"name": "folding", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
          "tokenizer": "standard_v2", "tokenFilters": ["lowercase", "asciifolding"], "charFilters": ["html_strip"]}],
        "normalizers": [{"name": "dashes", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer",
          "charFilters": ["no_dashes"], "tokenFilters": ["uppercase"]}],
        "charFilters": [{"name": "no_dashes", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
          "mappings": ["-=>"]}]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static void createNotes() {
        admin().body(INDEX).post(BASE + "/indexes" + API).then().statusCode(201);
    }

    private static ValidatableResponse analyze(String request) {
        return admin().body(request).post(BASE + "/indexes('notes')/search.analyze" + API).then();
    }

    private static ValidatableResponse search(String request) {
        return admin().body(request).post(BASE + "/indexes('notes')/docs/search.post.search" + API).then()
            .statusCode(200);
    }

    @Test
    void analyzeReturnsTokensWithOffsetsAndPositions() {
        createNotes();
        analyze("{\"text\": \"Text to analyze\", \"analyzer\": \"standard.lucene\"}").statusCode(200)
            .body("'@odata.context'", endsWith("$metadata#Microsoft.Azure.Search.V2019_05_06.AnalyzeResult"))
            .body("tokens.token", contains("text", "to", "analyze"))
            .body("tokens.startOffset", contains(0, 5, 8))
            .body("tokens.endOffset", contains(4, 7, 15))
            .body("tokens.position", contains(0, 1, 2));
        analyze("{\"text\": \"The runners' boxes\", \"analyzer\": \"en.lucene\"}").statusCode(200)
            .body("tokens.token", contains("runner", "box"));
        analyze("{\"text\": \"SVP10-NOR-00\", \"analyzer\": \"standard\"}").statusCode(200)
            .body("tokens.token", contains("svp10", "nor", "00"))
            .body("tokens.startOffset", contains(0, 6, 10))
            .body("tokens.endOffset", contains(5, 9, 12));
        analyze("{\"text\": \"SVP10-NOR-00\", \"analyzer\": \"keyword\"}").statusCode(200)
            .body("tokens.token", contains("SVP10-NOR-00"))
            .body("tokens[0].endOffset", equalTo(12));
    }

    @Test
    void analyzeRunsATokenizerWithFiltersOrANormalizer() {
        createNotes();
        analyze("{\"text\": \"The Quick-Brown fox\", \"tokenizer\": \"whitespace\", \"tokenFilters\": [\"lowercase\"]}")
            .statusCode(200)
            .body("tokens.token", contains("the", "quick-brown", "fox"));
        analyze("{\"text\": \"<p>Café</p>\", \"analyzer\": \"folding\"}").statusCode(200)
            .body("tokens.token", contains("cafe"))
            .body("tokens[0].startOffset", equalTo(3))
            .body("tokens[0].endOffset", equalTo(7));
        analyze("{\"text\": \"Vis-à-vis means Opposite\", \"normalizer\": \"asciifolding\"}").statusCode(200)
            .body("tokens.token", contains("Vis-a-vis means Opposite"))
            .body("tokens[0].startOffset", equalTo(0))
            .body("tokens[0].endOffset", equalTo(24))
            .body("tokens[0].position", equalTo(0));
        analyze("{\"text\": \"ab-12-cd\", \"normalizer\": \"dashes\"}").statusCode(200)
            .body("tokens.token", contains("AB12CD"));
    }

    @Test
    void analyzeRefusesAmbiguousOrUnknownComponents() {
        createNotes();
        analyze("{\"analyzer\": \"standard.lucene\"}").statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"));
        analyze("{\"text\": \"x\", \"analyzer\": \"standard.lucene\", \"tokenizer\": \"whitespace\"}")
            .statusCode(400);
        analyze("{\"text\": \"x\", \"analyzer\": \"standard.lucene\", \"tokenFilters\": [\"lowercase\"]}")
            .statusCode(400);
        analyze("{\"text\": \"x\"}").statusCode(400);
        analyze("{\"text\": \"x\", \"analyzer\": \"no.such.analyzer\"}").statusCode(400)
            .body("error.message", containsString("no.such.analyzer"));
        analyze("{\"text\": \"x\", \"tokenizer\": \"whitespace\", \"tokenFilters\": [\"nope\"]}").statusCode(400)
            .body("error.message", containsString("nope"));
        admin().body("{\"text\": \"x\", \"analyzer\": \"keyword\"}")
            .post(BASE + "/indexes('missing')/search.analyze" + API).then().statusCode(404);
    }

    @Test
    void customAnalyzersApplyAtIndexingAndQueryTime() {
        createNotes();
        admin().body("""
            {"value": [{"@search.action": "upload", "id": "1", "note": "Crème brûlée at the <b>Café</b>",
                        "body": "Running shoes for runners"},
                       {"@search.action": "upload", "id": "2", "note": "plain toast", "body": "a quiet walk"}]}
            """).post(BASE + "/indexes('notes')/docs/search.index" + API).then().statusCode(200);
        search("{\"search\": \"creme brulee\", \"searchMode\": \"all\"}").body("value.id", contains("1"));
        search("{\"search\": \"b\"}").body("value", empty());
        search("{\"search\": \"run\", \"searchFields\": \"body\"}").body("value.id", contains("1"));
    }

    @Test
    void normalizersApplyToFiltersSortingAndCustomNormalization() {
        createNotes();
        admin().body("""
            {"value": [{"@search.action": "upload", "id": "1", "city": "Seattle", "code": "ab-12"},
                       {"@search.action": "upload", "id": "2", "city": "AUSTIN", "code": "cd-34"},
                       {"@search.action": "upload", "id": "3", "city": "boston", "code": "AB12"}]}
            """).post(BASE + "/indexes('notes')/docs/search.index" + API).then().statusCode(200);
        search("{\"filter\": \"city eq 'SEATTLE'\"}").body("value.id", contains("1"));
        search("{\"orderby\": \"city asc\"}").body("value.id", contains("2", "3", "1"));
        search("{\"filter\": \"code eq 'ab12'\", \"orderby\": \"id\"}").body("value.id", contains("1", "3"));
        search("{\"filter\": \"search.in(city, 'austin,BOSTON')\", \"orderby\": \"id\"}")
            .body("value.id", contains("2", "3"));
    }

    @Test
    void tokensAFilterStacksOnOnePositionMatchAsAlternatives() {
        admin().body("""
            {"name": "notes", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "note", "type": "Edm.String", "searchable": true, "analyzer": "keep_accents"}],
             "analyzers": [{"name": "keep_accents", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
               "tokenizer": "standard_v2", "tokenFilters": ["lowercase", "folding"]}],
             "tokenFilters": [{"name": "folding", "@odata.type": "#Microsoft.Azure.Search.AsciiFoldingTokenFilter",
               "preserveOriginal": true}]}
            """).post(BASE + "/indexes" + API).then().statusCode(201);
        admin().body("""
            {"value": [{"@search.action": "upload", "id": "1", "note": "cafe noir"}]}
            """).post(BASE + "/indexes('notes')/docs/search.index" + API).then().statusCode(200);
        search("{\"search\": \"café noir\", \"searchMode\": \"all\"}").body("value.id", contains("1"));
    }

    @Test
    void theTutorialsPhoneNumberAnalyzerProducesTheDocumentedTokens() {
        admin().body("""
            {"name": "phones", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "phone_number", "type": "Edm.String", "searchable": true, "analyzer": "phone_analyzer"}],
             "analyzers": [{"@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer", "name": "phone_analyzer",
               "tokenizer": "keyword_v2", "tokenFilters": ["custom_ngram_filter"],
               "charFilters": ["phone_char_mapping"]}],
             "charFilters": [{"@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
               "name": "phone_char_mapping", "mappings": ["-=>", "(=>", ")=>", "+=>", ".=>", "\\\\u0020=>"]}],
             "tokenFilters": [{"@odata.type": "#Microsoft.Azure.Search.NGramTokenFilterV2",
               "name": "custom_ngram_filter", "minGram": 3, "maxGram": 20}]}
            """).post(BASE + "/indexes" + API).then().statusCode(201);
        admin().body("{\"text\": \"+1 (321) 555-0199\", \"analyzer\": \"phone_analyzer\"}")
            .post(BASE + "/indexes('phones')/search.analyze" + API).then().statusCode(200)
            .body("tokens[0].token", equalTo("132"))
            .body("tokens[0].startOffset", equalTo(1))
            .body("tokens[0].endOffset", equalTo(17))
            .body("tokens[0].position", equalTo(0))
            .body("tokens[1].token", equalTo("1321"))
            .body("tokens[2].token", equalTo("13215"));
        admin().body("{\"text\": \"12345\", \"analyzer\": \"phone_analyzer\"}")
            .post(BASE + "/indexes('phones')/search.analyze" + API).then().statusCode(200)
            .body("tokens.token", contains("123", "1234", "12345", "234", "2345", "345"));
        admin().body("{\"text\": \"(425) 555-0100\", \"analyzer\": \"standard.lucene\"}")
            .post(BASE + "/indexes('phones')/search.analyze" + API).then().statusCode(200)
            .body("tokens.token", contains("425", "555", "0100"))
            .body("tokens.startOffset", contains(1, 6, 10))
            .body("tokens.endOffset", contains(4, 9, 14));
    }

    @Test
    void invalidAnalysisDefinitionsAre400() {
        admin().body(INDEX.replace("\"tokenizer\": \"standard_v2\"", "\"tokenizer\": \"no_such_tokenizer\""))
            .post(BASE + "/indexes" + API).then().statusCode(400)
            .body("error.message", containsString("no_such_tokenizer"));
        admin().body(INDEX.replace("\"tokenFilters\": [\"uppercase\"]", "\"tokenFilters\": [\"porter_stem\"]"))
            .post(BASE + "/indexes" + API).then().statusCode(400)
            .body("error.message", containsString("normalizer"));
        admin().body(INDEX.replace("\"mappings\": [\"-=>\"]", "\"mappings\": [\"=>x\"]"))
            .post(BASE + "/indexes" + API).then().statusCode(400);
    }
}
