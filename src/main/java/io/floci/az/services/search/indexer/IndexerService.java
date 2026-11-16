package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Indexer management and execution: Create, Create or Update, Get, List and Delete Indexer; Run, Reset, Reset
 * Documents and Resync; Get Indexer Status. An indexer runs when it is created or updated unless it is disabled ("Run
 * on indexer creation or update (default)"). Its data source, target index and skillset must exist, and its field
 * mappings must target top-level, non-complex index fields.
 */
@ApplicationScoped
public class IndexerService {

    private final IndexerStore store;
    private final IndexerScheduler scheduler;
    private final SearchEngine engine;
    private final EmulatorConfig config;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * The outcome of a create or update.
     *
     * @param definition the stored definition
     * @param created    whether it was created
     */
    public record Write(IndexerDefinition definition, boolean created) {
    }

    @Inject
    public IndexerService(IndexerStore store, IndexerScheduler scheduler, SearchEngine engine, EmulatorConfig config) {
        this.store = store;
        this.scheduler = scheduler;
        this.engine = engine;
        this.config = config;
    }

    public List<IndexerDefinition> list(String service) {
        return store.indexers(service);
    }

    /** The indexer; a 404 when there is none. */
    public IndexerDefinition get(String service, String name) {
        return store.indexer(service, name).orElseThrow(() -> notFound(name));
    }

    /** Create Indexer (POST): a 409 when the name is taken. The indexer runs unless it is disabled. */
    public IndexerDefinition create(String service, JsonNode body) {
        IndexerDefinition stored;
        synchronized (lock(service)) {
            IndexerDefinition parsed = IndexerDefinitionParser.parse(body, null, null, minInterval(), Instant.now());
            if (store.indexer(service, parsed.name()).isPresent()) {
                throw SearchException.alreadyExists("Cannot create indexer '" + parsed.name() + "' because it "
                    + "already exists.");
            }
            validateReferences(service, parsed);
            stored = parsed.withEtag(Etags.next());
            store.putIndexer(service, stored);
            scheduler.schedule(service, stored, false);
        }
        if (!stored.disabled()) {
            scheduler.start(service, stored.name());
        }
        return stored;
    }

    /**
     * Create or Update Indexer (PUT). The indexer runs unless it is disabled or already running.
     *
     * @param preconditions checks {@code If-Match} and {@code If-None-Match} against the current ETag (null: absent)
     */
    public Write createOrUpdate(String service, String name, JsonNode body, Consumer<String> preconditions) {
        IndexerDefinition stored;
        boolean created;
        synchronized (lock(service)) {
            Optional<IndexerDefinition> existing = store.indexer(service, name);
            preconditions.accept(existing.map(IndexerDefinition::etag).orElse(null));
            IndexerDefinition parsed = IndexerDefinitionParser.parse(body, name, existing.orElse(null), minInterval(),
                Instant.now());
            validateReferences(service, parsed);
            stored = parsed.withEtag(Etags.next());
            store.putIndexer(service, stored);
            created = existing.isEmpty();
            boolean reenabled = false;
            if (existing.isPresent()) {
                store.state(service, name).clearError();
                reenabled = existing.get().disabled() && !stored.disabled();
                if (!Objects.equals(existing.get().schedule(), stored.schedule())) {
                    scheduler.resetBaseline(service, name);
                }
            }
            scheduler.schedule(service, stored, reenabled);
        }
        if (!stored.disabled()) {
            scheduler.start(service, name);
        }
        return new Write(stored, created);
    }

    /** Delete Indexer; a 404 when there is none. A run in progress is cancelled and its results discarded. */
    public void delete(String service, String name, Consumer<String> preconditions) {
        synchronized (lock(service)) {
            Optional<IndexerDefinition> existing = store.indexer(service, name);
            preconditions.accept(existing.map(IndexerDefinition::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(name);
            }
            scheduler.forget(service, name);
            store.deleteIndexer(service, name);
        }
    }

    /** Run Indexer: false when a run is already in progress. */
    public boolean run(String service, String name) {
        get(service, name);
        return scheduler.start(service, name);
    }

    /** Reset Indexer: clears the high-water mark; the next run indexes every document in full. */
    public void reset(String service, String name) {
        get(service, name);
        IndexerState state = store.state(service, name);
        state.reset(Instant.now());
        store.saveState(service, name, state);
    }

    /**
     * Reset Documents: queues document keys or data source document ids for the next run, which processes only them.
     *
     * @param overwrite whether the new keys or ids replace the queued ones instead of adding to them
     */
    public void resetDocuments(String service, String name, JsonNode body, boolean overwrite) {
        get(service, name);
        List<String> keys = strings(body, "documentKeys");
        List<String> ids = strings(body, "datasourceDocumentIds");
        if (keys == null && ids == null) {
            throw IndexerDefinitionParser.invalid("give the documentKeys or the datasourceDocumentIds to reset.");
        }
        if (keys != null && !keys.isEmpty() && ids != null && !ids.isEmpty()) {
            throw IndexerDefinitionParser.invalid("give either documentKeys or datasourceDocumentIds, not both.");
        }
        IndexerState state = store.state(service, name);
        state.resetDocuments(keys, ids, overwrite);
        store.saveState(service, name, state);
    }

    /** Resync: the next run reprocesses every document; {@code options} must list {@code permissions}. */
    public void resync(String service, String name, JsonNode body) {
        get(service, name);
        List<String> options = strings(body, "options");
        if (options == null || options.isEmpty()) {
            throw IndexerDefinitionParser.invalid("options is required; the only supported option is 'permissions'.");
        }
        for (String option : options) {
            if (!"permissions".equals(option)) {
                throw IndexerDefinitionParser.invalid("the resync option '" + option + "' is not supported; the only "
                    + "supported option is 'permissions'.");
            }
        }
        IndexerState state = store.state(service, name);
        state.resync();
        store.saveState(service, name, state);
    }

    /**
     * Get Indexer Status: the overall status, the last (or in-progress) result, the execution history newest first,
     * the limits, and for preview API versions the {@code currentState}.
     */
    public ObjectNode status(SearchServiceInfo service, String serviceName, String name, boolean preview) {
        IndexerDefinition definition = get(serviceName, name);
        IndexerState state = store.state(serviceName, name);
        ObjectNode status = SearchJson.object();
        status.put("name", definition.name());
        status.put("status", state.status());
        ExecutionResult last = state.lastResult();
        if (last == null) {
            status.putNull("lastResult");
        } else {
            status.set("lastResult", last.toJson(preview));
        }
        ArrayNode history = status.putArray("executionHistory");
        for (ExecutionResult result : state.history()) {
            history.add(result.toJson(preview));
        }
        status.set("limits", IndexerLimits.of(service.sku(), definition.parameters().executionEnvironment()).toJson());
        if (preview) {
            status.set("currentState", state.currentState());
        }
        return status;
    }

    public long count(String service) {
        return store.indexerCount(service);
    }

    private void validateReferences(String service, IndexerDefinition definition) {
        if (store.dataSource(service, definition.dataSourceName()).isEmpty()) {
            throw IndexerDefinitionParser.invalid("the data source '" + definition.dataSourceName() + "' does not "
                + "exist.");
        }
        LiveIndex index = engine.service(service).find(definition.targetIndexName()).orElseThrow(() ->
            IndexerDefinitionParser.invalid("the target index '" + definition.targetIndexName() + "' does not exist."));
        if (definition.skillsetName() != null && store.skillset(service, definition.skillsetName()).isEmpty()) {
            throw IndexerDefinitionParser.invalid("the skillset '" + definition.skillsetName() + "' does not exist.");
        }
        DocumentMapper mapper = new DocumentMapper(index.definition(), definition.fieldMappings(), false, false);
        for (FieldMapping mapping : definition.fieldMappings()) {
            if (mapper.targetOf(mapping) == null) {
                throw IndexerDefinitionParser.invalid("Field mapping specifies target field '"
                    + mapping.effectiveTarget() + "' that doesn't exist in the index.");
            }
        }
    }

    private Duration minInterval() {
        return Duration.ofSeconds(Math.max(1, config.services().search().indexerMinIntervalSeconds()));
    }

    /** The strings of {@code body[field]}; null when it is absent, a 400 when it is not an array of strings. */
    private static List<String> strings(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (IndexerJson.isNull(value)) {
            return null;
        }
        if (!(value instanceof ArrayNode array)) {
            throw IndexerDefinitionParser.invalid(field + " must be an array of strings.");
        }
        List<String> strings = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw IndexerDefinitionParser.invalid(field + " must be an array of strings.");
            }
            strings.add(item.textValue());
        }
        return strings;
    }

    private Object lock(String service) {
        return locks.computeIfAbsent(service.toLowerCase(Locale.ROOT), ignored -> new Object());
    }

    static SearchException notFound(String name) {
        return SearchException.notFound("Indexer '" + name + "' was not found.");
    }
}
