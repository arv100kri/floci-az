package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.search.SearchServiceManager;
import com.azure.resourcemanager.search.fluent.ServicesClient;
import com.azure.resourcemanager.search.fluent.models.AdminKeyResultInner;
import com.azure.resourcemanager.search.fluent.models.QueryKeyInner;
import com.azure.resourcemanager.search.fluent.models.SearchServiceInner;
import com.azure.resourcemanager.search.models.AdminKeyKind;
import com.azure.resourcemanager.search.models.CheckNameAvailabilityOutput;
import com.azure.resourcemanager.search.models.HostingMode;
import com.azure.resourcemanager.search.models.ProvisioningState;
import com.azure.resourcemanager.search.models.PublicNetworkAccess;
import com.azure.resourcemanager.search.models.SearchService;
import com.azure.resourcemanager.search.models.SearchServiceStatus;
import com.azure.resourcemanager.search.models.SearchServiceUpdate;
import com.azure.resourcemanager.search.models.Sku;
import com.azure.resourcemanager.search.models.SkuName;
import com.azure.resourcemanager.search.models.UnavailableNameReason;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.models.SearchOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search management plane ({@code Microsoft.Search/searchServices}) through Microsoft's
 * {@code azure-resourcemanager-search} client, pointed at floci-az the way {@link ContainerAppsCompatibilityTest} and
 * {@link AksCompatibilityTest} are: create, read, list, update and delete, admin and query keys, and name availability.
 * One test checks that the keys the management plane issues are the ones the service's data plane accepts.
 */
