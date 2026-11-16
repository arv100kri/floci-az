package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.analysis.Analyzer;
import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Boost;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.FieldScoped;
import io.floci.az.services.search.engine.query.QueryNode.Fuzzy;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Regex;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.engine.query.QueryNode.Wildcard;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which tokens of a field value a query matched, for hit highlighting: the terms of term queries, the tokens of
 * complete phrase occurrences only (Learn: "only phrases that match the full phrase query are returned"), and the
 * terms prefix, wildcard, regex and fuzzy queries expand to. Negated clauses highlight nothing, and a clause only
 * highlights the fields it searches: its field prefix, else the request's search fields. Not thread-safe.
 */
public final class QueryTermMatcher {

    private final QueryNode query;
    private final IndexAnalysis analysis;
    private final Set<String> defaultFields;
    private final Map<String, List<Leaf>> leavesByField = new HashMap<>();

    /**
     * @param query         the parsed query
     * @param analysis      the index's analyzers
     * @param defaultFields the fields unfielded clauses search: the request's {@code searchFields}, or every searchable
     *                      field
     */
    public QueryTermMatcher(QueryNode query, IndexAnalysis analysis, List<SearchFieldDefinition> defaultFields) {
        this.query = query;
        this.analysis = analysis;
        this.defaultFields = new HashSet<>();
        defaultFields.forEach(field -> this.defaultFields.add(field.path()));
    }

    /**
     * The matched tokens of one value of {@code field}, as indexes into {@code tokens}.
     *
     * @param tokens the value's tokens, from the field's index analyzer
     */
    public BitSet matches(SearchFieldDefinition field, List<Token> tokens) {
        BitSet matched = new BitSet(tokens.size());
        for (Leaf leaf : leavesByField.computeIfAbsent(field.path(), path -> leaves(field))) {
            leaf.mark(tokens, matched);
        }
        return matched;
    }

    /** Whether the query can highlight anything in {@code field}. */
    public boolean highlights(SearchFieldDefinition field) {
        return !leavesByField.computeIfAbsent(field.path(), path -> leaves(field)).isEmpty();
    }

    private List<Leaf> leaves(SearchFieldDefinition field) {
        List<Leaf> leaves = new ArrayList<>();
        collect(query, null, field, leaves);
        return leaves;
    }

    private void collect(QueryNode node, String scope, SearchFieldDefinition field, List<Leaf> leaves) {
        switch (node) {
            case Bool bool -> {
                for (Clause clause : bool.clauses()) {
                    if (clause.occur() != Occur.MUST_NOT) {
                        collect(clause.query(), scope, field, leaves);
                    }
                }
            }
            case FieldScoped scoped -> collect(scoped.query(), scoped.fieldPath(), field, leaves);
            case Boost boost -> collect(boost.query(), scope, field, leaves);
            default -> {
                boolean inScope = scope == null ? defaultFields.contains(field.path()) : scope.equals(field.path());
                if (inScope) {
                    Leaf leaf = leaf(node, analysis.searchAnalyzer(field));
                    if (leaf != null) {
                        leaves.add(leaf);
                    }
                }
            }
        }
    }

    private static Leaf leaf(QueryNode node, Analyzer analyzer) {
        return switch (node) {
            case Term term -> termSet(analyzer.analyze(term.text()));
            case Phrase phrase -> {
                List<Token> tokens = analyzer.analyze(phrase.text());
                yield tokens.size() <= 1 ? termSet(tokens) : new PhraseLeaf(tokens, phrase.slop());
            }
            case Prefix prefix -> {
                String normalized = analyzer.normalizeTerm(prefix.prefix());
                yield new PredicateLeaf(term -> term.startsWith(normalized));
            }
            case Wildcard wildcard -> {
                LuceneRegExp pattern = LuceneRegExp.wildcard(analyzer.normalizeTerm(wildcard.pattern()));
                yield new PredicateLeaf(pattern::matches);
            }
            case Regex regex -> {
                LuceneRegExp pattern = LuceneRegExp.compile(analyzer.normalizeTerm(regex.pattern()));
                yield new PredicateLeaf(pattern::matches);
            }
            case Fuzzy fuzzy -> {
                String normalized = analyzer.normalizeTerm(fuzzy.term());
                yield new PredicateLeaf(term -> EditDistance.within(term, normalized, fuzzy.maxEdits()));
            }
            default -> null;
        };
    }

    private static Leaf termSet(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        Set<String> terms = new HashSet<>();
        tokens.forEach(token -> terms.add(token.term()));
        return new PredicateLeaf(terms::contains);
    }

    /** One leaf of the query, marking the tokens it matches. */
    private interface Leaf {
        void mark(List<Token> tokens, BitSet matched);
    }

    private record PredicateLeaf(Predicate<String> matches) implements Leaf {

        @Override
        public void mark(List<Token> tokens, BitSet matched) {
            for (int index = 0; index < tokens.size(); index++) {
                if (matches.test(tokens.get(index).term())) {
                    matched.set(index);
                }
            }
        }
    }

    /** A phrase: marks the tokens of each occurrence, with the slop rules of {@link PhraseMatcher}. */
    private record PhraseLeaf(List<Token> phrase, int slop) implements Leaf {

        @Override
        public void mark(List<Token> tokens, BitSet matched) {
            Map<String, List<Integer>> positionsByTerm = new HashMap<>();
            for (Token token : tokens) {
                positionsByTerm.computeIfAbsent(token.term(), term -> new ArrayList<>()).add(token.position());
            }
            int[][] positions = new int[phrase.size()][];
            int[] offsets = new int[phrase.size()];
            int base = phrase.get(0).position();
            for (int term = 0; term < phrase.size(); term++) {
                List<Integer> found = positionsByTerm.get(phrase.get(term).term());
                if (found == null) {
                    return;
                }
                positions[term] = found.stream().mapToInt(Integer::intValue).sorted().toArray();
                offsets[term] = phrase.get(term).position() - base;
            }
            Set<String> matchedTokens = new HashSet<>();
            for (int anchor : positions[0]) {
                int[] occurrence = occurrence(positions, offsets, anchor - offsets[0]);
                if (occurrence != null) {
                    for (int term = 0; term < occurrence.length; term++) {
                        matchedTokens.add(occurrence[term] + ":" + phrase.get(term).term());
                    }
                }
            }
            for (int index = 0; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if (matchedTokens.contains(token.position() + ":" + token.term())) {
                    matched.set(index);
                }
            }
        }

        /** The position of each phrase term in the occurrence starting at {@code start}, or null when it misses. */
        private int[] occurrence(int[][] positions, int[] offsets, int start) {
            int[] found = new int[positions.length];
            int minDisplacement = 0;
            int maxDisplacement = 0;
            for (int term = 0; term < positions.length; term++) {
                int expected = start + offsets[term];
                int nearest = nearest(positions[term], expected);
                found[term] = nearest;
                minDisplacement = Math.min(minDisplacement, nearest - expected);
                maxDisplacement = Math.max(maxDisplacement, nearest - expected);
            }
            return maxDisplacement - minDisplacement <= slop ? found : null;
        }

        private static int nearest(int[] sorted, int target) {
            int index = Arrays.binarySearch(sorted, target);
            if (index >= 0) {
                return sorted[index];
            }
            int insertion = -index - 1;
            if (insertion == 0) {
                return sorted[0];
            }
            if (insertion == sorted.length) {
                return sorted[sorted.length - 1];
            }
            int before = sorted[insertion - 1];
            int after = sorted[insertion];
            return target - before <= after - target ? before : after;
        }
    }
}
