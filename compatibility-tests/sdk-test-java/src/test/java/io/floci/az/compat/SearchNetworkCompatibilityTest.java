package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.search.SearchServiceManager;
import com.azure.resourcemanager.search.fluent.SearchManagementClient;
import com.azure.resourcemanager.search.fluent.models.PrivateEndpointConnectionInner;
import com.azure.resourcemanager.search.fluent.models.PrivateLinkResourceInner;
import com.azure.resourcemanager.search.fluent.models.SearchServiceInner;
import com.azure.resourcemanager.search.fluent.models.SharedPrivateLinkResourceInner;
import com.azure.resourcemanager.search.models.PrivateEndpointConnectionProperties;
import com.azure.resourcemanager.search.models.PrivateEndpointConnectionPropertiesPrivateLinkServiceConnectionState;
import com.azure.resourcemanager.search.models.PrivateLinkServiceConnectionStatus;
import com.azure.resourcemanager.search.models.SharedPrivateLinkResourceProperties;
import com.azure.resourcemanager.search.models.SharedPrivateLinkResourceProvisioningState;
import com.azure.resourcemanager.search.models.SharedPrivateLinkResourceStatus;
import com.azure.resourcemanager.search.models.Sku;
import com.azure.resourcemanager.search.models.SkuName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The network children of a search service through Microsoft's {@code azure-resourcemanager-search} client: its private
 * link resource, shared private links (long-running create and delete), and the private endpoint connections a
 * Microsoft.Network private endpoint makes to it. The private endpoint itself is created over the raw ARM wire
 * protocol, as {@link PrivateLinkCompatibilityTest} creates its own.
 */
