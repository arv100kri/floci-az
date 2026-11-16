package io.floci.az.services.search;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.az.services.search.SearchIdentityFixtures.ACL_DOCUMENTS;
import static io.floci.az.services.search.SearchIdentityFixtures.ACL_INDEX;
import static io.floci.az.services.search.SearchIdentityFixtures.ALICE;
import static io.floci.az.services.search.SearchIdentityFixtures.API;
import static io.floci.az.services.search.SearchIdentityFixtures.BOB;
import static io.floci.az.services.search.SearchIdentityFixtures.READERS;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_INDEX_DATA_CONTRIBUTOR;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_INDEX_DATA_READER;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_SCOPE;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_SERVICE_CONTRIBUTOR;
import static io.floci.az.services.search.SearchIdentityFixtures.assignRole;
import static io.floci.az.services.search.SearchIdentityFixtures.createService;
import static io.floci.az.services.search.SearchIdentityFixtures.devUserToken;
import static io.floci.az.services.search.SearchIdentityFixtures.serviceId;
import static io.floci.az.services.search.SearchIdentityFixtures.unsignedToken;
import static io.floci.az.services.search.SearchIdentityFixtures.userToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(SearchStrictRbacTest.StrictRbac.class)
@DisplayName("Search data plane: parity-overrides.strict-rbac enforces role assignments on bearer callers")
class SearchStrictRbacTest {

