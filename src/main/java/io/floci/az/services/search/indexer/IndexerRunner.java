package io.floci.az.services.search.indexer;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.blob.BlobReader;
import io.floci.az.services.cosmos.CosmosReader;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.indexer.IndexerState.RunStart;
import io.floci.az.services.table.TableReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs indexers: the collaborators an {@link IndexerRun} reads from and writes to. */
@ApplicationScoped
public class IndexerRunner {

    private final IndexerStore store;
    private final SearchEngine engine;
    private final BlobReader blobs;
    private final TableReader tables;
    private final CosmosReader cosmos;
    private final SkillsetRunner skills;
    private final ManagedIdentityAccess identities;
    private final IndexerNetworkAccess network;
    private final EmulatorConfig config;

    @Inject
    public IndexerRunner(IndexerStore store, SearchEngine engine, BlobReader blobs, TableReader tables,
                         CosmosReader cosmos, SkillsetRunner skills, ManagedIdentityAccess identities,
                         IndexerNetworkAccess network, EmulatorConfig config) {
        this.store = store;
        this.engine = engine;
        this.blobs = blobs;
        this.tables = tables;
        this.cosmos = cosmos;
        this.skills = skills;
        this.identities = identities;
        this.network = network;
        this.config = config;
    }

    /**
     * Runs an indexer once, on the calling thread.
     *
     * @param service     the search service
     * @param indexer     the indexer definition, as it was when the run started
     * @param start       what the run starts from
     * @param cancelled   whether the run must stop (the emulator is resetting or shutting down)
     * @param progress    receives the result in progress after each batch
     * @return the run's outcome
     */
    public IndexerRun.Outcome run(SearchServiceInfo service, IndexerDefinition indexer, RunStart start,
                                  BooleanSupplier cancelled, Consumer<ExecutionResult> progress) {
        return new IndexerRun(this, service, indexer, start, cancelled, progress).execute();
    }

    IndexerStore store() {
        return store;
    }

    SearchEngine engine() {
        return engine;
    }

    BlobReader blobs() {
        return blobs;
    }

    TableReader tables() {
        return tables;
    }

    CosmosReader cosmos() {
        return cosmos;
    }

    SkillsetRunner skills() {
        return skills;
    }

    ManagedIdentityAccess identities() {
        return identities;
    }

    IndexerNetworkAccess network() {
        return network;
    }

    /** The emulator's base URL, for the {@code metadata_storage_path} of blobs. */
    String baseUrl() {
        String base = config.effectiveBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
