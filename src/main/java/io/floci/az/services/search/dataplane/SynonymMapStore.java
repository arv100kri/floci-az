package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.dataplane.NamedDefinitionStore.StoredDefinition;
import io.floci.az.services.search.engine.analysis.SolrSynonymRules;
import io.floci.az.services.search.engine.analysis.SynonymMap;
import io.floci.az.services.search.engine.analysis.SynonymMapSource;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The synonym maps of every search service, persisted in the {@code search} storage backend under
 * {@code dp/synonymmap/{service}/{name}}. The storage is the source of truth; parsed rules are cached per ETag, so a
 * query sees an update or a deletion (including the engine's reset) on its next lookup. The engine reads synonym maps
 * through {@link SynonymMapSource}.
 */
@ApplicationScoped
public class SynonymMapStore implements SynonymMapSource {

    private static final Logger LOG = Logger.getLogger(SynonymMapStore.class);

    private final NamedDefinitionStore store;
    private final Map<String, SynonymMap> parsed = new ConcurrentHashMap<>();

    @Inject
    public SynonymMapStore(StorageFactory storageFactory) {
        this.store = new NamedDefinitionStore(storageFactory.create("search"), "synonymmap");
    }

    Optional<StoredDefinition> definition(String service, String name) {
        return store.find(service, name);
    }

    List<StoredDefinition> definitions(String service) {
        return store.list(service);
    }

    long count(String service) {
        return store.count(service);
    }

    void save(String service, String name, ObjectNode definition, String etag) {
        store.put(service, name, definition, etag);
    }

    boolean delete(String service, String name) {
        parsed.remove(service + "/" + name);
        return store.delete(service, name);
    }

    @Override
    public Optional<SynonymMap> find(String service, String name) {
        String key = service + "/" + name;
        Optional<StoredDefinition> stored = store.find(service, name);
        if (stored.isEmpty()) {
            parsed.remove(key);
            return Optional.empty();
        }
        SynonymMap cached = parsed.get(key);
        if (cached != null && cached.etag() != null && cached.etag().equals(stored.get().etag())) {
            return Optional.of(cached);
        }
        String synonyms = SearchJson.text(stored.get().definition(), "synonyms");
        SolrSynonymRules rules;
        try {
            rules = SolrSynonymRules.parse(synonyms == null ? "" : synonyms);
        } catch (IllegalArgumentException e) {
            LOG.errorv("The stored synonym map {0}/{1} has invalid rules and expands nothing: {2}", service, name,
                e.getMessage());
            rules = SolrSynonymRules.parse("");
        }
        SynonymMap map = new SynonymMap(name, stored.get().etag(), rules);
        parsed.put(key, map);
        return Optional.of(map);
    }
}
