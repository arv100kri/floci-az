package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Search data plane: Search Documents")
class SearchQueryTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String SERVICE = "/query-search";
    private static final String HOTELS = """
        {"name": "hotels", "fields": [
          {"name": "HotelId", "type": "Edm.String", "key": true},
          {"name": "HotelName", "type": "Edm.String", "sortable": true},
          {"name": "Description", "type": "Edm.String"},
          {"name": "Category", "type": "Edm.String", "searchable": false},
          {"name": "Tags", "type": "Collection(Edm.String)"},
          {"name": "Rating", "type": "Edm.Double"},
          {"name": "Parking", "type": "Edm.Boolean"},
          {"name": "Renovated", "type": "Edm.DateTimeOffset"},
          {"name": "Location", "type": "Edm.GeographyPoint"},
          {"name": "Secret", "type": "Edm.String", "retrievable": false, "filterable": false},
          {"name": "Rooms", "type": "Collection(Edm.ComplexType)", "fields": [
            {"name": "Type", "type": "Edm.String"}, {"name": "BaseRate", "type": "Edm.Double"}]}
        ]}
        """;
    private static final String DOCUMENTS = """
        {"value": [
          {"HotelId": "1", "HotelName": "Stay-Kay City Hotel", "Description": "Classic hotel in the heart of the city",
           "Category": "Boutique", "Tags": ["view", "air conditioning", "concierge"], "Rating": 3.6, "Parking": false,
           "Renovated": "2022-01-18T00:00:00Z", "Location": {"type": "Point", "coordinates": [-73.975403, 40.760586]},
           "Rooms": [{"Type": "Budget Room", "BaseRate": 99.0}, {"Type": "Deluxe Room", "BaseRate": 249.0}]},
          {"HotelId": "2", "HotelName": "Old Century Hotel", "Description": "Historic hotel with a pool and free wifi",
           "Category": "Boutique", "Tags": ["pool", "free wifi", "concierge"], "Rating": 3.6, "Parking": false,
           "Renovated": "2019-02-18T00:00:00Z", "Location": {"type": "Point", "coordinates": [-82.452843, 27.384417]},
           "Rooms": [{"Type": "Budget Room", "BaseRate": 79.0}]},
          {"HotelId": "3", "HotelName": "Gastronomic Landscape Hotel", "Description": "Culinary excellence and a bar",
           "Category": "Suite", "Tags": ["restaurant", "bar", "continental breakfast"], "Rating": 4.8,
           "Parking": true, "Renovated": "2015-09-20T00:00:00Z",
           "Location": {"type": "Point", "coordinates": [-84.362465, 33.846432]},
           "Rooms": [{"Type": "Suite", "BaseRate": 299.0}]},
          {"HotelId": "4", "HotelName": "Sublime Palace", "Description": "Luxury palace near the old town",
           "Category": "Luxury", "Tags": ["concierge", "view", "air conditioning"], "Rating": 4.6, "Parking": true,
           "Renovated": "2020-02-06T00:00:00Z", "Location": {"type": "Point", "coordinates": [-98.498217, 29.564182]},
           "Rooms": []},
          {"HotelId": "5", "HotelName": "Nowhere Inn", "Description": null, "Category": null, "Tags": [],
           "Rating": null, "Secret": "hidden"}
        ]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        admin().body(HOTELS).post(SERVICE + "/indexes" + API).then().statusCode(201);
        admin().body(DOCUMENTS).post(SERVICE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse search(String body) {
        return admin().body(body).post(SERVICE + "/indexes('hotels')/docs/search.post.search" + API).then();
    }

    private static List<String> keys(String body) {
        return search(body).statusCode(200).extract().jsonPath().getList("value.HotelId");
    }

    @Test
    void aTermQueryReturnsScoredMatchesBestFirst() {
        search("{\"search\": \"pool\"}").statusCode(200)
            .body("'@odata.context'", endsWith("/query-search/indexes('hotels')/$metadata#docs(*)"))
            .body("value.HotelId", contains("2"))
            .body("value[0].'@search.score'", greaterThan(0f));
        List<Float> scores = search("{\"search\": \"hotel\"}").statusCode(200).extract().jsonPath()
            .getList("value.'@search.score'", Float.class);
        for (int index = 1; index < scores.size(); index++) {
            assertTrue(scores.get(index - 1) >= scores.get(index), "scores must be descending: " + scores);
        }
        assertEquals(List.of("1", "2", "3"), keys("{\"search\": \"hotel\", \"orderby\": \"HotelId\"}"));
    }

    @Test
    void anEmptySearchMatchesEverythingWithScoreOne() {
        search("{\"search\": \"*\", \"count\": true}").statusCode(200)
            .body("'@odata.count'", equalTo(5))
            .body("value", hasSize(5))
            .body("value.'@search.score'", everyItem(equalTo(1.0f)));
        search("{}").statusCode(200).body("value", hasSize(5));
        search("{\"filter\": \"\", \"orderby\": \"\"}").statusCode(200).body("value", hasSize(5));
    }

    @Test
    void searchModeDecidesBetweenAnyAndAll() {
        assertEquals(List.of("1", "2", "3", "4"), sorted(keys("{\"search\": \"pool palace hotel\"}")));
        assertEquals(List.of("2"), keys("{\"search\": \"pool hotel\", \"searchMode\": \"all\"}"));
        assertEquals(List.of("2"), keys("{\"search\": \"hotel +pool\"}"));
        assertEquals(List.of("1", "3"), sorted(keys("{\"search\": \"hotel -pool\", \"searchMode\": \"all\"}")));
        assertEquals(List.of("1", "3", "4", "5"), sorted(keys("{\"search\": \"-pool\"}")));
    }

    @Test
    void phrasesAndPrefixesMatch() {
        assertEquals(List.of("2"), keys("{\"search\": \"\\\"free wifi\\\"\"}"));
        assertEquals(List.of(), keys("{\"search\": \"\\\"wifi free\\\"\"}"));
        search("{\"search\": \"lux*\"}").statusCode(200)
            .body("value.HotelId", contains("4"))
            .body("value[0].'@search.score'", equalTo(1.0f));
    }

    @Test
    void searchFieldsScopeTheQuery() {
        assertEquals(List.of("1", "2", "3"), sorted(keys("{\"search\": \"hotel\", \"searchFields\": \"HotelName\"}")));
        assertEquals(List.of("1", "2"), sorted(keys("{\"search\": \"hotel\", \"searchFields\": \"Description\"}")));
        search("{\"search\": \"hotel\", \"searchFields\": \"Rating\"}").statusCode(400);
        search("{\"search\": \"hotel\", \"searchFields\": \"Nope\"}").statusCode(400);
    }

    @Test
    void filtersSelectDocuments() {
        Map<String, List<String>> expectations = Map.ofEntries(
            Map.entry("Rating gt 4", List.of("3", "4")),
            Map.entry("Rating ge 3.6 and Parking eq false", List.of("1", "2")),
            Map.entry("Category eq 'Boutique' or Category eq 'Luxury'", List.of("1", "2", "4")),
            Map.entry("not (Category eq 'Boutique')", List.of("3", "4", "5")),
            Map.entry("Category ne null", List.of("1", "2", "3", "4")),
            Map.entry("Rating eq null", List.of("5")),
            Map.entry("search.in(Category, 'Suite,Luxury', ',')", List.of("3", "4")),
            Map.entry("Tags/any(t: t eq 'pool')", List.of("2")),
            Map.entry("Tags/any()", List.of("1", "2", "3", "4")),
            Map.entry("not Tags/any()", List.of("5")),
            Map.entry("Tags/all(t: t ne 'concierge')", List.of("3", "5")),
            Map.entry("Rooms/any(r: r/BaseRate lt 100 and r/Type eq 'Budget Room')", List.of("1", "2")),
            Map.entry("Renovated ge 2020-01-01T00:00:00Z", List.of("1", "4")),
            Map.entry("Parking", List.of("3", "4")),
            Map.entry("geo.distance(Location, geography'POINT(-73.98 40.76)') le 10", List.of("1")),
            Map.entry("geo.intersects(Location, geography'POLYGON((-100 25, -80 25, -80 35, -100 35, -100 25))')",
                List.of("2", "3", "4")),
            Map.entry("search.ismatch('pool')", List.of("2")),
            Map.entry("search.ismatch('palace', 'HotelName') or Rating lt 3.7", List.of("1", "2", "4")));
        expectations.forEach((filter, expected) -> assertEquals(expected,
            sorted(keys("{\"filter\": \"" + filter.replace("'", "\\u0027") + "\"}")), filter));
    }

    @Test
    void invalidFiltersAre400() {
        for (String filter : List.of("Secret eq 'x'", "Nope eq 1", "Rating gt null", "Tags eq 'pool'",
                "Tags/any(t: t ne 'pool')", "Rating eq 'high'", "Rating gt", "Rooms/any(r: search.ismatch('x'))")) {
            search("{\"filter\": \"" + filter.replace("'", "\\u0027") + "\"}").statusCode(400)
                .body("error.code", equalTo("InvalidRequestParameter"));
        }
    }

    @Test
    void searchIsMatchScoringContributesTheScore() {
        search("{\"filter\": \"search.ismatchscoring('pool')\"}").statusCode(200)
            .body("value.HotelId", contains("2"))
            .body("value[0].'@search.score'", greaterThan(0f));
    }

    @Test
    void orderBySortsWithNullsFirstAscending() {
        assertEquals(List.of("5", "1", "2", "4", "3"), keys("{\"orderby\": \"Rating asc, HotelId\"}"));
        assertEquals(List.of("3", "4", "1", "2", "5"), keys("{\"orderby\": \"Rating desc, HotelId asc\"}"));
        assertEquals(List.of("3", "5", "2", "1", "4"), keys("{\"orderby\": \"HotelName\"}"));
        assertEquals(List.of("1", "3", "2", "4", "5"),
            keys("{\"orderby\": \"geo.distance(Location, geography'POINT(-73.98 40.76)') asc\"}"));
        search("{\"orderby\": \"Tags\"}").statusCode(400);
        search("{\"orderby\": \"Description\"}").statusCode(200);
        search("{\"orderby\": \"Secret\"}").statusCode(200);
    }

    @Test
    void selectProjectsRetrievableFields() {
        search("{\"search\": \"pool\", \"select\": \"HotelName, Rating\"}").statusCode(200)
            .body("value[0].HotelName", equalTo("Old Century Hotel"))
            .body("value[0].Rating", equalTo(3.6f))
            .body("value[0]", not(hasKey("HotelId")))
            .body("value[0]", hasKey("@search.score"));
        search("{\"search\": \"pool\"}").statusCode(200)
            .body("value[0]", not(hasKey("Secret")))
            .body("value[0].Rooms[0].Type", equalTo("Budget Room"));
        search("{\"select\": \"Secret\"}").statusCode(400);
    }

    @Test
    void topSkipAndCountPage() {
        search("{\"orderby\": \"HotelId\", \"top\": 2, \"skip\": 1, \"count\": true}").statusCode(200)
            .body("'@odata.count'", equalTo(5))
            .body("value.HotelId", contains("2", "3"))
            .body("$", not(hasKey("@search.nextPageParameters")));
        search("{\"skip\": 100001}").statusCode(400);
        search("{\"top\": -1}").statusCode(400);
    }

    @Test
    void getSearchTakesTheSameParameters() {
        admin().queryParam("api-version", "2024-07-01").queryParam("search", "hotel")
            .queryParam("$filter", "Rating gt 4").queryParam("$orderby", "HotelId")
            .queryParam("$select", "HotelId,Rating").queryParam("$count", "true").queryParam("searchMode", "any")
            .get(SERVICE + "/indexes('hotels')/docs")
            .then().statusCode(200)
            .body("'@odata.count'", equalTo(1))
            .body("value.HotelId", contains("3"))
            .body("value[0]", not(hasKey("HotelName")));
    }

    @Test
    void featuresOfLaterStagesAreClearlyMarked() {
        search("{\"search\": \"hotel\", \"queryType\": \"semantic\"}").statusCode(400);
        search("{\"search\": \"hotel\", \"scoringProfile\": \"missing\"}").statusCode(400);
        search("{\"search\": \"hotel\", \"facets\": [\"Category,count:5\"]}").statusCode(200)
            .body("'@search.facets'.Category.value", contains("Boutique", "Suite"));
        search("{\"search\": \"hotel\", \"searchMode\": \"sometimes\"}").statusCode(400);
        search("{\"search\": \"hotel\", \"queryType\": \"fancy\"}").statusCode(400);
        search("{\"search\": \"hotel\", \"top\": \"two\"}").statusCode(400);
    }

    @Test
    void largeResultSetsArePagedOnTheServer() {
        List<String> documents = new ArrayList<>();
        for (int index = 100; index < 160; index++) {
            documents.add("{\"HotelId\":\"" + index + "\",\"HotelName\":\"Paged Hotel " + index + "\"}");
        }
        admin().body("{\"value\":[" + String.join(",", documents) + "]}")
            .post(SERVICE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);

        JsonPath first = search("{\"search\": \"paged\", \"count\": true}").statusCode(200).extract().jsonPath();
        assertEquals(60, first.getInt("'@odata.count'"));
        assertEquals(50, first.getList("value").size());
        assertEquals(50, first.getInt("'@search.nextPageParameters'.skip"));
        assertEquals("paged", first.getString("'@search.nextPageParameters'.search"));
        assertTrue(first.getString("'@odata.nextLink'").endsWith(
            "/query-search/indexes('hotels')/docs/search.post.search?api-version=2024-07-01"));

        Map<String, Object> next = first.getMap("'@search.nextPageParameters'");
        JsonPath second = admin().body(next).post(SERVICE + "/indexes('hotels')/docs/search.post.search" + API)
            .then().statusCode(200).extract().jsonPath();
        assertEquals(10, second.getList("value").size());
        assertNull(second.get("'@search.nextPageParameters'"));

        admin().get(SERVICE + "/indexes('hotels')/docs?api-version=2024-07-01&search=paged").then().statusCode(200)
            .body("value", hasSize(50))
            .body("'@odata.nextLink'", containsString("%24skip=50"));
        search("{\"search\": \"paged\", \"top\": 55}").statusCode(200)
            .body("value", hasSize(55))
            .body("$", not(hasKey("@odata.nextLink")));
    }

    @Test
    void aTopAboveAThousandIsServedInPages() {
        for (int batch = 0; batch < 2; batch++) {
            List<String> documents = new ArrayList<>();
            for (int index = 0; index < 600; index++) {
                documents.add("{\"HotelId\":\"big-" + batch + "-" + index + "\",\"HotelName\":\"Big\"}");
            }
            admin().body("{\"value\":[" + String.join(",", documents) + "]}")
                .post(SERVICE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
        }
        search("{\"search\": \"big\", \"top\": 1100}").statusCode(200)
            .body("value", hasSize(1000))
            .body("'@search.nextPageParameters'.skip", equalTo(1000))
            .body("'@search.nextPageParameters'.top", equalTo(100));
        search("{\"search\": \"big\", \"top\": 100, \"skip\": 1000}").statusCode(200)
            .body("value", hasSize(100))
            .body("'@search.nextPageParameters'", nullValue());
    }

    @Test
    void responsesWithoutMetadataOmitTheContext() {
        admin().header("Accept", "application/json;odata.metadata=none").body("{\"search\":\"pool\"}")
            .post(SERVICE + "/indexes('hotels')/docs/search.post.search" + API).then().statusCode(200)
            .body("$", not(hasKey("@odata.context")))
            .header("Content-Type", containsString("odata.metadata=none"));
    }

    @Test
    void collectionValuesAreSearchable() {
        search("{\"search\": \"concierge\"}").statusCode(200)
            .body("value.HotelId", containsInAnyOrder("1", "2", "4"));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(null);
        return copy;
    }
}
