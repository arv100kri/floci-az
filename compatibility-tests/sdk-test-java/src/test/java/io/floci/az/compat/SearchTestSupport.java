package io.floci.az.compat;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.SearchIndexerClient;
import com.azure.search.documents.indexes.SearchIndexerClientBuilder;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Clients, names and fixtures shared by the Azure AI Search compatibility tests.
 *
 * <p>The data plane is addressed path style, {@code {FLOCI_AZ_ENDPOINT}/{service}-search}, and a data-plane request
 * provisions the named service with floci-az's development keys. azure-core's {@code KeyCredentialPolicy} refuses a
 * key over plain HTTP, so the clients are given the https form of the endpoint and {@link EmulatorConfig.ForceHttpPolicy}
 * (a per-retry policy, so it runs after the key policy) restores the emulator's scheme, the pattern the Key Vault and
 * App Configuration tests use.</p>
 */
final class SearchTestSupport {

    /** Primary admin key of a data-plane-provisioned service (floci-az's {@code SearchServiceRegistry}). */
    static final String ADMIN_KEY = "FlociAzSearchDevPrimaryAdminKey0000000000000000000000";

    /** Secondary admin key of a data-plane-provisioned service. */
    static final String SECONDARY_ADMIN_KEY = "FlociAzSearchDevSecondaryAdminKey00000000000000000000";

    /** Query key of a data-plane-provisioned service. */
    static final String QUERY_KEY = "FlociAzSearchDevQueryKey000000000000000000000000000000";

    /** Upper bound for writes to become visible: documents and alias updates are near real time on Azure. */
    static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(15);

    private SearchTestSupport() {
    }

    /** A service name: lowercase letters and digits only, so the {@code -search} path suffix stays unambiguous. */
    static String serviceName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** An index, alias, synonym map, data source, indexer or skillset name: lowercase, digits and dashes. */
    static String name(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    static String endpoint(String service) {
        return EmulatorConfig.httpBase().replace("http://", "https://") + "/" + service + "-search";
    }

    static SearchIndexClient indexClient(String service, String apiKey) {
        return new SearchIndexClientBuilder()
                .endpoint(endpoint(service))
                .credential(new AzureKeyCredential(apiKey))
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .buildClient();
    }

    static SearchIndexerClient indexerClient(String service, String apiKey) {
        return new SearchIndexerClientBuilder()
                .endpoint(endpoint(service))
                .credential(new AzureKeyCredential(apiKey))
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .buildClient();
    }

    static SearchClient searchClient(String service, String indexOrAlias, String apiKey) {
        return new SearchClientBuilder()
                .endpoint(endpoint(service))
                .indexName(indexOrAlias)
                .credential(new AzureKeyCredential(apiKey))
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .buildClient();
    }

    static Map<String, Object> doc(Object... keysAndValues) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            document.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return document;
    }

    static IndexAction action(IndexActionType type, Map<String, Object> document) {
        return new IndexAction().setActionType(type).setAdditionalProperties(document);
    }

