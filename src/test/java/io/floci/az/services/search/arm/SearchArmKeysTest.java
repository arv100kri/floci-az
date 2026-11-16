package io.floci.az.services.search.arm;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.SearchServiceRegistry.SearchQueryKey;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.floci.az.services.search.arm.SearchArmServiceTest.API;
import static io.floci.az.services.search.arm.SearchArmServiceTest.STANDARD;
import static io.floci.az.services.search.arm.SearchArmServiceTest.patch;
import static io.floci.az.services.search.arm.SearchArmServiceTest.put;
import static io.floci.az.services.search.arm.SearchArmServiceTest.service;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Microsoft.Search ARM: admin and query keys, and the registry the data plane reads them from")
class SearchArmKeysTest {

    private static final String KEY = "[A-Za-z0-9]{52}";
    private static final String DATA_API = "?api-version=2024-07-01";

    @Inject
    SearchServiceRegistry registry;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static Map<String, String> adminKeys(String path) {
        return keys(given().when().post(path + "/listAdminKeys" + API).then().statusCode(200)
            .body("primaryKey", matchesPattern(KEY))
            .body("secondaryKey", matchesPattern(KEY))
            .extract());
    }

    private static Map<String, String> keys(ExtractableResponse<Response> response) {
        return Map.of("primaryKey", response.path("primaryKey"), "secondaryKey", response.path("secondaryKey"));
    }

    private static ExtractableResponse<Response> queryKeys(String path) {
        return given().when().post(path + "/listQueryKeys" + API).then().statusCode(200).extract();
    }

    @Test
    void aNewServiceHasTwoAdminKeysAndOneUnnamedQueryKey() {
        String path = service("sub-keys-new", "rg", "keys-new");
        put(path, STANDARD, 201);
        Map<String, String> admin = adminKeys(path);
        assertNotEquals(admin.get("primaryKey"), admin.get("secondaryKey"));

        given().when().post(path + "/listQueryKeys" + API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", nullValue())
            .body("value[0].key", matchesPattern(KEY))
            .body("nextLink", nullValue());
        given().when().get(path + "/listQueryKeys" + API).then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void theRegistryHoldsWhatArmCreated() {
        String path = service("sub-keys-reg", "rg", "keys-reg");
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"identity\":{\"type\":\"SystemAssigned\"},"
            + "\"properties\":{\"networkRuleSet\":{\"ipRules\":[{\"value\":\"10.0.0.0/8\"}]}}}", 201);
        Map<String, String> admin = adminKeys(path);
        String queryKey = queryKeys(path).path("value[0].key");

        SearchServiceInfo info = registry.find("KEYS-REG").orElseThrow();
        assertEquals("keys-reg", info.name());
        assertEquals(path, info.resourceId());
        assertEquals(admin.get("primaryKey"), info.primaryAdminKey());
        assertEquals(admin.get("secondaryKey"), info.secondaryAdminKey());
        assertEquals(List.of(new SearchQueryKey("", queryKey)), info.queryKeys());
        assertFalse(info.disableLocalAuth());
        assertTrue(info.authOptions().has("apiKeyOnly"));
        assertEquals("enabled", info.publicNetworkAccess());
        assertEquals("10.0.0.0/8", info.networkRuleSet().path("ipRules").get(0).path("value").asText());
        assertEquals("None", info.networkRuleSet().path("bypass").asText());
        assertEquals("SystemAssigned", info.identity().path("type").asText());
        assertEquals(given().when().get(path + API).then().extract().path("identity.principalId"),
            info.identity().path("principalId").asText());
        assertEquals("basic", info.sku());
    }

    @Test
    void theRegistryFollowsEveryUpdate() {
        String path = service("sub-keys-upd", "rg", "keys-upd");
        put(path, STANDARD, 201);
        patch(path, "{\"properties\":{\"disableLocalAuth\":true,\"publicNetworkAccess\":\"SecuredByPerimeter\"}}", 200);

        SearchServiceInfo info = registry.find("keys-upd").orElseThrow();
        assertTrue(info.disableLocalAuth());
        assertNull(info.authOptions());
        assertEquals("securedByPerimeter", info.publicNetworkAccess());

        given().contentType("application/json").body("{\"location\":\"westus\",\"sku\":{\"name\":\"standard2\"},"
                + "\"properties\":{\"authOptions\":{\"aadOrApiKey\":{\"aadAuthFailureMode\":\"http403\"}}}}")
            .when().put(path + API).then().statusCode(200);
        info = registry.find("keys-upd").orElseThrow();
        assertFalse(info.disableLocalAuth());
        assertEquals("http403", info.authOptions().path("aadOrApiKey").path("aadAuthFailureMode").asText());
        assertEquals("enabled", info.publicNetworkAccess());
        assertEquals("standard2", info.sku());
    }

    @Test
    void regeneratingAnAdminKeyReplacesItEverywhere() {
        String path = service("sub-keys-regen", "rg", "keys-regen");
        put(path, STANDARD, 201);
        Map<String, String> before = adminKeys(path);

        Map<String, String> primary = keys(given().when().post(path + "/regenerateAdminKey/primary" + API)
            .then().statusCode(200).extract());
        assertNotEquals(before.get("primaryKey"), primary.get("primaryKey"));
        assertEquals(before.get("secondaryKey"), primary.get("secondaryKey"));
        assertEquals(primary, adminKeys(path));

        Map<String, String> secondary = keys(given().when().post(path + "/regenerateAdminKey/Secondary" + API)
            .then().statusCode(200).extract());
        assertEquals(primary.get("primaryKey"), secondary.get("primaryKey"));
        assertNotEquals(primary.get("secondaryKey"), secondary.get("secondaryKey"));

        SearchServiceInfo info = registry.find("keys-regen").orElseThrow();
        assertTrue(info.isAdminKey(secondary.get("primaryKey")));
        assertTrue(info.isAdminKey(secondary.get("secondaryKey")));
        assertFalse(info.isAdminKey(before.get("primaryKey")));
        assertFalse(info.isAdminKey(before.get("secondaryKey")));
        given().header("api-key", secondary.get("primaryKey")).get("/keys-regen-search/indexes" + DATA_API).then()
            .statusCode(200);
        given().header("api-key", before.get("primaryKey")).get("/keys-regen-search/indexes" + DATA_API).then()
            .statusCode(403).body("error.code", equalTo("Forbidden"));
        given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).get("/keys-regen-search/indexes"
            + DATA_API).then().statusCode(403);

        given().when().post(path + "/regenerateAdminKey/tertiary" + API).then().statusCode(400)
            .body("error.code", equalTo("BadRequest"));
        given().when().get(path + "/regenerateAdminKey/primary" + API).then().statusCode(405);
    }

