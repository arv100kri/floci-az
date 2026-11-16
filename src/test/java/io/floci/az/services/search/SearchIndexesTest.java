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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: index definitions")
class SearchIndexesTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/idx-search";
    private static final String HOTELS = """
        {"name": "hotels", "fields": [
          {"name": "HotelId", "type": "Edm.String", "key": true, "filterable": true},
          {"name": "HotelName", "type": "Edm.String", "searchable": true, "sortable": true},
          {"name": "Rating", "type": "Edm.Double"},
          {"name": "Tags", "type": "Collection(Edm.String)"},
          {"name": "Location", "type": "Edm.GeographyPoint"},
          {"name": "Address", "type": "Edm.ComplexType", "fields": [
            {"name": "City", "type": "Edm.String"}]}
        ],
        "scoringProfiles": [{"name": "boost", "text": {"weights": {"HotelName": 2}}}],
        "suggesters": [{"name": "sg", "searchMode": "analyzingInfixMatching", "sourceFields": ["HotelName"]}],
        "corsOptions": {"allowedOrigins": ["*"], "maxAgeInSeconds": 60},
        "semantic": {"configurations": [{"name": "default",
          "prioritizedFields": {"titleField": {"fieldName": "HotelName"}}}]},
        "permissionFilterOption": "disabled"}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse createHotels() {
        return admin().body(HOTELS).post(BASE + "/indexes" + API).then().statusCode(201);
    }

    @Test
    void createReturnsTheDefinitionWithDocumentedDefaults() {
        createHotels()
            .header("ETag", startsWith("\"0x"))
            .header("Location", endsWith("/idx-search/indexes('hotels')?api-version=2024-07-01"))
            .body("'@odata.etag'", startsWith("\"0x"))
            .body("name", equalTo("hotels"))
            .body("fields[0].searchable", equalTo(true))
            .body("fields[0].retrievable", equalTo(true))
            .body("fields[0].sortable", equalTo(true))
            .body("fields[0].key", equalTo(true))
            .body("fields[0].analyzer", nullValue())
            .body("fields[0].synonymMaps", empty())
            .body("fields[2].searchable", equalTo(false))
            .body("fields[2].filterable", equalTo(true))
            .body("fields[2].sortable", equalTo(true))
            .body("fields[2].facetable", equalTo(true))
            .body("fields[3].sortable", equalTo(false))
            .body("fields[3].facetable", equalTo(true))
            .body("fields[4].facetable", equalTo(false))
            .body("fields[5].searchable", nullValue())
            .body("fields[5].fields[0].name", equalTo("City"))
            .body("similarity.'@odata.type'", equalTo("#Microsoft.Azure.Search.BM25Similarity"))
            .body("similarity", hasKey("k1"))
            .body("similarity.k1", nullValue())
            .body("analyzers", empty())
            .body("tokenizers", empty())
            .body("encryptionKey", nullValue())
            .body("vectorSearch", nullValue());
    }

    @Test
    void everySectionRoundTrips() {
        createHotels();
        admin().get(BASE + "/indexes('hotels')" + API).then().statusCode(200)
            .header("ETag", startsWith("\"0x"))
            .body("scoringProfiles[0].name", equalTo("boost"))
            .body("scoringProfiles[0].text.weights.HotelName", equalTo(2))
            .body("suggesters[0].sourceFields", contains("HotelName"))
            .body("corsOptions.maxAgeInSeconds", equalTo(60))
            .body("semantic.configurations[0].name", equalTo("default"))
            .body("permissionFilterOption", equalTo("disabled"));
    }

    @Test
    void creatingAnExistingIndexWithPostIs409() {
        createHotels();
        admin().body(HOTELS).post(BASE + "/indexes" + API).then().statusCode(409);
    }

    @Test
    void aMissingIndexIs404() {
        admin().get(BASE + "/indexes('nope')" + API).then().statusCode(404)
            .body("error.message", equalTo("No index with the name 'nope' was found in the service 'idx'."));
        admin().delete(BASE + "/indexes('nope')" + API).then().statusCode(404);
    }

    @Test
    void putCreatesThenUpdatesHonouringPrefer() {
        admin().body(HOTELS).put(BASE + "/indexes('hotels')" + API).then().statusCode(201);
        String added = HOTELS.replace("{\"name\": \"Rating\", \"type\": \"Edm.Double\"},",
            "{\"name\": \"Rating\", \"type\": \"Edm.Double\"}, {\"name\": \"Stars\", \"type\": \"Edm.Int32\"},");
        admin().header("Prefer", "return=representation").body(added).put(BASE + "/indexes('hotels')" + API)
            .then().statusCode(200)
            .body("fields.name", contains("HotelId", "HotelName", "Rating", "Stars", "Tags", "Location", "Address"));
        admin().body(added).put(BASE + "/indexes('hotels')" + API).then().statusCode(204);
    }

    @Test
    void etagsGuardUpdatesAndDeletes() {
        String etag = createHotels().extract().header("ETag");
        admin().header("If-Match", "\"0x1\"").body(HOTELS).put(BASE + "/indexes('hotels')" + API)
            .then().statusCode(412).body("error.code", equalTo("PreconditionFailed"));
        admin().header("If-None-Match", "*").body(HOTELS).put(BASE + "/indexes('hotels')" + API)
            .then().statusCode(412);
        admin().header("If-Match", "*").body(HOTELS.replace("hotels", "other")).put(BASE + "/indexes('other')" + API)
            .then().statusCode(412);
        String next = admin().header("If-Match", etag).header("Prefer", "return=representation").body(HOTELS)
            .put(BASE + "/indexes('hotels')" + API).then().statusCode(200).extract().header("ETag");
        admin().header("If-Match", etag).delete(BASE + "/indexes('hotels')" + API).then().statusCode(412);
        admin().header("If-Match", next).delete(BASE + "/indexes('hotels')" + API).then().statusCode(204);
        admin().get(BASE + "/indexes('hotels')" + API).then().statusCode(404);
    }

    @Test
    void preferMinimalOnCreateAnswers204() {
        admin().header("Prefer", "return=minimal").body(HOTELS).post(BASE + "/indexes" + API).then().statusCode(204)
            .header("ETag", notNullValue());
    }

    @Test
    void listReturnsEveryIndexOrJustNames() {
        createHotels();
        admin().body(HOTELS.replace("\"hotels\"", "\"motels\"")).post(BASE + "/indexes" + API).then().statusCode(201);
        admin().get(BASE + "/indexes" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("/idx-search/$metadata#indexes"))
            .body("value.name", contains("hotels", "motels"))
            .body("value[0].fields", notNullValue());
        admin().get(BASE + "/indexes?api-version=2024-07-01&$select=name").then().statusCode(200)
            .body("value.name", contains("hotels", "motels"))
            .body("value[0]", not(hasKey("fields")));
    }

    @Test
    void invalidDefinitionsAre400() {
        String[] invalid = {
            "{\"name\":\"a\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true}]}",
            "{\"name\":\"nokey\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\"}]}",
            "{\"name\":\"twokeys\",\"fields\":[{\"name\":\"a\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"b\",\"type\":\"Edm.String\",\"key\":true}]}",
            "{\"name\":\"intkey\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.Int32\",\"key\":true}]}",
            "{\"name\":\"searchint\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"n\",\"type\":\"Edm.Int32\",\"searchable\":true}]}",
            "{\"name\":\"sortcoll\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"t\",\"type\":\"Collection(Edm.String)\",\"sortable\":true}]}",
            "{\"name\":\"facetgeo\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"g\",\"type\":\"Edm.GeographyPoint\",\"facetable\":true}]}",
            "{\"name\":\"badanalyzer\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true,"
                + "\"analyzer\":\"no.such.analyzer\"}]}",
            "{\"name\":\"Upper\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true}]}",
            "{\"name\":\"badtype\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"x\",\"type\":\"Edm.Float\"}]}",
            "{\"name\":\"novector\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"v\",\"type\":\"Collection(Edm.Single)\"}]}",
            "{\"name\":\"classic\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true}],"
                + "\"similarity\":{\"@odata.type\":\"#Microsoft.Azure.Search.ClassicSimilarity\"}}",
            "{\"name\":\"complexkey\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"c\",\"type\":\"Edm.ComplexType\",\"key\":true,\"fields\":[{\"name\":\"x\","
                + "\"type\":\"Edm.String\"}]}]}",
            "{\"name\":\"dupfield\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"id\",\"type\":\"Edm.String\"}]}",
            "{\"name\":\"defaultprofile\",\"defaultScoringProfile\":\"missing\",\"fields\":[{\"name\":\"id\","
                + "\"type\":\"Edm.String\",\"key\":true}]}"
        };
        for (String definition : invalid) {
            admin().body(definition).post(BASE + "/indexes" + API).then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestParameter"));
        }
    }

    @Test
    void vectorFieldsNeedAProfileThatExists() {
        String vector = "{\"name\":\"vec\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
            + "{\"name\":\"v\",\"type\":\"Collection(Edm.Single)\",\"dimensions\":3,\"vectorSearchProfile\":\"p\"}],"
            + "\"vectorSearch\":{\"algorithms\":[{\"name\":\"a\",\"kind\":\"exhaustiveKnn\"}],"
            + "\"profiles\":[{\"name\":\"p\",\"algorithm\":\"a\"}]}}";
        admin().body(vector).post(BASE + "/indexes" + API).then().statusCode(201)
            .body("fields[1].dimensions", equalTo(3))
            .body("fields[1].searchable", equalTo(true))
            .body("fields[1].filterable", equalTo(false))
            .body("vectorSearch.profiles[0].name", equalTo("p"));
        admin().body(vector.replace("\"vec\"", "\"vec2\"").replace("\"vectorSearchProfile\":\"p\"",
            "\"vectorSearchProfile\":\"missing\"")).post(BASE + "/indexes" + API).then().statusCode(400);
    }

    @Test
    void updatesMayNotChangeExistingFields() {
        createHotels();
        admin().body(HOTELS.replace("{\"name\": \"Rating\", \"type\": \"Edm.Double\"}",
            "{\"name\": \"Rating\", \"type\": \"Edm.Double\", \"filterable\": false}"))
            .put(BASE + "/indexes('hotels')" + API).then().statusCode(400)
            .body("error.message", containsString("'filterable'"));
        admin().body(HOTELS.replace("{\"name\": \"Rating\", \"type\": \"Edm.Double\"},", ""))
            .put(BASE + "/indexes('hotels')" + API).then().statusCode(400)
            .body("error.message", containsString("Existing field(s) cannot be deleted"));
        admin().header("Prefer", "return=representation").body(HOTELS.replace(
                "{\"name\": \"Rating\", \"type\": \"Edm.Double\"}",
                "{\"name\": \"Rating\", \"type\": \"Edm.Double\", \"retrievable\": false}"))
            .put(BASE + "/indexes('hotels')" + API).then().statusCode(200)
            .body("fields[2].retrievable", equalTo(false));
    }

    @Test
    void similarityChangesNeedDowntime() {
        createHotels();
        String tuned = HOTELS.replace("\"permissionFilterOption\": \"disabled\"",
            "\"permissionFilterOption\": \"disabled\", \"similarity\": {\"@odata.type\": "
                + "\"#Microsoft.Azure.Search.BM25Similarity\", \"k1\": 1.5, \"b\": 0.5}");
        admin().body(tuned).put(BASE + "/indexes('hotels')" + API).then().statusCode(400)
            .body("error.message", startsWith("Index update not allowed because it would cause downtime."));
        admin().header("Prefer", "return=representation").body(tuned)
            .put(BASE + "/indexes('hotels')?api-version=2024-07-01&allowIndexDowntime=true").then().statusCode(200)
            .body("similarity.k1", equalTo(1.5f))
            .body("similarity.b", equalTo(0.5f));
    }

    @Test
    void theNameInTheBodyMustMatchTheUrl() {
        admin().body(HOTELS).put(BASE + "/indexes('motels')" + API).then().statusCode(400);
    }

    @Test
    void analysisComponentsAreAddedWithDowntimeAndNeverChanged() {
        String index = """
            {"name": "notes", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "body", "type": "Edm.String", "searchable": true, "analyzer": "folding"}],
             "analyzers": [{"name": "folding", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
               "tokenizer": "standard_v2", "tokenFilters": ["lowercase"], "charFilters": ["no_dashes"]}%s],
             "charFilters": [{"name": "no_dashes", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
               "mappings": [%s]}]%s}
            """;
        String second = ", {\"name\": \"plain\", \"@odata.type\": \"#Microsoft.Azure.Search.CustomAnalyzer\", "
            + "\"tokenizer\": \"whitespace\"}";
        String downtime = "/indexes('notes')?api-version=2024-07-01&allowIndexDowntime=true";
        admin().body(index.formatted("", "\"-=>\"", "")).post(BASE + "/indexes" + API).then().statusCode(201);
        admin().body(index.formatted("", "\"-=>\"", "")).put(BASE + "/indexes('notes')" + API).then().statusCode(204);

        admin().body(index.formatted(second, "\"-=>\"", "")).put(BASE + "/indexes('notes')" + API).then()
            .statusCode(400)
            .body("error.message", startsWith("Index update not allowed because it would cause downtime."));
        admin().body(index.formatted(second, "\"-=>\"", "")).put(BASE + downtime).then().statusCode(204);
        admin().get(BASE + "/indexes('notes')" + API).then().statusCode(200)
            .body("analyzers.name", contains("folding", "plain"));

        admin().body(index.formatted(second, "\"-=>_\"", "")).put(BASE + downtime).then().statusCode(400)
            .body("error.message", containsString("the existing char filter 'no_dashes' cannot be modified"));
        admin().body(index.formatted("", "\"-=>\"", "")).put(BASE + downtime).then().statusCode(400)
            .body("error.message", containsString("the existing analyzer 'plain' cannot be deleted"));
        admin().body(index.formatted(second.replace("whitespace", "keyword_v2"), "\"-=>\"", ""))
            .put(BASE + "/indexes('notes')" + API).then().statusCode(400)
            .body("error.message", containsString("the existing analyzer 'plain' cannot be modified"));

        String normalizer = ", \"normalizers\": [{\"name\": \"upper\", \"@odata.type\": "
            + "\"#Microsoft.Azure.Search.CustomNormalizer\", \"tokenFilters\": [\"uppercase\"]}]";
        admin().body(index.formatted(second, "\"-=>\"", normalizer)).put(BASE + "/indexes('notes')" + API).then()
            .statusCode(400)
            .body("error.message", startsWith("Index update not allowed because it would cause downtime."));
        admin().body(index.formatted(second, "\"-=>\"", normalizer)).put(BASE + downtime).then().statusCode(204);
    }

    @Test
    void statisticsCountDocumentsAndIndexes() {
        createHotels();
        admin().body("{\"value\":[{\"HotelId\":\"1\",\"HotelName\":\"A\"},{\"HotelId\":\"2\",\"HotelName\":\"B\"}]}")
            .post(BASE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
        admin().get(BASE + "/indexes('hotels')/search.stats" + API).then().statusCode(200)
            .body("documentCount", equalTo(2))
            .body("storageSize", notNullValue())
            .body("vectorIndexSize", equalTo(0));
        admin().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.documentCount.usage", equalTo(2))
            .body("counters.indexesCount.usage", equalTo(1))
            .body("counters.indexersCount.usage", equalTo(0))
            .body("counters.synonymMaps.usage", equalTo(0))
            .body("counters.aliasesCount.usage", equalTo(0))
            .body("limits.maxFieldsPerIndex", equalTo(1000));
        admin().get(BASE + "/indexstats" + API).then().statusCode(200)
            .body("value[0].name", equalTo("hotels"))
            .body("value[0].documentCount", equalTo(2));
    }
}
