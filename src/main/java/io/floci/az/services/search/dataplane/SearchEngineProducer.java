package io.floci.az.services.search.dataplane;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.EmulatorConfig.SearchConfig;
import io.floci.az.config.EmulatorConfig.SemanticRankerMode;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** Creates the one {@link SearchEngine}, over the {@code search} storage backend and the search settings. */
@ApplicationScoped
public class SearchEngineProducer {

    @Produces
    @Singleton
    SearchEngine searchEngine(StorageFactory storageFactory, EmulatorConfig config, SynonymMapStore synonymMaps) {
        SearchConfig search = config.services().search();
        EngineSettings settings = new EngineSettings(search.maxDocumentsPerIndex(),
            search.semanticRanker() == SemanticRankerMode.STAND_IN, search.modelEndpoint().orElse(null));
        return new SearchEngine(new StorageIndexPersistence(storageFactory.create("search")), settings,
            SearchPipeline.standard(), synonymMaps);
    }
}