    @Test
    void queryKeysAreCreatedListedAndDeletedByValue() {
        String path = service("sub-keys-query", "rg", "keys-query");
        put(path, STANDARD, 201);
        String first = queryKeys(path).path("value[0].key");

        String created = given().when().post(path + "/createQueryKey/browser clients" + API).then().statusCode(200)
            .body("name", equalTo("browser clients"))
            .body("key", matchesPattern(KEY))
            .extract().path("key");
        given().when().post(path + "/listQueryKeys" + API).then()
            .body("value.name", equalTo(Arrays.asList(null, "browser clients")))
            .body("value.key", equalTo(List.of(first, created)));
        assertTrue(registry.find("keys-query").orElseThrow().isQueryKey(created));
        assertEquals("browser clients", registry.find("keys-query").orElseThrow().queryKeys().get(1).name());

        given().when().delete(path + "/deleteQueryKey/" + first + API).then().statusCode(204);
        given().when().post(path + "/listQueryKeys" + API).then()
            .body("value.key", equalTo(List.of(created)));
        assertFalse(registry.find("keys-query").orElseThrow().isQueryKey(first));
        assertTrue(registry.find("keys-query").orElseThrow().isQueryKey(created));

        given().when().delete(path + "/deleteQueryKey/" + first + API).then().statusCode(404);
    }

    @Test
    void aServiceHoldsAtMostFiftyQueryKeys() {
        String path = service("sub-keys-max", "rg", "keys-max");
        put(path, STANDARD, 201);
        for (int i = 1; i < 50; i++) {
            given().when().post(path + "/createQueryKey/k" + i + API).then().statusCode(200);
        }
        given().when().post(path + "/createQueryKey/one-too-many" + API).then().statusCode(400)
            .body("error.code", equalTo("BadRequest"));
        given().when().post(path + "/listQueryKeys" + API).then().body("value", hasSize(50));
    }

