package io.floci.az.services.search.engine.analysis;

import java.util.List;

/** Rewrites, drops or adds tokens after tokenization. Implementations are thread-safe. */
public interface TokenFilter {

    /** The filtered tokens. A filter that drops a token keeps the positions of the others, leaving a gap. */
    List<Token> filter(List<Token> tokens);

    /**
     * What this filter does to a term of a prefix, wildcard, regex or fuzzy query. Only normalizing filters (case and
     * character folding) change such terms; the default leaves the term alone, which is right for stop words,
     * stemmers and filters that add or split tokens.
     */
    default String normalizeTerm(String term) {
        return term;
    }
}
