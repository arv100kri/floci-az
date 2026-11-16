package io.floci.az.services.search.engine.analysis;

import java.util.Optional;

/**
 * Where the engine finds the synonym maps of a search service; the data plane's synonym map store implements it.
 * Synonym maps are service-level resources, so indexes reach them through the analyzer registry bound to their service
 * ({@link AnalyzerRegistry#forService}).
 */
@FunctionalInterface
public interface SynonymMapSource {

    /** A source with no synonym maps. */
    SynonymMapSource NONE = (service, name) -> Optional.empty();

    /** The current synonym map {@code name} of {@code service}, if it exists. */
    Optional<SynonymMap> find(String service, String name);
}