@Tag("search-arm")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure AI Search Network Isolation Compatibility")
class SearchNetworkCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String RESOURCE_GROUP = "search-net-rg-" + SUFFIX;
    private static final String SERVICE = "javanet" + SUFFIX;
    private static final String SERVICE_ID = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
            + "/providers/Microsoft.Search/searchServices/" + SERVICE;
    private static final String STORAGE_ID = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
            + "/providers/Microsoft.Storage/storageAccounts/javanet" + SUFFIX;
    private static final String NETWORK = ENDPOINT + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/"
            + RESOURCE_GROUP + "/providers/Microsoft.Network/";
    private static final String NETWORK_API = "?api-version=2024-05-01";

    // java.net.http.HttpClient clashes with the azure-core HttpClient the management pipeline is built with.
    private static final java.net.http.HttpClient RAW = java.net.http.HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SearchManagementClient client;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(HttpClient.createDefault()).build();
        client = SearchServiceManager.authenticate(pipeline, profile).serviceClient();
        client.getServices().createOrUpdate(RESOURCE_GROUP, SERVICE, new SearchServiceInner()
                .withLocation("eastus")
                .withSku(new Sku().withName(SkuName.BASIC)));
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/private-link-resources/list-supported (one resource,
    // groupId searchService, required zone privatelink.search.windows.net)
    // https://learn.microsoft.com/azure/search/service-create-private-endpoint ("Target subresource: searchService";
    // "Private DNS zone: privatelink.search.windows.net")
    @Test
    @Order(1)
    void theServicesPrivateLinkResourceIsSearchService() {
        List<PrivateLinkResourceInner> resources = new ArrayList<>();
        client.getPrivateLinkResources().listSupported(RESOURCE_GROUP, SERVICE).forEach(resources::add);

        assertEquals(1, resources.size());
        assertEquals("searchService", resources.getFirst().properties().groupId());
        assertEquals(List.of("searchService"), resources.getFirst().properties().requiredMembers());
        assertEquals(List.of("privatelink.search.windows.net"), resources.getFirst().properties().requiredZoneNames());
        assertTrue(resources.getFirst().properties().shareablePrivateLinkResourceTypes().stream()
                .anyMatch(type -> "blob".equals(type.properties().groupId())));
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/shared-private-link-resources/create-or-update (a
    // long-running operation: 202 Accepted with Azure-AsyncOperation)
    // https://learn.microsoft.com/azure/search/search-indexer-howto-access-private ("If the provisioning state ... is
    // Succeeded and connection state ... is Approved, ... the shared private link resource is functional")
    @Test
    @Order(2)
    void aSharedPrivateLinkIsCreatedThroughItsLongRunningOperation() {
        SharedPrivateLinkResourceInner created = client.getSharedPrivateLinkResources().createOrUpdate(RESOURCE_GROUP,
                SERVICE, "blob-link", new SharedPrivateLinkResourceInner().withProperties(
                        new SharedPrivateLinkResourceProperties()
                                .withPrivateLinkResourceId(STORAGE_ID)
                                .withGroupId("blob")
                                .withRequestMessage("please approve")));

        assertEquals("blob-link", created.name());
        assertEquals(SharedPrivateLinkResourceStatus.APPROVED, created.properties().status());
        assertEquals(SharedPrivateLinkResourceProvisioningState.SUCCEEDED, created.properties().provisioningState());

        SharedPrivateLinkResourceInner fetched = client.getSharedPrivateLinkResources()
                .get(RESOURCE_GROUP, SERVICE, "blob-link");
        assertEquals(STORAGE_ID, fetched.properties().privateLinkResourceId());
        assertEquals("please approve", fetched.properties().requestMessage());
        List<String> names = new ArrayList<>();
        client.getSharedPrivateLinkResources().listByService(RESOURCE_GROUP, SERVICE)
                .forEach(link -> names.add(link.name()));
        assertEquals(List.of("blob-link"), names);
    }

    // https://learn.microsoft.com/azure/search/search-indexer-howto-access-private ("there can be only one shared
    // private link created on your service for each resource and subresource combination")
    @Test
    @Order(3)
    void aSecondLinkToTheSameResourceAndGroupIsRefused() {
        ManagementException refused = assertThrows(ManagementException.class, () -> client
                .getSharedPrivateLinkResources().createOrUpdate(RESOURCE_GROUP, SERVICE, "blob-again",
                        new SharedPrivateLinkResourceInner().withProperties(new SharedPrivateLinkResourceProperties()
                                .withPrivateLinkResourceId(STORAGE_ID)
                                .withGroupId("blob"))));
        assertEquals(409, refused.getResponse().getStatusCode());
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/private-endpoint-connections/list-by-service
    // https://learn.microsoft.com/rest/api/searchmanagement/private-endpoint-connections/update (status Approved or
    // Rejected, as the resource owner decides)
    // https://learn.microsoft.com/azure/private-link/private-endpoint-overview ("Pending: The connection was created
    // manually and is pending approval by the private-link resource owner")
    @Test
    @Order(4)
    void aManualPrivateEndpointConnectionIsApprovedOnTheService() throws Exception {
        createManualPrivateEndpoint("pe-" + SUFFIX);

        List<PrivateEndpointConnectionInner> connections = connections();
        assertEquals(1, connections.size());
        PrivateEndpointConnectionInner pending = connections.getFirst();
        assertTrue(pending.name().startsWith("pe-" + SUFFIX + "."), pending.name());
        assertEquals(PrivateLinkServiceConnectionStatus.PENDING,
                pending.properties().privateLinkServiceConnectionState().status());

        PrivateEndpointConnectionInner approved = client.getPrivateEndpointConnections().update(RESOURCE_GROUP, SERVICE,
                pending.name(), new PrivateEndpointConnectionInner().withProperties(
                        new PrivateEndpointConnectionProperties().withPrivateLinkServiceConnectionState(
                                new PrivateEndpointConnectionPropertiesPrivateLinkServiceConnectionState()
                                        .withStatus(PrivateLinkServiceConnectionStatus.APPROVED)
                                        .withDescription("approved by the search admin"))));
        assertEquals(PrivateLinkServiceConnectionStatus.APPROVED,
                approved.properties().privateLinkServiceConnectionState().status());

        JsonNode endpoint = MAPPER.readTree(get(NETWORK + "privateEndpoints/pe-" + SUFFIX + NETWORK_API).body());
        assertEquals("Approved", endpoint.at("/properties/manualPrivateLinkServiceConnections/0/properties"
                + "/privateLinkServiceConnectionState/status").asText());
        assertEquals("approved by the search admin", client.getPrivateEndpointConnections()
                .get(RESOURCE_GROUP, SERVICE, pending.name()).properties().privateLinkServiceConnectionState()
                .description());
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/private-endpoint-connections/delete (200 with the
    // connection, Disconnected)
    @Test
    @Order(5)
    void deletingTheConnectionDisconnectsIt() {
        String name = connections().getFirst().name();

        PrivateEndpointConnectionInner deleted = client.getPrivateEndpointConnections()
                .delete(RESOURCE_GROUP, SERVICE, name);

        assertEquals(PrivateLinkServiceConnectionStatus.DISCONNECTED,
                deleted.properties().privateLinkServiceConnectionState().status());
        assertEquals(List.of(), connections());
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/shared-private-link-resources/delete (202, long-running)
    @Test
    @Order(6)
    void aSharedPrivateLinkIsDeletedThroughItsLongRunningOperation() {
        client.getSharedPrivateLinkResources().delete(RESOURCE_GROUP, SERVICE, "blob-link");

        ManagementException missing = assertThrows(ManagementException.class,
                () -> client.getSharedPrivateLinkResources().get(RESOURCE_GROUP, SERVICE, "blob-link"));
        assertEquals(404, missing.getResponse().getStatusCode());
        client.getServices().delete(RESOURCE_GROUP, SERVICE);
    }

    private static List<PrivateEndpointConnectionInner> connections() {
        List<PrivateEndpointConnectionInner> connections = new ArrayList<>();
        client.getPrivateEndpointConnections().listByService(RESOURCE_GROUP, SERVICE).forEach(connections::add);
        return connections;
    }

    private static void createManualPrivateEndpoint(String name) throws Exception {
        String subnet = "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.Network/virtualNetworks/vnet-" + SUFFIX + "/subnets/pe-subnet";
        assertEquals(200, put(NETWORK + "virtualNetworks/vnet-" + SUFFIX + NETWORK_API, """
                {"location": "eastus", "properties": {"addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
                 "subnets": [{"name": "pe-subnet", "properties": {"addressPrefix": "10.0.1.0/24"}}]}}
                """).statusCode());
        HttpResponse<String> created = put(NETWORK + "privateEndpoints/" + name + NETWORK_API, """
                {"location": "eastus", "properties": {"subnet": {"id": "%s"},
                 "manualPrivateLinkServiceConnections": [{"name": "to-search", "properties": {
                   "privateLinkServiceId": "%s", "groupIds": ["searchService"],
                   "requestMessage": "please approve"}}]}}
                """.formatted(subnet, SERVICE_ID));
        assertEquals(201, created.statusCode(), created.body());
    }

    private static HttpResponse<String> put(String url, String body) throws Exception {
        return RAW.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return RAW.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
