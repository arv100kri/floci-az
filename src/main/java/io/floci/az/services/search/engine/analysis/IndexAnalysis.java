package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The analyzers and normalizers one index uses: the ones it defines, compiled once, then the built-in ones; and the
 * synonym maps of its service. Immutable; a new instance is built whenever the index definition changes.
 */
public final class IndexAnalysis {

    private static final Logger LOG = Logger.getLogger(IndexAnalysis.class);

    private final AnalyzerRegistry registry;
    private final AnalysisComponents components;
    private final Map<String, Analyzer> customAnalyzers;
    private final Map<String, LexicalNormalizer> customNormalizers;

    private IndexAnalysis(AnalyzerRegistry registry, AnalysisComponents components,
                          Map<String, Analyzer> customAnalyzers, Map<String, LexicalNormalizer> customNormalizers) {
        this.registry = registry;
        this.components = components;
        this.customAnalyzers = Map.copyOf(customAnalyzers);
        this.customNormalizers = Map.copyOf(customNormalizers);
    }

    public static IndexAnalysis of(SearchIndexDefinition definition, AnalyzerRegistry registry) {
        Map<String, Analyzer> analyzers = new HashMap<>();
        Map<String, LexicalNormalizer> normalizers = new HashMap<>();
        JsonNode json = definition.json();
        AnalysisComponents components = AnalysisComponents.of(json);
        for (JsonNode analyzer : json.path("analyzers")) {
            String name = SearchJson.text(analyzer, "name");
            try {
                analyzers.put(name, components.analyzer(analyzer));
            } catch (IllegalArgumentException e) {
                LOG.warnv("The analyzer {0} of index {1} is invalid and analyzes like standard.lucene: {2}", name,
                    definition.name(), e.getMessage());
            }
        }
        for (JsonNode normalizer : json.path("normalizers")) {
            String name = SearchJson.text(normalizer, "name");
            try {
                normalizers.put(name, components.normalizer(normalizer));
            } catch (IllegalArgumentException e) {
                LOG.warnv("The normalizer {0} of index {1} is invalid and is ignored: {2}", name, definition.name(),
                    e.getMessage());
            }
        }
        return new IndexAnalysis(registry, components, analyzers, normalizers);
    }

    /** The analyzer named {@code name}: the index's own first, then a built-in one, else the standard analyzer. */
    public Analyzer analyzer(String name) {
        Analyzer custom = customAnalyzers.get(name);
        if (custom != null) {
            return custom;
        }
        return registry.analyzer(name).orElseGet(registry::standard);
    }

    /** The analyzer named {@code name}, the index's own or a built-in one, if either exists. */
    public Optional<Analyzer> findAnalyzer(String name) {
        Analyzer custom = customAnalyzers.get(name);
        return custom != null ? Optional.of(custom) : registry.analyzer(name);
    }

    /** The analyzer that turns values of {@code field} into indexed terms. */
    public Analyzer indexAnalyzer(SearchFieldDefinition field) {
        return analyzer(field.effectiveIndexAnalyzer());
    }

    /** The analyzer that turns query text into terms for {@code field}. */
    public Analyzer searchAnalyzer(SearchFieldDefinition field) {
        return analyzer(field.effectiveSearchAnalyzer());
    }

    /** The normalizer {@code field} names, if any. */
    public Optional<LexicalNormalizer> normalizer(SearchFieldDefinition field) {
        return field.normalizer() == null ? Optional.empty() : findNormalizer(field.normalizer());
    }

    /** The normalizer named {@code name}, the index's own or a built-in one, if either exists. */
    public Optional<LexicalNormalizer> findNormalizer(String name) {
        LexicalNormalizer custom = customNormalizers.get(name);
        return custom != null ? Optional.of(custom) : registry.normalizer(name);
    }

    /** The tokenizers, token filters and character filters of this index and the built-in ones, by name. */
    public AnalysisComponents components() {
        return components;
    }

    /** The synonym map {@code name} of the index's service, if it exists. */
    public Optional<SynonymMap> synonymMap(String name) {
        return registry.synonymMap(name);
    }
}
