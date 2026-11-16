package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.model.SearchException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Data source management: Create, Create or Update, Get, List and Delete Data Source. Writes to one service are
 * serialized, so an {@code If-Match} or {@code If-None-Match} condition and the write it guards see the same
 * definition.
 */
@ApplicationScoped
public class DataSourceService {

    private final IndexerStore store;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * The outcome of a create or update.
     *
     * @param definition the stored definition
     * @param created    whether it was created
     */
    public record Write(DataSourceDefinition definition, boolean created) {
    }

    @Inject
    public DataSourceService(IndexerStore store) {
        this.store = store;
    }

    public List<DataSourceDefinition> list(String service) {
        return store.dataSources(service);
    }

    /** The data source; a 404 when there is none. */
    public DataSourceDefinition get(String service, String name) {
        return store.dataSource(service, name).orElseThrow(() -> notFound(name));
    }

    /** Create Data Source (POST): a 409 when the name is taken. */
    public DataSourceDefinition create(String service, JsonNode body) {
        synchronized (lock(service)) {
            DataSourceDefinition parsed = DataSourceParser.parse(body, null, null);
            if (store.dataSource(service, parsed.name()).isPresent()) {
                throw SearchException.alreadyExists("Cannot create data source '" + parsed.name() + "' because it "
                    + "already exists.");
            }
            DataSourceDefinition stored = withEtag(parsed);
            store.putDataSource(service, stored);
            return stored;
        }
    }

    /**
     * Create or Update Data Source (PUT).
     *
     * @param preconditions checks {@code If-Match} and {@code If-None-Match} against the current ETag (null: absent)
     */
    public Write createOrUpdate(String service, String name, JsonNode body, Consumer<String> preconditions) {
        synchronized (lock(service)) {
            Optional<DataSourceDefinition> existing = store.dataSource(service, name);
            preconditions.accept(existing.map(DataSourceDefinition::etag).orElse(null));
            DataSourceDefinition stored = withEtag(DataSourceParser.parse(body, name, existing.orElse(null)));
            store.putDataSource(service, stored);
            if (existing.isPresent()) {
                for (IndexerDefinition indexer : store.indexers(service)) {
                    if (indexer.dataSourceName().equals(name)) {
                        store.state(service, indexer.name()).clearError();
                    }
                }
            }
            return new Write(stored, existing.isEmpty());
        }
    }

    /** Delete Data Source; a 404 when there is none. */
    public void delete(String service, String name, Consumer<String> preconditions) {
        synchronized (lock(service)) {
            Optional<DataSourceDefinition> existing = store.dataSource(service, name);
            preconditions.accept(existing.map(DataSourceDefinition::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(name);
            }
            store.deleteDataSource(service, name);
        }
    }

    public long count(String service) {
        return store.dataSourceCount(service);
    }

    private static DataSourceDefinition withEtag(DataSourceDefinition parsed) {
        return new DataSourceDefinition(parsed.name(), parsed.type(), parsed.json(), Etags.next());
    }

    private Object lock(String service) {
        return locks.computeIfAbsent(service.toLowerCase(Locale.ROOT), ignored -> new Object());
    }

    static SearchException notFound(String name) {
        return SearchException.notFound("No data source with the name '" + name + "' was found.");
    }
}
