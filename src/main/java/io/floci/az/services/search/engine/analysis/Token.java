package io.floci.az.services.search.engine.analysis;

/**
 * One token an analyzer produced.
 *
 * @param term        the token text, after every filter
 * @param position    the token's position in the field; filters that drop tokens leave gaps, and filters that add
 *                    alternatives (synonyms, preserved originals) put them at the same position
 * @param startOffset the offset of the token's first character in the original text
 * @param endOffset   the offset just past the token's last character in the original text
 * @param keyword     whether a filter marked the token as a keyword ({@code keyword_marker}, {@code keyword_repeat},
 *                    {@code stemmer_override}), which stemmers further down the chain leave alone
 */
public record Token(String term, int position, int startOffset, int endOffset, boolean keyword) {

    public Token(String term, int position, int startOffset, int endOffset) {
        this(term, position, startOffset, endOffset, false);
    }

    public Token withTerm(String newTerm) {
        return new Token(newTerm, position, startOffset, endOffset, keyword);
    }

    public Token withPosition(int newPosition) {
        return new Token(term, newPosition, startOffset, endOffset, keyword);
    }

    public Token withOffsets(int newStartOffset, int newEndOffset) {
        return new Token(term, position, newStartOffset, newEndOffset, keyword);
    }

    public Token asKeyword(boolean isKeyword) {
        return new Token(term, position, startOffset, endOffset, isKeyword);
    }
}
