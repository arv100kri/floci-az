package io.floci.az.services.search.model;

/**
 * What an index definition needs to know about analyzer and normalizer names it does not define itself: which names
 * are built in, and which of those are language analyzers (which {@code searchAnalyzer} and {@code indexAnalyzer} may
 * not name). The engine's analyzer registry implements it.
 */
public interface AnalysisCatalog {

    boolean isBuiltInAnalyzer(String name);

    boolean isLanguageAnalyzer(String name);

    boolean isBuiltInNormalizer(String name);
}
