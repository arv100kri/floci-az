package io.floci.az.compat;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.search.SearchServiceManager;
import com.azure.resourcemanager.search.fluent.SearchManagementClient;
import com.azure.resourcemanager.search.fluent.ServicesClient;
import com.azure.resourcemanager.search.fluent.models.SearchServiceInner;
import com.azure.resourcemanager.search.models.AadAuthFailureMode;
import com.azure.resourcemanager.search.models.DataPlaneAadOrApiKeyAuthOption;
import com.azure.resourcemanager.search.models.DataPlaneAuthOptions;
import com.azure.resourcemanager.search.models.Sku;
import com.azure.resourcemanager.search.models.SkuName;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.floci.az.compat.SearchTestSupport.doc;
import static io.floci.az.compat.SearchTestSupport.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure AI Search identity: a Microsoft Entra token acquired through {@code azure-identity} against floci-az's own
 * Entra, a service's {@code authOptions} and {@code disableLocalAuth} set through {@code azure-resourcemanager-search},
 * and document-level permission filters queried through {@code azure-search-documents}.
 *
 * <p>The emulator runs at compat parity here, where tokens are not validated and role assignments not evaluated (both
 * are covered by the emulator's own tests under {@code parity-overrides.strict-tokens} and {@code strict-rbac}). The
 * service's own authentication settings and permission filters apply at every parity level.</p>
 *
 * <p>The SDK's api-version (2026-04-01) has no {@code permissionFilter} in its index model, so the permission-filtered
 * index is created through the REST API (2026-10-01); its documents are loaded and queried through the SDK, which
 * documents {@code x-ms-query-source-authorization} and {@code x-ms-enable-elevated-read} as request headers.</p>
 */
@Tag("search-identity")
@DisplayName("Azure AI Search Identity Compatibility")
class SearchIdentityCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_SECRET = "floci-az-dev-secret";
    private static final String SEARCH_SCOPE = "https://search.azure.com/.default";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String RESOURCE_GROUP = "search-identity-rg-" + SUFFIX;
    private static final String READERS = "cccccccc-0000-0000-0000-0000000000c1";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SearchManagementClient management;
    private static ServicesClient services;
    private static TokenCredential credential;
    private static final List<String> CREATED = new ArrayList<>();

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        // azure-core's HttpClient, qualified: java.net.http.HttpClient sends the raw requests below.
        HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(com.azure.core.http.HttpClient.createDefault())
                .build();
        management = SearchServiceManager.authenticate(pipeline,
                new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints))).serviceClient();
        services = management.getServices();
        credential = new ClientSecretCredentialBuilder()
                .tenantId(TENANT)
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .authorityHost(ENDPOINT.replace("http://", "https://"))
                .disableInstanceDiscovery()
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .build();
    }

    @AfterAll
    static void cleanup() {
        for (String service : CREATED) {
            services.delete(RESOURCE_GROUP, service);
        }
    }

    /** Creates a service through Microsoft.Search; returns its primary admin key. */
    private static String createService(String name, DataPlaneAuthOptions authOptions, Boolean disableLocalAuth) {
        services.createOrUpdate(RESOURCE_GROUP, name, new SearchServiceInner()
                .withLocation("eastus")
                .withSku(new Sku().withName(SkuName.BASIC))
                .withAuthOptions(authOptions)
                .withDisableLocalAuth(disableLocalAuth));
        CREATED.add(name);
        return management.getAdminKeys().get(RESOURCE_GROUP, name).primaryKey();
    }

    private static DataPlaneAuthOptions aadOrApiKey(AadAuthFailureMode mode) {
        return new DataPlaneAuthOptions().withAadOrApiKey(new DataPlaneAadOrApiKeyAuthOption()
                .withAadAuthFailureMode(mode));
    }

    private static SearchIndexClient tokenIndexClient(String service) {
        return new SearchIndexClientBuilder().endpoint(SearchTestSupport.endpoint(service)).credential(credential)
                .addPolicy(new EmulatorConfig.ForceHttpPolicy()).buildClient();
    }

    private static SearchClient tokenSearchClient(String service, String index) {
        return new SearchClientBuilder().endpoint(SearchTestSupport.endpoint(service)).indexName(index)
                .credential(credential).addPolicy(new EmulatorConfig.ForceHttpPolicy()).buildClient();
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // https://learn.microsoft.com/azure/search/search-security-enable-roles ("Both: Requests are valid using either an
    // API key or role-based access control"; the scope https://search.azure.com/.default)
    @Test
    void anEntraTokenAuthorizesAServiceThatAcceptsRoles() {
        String service = SearchTestSupport.serviceName("javaidboth");
        String key = createService(service, aadOrApiKey(AadAuthFailureMode.HTTP401WITH_BEARER_CHALLENGE), null);
        String index = SearchTestSupport.name("hotels");
        tokenIndexClient(service).createIndex(SearchTestSupport.hotelsIndex(index));
        SearchClient hotels = tokenSearchClient(service, index);
        SearchTestSupport.upload(hotels, SearchTestSupport.hotels());
        SearchTestSupport.eventually(() -> assertEquals(6L, hotels.getDocumentCount()));
        assertEquals(List.of("1"), SearchTestSupport.searchKeys(hotels, new SearchOptions()
                .setSearchText("ocean").setFilter("rating eq 5")));
        assertEquals(6L, SearchTestSupport.searchClient(service, index, key).getDocumentCount());
    }

    // https://learn.microsoft.com/azure/search/search-security-rbac ("If you don't change this setting to Both or
    // Role-based access control, all requests that use role-based authentication are automatically denied")
    @Test
    void aServiceThatAcceptsOnlyKeysRefusesTokens() {
        String service = SearchTestSupport.serviceName("javaidkeys");
        String key = createService(service, null, null);
        assertEquals(403, statusOf(() -> tokenIndexClient(service).listIndexNames().forEach(name -> { })));
        SearchTestSupport.indexClient(service, key).listIndexNames().forEach(name -> { });
    }

    // https://learn.microsoft.com/azure/search/search-security-enable-roles ("Disabling API keys causes the search
    // service to refuse all data-related requests that pass an API key in the header")
    @Test
    void disablingLocalAuthRefusesKeysAndKeepsTokens() {
        String service = SearchTestSupport.serviceName("javaidroles");
        String key = createService(service, null, true);
        assertEquals(403, statusOf(() -> SearchTestSupport.indexClient(service, key).listIndexNames()
                .forEach(name -> { })));
        tokenIndexClient(service).listIndexNames().forEach(name -> { });
    }

    // https://learn.microsoft.com/rest/api/searchmanagement/services/create-or-update#aadauthfailuremode
    // (http401WithBearerChallenge: "401 (Unauthorized) and present a Bearer Challenge"; http403: "403 (Forbidden)")
    @Test
    void theFailureModeAnswersARequestWithoutCredentials() throws Exception {
        String challenge = SearchTestSupport.serviceName("javaid401");
        createService(challenge, aadOrApiKey(AadAuthFailureMode.HTTP401WITH_BEARER_CHALLENGE), null);
        HttpResponse<String> unauthorized = send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/" + challenge
                + "-search/indexes?api-version=2026-04-01")).GET());
        assertEquals(401, unauthorized.statusCode());
        String header = unauthorized.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(header.startsWith("Bearer ") && header.contains("resource=\"https://search.azure.com\""), header);

        String forbidden = SearchTestSupport.serviceName("javaid403");
        createService(forbidden, aadOrApiKey(AadAuthFailureMode.HTTP403), null);
        assertEquals(403, send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/" + forbidden
                + "-search/indexes?api-version=2026-04-01")).GET()).statusCode());
    }

    // https://learn.microsoft.com/azure/search/search-index-access-control-lists-and-rbac-push-api (userIds, groupIds,
    // "all" and "none") and https://learn.microsoft.com/azure/search/search-query-access-control-rbac-enforcement
    // ("If the query token is omitted, only public documents accessible to everyone are returned"; elevated read)
    @Test
    void documentLevelPermissionsTrimResultsToTheEndUser() throws Exception {
        String service = SearchTestSupport.serviceName("javaidacl");
        String index = SearchTestSupport.name("acl");
        String definition = "{\"name\":\"" + index + "\",\"permissionFilterOption\":\"enabled\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"Edm.String\",\"key\":true},"
                + "{\"name\":\"title\",\"type\":\"Edm.String\"},"
                + "{\"name\":\"oid\",\"type\":\"Collection(Edm.String)\",\"filterable\":true,"
                + "\"permissionFilter\":\"userIds\"},"
                + "{\"name\":\"group\",\"type\":\"Collection(Edm.String)\",\"filterable\":true,"
                + "\"permissionFilter\":\"groupIds\"}]}";
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/" + service
                        + "-search/indexes?api-version=2026-10-01"))
                .header("api-key", SearchTestSupport.ADMIN_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(definition)));
        assertEquals(201, created.statusCode(), created.body());

        String userToken = credential.getToken(new TokenRequestContext().addScopes(SEARCH_SCOPE))
                .block(Duration.ofSeconds(30)).getToken();
        String user = objectId(userToken);
        SearchClient admin = SearchTestSupport.searchClient(service, index, SearchTestSupport.ADMIN_KEY);
        admin.indexDocuments(SearchTestSupport.batch(IndexActionType.UPLOAD, List.of(
                doc("id", "1", "title", "mine", "oid", List.of(user), "group", List.of("none")),
                doc("id", "2", "title", "public", "oid", List.of("all"), "group", List.of()),
                doc("id", "3", "title", "readers", "oid", List.of("none"), "group", List.of(READERS)),
                doc("id", "4", "title", "nobody", "oid", List.of("none"), "group", List.of("none")))));

        RequestOptions asUser = new RequestOptions().setHeader("x-ms-query-source-authorization", userToken);
        SearchTestSupport.eventually(() -> assertEquals(Set.of("1", "2"), keys(admin, asUser)));
        assertEquals(Set.of("2"), keys(admin, new RequestOptions()));
        assertEquals(Set.of("1", "2", "3", "4"), keys(admin,
                new RequestOptions().setHeader("x-ms-enable-elevated-read", "true")));
        assertEquals(2L, admin.getDocumentCountWithResponse(asUser).getValue());
        assertEquals(404, statusOf(() -> admin.getDocumentWithResponse("4", asUser)));
        assertEquals("mine", admin.getDocumentWithResponse("1", asUser).getValue()
                .getAdditionalProperties().get("title"));
        SearchClient query = SearchTestSupport.searchClient(service, index, SearchTestSupport.QUERY_KEY);
        assertEquals(403, statusOf(() -> keys(query,
                new RequestOptions().setHeader("x-ms-enable-elevated-read", "true"))));
    }

    private static Set<String> keys(SearchClient client, RequestOptions options) {
        Set<String> keys = new HashSet<>();
        for (SearchResult result : client.search(new SearchOptions().setSearchText("*"), options)) {
            keys.add(String.valueOf(result.getAdditionalProperties().get("id")));
        }
        return keys;
    }

    /** The {@code oid} claim of a JWT. */
    private static String objectId(String jwt) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1])).get("oid").asText();
    }
}
