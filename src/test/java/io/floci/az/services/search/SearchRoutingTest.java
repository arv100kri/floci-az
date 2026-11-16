package io.floci.az.services.search;

import io.floci.az.services.search.dataplane.SearchDispatchTable;
import io.floci.az.services.search.security.SearchOperation;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Search data plane: routing, api-version and request ids")
class SearchRoutingTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String INDEX = "{\"name\":\"hotels\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\","
        + "\"key\":true}]}";

    @Inject
    SearchDispatchTable dispatchTable;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    @Test
    void pathStyleAndHostStyleReachTheSameService() {
        admin().body(INDEX).post("/routing-search/indexes" + API).then().statusCode(201)
            .body("name", equalTo("hotels"));
        admin().header("Host", "routing.search.windows.net").get("/indexes('hotels')" + API).then().statusCode(200)
            .body("name", equalTo("hotels"))
            .body("'@odata.context'", equalTo("http://routing.search.windows.net/$metadata#indexes/$entity"));
        admin().get("/routing-search/indexes/hotels" + API).then().statusCode(200)
            .body("'@odata.context'", startsWith("http://localhost:"))
            .body("'@odata.context'", equalTo(baseUri() + "/routing-search/$metadata#indexes/$entity"));
    }

    @Test
    void theHostServiceMarkerFormRoutesToo() {
        admin().body(INDEX).header("Host", "marker.search.localhost").post("/indexes" + API).then().statusCode(201);
        admin().get("/marker-search/indexes('hotels')" + API).then().statusCode(200);
    }

    @Test
    void aMissingApiVersionIsA400() {
        admin().get("/svc-search/indexes").then().statusCode(400)
            .body("error.code", equalTo("MissingApiVersionParameter"))
            .body("error.message", equalTo("Invalid or missing api-version query string parameter."));
    }

    @Test
    void anUnknownApiVersionIsA400() {
        admin().get("/svc-search/indexes?api-version=2099-01-01").then().statusCode(400)
            .body("error.code", equalTo("InvalidApiVersionParameter"));
    }

    @Test
    void stableAndPreviewVersionsTheSdksSendAreAccepted() {
        for (String version : new String[] {"2023-11-01", "2024-07-01", "2025-09-01", "2024-05-01-preview",
            "2025-11-01-Preview", "2026-10-01"}) {
            admin().get("/svc-search/indexes?api-version=" + version).then().statusCode(200);
        }
    }

    @Test
    void requestIdEchoesTheClientRequestId() {
        admin().header("client-request-id", "9c4d50ee-2d56-4cd3-8152-34347dc9f2b0")
            .header("return-client-request-id", "true")
            .get("/svc-search/indexes" + API).then().statusCode(200)
            .header("request-id", equalTo("9c4d50ee-2d56-4cd3-8152-34347dc9f2b0"))
            .header("client-request-id", equalTo("9c4d50ee-2d56-4cd3-8152-34347dc9f2b0"))
            .header("elapsed-time", notNullValue());
        admin().header("x-ms-client-request-id", "00000000-0000-0000-0000-000000000001")
            .get("/svc-search/indexes" + API).then().statusCode(200)
            .header("x-ms-client-request-id", equalTo("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void unknownPathsAre404AndWrongMethodsAre405() {
        admin().get("/svc-search/nothing-here" + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        admin().patch("/svc-search/indexes" + API).then().statusCode(405)
            .body("error.code", equalTo("MethodNotAllowed"));
    }

    @Test
    void featuresNotEmulatedYetAnswer501() {
        admin().get("/svc-search/synonymmaps" + API).then().statusCode(501)
            .body("error.code", equalTo("NotImplemented"));
        admin().get("/svc-search/indexers('x')/search.status" + API).then().statusCode(501)
            .body("error.code", equalTo("NotImplemented"));
        admin().body("{}").post("/svc-search/knowledgebases('kb')/retrieve" + API).then().statusCode(501);
    }

    @Test
    void everyDataPlaneOperationHasARoute() {
        Set<SearchOperation> routed = EnumSet.noneOf(SearchOperation.class);
        dispatchTable.routes().forEach(route -> routed.add(route.operation()));
        assertEquals(EnumSet.allOf(SearchOperation.class), routed);
    }

    @Test
    void quotedSegmentsAreKeysNotActions() {
        admin().body(INDEX).post("/svc-search/indexes" + API).then().statusCode(201);
        admin().get("/svc-search/indexes('hotels')/docs('$count')" + API).then().statusCode(404)
            .body("error.message", equalTo("Document not found."));
        admin().get("/svc-search/indexes('hotels')/docs/$count" + API).then().statusCode(200);
    }

    private static String baseUri() {
        return "http://localhost:" + RestAssured.port;
    }
}
