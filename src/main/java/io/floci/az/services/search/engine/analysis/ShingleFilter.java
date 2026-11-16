package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code shingle}: joins runs of {@code minShingleSize} to {@code maxShingleSize} adjacent tokens into single tokens
 * ({@code quick brown}), each at the position of its first token. A position without a token (a removed stop word)
 * contributes {@code filterToken}. The single tokens are kept with {@code outputUnigrams}, or with
 * {@code outputUnigramsIfNoShingles} when no shingle could be formed.
 */
final class ShingleFilter implements TokenFilter {

    private final int minShingleSize;
    private final int maxShingleSize;
    private final boolean outputUnigrams;
    private final boolean outputUnigramsIfNoShingles;
    private final String tokenSeparator;
    private final String filterToken;

    ShingleFilter(int minShingleSize, int maxShingleSize, boolean outputUnigrams, boolean outputUnigramsIfNoShingles,
                  String tokenSeparator, String filterToken) {
        this.minShingleSize = minShingleSize;
        this.maxShingleSize = maxShingleSize;
        this.outputUnigrams = outputUnigrams;
        this.outputUnigramsIfNoShingles = outputUnigramsIfNoShingles;
        this.tokenSeparator = tokenSeparator;
        this.filterToken = filterToken;
    }

    @Override
    public List<Token> filter(List<Token> tokens) {
        List<Token> slots = new ArrayList<>();
        for (Token token : tokens) {
            if (!slots.isEmpty()) {
                Token previous = slots.get(slots.size() - 1);
                for (int gap = previous.position() + 1; gap < token.position(); gap++) {
                    slots.add(null);
                }
            }
            slots.add(token);
        }
        List<Token> shingles = new ArrayList<>();
        List<Token> result = new ArrayList<>();
        for (int start = 0; start < slots.size(); start++) {
            Token first = slots.get(start);
            if (first == null) {
                continue;
            }
            if (outputUnigrams) {
                result.add(first);
            }
            StringBuilder text = new StringBuilder(first.term());
            Token last = first;
            for (int size = 2; size <= maxShingleSize && start + size <= slots.size(); size++) {
                Token next = slots.get(start + size - 1);
                text.append(tokenSeparator).append(next == null ? filterToken : next.term());
                if (next != null) {
                    last = next;
                }
                if (size >= minShingleSize && next != null) {
                    Token shingle = new Token(text.toString(), first.position(), first.startOffset(), last.endOffset());
                    shingles.add(shingle);
                    result.add(shingle);
                }
            }
        }
        if (!outputUnigrams && shingles.isEmpty() && outputUnigramsIfNoShingles) {
            return tokens;
        }
        return result;
    }
}
