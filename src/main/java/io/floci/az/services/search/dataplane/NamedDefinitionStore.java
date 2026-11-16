package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps one kind of named, service-level search resource (synonym maps, aliases) in the shared {@code search} storage
 * backend, as {@code dp/{kind}/{service}/{name}} holding the definition JSON, with its ETag in the stored object. The
 * engine's reset clears every {@code dp/} key, these included.
 */
final class NamedDefinitionStore {

    private static final Logger LOG = Logger.getLogger(NamedDefinitionStore.class);

    private final StorageBackend<String, StoredObject> store;
    private final String prefix;

    NamedDefinitionStore(StorageBackend<String, StoredObject> store, String kind) {
        this.store = store;
        this.prefix = "dp/" + kind + "/";
    }

    /**
     * One stored definition.
     *
     * @param name       the resource name
     * @param definition the definition as GET returns it, without {@code @odata.etag}; never mutate it
     * @param etag       the ETag
     */
    record StoredDefinition(String name, ObjectNode definition, String etag) {
    }

    Optional<StoredDefinition> find(String service, String name) {
        return store.get(key(service, name)).flatMap(stored -> parse(stored, name));
    }

    /** The service's definitions, ordered by name. */
    List<StoredDefinition> list(String service) {
        String servicePrefix = prefix + service + "/";
        List<StoredDefinition> definitions = new ArrayList<>();
        for (StoredObject stored : store.scan(key -> key.startsWith(servicePrefix))) {
            parse(stored, stored.key().substring(servicePrefix.length())).ifPresent(definitions::add);
        }
        definitions.sort(Comparator.comparing(StoredDefinition::name));
        return definitions;
    }

    long count(String service) {
        String servicePrefix = prefix + service + "/";
        return store.keys().stream().filter(key -> key.startsWith(servicePrefix)).count();
    }

    void put(String service, String name, ObjectNode definition, String etag) {
        String key = key(service, name);
        store.put(key, new StoredObject(key, SearchJson.writeBytes(definition), Map.of(), Instant.now(), etag));
    }

    boolean delete(String service, String name) {
        return store.remove(key(service, name)).isPresent();
    }

    private String key(String service, String name) {
        return prefix + service + "/" + name;
    }

    private static Optional<StoredDefinition> parse(StoredObject stored, String name) {
        try {
            JsonNode node = SearchJson.parse(stored.data());
            if (node instanceof ObjectNode object) {
                return Optional.of(new StoredDefinition(name, object, stored.etag()));
            }
            LOG.errorv("The stored search object {0} is not a JSON object and was skipped", stored.key());
        } catch (SearchException e) {
            LOG.errorv("The stored search object {0} is not valid JSON and was skipped: {1}", stored.key(),
                e.getMessage());
        }
        return Optional.empty();
    }
}
