package io.floci.az.services.search.engine;

import io.floci.az.services.search.engine.analysis.AnalyzerRegistry;
import io.floci.az.services.search.engine.index.IndexPersistence;
import io.floci.az.services.search.engine.index.IndexPersistence.StoredIndex;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.IndexDefinitionParser;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchIndexDefinition;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The indexes of one search service, loaded from persistence when the service is first used. Index creation, update
 * and deletion are serialised per service; documents are guarded by each {@link LiveIndex}.
 */
public final class ServiceIndexes {

    private static final Logger LOG = Logger.getLogger(ServiceIndexes.class);

    private final String service;
    private final IndexPersistence persistence;
    private final AnalyzerRegistry registry;
    private final Map<String, LiveIndex> indexes = new ConcurrentHashMap<>();

    ServiceIndexes(String service, IndexPersistence persistence, AnalyzerRegistry registry) {
        this.service = service;
        this.persistence = persistence;
        this.registry = registry;
        load();
    }

    private void load() {
        for (StoredIndex stored : persistence.loadIndexes(service)) {
            try {
                SearchIndexDefinition definition = IndexDefinitionParser.parse(stored.definition(), stored.name(),
                    registry);
                LiveIndex index = new LiveIndex(definition, stored.etag(), registry);
                persistence.loadDocuments(service, stored.name(), index::load);
                indexes.put(stored.name(), index);
                LOG.debugv("Rebuilt search index {0}/{1} with {2} documents", service, stored.name(),
                    index.documentCount());
            } catch (SearchException e) {
                LOG.errorv("The persisted search index {0}/{1} is no longer valid and was skipped: {2}", service,
                    stored.name(), e.getMessage());
            }
        }
    }

    public String service() {
        return service;
    }

    public Optional<LiveIndex> find(String name) {
        return Optional.ofNullable(indexes.get(name));
    }

    /** The indexes, ordered by name. */
    public List<LiveIndex> list() {
        List<LiveIndex> sorted = new ArrayList<>(indexes.values());
        sorted.sort(Comparator.comparing(LiveIndex::name));
        return sorted;
    }

    /** Creates an index; a 409 when one of that name exists. */
    public synchronized LiveIndex create(SearchIndexDefinition definition) {
        if (indexes.containsKey(definition.name())) {
            throw SearchException.alreadyExists("Cannot create index '" + definition.name() + "' because it already "
                + "exists.");
        }
        LiveIndex index = new LiveIndex(definition, Etags.next(), registry);
        persistence.saveIndex(service, definition.name(), definition.json(), index.etag());
        indexes.put(definition.name(), index);
        return index;
    }

    /** Replaces the definition of {@code index} with an update the caller validated. */
    public synchronized void update(LiveIndex index, SearchIndexDefinition definition) {
        String etag = Etags.next();
        persistence.saveIndex(service, definition.name(), definition.json(), etag);
        index.redefine(definition, etag);
    }

    /** Deletes the index and its documents; false when there was none. */
    public synchronized boolean delete(String name) {
        LiveIndex removed = indexes.remove(name);
        if (removed == null) {
            return false;
        }
        persistence.deleteIndex(service, name);
        return true;
    }
}
