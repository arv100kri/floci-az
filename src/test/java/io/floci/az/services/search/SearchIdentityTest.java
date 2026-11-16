package io.floci.az.services.search;

import io.floci.az.services.entra.EntraStore;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.az.services.search.SearchIdentityFixtures.ACL_DOCUMENTS;
import static io.floci.az.services.search.SearchIdentityFixtures.ACL_INDEX;
import static io.floci.az.services.search.SearchIdentityFixtures.ALICE;
import static io.floci.az.services.search.SearchIdentityFixtures.API;
import static io.floci.az.services.search.SearchIdentityFixtures.READERS;
import static io.floci.az.services.search.SearchIdentityFixtures.SEARCH_SCOPE;
import static io.floci.az.services.search.SearchIdentityFixtures.TENANT;
import static io.floci.az.services.search.SearchIdentityFixtures.createService;
import static io.floci.az.services.search.SearchIdentityFixtures.devUserToken;
import static io.floci.az.services.search.SearchIdentityFixtures.serviceId;
import static io.floci.az.services.search.SearchIdentityFixtures.unsignedToken;
import static io.floci.az.services.search.SearchIdentityFixtures.userToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: authentication options and document-level access under compat parity")
class SearchIdentityTest {

    private static final String ADMIN = SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY;
    private static final String QUERY = SearchServiceRegistry.DEV_QUERY_KEY;
    private static final String SUBSCRIPTION = "eeeeeeee-5ea7-0000-0000-000000000001";
    private static final String TOKEN = "Bearer " + unsignedToken("{\"oid\":\"" + ALICE + "\"}");
    private static final String SIMPLE_INDEX = "{\"name\":\"docs\",\"fields\":[{\"name\":\"id\",\"type\":"
        + "\"Edm.String\",\"key\":true}]}";

    @Inject
    SearchServiceRegistry registry;

