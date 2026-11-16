package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps data sources, indexers and indexer state in the shared {@code search} storage backend
 * ({@code StorageFactory.create("search")}), under keys of their own:
 * <ul>
 *   <li>{@code dp/datasource/{service}/{name}}: a data source definition, connection string included, its ETag in
 *       the stored object;</li>
 *   <li>{@code dp/indexer/{service}/{name}}: an indexer definition and its ETag;</li>
 *   <li>{@code dp/indexerstate/{service}/{name}}: an indexer's execution history and change tracking state.</li>
 * </ul>
 * It also reads skillset definitions, which the skillsets API stores at {@code dp/skillset/{service}/{name}}.
 * Everything is read in at first use and kept in memory; service names are lowercased.
 */
@ApplicationScoped
public class IndexerStore {

    private static final Logger LOG = Logger.getLogger(IndexerStore.class);

    static final String DATA_SOURCE_PREFIX = "dp/datasource/";
    static final String INDEXER_PREFIX = "dp/indexer/";
    static final String STATE_PREFIX = "dp/indexerstate/";
    static final String SKILLSET_PREFIX = "dp/skillset/";

    private final StorageBackend<String, StoredObject> storage;
    private final Map<String, DataSourceDefinition> dataSources = new ConcurrentHashMap<>();
    private final Map<String, IndexerDefinition> indexers = new ConcurrentHashMap<>();
    private final Map<String, IndexerState> states = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Inject
    public IndexerStore(StorageFactory storageFactory) {
        this.storage = storageFactory.create("search");
    }

    // ── data sources ──────────────────────────────────────────────────────────────────────────────────────────

    public Optional<DataSourceDefinition> dataSource(String service, String name) {
        load();
        return Optional.ofNullable(dataSources.get(key(service, name)));
    }

    /** The service's data sources, by name. */
    public List<DataSourceDefinition> dataSources(String service) {
        load();
        String prefix = lower(service) + "/";
        List<DataSourceDefinition> found = new ArrayList<>();
        dataSources.forEach((key, definition) -> {
            if (key.startsWith(prefix)) {
                found.add(definition);
            }
        });
        found.sort(Comparator.comparing(DataSourceDefinition::name));
        return found;
    }

    public void putDataSource(String service, DataSourceDefinition definition) {
        load();
        String storageKey = DATA_SOURCE_PREFIX + key(service, definition.name());
        storage.put(storageKey, new StoredObject(storageKey, SearchJson.writeBytes(definition.json()), Map.of(),
            Instant.now(), definition.etag()));
        dataSources.put(key(service, definition.name()), definition);
    }

    public boolean deleteDataSource(String service, String name) {
        load();
        if (dataSources.remove(key(service, name)) == null) {
            return false;
        }
        storage.delete(DATA_SOURCE_PREFIX + key(service, name));
        return true;
    }

    // ── indexers ──────────────────────────────────────────────────────────────────────────────────────────────

    public Optional<IndexerDefinition> indexer(String service, String name) {
        load();
        return Optional.ofNullable(indexers.get(key(service, name)));
    }

    /** The service's indexers, by name. */
    public List<IndexerDefinition> indexers(String service) {
        load();
        String prefix = lower(service) + "/";
        List<IndexerDefinition> found = new ArrayList<>();
        indexers.forEach((key, definition) -> {
            if (key.startsWith(prefix)) {
                found.add(definition);
            }
        });
        found.sort(Comparator.comparing(IndexerDefinition::name));
        return found;
    }

    /** Every indexer of every service, with its service name. */
    public List<Map.Entry<String, IndexerDefinition>> allIndexers() {
        load();
        List<Map.Entry<String, IndexerDefinition>> all = new ArrayList<>();
        indexers.forEach((key, definition) -> all.add(Map.entry(key.substring(0, key.indexOf('/')), definition)));
        return all;
    }

