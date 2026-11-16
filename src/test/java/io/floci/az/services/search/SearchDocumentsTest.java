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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Search data plane: document batches, lookup and count")
class SearchDocumentsTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BASE = "/docsvc-search/indexes('hotels')";
    private static final String HOTELS = """
        {"name": "hotels", "fields": [
          {"name": "HotelId", "type": "Edm.String", "key": true},
          {"name": "HotelName", "type": "Edm.String"},
          {"name": "Secret", "type": "Edm.String", "retrievable": false},
          {"name": "Rating", "type": "Edm.Double"},
          {"name": "Rooms", "type": "Edm.Int32"},
          {"name": "Parking", "type": "Edm.Boolean"},
          {"name": "Renovated", "type": "Edm.DateTimeOffset"},
          {"name": "Tags", "type": "Collection(Edm.String)"},
          {"name": "Location", "type": "Edm.GeographyPoint"},
          {"name": "Address", "type": "Edm.ComplexType", "fields": [
            {"name": "City", "type": "Edm.String"}, {"name": "Country", "type": "Edm.String"}]}
        ]}
        """;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        admin().body(HOTELS).post("/docsvc-search/indexes" + API).then().statusCode(201);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse batch(String body) {
        return admin().body(body).post(BASE + "/docs/search.index" + API).then();
    }

    @Test
    void uploadCreatesThenReplaces() {
        batch("{\"value\":[{\"@search.action\":\"upload\",\"HotelId\":\"1\",\"HotelName\":\"Alpha\"},"
            + "{\"HotelId\":\"2\",\"HotelName\":\"Beta\"}]}")
            .statusCode(200)
            .body("value.key", contains("1", "2"))
            .body("value.status", contains(true, true))
            .body("value.statusCode", contains(201, 201))
            .body("value[0]", hasKey("errorMessage"))
            .body("value[0].errorMessage", nullValue());
        batch("{\"value\":[{\"@search.action\":\"upload\",\"HotelId\":\"1\",\"Rating\":4.5}]}")
            .statusCode(200).body("value[0].statusCode", equalTo(200));
        admin().get(BASE + "/docs('1')" + API).then().statusCode(200)
            .body("HotelName", nullValue())
            .body("Rating", equalTo(4.5f));
    }

    @Test
    void aBatchWithAFailedActionIs207() {
        batch("{\"value\":[{\"HotelId\":\"1\"},{\"@search.action\":\"merge\",\"HotelId\":\"missing\",\"Rating\":1},"
            + "{\"@search.action\":\"delete\",\"HotelId\":\"never-there\"}]}")
            .statusCode(207)
            .body("value.statusCode", contains(201, 404, 200))
            .body("value[1].status", equalTo(false))
            .body("value[1].errorMessage", equalTo("Document not found."));
    }

    @Test
    void mergeReplacesCollectionsMergesComplexObjectsAndClearsNulls() {
        batch("{\"value\":[{\"HotelId\":\"1\",\"HotelName\":\"Alpha\",\"Rating\":3.0,\"Tags\":[\"budget\"],"
            + "\"Address\":{\"City\":\"Paris\",\"Country\":\"France\"}}]}").statusCode(200);
        batch("{\"value\":[{\"@search.action\":\"merge\",\"HotelId\":\"1\",\"Tags\":[\"economy\",\"pool\"],"
            + "\"Address\":{\"City\":\"Lyon\"},\"Rating\":null}]}")
            .statusCode(200).body("value[0].statusCode", equalTo(200));
        admin().get(BASE + "/docs('1')" + API).then().statusCode(200)
            .body("HotelName", equalTo("Alpha"))
            .body("Tags", contains("economy", "pool"))
            .body("Address.City", equalTo("Lyon"))
            .body("Address.Country", equalTo("France"))
            .body("Rating", nullValue());
    }

    @Test
    void mergeOrUploadUploadsNewDocumentsAndMergesExistingOnes() {
        batch("{\"value\":[{\"@search.action\":\"mergeOrUpload\",\"HotelId\":\"1\",\"HotelName\":\"Alpha\"}]}")
            .statusCode(200).body("value[0].statusCode", equalTo(201));
        batch("{\"value\":[{\"@search.action\":\"mergeOrUpload\",\"HotelId\":\"1\",\"Rating\":2}]}")
            .statusCode(200).body("value[0].statusCode", equalTo(200));
        admin().get(BASE + "/docs/1" + API).then().statusCode(200)
            .body("HotelName", equalTo("Alpha"))
            .body("Rating", equalTo(2.0f));
    }

    @Test
    void deleteRemovesTheDocument() {
        batch("{\"value\":[{\"HotelId\":\"1\"},{\"HotelId\":\"2\"}]}").statusCode(200);
        batch("{\"value\":[{\"@search.action\":\"delete\",\"HotelId\":\"1\",\"HotelName\":\"ignored\"}]}")
            .statusCode(200).body("value[0].statusCode", equalTo(200));
        admin().get(BASE + "/docs('1')" + API).then().statusCode(404);
        admin().get(BASE + "/docs/$count" + API).then().statusCode(200)
            .contentType(containsString("text/plain"))
            .body(equalTo("1"));
    }

    @Test
    void lookupReturnsRetrievableFieldsWithNullsAndHonoursSelect() {
        batch("{\"value\":[{\"HotelId\":\"1\",\"HotelName\":\"Alpha\",\"Secret\":\"s\"}]}").statusCode(200);
        admin().get(BASE + "/docs('1')" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("/docsvc-search/indexes('hotels')/$metadata#docs(*)/$entity"))
            .body("HotelId", equalTo("1"))
            .body("$", not(hasKey("Secret")))
            .body("$", hasKey("Rating"))
            .body("Rating", nullValue())
            .body("Tags", empty())
            .body("Address", nullValue());
        admin().get(BASE + "/docs('1')?api-version=2024-07-01&$select=HotelName,Address/City").then().statusCode(200)
            .body("HotelName", equalTo("Alpha"))
            .body("$", not(hasKey("HotelId")))
            .body("$", hasKey("Address"));
        admin().get(BASE + "/docs('1')?api-version=2024-07-01&$select=Secret").then().statusCode(400);
        admin().header("Accept", "application/json;odata.metadata=none").get(BASE + "/docs('1')" + API).then()
            .statusCode(200).body("$", not(hasKey("@odata.context")));
    }

    @Test
    void valuesAreConvertedToTheirEdmTypes() {
        batch("{\"value\":[{\"HotelId\":\"1\",\"Parking\":\"false\",\"Rooms\":\"12\",\"Rating\":\"NaN\","
            + "\"Renovated\":\"2024-01-13T14:03:00-08:00\",\"Location\":{\"type\":\"Point\","
            + "\"coordinates\":[-122.13,47.67]}}]}").statusCode(200);
        admin().get(BASE + "/docs('1')" + API).then().statusCode(200)
            .body("Parking", equalTo(false))
            .body("Rooms", equalTo(12))
            .body("Rating", equalTo("NaN"))
            .body("Renovated", equalTo("2024-01-13T22:03:00Z"))
            .body("Location.type", equalTo("Point"))
            .body("Location.coordinates", contains(-122.13f, 47.67f))
            .body("Location.crs.properties.name", equalTo("EPSG:4326"));
    }

    @Test
    void documentsThatDoNotFitTheSchemaFailTheRequest() {
        batch("{\"value\":[{\"HotelId\":\"1\",\"Unknown\":\"x\"}]}").statusCode(400)
            .body("error.message", containsString("The property 'Unknown' does not exist"));
        batch("{\"value\":[{\"HotelId\":\"1\",\"Rooms\":\"many\"}]}").statusCode(400);
        batch("{\"value\":[{\"HotelId\":\"1\",\"Rooms\":3000000000}]}").statusCode(400);
        batch("{\"value\":[{\"HotelId\":\"1\",\"Renovated\":\"yesterday\"}]}").statusCode(400);
        batch("{\"value\":[{\"HotelId\":\"1\",\"Tags\":[\"a\",null]}]}").statusCode(400);
        batch("{\"value\":[{\"HotelName\":\"no key\"}]}").statusCode(400)
            .body("error.message", containsString("Document key cannot be missing or empty"));
        batch("{\"value\":[{\"@search.action\":\"replace\",\"HotelId\":\"1\"}]}").statusCode(400);
        batch("{\"value\":[]}").statusCode(400);
        admin().get(BASE + "/docs/$count" + API).then().body(equalTo("0"));
    }

    @Test
    void anInvalidKeyFailsOnlyItsAction() {
        batch("{\"value\":[{\"HotelId\":\"ok-1\"},{\"HotelId\":\"not ok!\"},{\"HotelId\":\"_hidden\"}]}")
            .statusCode(207)
            .body("value.statusCode", contains(201, 400, 400))
            .body("value[1].errorMessage", containsString("Invalid document key"));
    }

    @Test
    void moreThanAThousandActionsAre413() {
        List<String> actions = new ArrayList<>();
        for (int index = 0; index <= 1000; index++) {
            actions.add("{\"HotelId\":\"" + index + "\"}");
        }
        batch("{\"value\":[" + String.join(",", actions) + "]}").statusCode(413)
            .body("error.code", equalTo("RequestEntityTooLarge"));
    }

    @Test
    void aNonJsonBodyIs415() {
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("text/plain")
            .body("HotelId=1").post(BASE + "/docs/search.index" + API).then().statusCode(415);
    }

    @Test
    void documentOperationsOnAMissingIndexAre404() {
        admin().get("/docsvc-search/indexes('nope')/docs/$count" + API).then().statusCode(404);
        admin().body("{\"value\":[{\"HotelId\":\"1\"}]}").post("/docsvc-search/indexes('nope')/docs/index" + API)
            .then().statusCode(404);
    }

    @Test
    void theShortActionPathsWork() {
        admin().body("{\"value\":[{\"HotelId\":\"1\",\"HotelName\":\"Alpha\"}]}")
            .post("/docsvc-search/indexes/hotels/docs/index" + API).then().statusCode(200);
        admin().body("{\"search\":\"alpha\"}").post("/docsvc-search/indexes/hotels/docs/search" + API).then()
            .statusCode(200).body("value[0].HotelId", equalTo("1"));
    }
}
