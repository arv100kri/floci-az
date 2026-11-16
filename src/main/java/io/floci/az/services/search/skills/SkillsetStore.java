package io.floci.az.services.search.skills;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps skillset definitions in the shared {@code search} storage backend, one key per skillset:
 * {@code dp/skillset/{service}/{name}} holds the definition as GET returns it (the JSON the client sent, normalised,
 * with the defaults the service adds, without {@code @odata.etag}), and the stored object carries the ETag. The service
 * name is lowercased. Nothing is cached: the backend is the only state, so a data-plane reset, which clears every
 * {@code dp/} key, clears skillsets too.
 */
@ApplicationScoped
public class SkillsetStore {

    /** The prefix of every skillset key. */
    public static final String PREFIX = "dp/skillset/";

    private static final Logger LOG = Logger.getLogger(SkillsetStore.class);

    private final StorageBackend<String, StoredObject> backend;

    @Inject
    public SkillsetStore(StorageFactory storageFactory) {
        this(storageFactory.create("search"));
    }

    SkillsetStore(StorageBackend<String, StoredObject> backend) {
        this.backend = backend;
    }

    /** The storage key of skillset {@code name} of {@code service}. */
    public static String key(String service, String name) {
        return PREFIX + service.toLowerCase(Locale.ROOT) + "/" + name;
    }

    /** The skillset {@code name} of {@code service}. */
    public Optional<StoredSkillset> find(String service, String name) {
        return backend.get(key(service, name)).map(this::read).filter(stored -> stored.definition() != null);
    }

    /** The skillsets of {@code service}, ordered by name. */
    public List<StoredSkillset> list(String service) {
        String prefix = PREFIX + service.toLowerCase(Locale.ROOT) + "/";
        List<StoredSkillset> skillsets = new ArrayList<>();
        for (StoredObject stored : backend.scan(key -> key.startsWith(prefix))) {
            StoredSkillset skillset = read(stored);
            if (skillset.definition() != null) {
                skillsets.add(skillset);
            }
        }
        skillsets.sort(Comparator.comparing(StoredSkillset::name));
        return skillsets;
    }

    /** How many skillsets {@code service} has. */
    public long count(String service) {
        String prefix = PREFIX + service.toLowerCase(Locale.ROOT) + "/";
        return backend.keys().stream().filter(key -> key.startsWith(prefix)).count();
    }

    public void save(String service, String name, ObjectNode definition, String etag) {
        String key = key(service, name);
        backend.put(key, new StoredObject(key, SearchJson.writeBytes(definition), Map.of(), Instant.now(), etag));
    }

    /** Deletes the skillset; false when there was none. */
    public boolean delete(String service, String name) {
        return backend.remove(key(service, name)).isPresent();
    }

    private StoredSkillset read(StoredObject stored) {
        String name = stored.key().substring(stored.key().lastIndexOf('/') + 1);
        try {
            JsonNode node = SearchJson.parse(stored.data());
            if (node instanceof ObjectNode definition) {
                return new StoredSkillset(name, definition, stored.etag());
            }
            LOG.errorv("The stored skillset {0} is not a JSON object and was skipped", stored.key());
        } catch (SearchException e) {
            LOG.errorv("The stored skillset {0} is not valid JSON and was skipped: {1}", stored.key(), e.getMessage());
        }
        return new StoredSkillset(name, null, stored.etag());
    }

    /**
     * A stored skillset.
     *
     * @param name       the skillset name
     * @param definition the definition as GET returns it, without {@code @odata.etag}; a fresh copy
     * @param etag       the ETag
     */
    public record StoredSkillset(String name, ObjectNode definition, String etag) {
    }
}