    @Inject
    GraphDirectoryStore directory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", ADMIN);
    }

    // ── authOptions and disableLocalAuth ────────────────────────────────────

    @Test
    void aNewServiceAcceptsKeysOnlyAndRefusesTokens() {
        String key = createService(serviceId(SUBSCRIPTION, "keysonly"), "{}");
        given().header("api-key", key).get("/keysonly-search/indexes" + API).then().statusCode(200);
        given().header("Authorization", TOKEN).get("/keysonly-search/indexes" + API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"))
            .body("error.message", containsString("apiKeyOnly"));
        given().get("/keysonly-search/indexes" + API).then().statusCode(401)
            .header("WWW-Authenticate", nullValue());
    }

    @Test
    void bothCredentialsAreAcceptedAndAFailureIsAChallengeByDefault() {
        String key = createService(serviceId(SUBSCRIPTION, "both"), "{\"authOptions\":{\"aadOrApiKey\":{}}}");
        given().header("api-key", key).get("/both-search/indexes" + API).then().statusCode(200);
        given().header("Authorization", TOKEN).get("/both-search/indexes" + API).then().statusCode(200);
        given().get("/both-search/indexes" + API).then().statusCode(401)
            .body("error.code", equalTo("Unauthorized"))
            .header("WWW-Authenticate", startsWith("Bearer authorization_uri=\""))
            .header("WWW-Authenticate", containsString("/" + TENANT + "/oauth2/v2.0/authorize\""))
            .header("WWW-Authenticate", containsString("resource=\"https://search.azure.com\""));
    }

    @Test
    void theChallengeModeIsExplicitToo() {
        createService(serviceId(SUBSCRIPTION, "challenge"),
            "{\"authOptions\":{\"aadOrApiKey\":{\"aadAuthFailureMode\":\"http401WithBearerChallenge\"}}}");
        given().get("/challenge-search/indexes" + API).then().statusCode(401)
            .header("WWW-Authenticate", containsString("resource=\"https://search.azure.com\""));
    }

    @Test
    void theHttp403ModeAnswersAFailureWithA403() {
        createService(serviceId(SUBSCRIPTION, "forbidden"),
            "{\"authOptions\":{\"aadOrApiKey\":{\"aadAuthFailureMode\":\"http403\"}}}");
        given().get("/forbidden-search/indexes" + API).then().statusCode(403)
            .body("error.code", equalTo("Forbidden"))
            .header("WWW-Authenticate", nullValue());
        given().header("Authorization", TOKEN).get("/forbidden-search/indexes" + API).then().statusCode(200);
    }

    @Test
    void anApiKeyWinsWhenARequestCarriesBoth() {
        createService(serviceId(SUBSCRIPTION, "keywins"), "{\"authOptions\":{\"aadOrApiKey\":{}}}");
        String queryKey = registry.find("keywins").orElseThrow().queryKeys().get(0).key();
        given().header("Authorization", TOKEN).header("api-key", queryKey)
            .get("/keywins-search/indexes" + API).then().statusCode(403)
            .body("error.message", containsString("query key"));
    }

    @Test
    void disablingLocalAuthRefusesEveryRequestWithAKey() {
        String path = serviceId(SUBSCRIPTION, "rolesonly");
        String key = createService(path, "{\"disableLocalAuth\":true}");
        given().header("api-key", key).get("/rolesonly-search/indexes" + API).then().statusCode(403);
        given().header("api-key", key).header("Authorization", TOKEN).get("/rolesonly-search/indexes" + API)
            .then().statusCode(403);
        given().header("Authorization", TOKEN).get("/rolesonly-search/indexes" + API).then().statusCode(200);
        given().get("/rolesonly-search/indexes" + API).then().statusCode(401)
            .header("WWW-Authenticate", containsString("resource=\"https://search.azure.com\""));
    }

    @Test
    void aDataPlaneOnlyServiceAcceptsKeysAndTokensWithoutRoles() {
        admin().contentType("application/json").body(SIMPLE_INDEX).post("/devsvc-search/indexes" + API)
            .then().statusCode(201);
        given().header("Authorization", TOKEN).contentType("application/json")
            .body("{\"value\":[{\"@search.action\":\"upload\",\"id\":\"1\"}]}")
            .post("/devsvc-search/indexes('docs')/docs/search.index" + API).then().statusCode(200);
        given().header("Authorization", TOKEN).delete("/devsvc-search/indexes('docs')" + API).then().statusCode(204);
        given().get("/devsvc-search/indexes" + API).then().statusCode(401)
            .header("WWW-Authenticate", containsString("resource=\"https://search.azure.com\""));
    }

    @Test
    void aSignedTokenForAnotherAudienceIsAcceptedWhileTokensAreNotValidated() {
        given().auth().oauth2(SearchIdentityFixtures.appToken("https://vault.azure.net/.default"))
            .get("/lenient-search/indexes" + API).then().statusCode(200);
    }

    // ── Document-level access ───────────────────────────────────────────────

    private void aclIndex(String service) {
        admin().contentType("application/json").body(ACL_INDEX).post("/" + service + "-search/indexes" + API)
            .then().statusCode(201);
        admin().contentType("application/json").body(ACL_DOCUMENTS)
            .post("/" + service + "-search/indexes('acl')/docs/search.index" + API).then().statusCode(200);
    }

    private static List<String> search(RequestSpecification request, String service) {
        return request.contentType("application/json").body("{\"search\":\"*\",\"count\":true}")
            .post("/" + service + "-search/indexes('acl')/docs/search.post.search" + API)
            .then().statusCode(200).extract().path("value.id");
    }

    @Test
    void theIndexKeepsItsPermissionSettings() {
        aclIndex("aclget");
        admin().get("/aclget-search/indexes('acl')" + API).then().statusCode(200)
            .body("permissionFilterOption", equalTo("enabled"))
            .body("fields.find { it.name == 'oid' }.permissionFilter", equalTo("userIds"))
            .body("fields.find { it.name == 'group' }.permissionFilter", equalTo("groupIds"));
    }

    @Test
    void aReadWithoutAnEndUserSeesOnlyWhatIsGrantedToAll() {
        aclIndex("acl1");
        assertThat(search(admin(), "acl1"), containsInAnyOrder("2"));
        assertThat(search(given().header("api-key", QUERY), "acl1"),
            containsInAnyOrder("2"));
        assertThat(search(given().header("Authorization", TOKEN), "acl1"),
            containsInAnyOrder("2"));
    }

    @Test
    void theEndUserSeesItsOwnDocumentsAndThoseOfItsGroups() {
        aclIndex("acl2");
        assertThat(search(admin().header("x-ms-query-source-authorization",
            userToken(ALICE)), "acl2"), containsInAnyOrder("1", "2"));
        String inReaders = unsignedToken("{\"oid\":\"" + ALICE + "\",\"groups\":[\"" + READERS + "\"]}");
        assertThat(search(given().header("api-key", QUERY)
            .header("x-ms-query-source-authorization", "Bearer " + inReaders), "acl2"),
            containsInAnyOrder("1", "2", "3"));
    }

    @Test
    void groupsComeFromTheDirectoryTransitively() {
        aclIndex("acl3");
        directory.put(GraphDirectoryStore.group(directory.defaultTenantId(), READERS, "Readers", true));
        given().contentType("application/json")
            .body("{\"@odata.id\":\"https://graph.microsoft.com/v1.0/directoryObjects/"
                + EntraStore.DEV_GROUP_OBJECT_ID + "\"}")
            .when().post("/v1.0/groups/{id}/members/$ref", READERS).then().statusCode(204);
        // The dev user is a member of the dev group, which is now a member of the readers group.
        assertThat(search(admin().header("x-ms-query-source-authorization",
            devUserToken(SEARCH_SCOPE)), "acl3"), containsInAnyOrder("2", "3"));
    }

    @Test
    void countLookupAndFiltersAreTrimmedToo() {
        aclIndex("acl4");
        String alice = userToken(ALICE);
        admin().get("/acl4-search/indexes('acl')/docs/$count" + API).then().statusCode(200).body(equalTo("1"));
        admin().header("x-ms-query-source-authorization", alice).get("/acl4-search/indexes('acl')/docs/$count" + API)
            .then().statusCode(200).body(equalTo("2"));
        admin().header("x-ms-query-source-authorization", alice).get("/acl4-search/indexes('acl')/docs('1')" + API)
            .then().statusCode(200).body("title", equalTo("alice report"));
        admin().header("x-ms-query-source-authorization", alice).get("/acl4-search/indexes('acl')/docs('5')" + API)
            .then().statusCode(404);
        admin().get("/acl4-search/indexes('acl')/docs('1')" + API).then().statusCode(404);
        admin().header("x-ms-query-source-authorization", alice).contentType("application/json")
            .body("{\"search\":\"report\",\"filter\":\"id ne '2'\",\"count\":true}")
            .post("/acl4-search/indexes('acl')/docs/search.post.search" + API).then().statusCode(200)
            .body("'@odata.count'", equalTo(1))
            .body("value.id", containsInAnyOrder("1"));
    }

    @Test
    void anElevatedReadSeesEverythingWithAnAdminKeyButNotWithAQueryKey() {
        aclIndex("acl5");
        assertThat(search(admin().header("x-ms-enable-elevated-read", "true"), "acl5"),
            containsInAnyOrder("1", "2", "3", "4", "5"));
        admin().header("x-ms-enable-elevated-read", "true").get("/acl5-search/indexes('acl')/docs/$count" + API)
            .then().statusCode(200).body(equalTo("5"));
        given().header("api-key", QUERY).header("x-ms-enable-elevated-read", "true").contentType("application/json")
            .body("{\"search\":\"*\"}").post("/acl5-search/indexes('acl')/docs/search.post.search" + API)
            .then().statusCode(403)
            .body("error.message", containsString("contentSecurity/elevatedOperations/read"));
        assertThat(search(given().header("Authorization", TOKEN)
            .header("x-ms-enable-elevated-read", "true"), "acl5"), containsInAnyOrder("1", "2", "3", "4", "5"));
    }

    @Test
    void aDisabledOptionOrAnRbacScopeOnlyIndexIsNotTrimmed() {
        admin().contentType("application/json").body(ACL_INDEX.replace("\"enabled\"", "\"disabled\""))
            .post("/acl6-search/indexes" + API).then().statusCode(201);
        admin().contentType("application/json").body(ACL_DOCUMENTS)
            .post("/acl6-search/indexes('acl')/docs/search.index" + API).then().statusCode(200);
        assertThat(search(admin(), "acl6"), containsInAnyOrder("1", "2", "3", "4", "5"));

        admin().contentType("application/json").body("{\"name\":\"scoped\",\"permissionFilterOption\":\"enabled\","
                + "\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"scope\",\"type\":\"Edm.String\",\"permissionFilter\":\"rbacScope\"}]}")
            .post("/acl6-search/indexes" + API).then().statusCode(201);
        admin().contentType("application/json")
            .body("{\"value\":[{\"@search.action\":\"upload\",\"id\":\"1\",\"scope\":\"/subscriptions/s/"
                + "resourceGroups/r/providers/Microsoft.Storage/storageAccounts/a/blobServices/default/"
                + "containers/c\"}]}")
            .post("/acl6-search/indexes('scoped')/docs/search.index" + API).then().statusCode(200);
        admin().get("/acl6-search/indexes('scoped')/docs/$count" + API).then().statusCode(200).body(equalTo("1"));
    }

    @Test
    void permissionSettingsAreValidated() {
        String base = "{\"name\":\"bad\",\"fields\":[{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},";
        for (String invalid : List.of(
                base + "{\"name\":\"u\",\"type\":\"Collection(Edm.String)\",\"permissionFilter\":\"users\"}]}",
                base + "{\"name\":\"u\",\"type\":\"Edm.Int32\",\"permissionFilter\":\"userIds\"}]}",
                base + "{\"name\":\"u\",\"type\":\"Collection(Edm.String)\",\"filterable\":false,"
                    + "\"permissionFilter\":\"userIds\"}]}",
                base + "{\"name\":\"u\",\"type\":\"Collection(Edm.String)\",\"permissionFilter\":\"userIds\"},"
                    + "{\"name\":\"v\",\"type\":\"Collection(Edm.String)\",\"permissionFilter\":\"userIds\"}]}",
                base + "{\"name\":\"acl\",\"type\":\"Edm.ComplexType\",\"fields\":[{\"name\":\"u\","
                    + "\"type\":\"Edm.String\",\"permissionFilter\":\"userIds\"}]}]}",
                base.replace("\"bad\",", "\"bad\",\"permissionFilterOption\":\"on\",")
                    + "{\"name\":\"u\",\"type\":\"Edm.String\"}]}")) {
            admin().contentType("application/json").body(invalid).post("/aclbad-search/indexes" + API)
                .then().statusCode(400)
                .body("error.message", startsWith("The request is invalid. Details: definition : "));
        }
        admin().contentType("application/json")
            .body(base + "{\"name\":\"f\",\"type\":\"Collection(Edm.String)\",\"permissionFilter\":"
                + "\"oneLakeParentFolders\"}]}")
            .post("/aclbad-search/indexes" + API).then().statusCode(501);
    }

    @Test
    void aPermissionFilterCanBeAddedToAnExistingField() {
        admin().contentType("application/json").body(ACL_INDEX.replace(",\"permissionFilter\":\"groupIds\"", ""))
            .post("/acl7-search/indexes" + API).then().statusCode(201);
        admin().contentType("application/json").body(ACL_DOCUMENTS)
            .post("/acl7-search/indexes('acl')/docs/search.index" + API).then().statusCode(200);
        assertThat(search(admin().header("x-ms-query-source-authorization",
            "Bearer " + unsignedToken("{\"oid\":\"x\",\"groups\":[\"" + READERS + "\"]}")), "acl7"),
            containsInAnyOrder("2"));
        admin().contentType("application/json").body(ACL_INDEX).put("/acl7-search/indexes('acl')" + API)
            .then().statusCode(204);
        assertThat(search(admin().header("x-ms-query-source-authorization",
            "Bearer " + unsignedToken("{\"oid\":\"x\",\"groups\":[\"" + READERS + "\"]}")), "acl7"),
            containsInAnyOrder("2", "3"));
    }

    @Test
    void aRegisteredServiceWithoutAuthOptionsKeepsItsKeys() {
        registry.put(new SearchServiceInfo("legacy", "/subscriptions/s/resourceGroups/r/providers/Microsoft.Search/"
            + "searchServices/legacy", "primary-admin", "secondary-admin", List.of(new SearchQueryKey("", "q")),
            false, null, "enabled", null, null, "standard"));
        given().header("api-key", "primary-admin").get("/legacy-search/indexes" + API).then().statusCode(200);
        given().header("Authorization", TOKEN).get("/legacy-search/indexes" + API).then().statusCode(403);
    }
}
