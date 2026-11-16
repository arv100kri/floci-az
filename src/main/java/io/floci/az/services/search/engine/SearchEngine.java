package io.floci.az.services.search.engine;

import io.floci.az.services.search.engine.analysis.AnalyzerRegistry;
import io.floci.az.services.search.engine.analysis.SynonymMapSource;
import io.floci.az.services.search.engine.index.IndexAction;
import io.floci.az.services.search.engine.index.IndexPersistence;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.model.IndexingResult;
import io.floci.az.services.search.model.SearchRequest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-process search engine: every service's indexes, the analyzers, and the query pipeline. Plain Java with no
 * Quarkus dependency; the data plane creates one and hands it its persistence and settings.
 */
public final class SearchEngine {

    private final IndexPersistence persistence;
    private final EngineSettings settings;
    private final AnalyzerRegistry analyzers;
    private final SearchPipeline pipeline;
    private final SynonymMapSource synonymMaps;
    private final Map<String, ServiceIndexes> services = new ConcurrentHashMap<>();

    public SearchEngine(IndexPersistence persistence, EngineSettings settings, SearchPipeline pipeline,
                        SynonymMapSource synonymMaps) {
        this.persistence = persistence;
        this.settings = settings;
        this.analyzers = AnalyzerRegistry.defaultRegistry();
        this.pipeline = pipeline;
        this.synonymMaps = synonymMaps;
    }

    public SearchEngine(IndexPersistence persistence, EngineSettings settings, SearchPipeline pipeline) {
        this(persistence, settings, pipeline, SynonymMapSource.NONE);
    }

    public SearchEngine(IndexPersistence persistence, EngineSettings settings) {
        this(persistence, settings, SearchPipeline.standard());
    }

    public EngineSettings settings() {
        return settings;
    }

    public AnalyzerRegistry analyzers() {
        return analyzers;
    }

    public SearchPipeline pipeline() {
        return pipeline;
    }

    /** The indexes of {@code service}, loading them from persistence on first use. Service names ignore case. */
    public ServiceIndexes service(String service) {
        return services.computeIfAbsent(service.toLowerCase(Locale.ROOT),
            name -> new ServiceIndexes(name, persistence, analyzers.forService(name, synonymMaps)));
    }

    /** Runs a query. */
    public SearchOutcome search(LiveIndex index, SearchRequest request) {
        return pipeline.run(index, request, settings);
    }

    /** Applies an indexing batch to {@code index} of {@code service} and persists its changes. */
    public List<IndexingResult> index(String service, LiveIndex index, List<IndexAction> actions) {
        String serviceName = service.toLowerCase(Locale.ROOT);
        return index.apply(actions, settings.maxDocumentsPerIndex(),
            (upserts, deletes) -> persistence.saveDocuments(serviceName, index.name(), upserts, deletes));
    }

    /** Forgets every service, index and document, persisted ones included. */
    public void clear() {
        services.clear();
        persistence.clear();
    }
}
