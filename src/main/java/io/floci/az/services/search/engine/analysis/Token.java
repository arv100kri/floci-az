package io.floci.az.services.search.engine.analysis;

/**
 * One token an analyzer produced.
 *
 * @param term        the token text, after every filter
 * @param position    the token's position in the field; filters that drop tokens leave gaps
 * @param startOffset the offset of the token's first character in the original text
 * @param endOffset   the offset just past the token's last character in the original text
 */
public record Token(String term, int position, int startOffset, int endOffset) {

    public Token withTerm(String newTerm) {
        return new Token(newTerm, position, startOffset, endOffset);
    }
}