@Tag("search-arm")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure AI Search Management Compatibility")
class SearchManagementCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String RESOURCE_GROUP = "search-rg-" + SUFFIX;
    // Letters and digits only, so the data-plane path /{service}-search stays unambiguous.
    private static final String SERVICE = "javaarm" + SUFFIX;

    private static SearchServiceManager manager;
    private static ServicesClient services;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder()
                .httpClient(HttpClient.createDefault())
                .build();
        manager = SearchServiceManager.authenticate(pipeline, profile);
        services = manager.serviceClient().getServices();
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/create-or-update (200/201; replicaCount and
    // partitionCount default to 1, hostingMode to Default, publicNetworkAccess to Enabled; "After capacity is set up,
    // provisioningState changes to either 'Succeeded' or 'Failed'"; status "running": "no provisioning operations are
    // underway")
    @Test
    @Order(1)
    void createServiceReachesSucceeded() {
        SearchServiceInner created = services.createOrUpdate(RESOURCE_GROUP, SERVICE, new SearchServiceInner()
                .withLocation("eastus")
                .withSku(new Sku().withName(SkuName.BASIC))
                .withTags(Map.of("suite", "java-sdk")));

        assertEquals(SERVICE, created.name());
        assertEquals("Microsoft.Search/searchServices", created.type());
        assertEquals("/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.Search/searchServices/" + SERVICE, created.id());
        assertEquals(ProvisioningState.SUCCEEDED, created.provisioningState());
        assertEquals(SearchServiceStatus.RUNNING, created.status());
        assertEquals(SkuName.BASIC, created.sku().name());
        assertEquals(1, created.replicaCount());
        assertEquals(1, created.partitionCount());
        assertEquals(HostingMode.DEFAULT, created.hostingMode());
        assertEquals(PublicNetworkAccess.ENABLED, created.publicNetworkAccess());
        assertEquals("java-sdk", created.tags().get("suite"));
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/get
    // https://learn.microsoft.com/rest/api/searchmanagement/services/list-by-resource-group
    // https://learn.microsoft.com/rest/api/searchmanagement/services/list-by-subscription
    @Test
    @Order(2)
    void getAndListServices() {
        SearchService service = manager.searchServices().getByResourceGroup(RESOURCE_GROUP, SERVICE);
        assertEquals(SERVICE, service.name());
        assertEquals("eastus", service.regionName());
        assertEquals(SkuName.BASIC, service.sku().name());
        assertEquals(ProvisioningState.SUCCEEDED, service.provisioningState());
        assertEquals("java-sdk", service.tags().get("suite"));

        List<String> inGroup = new ArrayList<>();
        services.listByResourceGroup(RESOURCE_GROUP).forEach(value -> inGroup.add(value.name()));
        assertEquals(List.of(SERVICE), inGroup);
        List<String> inSubscription = new ArrayList<>();
        services.list().forEach(value -> inSubscription.add(value.name()));
        assertTrue(inSubscription.contains(SERVICE), "subscription list: " + inSubscription);
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/update (PATCH, 200 OK; replicaCount "between 1 and
    // 3 inclusive for basic SKU")
    @Test
    @Order(3)
    void updateChangesReplicaCountAndTags() {
        SearchServiceInner updated = services.update(RESOURCE_GROUP, SERVICE, new SearchServiceUpdate()
                .withReplicaCount(2)
                .withTags(Map.of("suite", "java-sdk", "stage", "updated")));
        assertEquals(2, updated.replicaCount());
        assertEquals("updated", updated.tags().get("stage"));

        SearchServiceInner fetched = services.getByResourceGroup(RESOURCE_GROUP, SERVICE);
        assertEquals(2, fetched.replicaCount());
        assertEquals(SkuName.BASIC, fetched.sku().name(), "a PATCH leaves the properties it does not name");
        assertEquals("updated", fetched.tags().get("stage"));
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/check-name-availability (names "must only contain
    // lowercase letters, digits or dashes, cannot use dash as the first two or last one characters, cannot contain
    // consecutive dashes, and must be between 2 and 60 characters"; reason Invalid or AlreadyExists)
    @Test
    @Order(4)
    void checkNameAvailability() {
        CheckNameAvailabilityOutput taken = manager.searchServices().checkNameAvailability(SERVICE);
        assertFalse(taken.isNameAvailable());
        assertEquals(UnavailableNameReason.ALREADY_EXISTS, taken.reason());

        CheckNameAvailabilityOutput free = manager.searchServices().checkNameAvailability("javafree" + SUFFIX);
        assertTrue(free.isNameAvailable());

        CheckNameAvailabilityOutput invalid = manager.searchServices().checkNameAvailability("Invalid_Name");
        assertFalse(invalid.isNameAvailable());
        assertEquals(UnavailableNameReason.INVALID, invalid.reason());
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/admin-keys/get (listAdminKeys: primaryKey, secondaryKey)
    // https://learn.microsoft.com/rest/api/searchmanagement/query-keys/list-by-search-service
    // https://learn.microsoft.com/rest/api/searchmanagement/query-keys/create
    // https://learn.microsoft.com/rest/api/searchmanagement/query-keys/delete (by key value; 200 or 204)
    // https://learn.microsoft.com/azure/search/search-security-api-keys ("Two admin keys, primary and secondary, are
    // generated when the service is created"; "One query key is generated with the service. More can be created on
    // demand")
    @Test
    @Order(5)
    void adminAndQueryKeysAreListedCreatedAndDeleted() {
        AdminKeyResultInner admin = manager.serviceClient().getAdminKeys().get(RESOURCE_GROUP, SERVICE);
        assertFalse(admin.primaryKey() == null || admin.primaryKey().isBlank());
        assertFalse(admin.secondaryKey() == null || admin.secondaryKey().isBlank());
        assertNotEquals(admin.primaryKey(), admin.secondaryKey());

        List<QueryKeyInner> initial = queryKeys();
        assertEquals(1, initial.size(), "the query key generated with the service");

        QueryKeyInner created = manager.serviceClient().getQueryKeys().create(RESOURCE_GROUP, SERVICE, "app-key");
        assertEquals("app-key", created.name());
        assertFalse(created.key() == null || created.key().isBlank());
        assertTrue(queryKeys().stream().anyMatch(key -> created.key().equals(key.key())));

        manager.serviceClient().getQueryKeys().delete(RESOURCE_GROUP, SERVICE, created.key());
        List<QueryKeyInner> after = queryKeys();
        assertTrue(after.stream().noneMatch(key -> created.key().equals(key.key())));
        assertEquals(1, after.size());
    }

    // https://learn.microsoft.com/azure/search/search-security-api-keys (admin keys give "Full access (read-write) for
    // all data plane (content) operations"; query keys are "Read-only access, scoped to the documents collection";
    // after a regeneration, "client requests using those keys will fail with HTTP 403 Forbidden")
    // https://learn.microsoft.com/rest/api/searchmanagement/admin-keys/regenerate
    @Test
    @Order(6)
    void managementPlaneKeysAuthorizeTheServiceDataPlane() {
        AdminKeyResultInner admin = manager.serviceClient().getAdminKeys().get(RESOURCE_GROUP, SERVICE);
        List<String> indexes = new ArrayList<>();
        SearchTestSupport.indexClient(SERVICE, admin.primaryKey()).listIndexNames().forEach(indexes::add);
        assertEquals(List.of(), indexes, "a new service has no indexes");

        String queryKey = queryKeys().getFirst().key();
        SearchIndexClient readOnly = SearchTestSupport.indexClient(SERVICE, queryKey);
        assertEquals(403, statusOf(() -> readOnly.listIndexNames().forEach(name -> { })));

        AdminKeyResultInner regenerated = manager.serviceClient().getAdminKeys()
                .regenerate(RESOURCE_GROUP, SERVICE, AdminKeyKind.PRIMARY);
        assertNotEquals(admin.primaryKey(), regenerated.primaryKey());
        assertEquals(admin.secondaryKey(), regenerated.secondaryKey(), "only the named key is regenerated");

        SearchTestSupport.indexClient(SERVICE, regenerated.primaryKey()).listIndexNames().forEach(name -> { });
        SearchTestSupport.indexClient(SERVICE, regenerated.secondaryKey()).listIndexNames().forEach(name -> { });
        assertEquals(403, statusOf(() -> SearchTestSupport.indexClient(SERVICE, admin.primaryKey())
                .listIndexNames().forEach(name -> { })));
    }

    // https://learn.microsoft.com/azure/search/search-security-api-keys ("A request made to a search service endpoint
    // is accepted if both the request and the API key are valid")
    // https://learn.microsoft.com/rest/api/searchservice/http-status-codes ("403 Forbidden: Returned when
    // authorization fails")
    @Test
    @Order(7)
    void anUnknownApiKeyIsRejected() {
        SearchClient unknown = SearchTestSupport.searchClient(SERVICE, "hotels", "not-a-key-of-this-service");
        assertEquals(403, statusOf(() -> SearchTestSupport.searchKeys(unknown,
                new SearchOptions().setSearchText("*"))));
        assertEquals(403, statusOf(() -> SearchTestSupport.indexClient(SERVICE, "not-a-key-of-this-service")
                .listIndexNames().forEach(name -> { })));
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/delete (200 OK or 204 No Content)
    @Test
    @Order(8)
    void deleteServiceRemovesIt() {
        services.delete(RESOURCE_GROUP, SERVICE);

        ManagementException missing = assertThrows(ManagementException.class,
                () -> services.getByResourceGroup(RESOURCE_GROUP, SERVICE));
        assertEquals(404, missing.getResponse().getStatusCode());
        List<String> inGroup = new ArrayList<>();
        services.listByResourceGroup(RESOURCE_GROUP).forEach(value -> inGroup.add(value.name()));
        assertFalse(inGroup.contains(SERVICE));
    }

    private static List<QueryKeyInner> queryKeys() {
        List<QueryKeyInner> keys = new ArrayList<>();
        manager.serviceClient().getQueryKeys().listBySearchService(RESOURCE_GROUP, SERVICE).forEach(keys::add);
        return keys;
    }
}