    public static class StrictRbac implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-rbac", "true");
        }
    }

    private static final String SUBSCRIPTION = "eeeeeeee-5ea7-0000-0000-000000000002";
    private static final String RESOURCE_GROUP = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/search-identity";
    private static final String SERVICE = serviceId(SUBSCRIPTION, "rbacsvc");
    private static final String BASE = "/rbacsvc-search";
    private static final String INDEX = "{\"name\":\"%s\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\","
        + "\"key\":true},{\"name\":\"title\",\"type\":\"Edm.String\"}]}";
    private static final String UPLOAD = "{\"value\":[{\"@search.action\":\"upload\",\"id\":\"1\","
        + "\"title\":\"harbor view\"}]}";
    private static final String DOCUMENTS = "Microsoft.Search/searchServices/indexes/documents/";

    private String adminKey;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        adminKey = createService(SERVICE, "{\"authOptions\":{\"aadOrApiKey\":{}}}");
        for (String index : new String[] {"hotels", "motels"}) {
            given().header("api-key", adminKey).contentType("application/json").body(INDEX.formatted(index))
                .post(BASE + "/indexes" + API).then().statusCode(201);
            given().header("api-key", adminKey).contentType("application/json").body(UPLOAD)
                .post(BASE + "/indexes('" + index + "')/docs/search.index" + API).then().statusCode(200);
        }
    }

    private static RequestSpecification as(String oid) {
        return given().header("Authorization", "Bearer " + userToken(oid));
    }

    private static void search(RequestSpecification request, String index, int status) {
        request.contentType("application/json").body("{\"search\":\"*\"}")
            .post(BASE + "/indexes('" + index + "')/docs/search.post.search" + API).then().statusCode(status);
    }

    @Test
    void withoutARoleABearerCallerIsRefusedNamingThePermission() {
        as(ALICE).contentType("application/json").body("{\"search\":\"*\"}")
            .post(BASE + "/indexes('hotels')/docs/search.post.search" + API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"))
            .body("error.message", containsString("'" + DOCUMENTS + "read'"))
            .body("error.message", containsString("'" + SERVICE + "/indexes/hotels'"));
    }

    @Test
    void aSearchIndexDataReaderQueriesButCannotUploadOrReadDefinitions() {
        assignRole(SERVICE, SEARCH_INDEX_DATA_READER, ALICE, "User");
        search(as(ALICE), "hotels", 200);
        as(ALICE).get(BASE + "/indexes('hotels')/docs('1')" + API).then().statusCode(200)
            .body("title", equalTo("harbor view"));
        as(ALICE).get(BASE + "/indexes('motels')/docs/$count" + API).then().statusCode(200).body(equalTo("1"));
        as(ALICE).contentType("application/json").body(UPLOAD)
            .post(BASE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(403)
            .body("error.message", containsString("'" + DOCUMENTS + "write'"));
        as(ALICE).get(BASE + "/indexes" + API).then().statusCode(403)
            .body("error.message", containsString("'Microsoft.Search/searchServices/indexes/read'"));
        search(as(BOB), "hotels", 403);
    }

    @Test
    void aSearchIndexDataContributorUploadsAndQueries() {
        assignRole(SERVICE, SEARCH_INDEX_DATA_CONTRIBUTOR, ALICE, "User");
        as(ALICE).contentType("application/json").body(UPLOAD.replace("\"upload\"", "\"delete\""))
            .post(BASE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
        search(as(ALICE), "motels", 200);
        as(ALICE).delete(BASE + "/indexes('hotels')" + API).then().statusCode(403);
    }

    @Test
    void aSearchServiceContributorManagesObjectsButReadsNoDocuments() {
        assignRole(RESOURCE_GROUP, SEARCH_SERVICE_CONTRIBUTOR, ALICE, "User");
        as(ALICE).contentType("application/json").body(INDEX.formatted("inns")).post(BASE + "/indexes" + API)
            .then().statusCode(201);
        as(ALICE).get(BASE + "/indexes('hotels')" + API).then().statusCode(200);
        as(ALICE).get(BASE + "/indexes('hotels')/search.stats" + API).then().statusCode(200);
        as(ALICE).contentType("application/json").body("{\"text\":\"harbor view\",\"analyzer\":\"standard.lucene\"}")
            .post(BASE + "/indexes('hotels')/search.analyze" + API).then().statusCode(not(equalTo(403)));
        as(ALICE).get(BASE + "/servicestats" + API).then().statusCode(200);
        as(ALICE).delete(BASE + "/indexes('inns')" + API).then().statusCode(204);
        search(as(ALICE), "hotels", 403);
        as(ALICE).contentType("application/json").body(UPLOAD)
            .post(BASE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(403);
    }

    @Test
    void anIndexScopedAssignmentReachesThatIndexOnly() {
        assignRole(SERVICE + "/indexes/hotels", SEARCH_INDEX_DATA_READER, ALICE, "User");
        search(as(ALICE), "hotels", 200);
        search(as(ALICE), "motels", 403);
    }

    @Test
    void aGroupAssignmentReachesTheGroupsMembers() {
        assignRole(SERVICE, SEARCH_INDEX_DATA_READER, READERS, "Group");
        search(given().header("Authorization", "Bearer " + unsignedToken("{\"oid\":\"" + BOB + "\",\"groups\":[\""
            + READERS + "\"]}")), "hotels", 200);
        search(as(BOB), "hotels", 403);

        assignRole(SERVICE, SEARCH_INDEX_DATA_READER, EntraStore.DEV_GROUP_OBJECT_ID, "Group");
        search(given().auth().oauth2(devUserToken(SEARCH_SCOPE)), "motels", 200);
    }

    @Test
    void keysBypassRoles() {
        given().header("api-key", adminKey).contentType("application/json").body(UPLOAD)
            .post(BASE + "/indexes('hotels')/docs/search.index" + API).then().statusCode(200);
        given().header("api-key", adminKey).delete(BASE + "/indexes('motels')" + API).then().statusCode(204);
    }

    @Test
    void aServiceWithoutAnArmResourceHasNoScopeForARole() {
        assignRole(SERVICE, SEARCH_SERVICE_CONTRIBUTOR, ALICE, "User");
        as(ALICE).get("/devonly-search/indexes" + API).then().statusCode(403)
            .body("error.message", containsString("no Azure resource"));
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).get("/devonly-search/indexes" + API)
            .then().statusCode(200);
    }

    @Test
    void anElevatedReadNeedsItsOwnPermission() {
        given().header("api-key", adminKey).contentType("application/json").body(ACL_INDEX)
            .post(BASE + "/indexes" + API).then().statusCode(201);
        given().header("api-key", adminKey).contentType("application/json").body(ACL_DOCUMENTS)
            .post(BASE + "/indexes('acl')/docs/search.index" + API).then().statusCode(200);
        assignRole(SERVICE, SEARCH_INDEX_DATA_READER, ALICE, "User");
        assignRole(SERVICE, SEARCH_INDEX_DATA_CONTRIBUTOR, BOB, "User");

        as(ALICE).header("x-ms-query-source-authorization", userToken(ALICE)).contentType("application/json")
            .body("{\"search\":\"*\"}").post(BASE + "/indexes('acl')/docs/search.post.search" + API)
            .then().statusCode(200).body("value.id", containsInAnyOrder("1", "2"));
        as(ALICE).header("x-ms-enable-elevated-read", "true").contentType("application/json")
            .body("{\"search\":\"*\"}").post(BASE + "/indexes('acl')/docs/search.post.search" + API)
            .then().statusCode(403)
            .body("error.message", containsString("contentSecurity/elevatedOperations/read"));
        as(BOB).header("x-ms-enable-elevated-read", "true").contentType("application/json")
            .body("{\"search\":\"*\"}").post(BASE + "/indexes('acl')/docs/search.post.search" + API)
            .then().statusCode(200).body("value.id", containsInAnyOrder("1", "2", "3", "4", "5"));
    }
}