    public void putIndexer(String service, IndexerDefinition definition) {
        load();
        String storageKey = INDEXER_PREFIX + key(service, definition.name());
        storage.put(storageKey, new StoredObject(storageKey, SearchJson.writeBytes(definition.json()), Map.of(),
            Instant.now(), definition.etag()));
        indexers.put(key(service, definition.name()), definition);
    }

    /** Deletes an indexer and its state; false when there was none. */
    public boolean deleteIndexer(String service, String name) {
        load();
        if (indexers.remove(key(service, name)) == null) {
            return false;
        }
        states.remove(key(service, name));
        storage.applyBatch(Map.of(), Set.of(INDEXER_PREFIX + key(service, name), STATE_PREFIX + key(service, name)));
        return true;
    }

    /** The state of an indexer, created empty on first use. */
    public IndexerState state(String service, String name) {
        load();
        return states.computeIfAbsent(key(service, name), ignored -> new IndexerState());
    }

    /** Persists the state of an indexer that still exists. */
    public void saveState(String service, String name, IndexerState state) {
        String storageKey = STATE_PREFIX + key(service, name);
        if (!indexers.containsKey(key(service, name)) || states.get(key(service, name)) != state) {
            return;
        }
        storage.put(storageKey, new StoredObject(storageKey, SearchJson.writeBytes(state.toJson()), Map.of(),
            Instant.now(), null));
    }

    // ── skillsets (read only) ─────────────────────────────────────────────────────────────────────────────────

    /** The stored definition of a skillset, as the skillsets API keeps it; empty when there is none. */
    public Optional<ObjectNode> skillset(String service, String name) {
        Optional<StoredObject> stored = storage.get(SKILLSET_PREFIX + key(service, name));
        return stored.map(IndexerStore::parse);
    }

    public long dataSourceCount(String service) {
        return dataSources(service).size();
    }

    public long indexerCount(String service) {
        return indexers(service).size();
    }

    /** Forgets every data source, indexer and indexer state, persisted ones included. */
    public synchronized void clear() {
        dataSources.clear();
        indexers.clear();
        states.clear();
        Set<String> deletes = new HashSet<>();
        for (String storageKey : storage.keys()) {
            if (storageKey.startsWith(DATA_SOURCE_PREFIX) || storageKey.startsWith(INDEXER_PREFIX)
                    || storageKey.startsWith(STATE_PREFIX)) {
                deletes.add(storageKey);
            }
        }
        if (!deletes.isEmpty()) {
            storage.applyBatch(Map.of(), deletes);
        }
        loaded = false;
    }

    /** Reads the persisted definitions and states in once. */
    private void load() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            for (StoredObject stored : storage.scan(storageKey -> storageKey.startsWith(DATA_SOURCE_PREFIX))) {
                ObjectNode json = parse(stored);
                String name = json == null ? null : SearchJson.text(json, "name");
                Optional<DataSourceType> type = DataSourceType.parse(SearchJson.text(json, "type"));
                if (name != null && type.isPresent()) {
                    dataSources.put(stored.key().substring(DATA_SOURCE_PREFIX.length()),
                        new DataSourceDefinition(name, type.get(), json, stored.etag()));
                }
            }
            for (StoredObject stored : storage.scan(storageKey -> storageKey.startsWith(INDEXER_PREFIX))) {
                ObjectNode json = parse(stored);
                if (json != null && SearchJson.text(json, "name") != null) {
                    indexers.put(stored.key().substring(INDEXER_PREFIX.length()),
                        IndexerDefinition.fromJson(json, stored.etag()));
                }
            }
            for (StoredObject stored : storage.scan(storageKey -> storageKey.startsWith(STATE_PREFIX))) {
                ObjectNode json = parse(stored);
                if (json != null) {
                    states.put(stored.key().substring(STATE_PREFIX.length()), IndexerState.fromJson(json));
                }
            }
            loaded = true;
        }
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

    private static String key(String service, String name) {
        return lower(service) + "/" + name;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
