package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code cjk_bigram}: turns runs of adjacent single-character CJK tokens (as the standard tokenizer emits Han and
 * Hiragana) and multi-character Katakana and Hangul tokens into overlapping bigrams of their characters. A run of one
 * character stays a unigram; with {@code outputUnigrams} every character is also kept, at the bigram's position.
 * Scripts in {@code ignoreScripts} ({@code han}, {@code hiragana}, {@code katakana}, {@code hangul}) pass through.
 */
final class CjkBigramFilter implements TokenFilter {

    private final Set<String> ignoredScripts;
    private final boolean outputUnigrams;

    CjkBigramFilter(Set<String> ignoredScripts, boolean outputUnigrams) {
        this.ignoredScripts = Set.copyOf(ignoredScripts);
        this.outputUnigrams = outputUnigrams;
    }

    @Override
    public List<Token> filter(List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens.size());
        List<Character> pending = new ArrayList<>();
        List<int[]> pendingOffsets = new ArrayList<>();
        int position = 0;
        int lastEnd = -1;
        int nextPosition = 0;
        for (Token token : tokens) {
            boolean cjk = isCjkToken(token.term());
            if (!cjk || token.startOffset() != lastEnd) {
                nextPosition = flush(pending, pendingOffsets, position, result, nextPosition);
            }
            if (!cjk) {
                result.add(token.withPosition(Math.max(token.position(), nextPosition)));
                nextPosition = Math.max(token.position(), nextPosition) + 1;
                lastEnd = -1;
                continue;
            }
            if (pending.isEmpty()) {
                position = Math.max(token.position(), nextPosition);
            }
            int offset = token.startOffset();
            for (char character : token.term().toCharArray()) {
                pending.add(character);
                pendingOffsets.add(new int[] {offset, offset + 1});
                offset++;
            }
            lastEnd = token.endOffset();
        }
        flush(pending, pendingOffsets, position, result, nextPosition);
        return result;
    }

    /** Emits the bigrams of the pending characters from {@code position}; returns the next free position. */
    private int flush(List<Character> pending, List<int[]> offsets, int position, List<Token> result,
                      int nextPosition) {
        if (pending.isEmpty()) {
            return nextPosition;
        }
        int next = position;
        if (pending.size() == 1) {
            result.add(new Token(String.valueOf(pending.get(0)), next++, offsets.get(0)[0], offsets.get(0)[1]));
        } else {
            for (int index = 0; index < pending.size(); index++) {
                if (outputUnigrams) {
                    result.add(new Token(String.valueOf(pending.get(index)), next, offsets.get(index)[0],
                        offsets.get(index)[1]));
                }
                if (index + 1 < pending.size()) {
                    result.add(new Token("" + pending.get(index) + pending.get(index + 1), next,
                        offsets.get(index)[0], offsets.get(index + 1)[1]));
                }
                next++;
            }
        }
        pending.clear();
        offsets.clear();
        return next;
    }

    private boolean isCjkToken(String term) {
        if (term.isEmpty()) {
            return false;
        }
        for (int index = 0; index < term.length(); ) {
            int codePoint = term.codePointAt(index);
            String script = CharacterClasses.cjkScript(codePoint);
            if (script == null || ignoredScripts.contains(script) || Character.charCount(codePoint) != 1) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }
}