    @Test
    void keyActionsOnAMissingServiceAreNotFound() {
        String path = service("sub-keys-none", "rg", "keys-none");
        given().when().post(path + "/listAdminKeys" + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        given().when().post(path + "/regenerateAdminKey/primary" + API).then().statusCode(404);
        given().when().post(path + "/listQueryKeys" + API).then().statusCode(404);
        given().when().post(path + "/createQueryKey/k" + API).then().statusCode(404);
        given().when().delete(path + "/deleteQueryKey/k" + API).then().statusCode(404);
    }

    @Test
    void anArmServiceReplacesADataPlaneServiceOfTheSameNameUntilItIsDeleted() {
        SearchServiceInfo provisioned = registry.findOrProvision("dp-first");
        assertEquals(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY, provisioned.primaryAdminKey());
        assertNull(provisioned.resourceId());

        String path = service("sub-keys-dp", "rg", "dp-first");
        put(path, STANDARD, 201);
        SearchServiceInfo created = registry.findOrProvision("dp-first");
        assertEquals(path, created.resourceId());
        assertEquals(adminKeys(path).get("primaryKey"), created.primaryAdminKey());
        assertFalse(created.isAdminKey(SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY));

        given().when().delete(path + API).then().statusCode(200);
        assertTrue(registry.find("dp-first").isEmpty());
    }

    /**
     * The service an earlier run stored is served again, and the registry rebuilds it from the store, as after a
     * restart in a persistent storage mode: the stored entry is put back into the backend after a reset wiped both.
     */
    @Test
    void aServiceStoredByAnEarlierRunIsServedAndReachesTheRegistry() {
        String path = service("sub-keys-restart", "RG", "keys-restart");
        put(path, "{\"location\":\"westus\",\"sku\":{\"name\":\"basic\"},\"tags\":{\"k\":\"v\"}}", 201);
        given().when().post(path + "/createQueryKey/kept" + API).then().statusCode(200);
        Map<String, String> admin = adminKeys(path);
        List<Object> query = queryKeys(path).path("value.key");
        String eTag = given().when().get(path + API).then().extract().path("properties.eTag");

        StorageBackend<String, StoredObject> backend = storageFactory.create(SearchServiceStore.STORE);
        String key = path.toLowerCase(Locale.ROOT);
        StoredObject stored = backend.get(key).orElseThrow();
        given().post("/_admin/reset").then().statusCode(204);
        assertTrue(registry.find("keys-restart").isEmpty());
        backend.put(key, stored);

        assertEquals(admin, adminKeys(path));
        given().when().get(path + API).then().statusCode(200)
            .body("tags.k", equalTo("v"))
            .body("properties.eTag", equalTo(eTag));
        given().when().post(path + "/listQueryKeys" + API).then().body("value.key", equalTo(query));
        given().when().get("/subscriptions/sub-keys-restart/providers/Microsoft.Search/searchServices" + API)
            .then().body("value", hasSize(1));

        registry.clear();
        SearchServiceInfo info = registry.find("keys-restart").orElseThrow();
        assertEquals(admin.get("primaryKey"), info.primaryAdminKey());
        assertTrue(info.isQueryKey(String.valueOf(query.get(1))));
        assertTrue(info.authOptions().has("apiKeyOnly"));
    }

    @Test
    void theNameOfAServiceStoredByAnEarlierRunStaysTaken() {
        String path = service("sub-keys-name", "rg", "kept-name");
        put(path, STANDARD, 201);
        StorageBackend<String, StoredObject> backend = storageFactory.create(SearchServiceStore.STORE);
        StoredObject stored = backend.get(path.toLowerCase(Locale.ROOT)).orElseThrow();
        given().post("/_admin/reset").then().statusCode(204);
        backend.put(path.toLowerCase(Locale.ROOT), stored);

        given().contentType("application/json").body(STANDARD)
            .when().put(service("sub-keys-thief", "rg", "kept-name") + API)
            .then().statusCode(409);
    }
}
