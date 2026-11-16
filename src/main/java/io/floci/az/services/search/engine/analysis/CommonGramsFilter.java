package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code common_grams}: wherever a token or its successor is a common word, adds the bigram of the two
 * ({@code the_quick}) at the first token's position, keeping the single tokens. In {@code queryMode} only the bigrams
 * are kept, along with the single tokens that are not part of any bigram.
 */
final class CommonGramsFilter implements TokenFilter {

    private final Set<String> commonWords;
    private final boolean ignoreCase;
    private final boolean queryMode;

    CommonGramsFilter(Set<String> commonWords, boolean ignoreCase, boolean queryMode) {
        this.commonWords = ignoreCase ? TokenFilters.lowercaseAll(commonWords) : Set.copyOf(commonWords);
        this.ignoreCase = ignoreCase;
        this.queryMode = queryMode;
    }

    @Override
    public List<Token> filter(List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens.size() * 2);
        boolean[] inBigram = new boolean[tokens.size()];
        List<Token> bigrams = new ArrayList<>();
        for (int index = 0; index + 1 < tokens.size(); index++) {
            Token first = tokens.get(index);
            Token second = tokens.get(index + 1);
            if (isCommon(first) || isCommon(second)) {
                bigrams.add(new Token(first.term() + "_" + second.term(), first.position(), first.startOffset(),
                    second.endOffset()));
                inBigram[index] = true;
                inBigram[index + 1] = true;
            } else {
                bigrams.add(null);
            }
        }
        for (int index = 0; index < tokens.size(); index++) {
            if (!queryMode || !inBigram[index]) {
                result.add(tokens.get(index));
            }
            if (index < bigrams.size() && bigrams.get(index) != null) {
                result.add(bigrams.get(index));
            }
        }
        return result;
    }

    private boolean isCommon(Token token) {
        return commonWords.contains(ignoreCase ? token.term().toLowerCase(Locale.ROOT) : token.term());
    }
}
