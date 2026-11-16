package io.floci.az.services.search.engine.analysis;

import io.floci.az.services.search.model.AnalysisCatalog;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in analyzers and normalizers by name ({@link BuiltInAnalyzers}). Immutable and shared; index-defined
 * analyzers are resolved per index by {@link IndexAnalysis}.
 */
public final class AnalyzerRegistry implements AnalysisCatalog {

    private static final AnalyzerRegistry DEFAULT = new AnalyzerRegistry(BuiltInAnalyzers.analyzers(),
        BuiltInAnalyzers.normalizers());

    private final Map<String, Analyzer> analyzers;
    private final Map<String, LexicalNormalizer> normalizers;

    private AnalyzerRegistry(Map<String, Analyzer> analyzers, Map<String, LexicalNormalizer> normalizers) {
        this.analyzers = Map.copyOf(analyzers);
        this.normalizers = Map.copyOf(normalizers);
    }

    /** The registry of every built-in analyzer and normalizer. */
    public static AnalyzerRegistry defaultRegistry() {
        return DEFAULT;
    }

    /** The built-in analyzer named {@code name}. */
    public Optional<Analyzer> analyzer(String name) {
        return Optional.ofNullable(analyzers.get(name));
    }

    /** The standard Lucene analyzer, the default of every searchable field. */
    public Analyzer standard() {
        return analyzers.get(BuiltInAnalyzers.STANDARD);
    }

    /** The built-in normalizer named {@code name}. */
    public Optional<LexicalNormalizer> normalizer(String name) {
        return Optional.ofNullable(normalizers.get(name));
    }

    public Set<String> analyzerNames() {
        return analyzers.keySet();
    }

    @Override
    public boolean isBuiltInAnalyzer(String name) {
        return analyzers.containsKey(name);
    }

    @Override
    public boolean isLanguageAnalyzer(String name) {
        return analyzers.containsKey(name) && !BuiltInAnalyzers.GENERIC_ANALYZER_NAMES.contains(name);
    }

    @Override
    public boolean isBuiltInNormalizer(String name) {
        return normalizers.containsKey(name);
    }
}
