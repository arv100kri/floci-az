package io.floci.az.services.search.engine.index;

import io.floci.az.services.search.engine.analysis.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The inverted index of one searchable field: a sorted term dictionary mapping each term to its postings (the
 * documents containing it, with the term's positions in each), and the field length of every document, for BM25.
 * Not thread-safe; {@link LiveIndex} guards it with its read-write lock.
 */
public final class InvertedField {

    private final TreeMap<String, Map<Integer, int[]>> terms = new TreeMap<>();
    private final Map<Integer, Integer> lengths = new HashMap<>();
    private long totalLength;

    /** Adds the tokens of document {@code ordinal}; the document must not be in the field yet. */
    void add(int ordinal, List<Token> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        Map<String, List<Integer>> positions = new HashMap<>();
        for (Token token : tokens) {
            positions.computeIfAbsent(token.term(), term -> new ArrayList<>()).add(token.position());
        }
        for (Map.Entry<String, List<Integer>> entry : positions.entrySet()) {
            int[] sorted = entry.getValue().stream().mapToInt(Integer::intValue).sorted().toArray();
            terms.computeIfAbsent(entry.getKey(), term -> new HashMap<>()).put(ordinal, sorted);
        }
        lengths.put(ordinal, tokens.size());
        totalLength += tokens.size();
    }

    /** Removes document {@code ordinal}, whose tokens were {@code tokens}. */
    void remove(int ordinal, List<Token> tokens) {
        Integer length = lengths.remove(ordinal);
        if (length == null) {
            return;
        }
        totalLength -= length;
        for (Token token : tokens) {
            Map<Integer, int[]> postings = terms.get(token.term());
            if (postings != null) {
                postings.remove(ordinal);
                if (postings.isEmpty()) {
                    terms.remove(token.term());
                }
            }
        }
    }

    /** The documents containing {@code term}, each with the term's sorted positions; empty when none do. */
    public Map<Integer, int[]> postings(String term) {
        Map<Integer, int[]> postings = terms.get(term);
        return postings == null ? Map.of() : Collections.unmodifiableMap(postings);
    }

    /** The term dictionary, sorted, for prefix, wildcard, regex and fuzzy expansion. */
    public NavigableMap<String, Map<Integer, int[]>> dictionary() {
        return Collections.unmodifiableNavigableMap(terms);
    }

    /** The number of tokens of {@code ordinal} in this field, 0 when it has none. */
    public int length(int ordinal) {
        return lengths.getOrDefault(ordinal, 0);
    }

    /** The number of documents with at least one token in this field. */
    public int documentCount() {
        return lengths.size();
    }

    /** The average number of tokens per document that has the field. */
    public double averageLength() {
        return lengths.isEmpty() ? 0 : (double) totalLength / lengths.size();
    }
}
