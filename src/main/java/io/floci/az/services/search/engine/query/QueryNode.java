package io.floci.az.services.search.engine.query;

import java.util.List;

/**
 * The query tree a query parser produces and {@link QueryExecutor} runs. Leaf nodes carry the user's text, not
 * analyzed terms: the executor analyzes it once per target field with that field's search analyzer, so one tree serves
 * fields with different analyzers. Every node type both syntaxes need is defined here; the simple parser emits
 * {@link Term}, {@link Phrase}, {@link Prefix}, {@link Bool} and {@link MatchAll}, and the full Lucene parser emits the
 * rest.
 */
public sealed interface QueryNode {

    /** How a clause of a {@link Bool} takes part in matching and scoring. */
    enum Occur {
        /** Must match; contributes to the score. */
        MUST,
        /** Optional when the query has a MUST or FILTER clause, else at least one SHOULD must match; scores. */
        SHOULD,
        /** Must not match. */
        MUST_NOT,
        /** Must match; does not contribute to the score. */
        FILTER
    }

    /**
     * A word. Analysis may turn it into several terms (a hyphenated word under the standard analyzer); they then
     * combine with {@code multiTokenOccur}, the default operator of the query's search mode.
     */
    record Term(String text, Occur multiTokenOccur) implements QueryNode {
    }

    /** Words that must appear in order; {@code slop} is how many moves out of position still match. */
    record Phrase(String text, int slop) implements QueryNode {
    }

    /** Terms starting with {@code prefix} ({@code lingui*}); unscored, so every match scores 1. */
    record Prefix(String prefix) implements QueryNode {
    }

    /** Terms matching a pattern of {@code *} (any characters) and {@code ?} (one character); unscored. */
    record Wildcard(String pattern) implements QueryNode {
    }

    /** Terms matching a regular expression ({@code /[mh]otel/}) as a whole; unscored. */
    record Regex(String pattern) implements QueryNode {
    }

    /** Terms within {@code maxEdits} edits (0 to 2) of {@code term}; unscored. */
    record Fuzzy(String term, int maxEdits) implements QueryNode {
    }

    /** Clauses combined by their {@link Occur}. */
    record Bool(List<Clause> clauses) implements QueryNode {

        public Bool {
            clauses = List.copyOf(clauses);
        }
    }

    /** One clause of a {@link Bool}. */
    record Clause(Occur occur, QueryNode query) {
    }

    /** {@code query} restricted to the field at {@code fieldPath} ({@code title:hotel}). */
    record FieldScoped(String fieldPath, QueryNode query) implements QueryNode {
    }

    /** {@code query} with its score multiplied by {@code boost} ({@code hotel^3}). */
    record Boost(QueryNode query, float boost) implements QueryNode {
    }

    /** Every document, scoring 1 ({@code *}). */
    record MatchAll() implements QueryNode {
    }

    /** No document. */
    record MatchNone() implements QueryNode {
    }
}
