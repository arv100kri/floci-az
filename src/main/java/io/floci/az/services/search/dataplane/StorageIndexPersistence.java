package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.search.engine.index.IndexPersistence;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Keeps index definitions and documents in the {@code search} storage backend
 * ({@code StorageFactory.create("search")}), under keys of their own so other parts of the service can share the store:
 * <ul>
 *   <li>{@code dp/index/{service}/{index}}: the definition JSON, its ETag in the stored object;</li>
 *   <li>{@code dp/doc/{service}/{index}/{key}}: one document's JSON. Document keys never contain {@code /}.</li>
 * </ul>
 */
public final class StorageIndexPersistence implements IndexPersistence {

    private static final Logger LOG = Logger.getLogger(StorageIndexPersistence.class);
    private static final String ROOT = "dp/";
    private static final String INDEX_PREFIX = ROOT + "index/";
    private static final String DOCUMENT_PREFIX = ROOT + "doc/";

    private final StorageBackend<String, StoredObject> store;

    public StorageIndexPersistence(StorageBackend<String, StoredObject> store) {
        this.store = store;
    }

    @Override
    public void saveIndex(String service, String index, ObjectNode definition, String etag) {
        String key = INDEX_PREFIX + service + "/" + index;
        store.put(key, new StoredObject(key, SearchJson.writeBytes(definition), Map.of(), Instant.now(), etag));
    }

    @Override
    public void deleteIndex(String service, String index) {
        String documents = documentPrefix(service, index);
        Set<String> deletes = new HashSet<>();
        for (String key : store.keys()) {
            if (key.startsWith(documents)) {
                deletes.add(key);
            }
        }
        deletes.add(INDEX_PREFIX + service + "/" + index);
        store.applyBatch(Map.of(), deletes);
    }

    @Override
    public void saveDocuments(String service, String index, Map<String, ObjectNode> upserts, Set<String> deletes) {
        String prefix = documentPrefix(service, index);
        Map<String, StoredObject> puts = new LinkedHashMap<>();
        Instant now = Instant.now();
        upserts.forEach((documentKey, document) -> {
            String key = prefix + documentKey;
            puts.put(key, new StoredObject(key, SearchJson.writeBytes(document), Map.of(), now, null));
        });
        Set<String> removed = new HashSet<>();
        deletes.forEach(documentKey -> removed.add(prefix + documentKey));
        store.applyBatch(puts, removed);
    }

    @Override
    public List<StoredIndex> loadIndexes(String service) {
        String prefix = INDEX_PREFIX + service + "/";
        List<StoredIndex> indexes = new ArrayList<>();
        for (StoredObject stored : store.scan(key -> key.startsWith(prefix))) {
            ObjectNode definition = parse(stored);
            if (definition != null) {
                indexes.add(new StoredIndex(stored.key().substring(prefix.length()), definition, stored.etag()));
            }
        }
        return indexes;
    }

    @Override
    public void loadDocuments(String service, String index, BiConsumer<String, ObjectNode> consumer) {
        String prefix = documentPrefix(service, index);
        for (StoredObject stored : store.scan(key -> key.startsWith(prefix))) {
            ObjectNode document = parse(stored);
            if (document != null) {
                consumer.accept(stored.key().substring(prefix.length()), document);
            }
        }
    }

    @Override
    public void clear() {
        Set<String> deletes = new HashSet<>();
        for (String key : store.keys()) {
            if (key.startsWith(ROOT)) {
                deletes.add(key);
            }
        }
        if (!deletes.isEmpty()) {
            store.applyBatch(Map.of(), deletes);
        }
    }

    private static String documentPrefix(String service, String index) {
        return DOCUMENT_PREFIX + service + "/" + index + "/";
    }

    private static ObjectNode parse(StoredObject stored) {
        try {
            JsonNode node = SearchJson.parse(stored.data());
            if (node instanceof ObjectNode object) {
                return object;
            }
            LOG.errorv("The stored search object {0} is not a JSON object and was skipped", stored.key());
        } catch (SearchException e) {
            LOG.errorv("The stored search object {0} is not valid JSON and was skipped: {1}", stored.key(),
                e.getMessage());
        }
        return null;
    }
}
