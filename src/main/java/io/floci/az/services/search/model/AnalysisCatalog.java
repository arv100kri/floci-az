package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What an index definition needs to know about analyzer and normalizer names it does not define itself: which names
 * are built in, and which of those are language analyzers (which {@code searchAnalyzer} and {@code indexAnalyzer} may
 * not name). The engine's analyzer registry implements it.
 */
public interface AnalysisCatalog {

    boolean isBuiltInAnalyzer(String name);

    boolean isLanguageAnalyzer(String name);

    boolean isBuiltInNormalizer(String name);

    /**
     * Validates the analysis sections of a normalised index definition ({@code analyzers}, {@code tokenizers},
     * {@code tokenFilters}, {@code charFilters}, {@code normalizers}): component names, types, parameters and the
     * names they reference. Throws a {@link SearchException} for a definition the service refuses.
     */
    default void validateAnalysis(ObjectNode index) {
    }
}
