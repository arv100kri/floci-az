package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Search data plane: full syntax, facets, highlights, suggestions and scoring profiles")
class SearchQueryFeaturesTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String SERVICE = "/features-search";
    private static final String DOCS = SERVICE + "/indexes('hotels')/docs";
    private static final String HOTELS = """
        {"name": "hotels", "fields": [
          {"name": "id", "type": "Edm.String", "key": true, "searchable": false, "sortable": true},
          {"name": "name", "type": "Edm.String", "sortable": true, "facetable": false},
          {"name": "description", "type": "Edm.String", "filterable": false, "facetable": false},
          {"name": "category", "type": "Edm.String", "searchable": false, "sortable": true},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "rating", "type": "Edm.Int32", "sortable": true},
          {"name": "price", "type": "Edm.Double", "sortable": true},
          {"name": "parking", "type": "Edm.Boolean"},
          {"name": "location", "type": "Edm.GeographyPoint"}
        ],
        "suggesters": [{"name": "sg", "searchMode": "analyzingInfixMatching", "sourceFields": ["name"]}],
        "scoringProfiles": [
          {"name": "ratingBoost", "functions": [{"type": "magnitude", "fieldName": "rating", "boost": 10,
            "interpolation": "linear", "magnitude": {"boostingRangeStart": 1, "boostingRangeEnd": 5}}]},
          {"name": "near", "functions": [{"type": "distance", "fieldName": "location", "boost": 5,
            "distance": {"referencePointParameter": "here", "boostingDistance": 50}}]}
        ]}
        """;
    private static final String DOCUMENTS = """
        {"value": [
          {"id": "1", "name": "Ocean View Resort", "description": "Luxury resort with a pool and an ocean view",
           "category": "Resort", "tags": ["pool", "view", "wifi"], "rating": 5, "price": 250.0, "parking": true,
           "location": {"type": "Point", "coordinates": [-122.33, 47.61]}},
          {"id": "2", "name": "Budget Inn", "description": "Cheap motel near the airport with free parking",
           "category": "Budget", "tags": ["parking", "wifi"], "rating": 2, "price": 60.0, "parking": true,
           "location": {"type": "Point", "coordinates": [-122.30, 47.45]}},
          {"id": "3", "name": "City Center Hotel", "description": "Modern hotel in the city center with a rooftop pool",
           "category": "Luxury", "tags": ["pool", "gym"], "rating": 4, "price": 180.0, "parking": false,
           "location": {"type": "Point", "coordinates": [-122.34, 47.60]}},
          {"id": "4", "name": "Mountain Lodge", "description": "Rustic lodge with hiking trails and a stone fireplace",
           "category": "Resort", "tags": ["hiking", "fireplace"], "rating": 3, "price": 120.0, "parking": true,
           "location": {"type": "Point", "coordinates": [-105.27, 40.01]}},
          {"id": "5", "name": "Airport Suites", "description": "Convenient suites next to the airport terminal",
           "category": "Budget", "tags": ["wifi", "shuttle"], "rating": 3, "price": 90.0, "parking": false,
           "location": {"type": "Point", "coordinates": [-122.31, 47.44]}},
          {"id": "6", "name": "Harbor Boutique", "description": "Small boutique hotel on the harbor with a view",
           "category": "Boutique", "tags": ["boutique", "view"], "rating": 4, "price": 150.0, "parking": false,
           "location": {"type": "Point", "coordinates": [-122.40, 47.65]}}
        ]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        admin().body(HOTELS).post(SERVICE + "/indexes" + API).then().statusCode(201);
        admin().body(DOCUMENTS).post(DOCS + "/search.index" + API).then().statusCode(200);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse search(String body) {
        return admin().body(body).post(DOCS + "/search.post.search" + API).then();
    }

    private static List<String> keys(String body) {
        return search(body).statusCode(200).extract().jsonPath().getList("value.id");
    }

    private static RequestSpecification get() {
        return admin().queryParam("api-version", "2024-07-01");
    }

    @Test
    void fullLuceneSyntaxQueries() {
        assertEquals(List.of("4"), keys("{\"search\": \"name:lodge\", \"queryType\": \"full\"}"));
        assertEquals(List.of("1"), keys("{\"search\": \"pool AND view\", \"queryType\": \"full\"}"));
        assertEquals(List.of("1"), keys("{\"search\": \"pool NOT rooftop\", \"queryType\": \"full\"}"));
        assertEquals(List.of("2", "3", "6"), sorted(keys("{\"search\": \"/[mh]otel/\", \"queryType\": \"full\"}")));
        assertEquals(List.of("6"), keys("{\"search\": \"\\\"hotel harbor\\\"~5\", \"queryType\": \"full\"}"));
        assertEquals(List.of("4"), keys("{\"search\": \"montain~1\", \"queryType\": \"full\"}"));
        get().queryParam("search", "l?dge").queryParam("queryType", "full").get(DOCS).then().statusCode(200)
            .body("value.id", contains("4"));
        assertEquals(List.of("3"), keys("{\"filter\": \"search.ismatch('pool AND rooftop', 'description', 'full', "
            + "'all')\"}"));
    }

    @Test
    void malformedFullSyntaxIsABadRequestNamingThePosition() {
        search("{\"search\": \"*abc\", \"queryType\": \"full\"}").statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"))
            .body("error.message", startsWith("Failed to parse query string at line 1, column 1."));
        search("{\"search\": \"category:Budget\", \"queryType\": \"full\"}").statusCode(400);
    }

    @Test
    void facetsByPostAndGet() {
        search("{\"search\": \"*\", \"facets\": [\"category,count:10,sort:value\", \"price,values:100|200\", "
            + "\"rating,interval:2\"]}").statusCode(200)
            .body("'@search.facets'.category.value", contains("Boutique", "Budget", "Luxury", "Resort"))
            .body("'@search.facets'.category.count", contains(1, 2, 1, 2))
            .body("'@search.facets'.price.count", contains(2, 3, 1))
            .body("'@search.facets'.price[1].from", equalTo(100.0f))
            .body("'@search.facets'.price[1].to", equalTo(200.0f))
            .body("'@search.facets'.rating.value", contains(2, 4));
        get().queryParam("search", "*").queryParam("$filter", "parking eq true")
            .queryParam("facet", "category", "tags,count:1").get(DOCS).then().statusCode(200)
            .body("'@search.facets'.category.value", contains("Resort", "Budget"))
            .body("'@search.facets'.category.count", contains(2, 1))
            .body("'@search.facets'.tags.value", contains("wifi"));
        search("{\"facets\": [\"name\"]}").statusCode(400);
    }

    @Test
    void highlightsByPostAndGet() {
        search("{\"search\": \"pool\", \"highlight\": \"description\"}").statusCode(200)
            .body("value.find { it.id == '1' }.'@search.highlights'.description",
                contains("Luxury resort with a <em>pool</em> and an ocean view"));
        get().queryParam("search", "pool").queryParam("highlight", "description-1")
            .queryParam("highlightPreTag", "<b>").queryParam("highlightPostTag", "</b>").get(DOCS).then()
            .statusCode(200)
            .body("value.find { it.id == '3' }.'@search.highlights'.description",
                contains("Modern hotel in the city center with a rooftop <b>pool</b>"));
        search("{\"search\": \"*\", \"highlight\": \"description\"}").statusCode(200)
            .body("value[0]", not(hasKey("@search.highlights")));
        search("{\"search\": \"pool\", \"highlight\": \"category\"}").statusCode(400);
    }

    @Test
    void suggestByPostAndGet() {
        admin().body("{\"search\": \"bud\", \"suggesterName\": \"sg\"}").post(DOCS + "/search.post.suggest" + API)
            .then().statusCode(200)
            .body("'@odata.context'", endsWith("/features-search/indexes('hotels')/$metadata#docs(*)"))
            .body("value[0].'@search.text'", equalTo("Budget Inn"))
            .body("value[0].id", equalTo("2"))
            .body("value[0]", not(hasKey("name")));
        get().queryParam("search", "bidget").queryParam("suggesterName", "sg").queryParam("fuzzy", "true")
            .queryParam("$select", "id,category").queryParam("highlightPreTag", "<b>")
            .queryParam("highlightPostTag", "</b>").get(DOCS + "/search.suggest").then().statusCode(200)
            .body("value[0].'@search.text'", equalTo("<b>Budget</b> Inn"))
            .body("value[0].category", equalTo("Budget"));
        get().queryParam("search", "b").queryParam("suggesterName", "sg").queryParam("$orderby", "id desc")
            .queryParam("$top", "1").queryParam("minimumCoverage", "80").get(DOCS + "/search.suggest").then()
            .statusCode(200)
            .body("'@search.coverage'", equalTo(100.0f))
            .body("value.id", contains("6"));
        admin().body("{\"search\": \"bud\", \"suggesterName\": \"nope\"}").post(DOCS + "/search.post.suggest" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidRequestParameter"));
    }

    @Test
    void autocompleteByPostAndGet() {
        admin().body("{\"search\": \"budget i\", \"suggesterName\": \"sg\"}")
            .post(DOCS + "/search.post.autocomplete" + API).then().statusCode(200)
            .body("value.text", contains("inn"))
            .body("value.queryPlusText", contains("budget inn"));
        get().queryParam("search", "moun").queryParam("suggesterName", "sg").queryParam("autocompleteMode", "twoTerms")
            .get(DOCS + "/search.autocomplete").then().statusCode(200)
            .body("value.text", contains("mountain lodge"));
        admin().body("{\"search\": \"harbor b\", \"suggesterName\": \"sg\", \"autocompleteMode\": "
            + "\"oneTermWithContext\", \"highlightPreTag\": \"<b>\", \"highlightPostTag\": \"</b>\"}")
            .post(DOCS + "/search.post.autocomplete" + API).then().statusCode(200)
            .body("value.queryPlusText", contains("<b>harbor boutique</b>"));
        admin().body("{\"search\": \"moun\", \"suggesterName\": \"sg\", \"top\": 0}")
            .post(DOCS + "/search.post.autocomplete" + API).then().statusCode(400);
    }

    @Test
    void scoringProfilesByPostAndGet() {
        assertEquals(List.of("1", "3", "6", "4", "5", "2"),
            keys("{\"search\": \"*\", \"scoringProfile\": \"ratingBoost\"}"));
        get().queryParam("search", "*").queryParam("scoringProfile", "near")
            .queryParam("scoringParameter", "here--122.40,47.65").get(DOCS).then().statusCode(200)
            .body("value.id", contains("6", "1", "3", "2", "5", "4"));
        search("{\"search\": \"*\", \"scoringProfile\": \"near\"}").statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"));
        search("{\"search\": \"*\", \"scoringProfile\": \"missing\"}").statusCode(400);
    }

    @Test
    void indexDefinitionsWithInvalidProfilesOrSuggestersAreRefused() {
        String badProfile = HOTELS.replace("\"interpolation\": \"linear\"", "\"interpolation\": \"cubic\"");
        admin().body(badProfile.replace("\"hotels\"", "\"bad-profile\"")).post(SERVICE + "/indexes" + API).then()
            .statusCode(400).body("error.code", equalTo("InvalidRequestParameter"));
        String unsearchable = HOTELS.replace("\"sourceFields\": [\"name\"]", "\"sourceFields\": [\"category\"]");
        admin().body(unsearchable.replace("\"hotels\"", "\"bad-suggester\"")).post(SERVICE + "/indexes" + API)
            .then().statusCode(400);
        String keyword = HOTELS.replace("{\"name\": \"name\", \"type\": \"Edm.String\", \"sortable\": true,",
            "{\"name\": \"name\", \"type\": \"Edm.String\", \"analyzer\": \"keyword\", \"sortable\": true,");
        admin().body(keyword.replace("\"hotels\"", "\"bad-analyzer\"")).post(SERVICE + "/indexes" + API)
            .then().statusCode(400);
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(null);
        return copy;
    }
}