    static IndexDocumentsBatch batch(IndexActionType type, List<Map<String, Object>> documents) {
        List<IndexAction> actions = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            actions.add(action(type, document));
        }
        return new IndexDocumentsBatch(actions);
    }

    static void upload(SearchClient client, List<Map<String, Object>> documents) {
        client.indexDocuments(batch(IndexActionType.UPLOAD, documents));
    }

    /** Every result of the query, following the service's continuation across pages. */
    static List<SearchResult> searchAll(SearchClient client, SearchOptions options) {
        List<SearchResult> results = new ArrayList<>();
        for (SearchResult result : client.search(options)) {
            results.add(result);
        }
        return results;
    }

    static List<String> keys(List<SearchResult> results, String keyField) {
        List<String> keys = new ArrayList<>();
        for (SearchResult result : results) {
            keys.add(String.valueOf(result.getAdditionalProperties().get(keyField)));
        }
        return keys;
    }

    /** Keys of the documents matching {@code options}, in the order the service returned them. */
    static List<String> searchKeys(SearchClient client, SearchOptions options) {
        return keys(searchAll(client, options), "id");
    }

    /** The document's own fields, without the {@code @search.*} annotations the service adds. */
    static Map<String, Object> fields(Map<String, Object> properties) {
        Map<String, Object> fields = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (!key.startsWith("@")) {
                fields.put(key, value);
            }
        });
        return fields;
    }

    static int statusOf(Executable call) {
        return assertThrows(HttpResponseException.class, call).getResponse().getStatusCode();
    }

    /** Re-runs {@code assertion} until it passes or {@code timeout} elapses, then rethrows its last failure. */
    static void eventually(Duration timeout, Runnable assertion) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError failure) {
                if (Instant.now().isAfter(deadline)) {
                    throw failure;
                }
            }
            pause(Duration.ofMillis(250));
        }
    }

    static void eventually(Runnable assertion) {
        eventually(VISIBILITY_TIMEOUT, assertion);
    }

    /** Polls {@code supplier} until {@code done} accepts its value; returns the last value either way. */
    static <T> T poll(Supplier<T> supplier, Predicate<T> done, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        T value = supplier.get();
        while (!done.test(value) && Instant.now().isBefore(deadline)) {
            pause(Duration.ofMillis(500));
            value = supplier.get();
        }
        return value;
    }

    static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }

    static void deleteIndexQuietly(SearchIndexClient client, String name) {
        try {
            client.deleteIndex(name);
        } catch (HttpResponseException ignored) {
            // Cleanup only: the index may already be gone, or the test failed before creating it.
        }
    }

    // ── The hotels corpus: six documents whose filters, sorts and distances have one right answer ─────────────

    /** Reference point for the geo tests: downtown Seattle, longitude first as in OData {@code POINT(lon lat)}. */
    static final String SEATTLE = "geography'POINT(-122.335 47.608)'";

    static SearchIndex hotelsIndex(String name) {
        return new SearchIndex(name,
                new SearchField("id", SearchFieldDataType.STRING).setKey(true)
                        .setFilterable(true).setSortable(true).setSearchable(false),
                new SearchField("name", SearchFieldDataType.STRING).setSearchable(true)
                        .setFilterable(true).setSortable(true).setFacetable(false),
                new SearchField("description", SearchFieldDataType.STRING).setSearchable(true)
                        .setFilterable(false).setSortable(false).setFacetable(false),
                new SearchField("category", SearchFieldDataType.STRING).setSearchable(false)
                        .setFilterable(true).setSortable(true).setFacetable(true),
                new SearchField("tags", SearchFieldDataType.collection(SearchFieldDataType.STRING))
                        .setSearchable(true).setFilterable(true).setFacetable(true),
                new SearchField("rating", SearchFieldDataType.INT32)
                        .setFilterable(true).setSortable(true).setFacetable(true),
                new SearchField("price", SearchFieldDataType.DOUBLE)
                        .setFilterable(true).setSortable(true).setFacetable(true),
                new SearchField("parking", SearchFieldDataType.BOOLEAN)
                        .setFilterable(true).setFacetable(true),
                new SearchField("location", SearchFieldDataType.GEOGRAPHY_POINT)
                        .setFilterable(true).setSortable(true));
    }

    static List<Map<String, Object>> hotels() {
        return List.of(
                hotel("1", "Ocean View Resort", "Luxury resort with a pool and an ocean view", "Resort",
                        List.of("pool", "view", "wifi"), 5, 250.0, true, -122.33, 47.61),
                hotel("2", "Budget Inn", "Cheap motel near the airport with free parking", "Budget",
                        List.of("parking", "wifi"), 2, 60.0, true, -122.30, 47.45),
                hotel("3", "City Center Hotel", "Modern hotel in the city center with a rooftop pool", "Luxury",
                        List.of("pool", "gym"), 4, 180.0, false, -122.34, 47.60),
                hotel("4", "Mountain Lodge", "Rustic lodge with hiking trails and a stone fireplace", "Resort",
                        List.of("hiking", "fireplace"), 3, 120.0, true, -105.27, 40.01),
                hotel("5", "Airport Suites", "Convenient suites next to the airport terminal", "Budget",
                        List.of("wifi", "shuttle"), 3, 90.0, false, -122.31, 47.44),
                hotel("6", "Harbor Boutique", "Small boutique hotel on the harbor with a view", "Boutique",
                        List.of("boutique", "view"), 4, 150.0, false, -122.40, 47.65));
    }

    private static Map<String, Object> hotel(String id, String name, String description, String category,
                                             List<String> tags, int rating, double price, boolean parking,
                                             double longitude, double latitude) {
        return doc("id", id, "name", name, "description", description, "category", category, "tags", tags,
                "rating", rating, "price", price, "parking", parking,
                "location", Map.of("type", "Point", "coordinates", List.of(longitude, latitude)));
    }
}
