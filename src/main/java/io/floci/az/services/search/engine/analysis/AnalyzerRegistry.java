package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.AnalysisCatalog;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in analyzers and normalizers by name ({@link BuiltInAnalyzers}), and the synonym maps of the search
 * service an index belongs to. Immutable and shared: {@link #defaultRegistry()} serves every service that has no
 * synonym maps, and {@link #forService} binds the same built-ins to one service's synonym maps. Index-defined analyzers
 * are resolved per index by {@link IndexAnalysis}. Built-in analyzer names match ignoring case, as Learn spells some of
 * them differently from the API ({@code pt-Br.microsoft}).
 */
public final class AnalyzerRegistry implements AnalysisCatalog {

    private static final AnalyzerRegistry DEFAULT = new AnalyzerRegistry(BuiltInAnalyzers.analyzers(),
        BuiltInAnalyzers.normalizers(), null, SynonymMapSource.NONE);

    private final Map<String, Analyzer> analyzers;
    private final Map<String, Analyzer> analyzersIgnoringCase;
    private final Map<String, LexicalNormalizer> normalizers;
    private final String service;
    private final SynonymMapSource synonymMaps;

    private AnalyzerRegistry(Map<String, Analyzer> analyzers, Map<String, LexicalNormalizer> normalizers,
                             String service, SynonymMapSource synonymMaps) {
        this.analyzers = Map.copyOf(analyzers);
        Map<String, Analyzer> lowered = new HashMap<>();
        analyzers.forEach((name, analyzer) -> lowered.put(name.toLowerCase(Locale.ROOT), analyzer));
        this.analyzersIgnoringCase = Map.copyOf(lowered);
        this.normalizers = Map.copyOf(normalizers);
        this.service = service;
        this.synonymMaps = synonymMaps;
    }

    /** The registry of every built-in analyzer and normalizer. */
    public static AnalyzerRegistry defaultRegistry() {
        return DEFAULT;
    }

    /** These built-ins, for the indexes of {@code service}, whose synonym maps {@code source} holds. */
    public AnalyzerRegistry forService(String service, SynonymMapSource source) {
        return new AnalyzerRegistry(analyzers, normalizers, service, source);
    }

    /** The synonym map {@code name} of this registry's service, if the service has one by that name. */
    public Optional<SynonymMap> synonymMap(String name) {
        return service == null ? Optional.empty() : synonymMaps.find(service, name);
    }

    /** The built-in analyzer named {@code name}. */
    public Optional<Analyzer> analyzer(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Analyzer exact = analyzers.get(name);
        return Optional.ofNullable(exact != null ? exact : analyzersIgnoringCase.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The standard Lucene analyzer, the default of every searchable field. */
    public Analyzer standard() {
        return analyzers.get(BuiltInAnalyzers.STANDARD);
    }

    /** The built-in normalizer named {@code name}. */
    public Optional<LexicalNormalizer> normalizer(String name) {
        return Optional.ofNullable(name == null ? null : normalizers.get(name));
    }

    public Set<String> analyzerNames() {
        return analyzers.keySet();
    }

    /** The built-in normalizer names. */
    public Set<String> normalizerNames() {
        return normalizers.keySet();
    }

    @Override
    public boolean isBuiltInAnalyzer(String name) {
        return analyzer(name).isPresent();
    }

    @Override
    public boolean isLanguageAnalyzer(String name) {
        return analyzer(name).map(analyzer -> !BuiltInAnalyzers.GENERIC_ANALYZER_NAMES.contains(analyzer.name()))
            .orElse(false);
    }

    @Override
    public boolean isBuiltInNormalizer(String name) {
        return normalizers.containsKey(name);
    }

    @Override
    public void validateAnalysis(ObjectNode index) {
        AnalysisDefinitionValidator.validate(index, this);
    }
}
