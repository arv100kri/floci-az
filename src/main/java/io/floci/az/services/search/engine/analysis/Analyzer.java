package io.floci.az.services.search.engine.analysis;

import java.util.List;

/**
 * Turns text into the terms an index stores and a query matches. Implementations are immutable and thread-safe.
 *
 * <p>Built-in analyzers are registered in {@link BuiltInAnalyzers}; analyzers an index defines are compiled by
 * {@link AnalysisComponents}. Most analyzers are a {@link ComposedAnalyzer}: character filters, one tokenizer, then
 * token filters.</p>
 */
public interface Analyzer {

    /** The analyzer's name as an index definition refers to it. */
    String name();

    /** The tokens of {@code text}, in order, with positions and offsets into {@code text}. */
    List<Token> analyze(String text);

    /**
     * How a term of a prefix, wildcard, regex or fuzzy query is normalised before it is matched against indexed
     * terms. Such terms are not tokenized; only case and character folding apply, the way the analyzer's
     * normalizing filters would apply them.
     */
    String normalizeTerm(String term);
}
