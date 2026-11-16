package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code synonym}: matches single- and multi-word synonyms in the token stream, the longest match first, and replaces
 * each match with its alternatives at the match's position (a multi-word alternative on consecutive positions); the
 * original tokens stay where the rule maps a term to itself (an equivalency rule with {@code expand}). Rule terms are
 * split on whitespace, and with {@code ignoreCase} lowercased, as the tokens they are compared with.
 */
final class SynonymTokenFilter implements TokenFilter {

    private final SolrSynonymRules.Compiled rules;
    private final boolean ignoreCase;

    SynonymTokenFilter(SolrSynonymRules rules, boolean ignoreCase, boolean expand) {
        this.ignoreCase = ignoreCase;
        this.rules = rules.compile(this::words, expand);
    }

    private List<String> words(String text) {
        String folded = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
        return Arrays.stream(folded.strip().split("\\s+")).filter(word -> !word.isEmpty()).toList();
    }

    @Override
    public List<Token> filter(List<Token> tokens) {
        if (rules.mappings().isEmpty()) {
            return tokens;
        }
        List<String> terms = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            terms.add(ignoreCase ? token.term().toLowerCase(Locale.ROOT) : token.term());
        }
        List<Token> result = new ArrayList<>(tokens.size());
        int index = 0;
        while (index < tokens.size()) {
            int length = rules.longestMatch(terms, index);
            if (length == 0) {
                result.add(tokens.get(index++));
                continue;
            }
            List<String> matched = terms.subList(index, index + length);
            Token first = tokens.get(index);
            Token last = tokens.get(index + length - 1);
            for (SolrSynonymRules.Alternative alternative : rules.alternatives(matched)) {
                if (alternative.terms().equals(matched)) {
                    result.addAll(tokens.subList(index, index + length));
                    continue;
                }
                for (int word = 0; word < alternative.terms().size(); word++) {
                    result.add(new Token(alternative.terms().get(word), first.position() + word, first.startOffset(),
                        last.endOffset()));
                }
            }
            index += length;
        }
        return result;
    }
}
