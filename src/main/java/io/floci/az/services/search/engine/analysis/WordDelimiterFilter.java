package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code word_delimiter}: splits tokens into subwords at non-alphanumeric characters, at lower-to-upper case changes
 * ({@code splitOnCaseChange}) and between letters and digits ({@code splitOnNumerics}), and optionally catenates runs
 * of word parts, of number parts, or of all parts. Subwords take consecutive positions; a catenation takes the
 * position of the first part it joins; later tokens move up by the positions the subwords added.
 */
final class WordDelimiterFilter implements TokenFilter {

    private static final int LOWER = 1;
    private static final int UPPER = 2;
    private static final int DIGIT = 3;

    private final boolean generateWordParts;
    private final boolean generateNumberParts;
    private final boolean catenateWords;
    private final boolean catenateNumbers;
    private final boolean catenateAll;
    private final boolean splitOnCaseChange;
    private final boolean preserveOriginal;
    private final boolean splitOnNumerics;
    private final boolean stemEnglishPossessive;
    private final Set<String> protectedWords;

    WordDelimiterFilter(boolean generateWordParts, boolean generateNumberParts, boolean catenateWords,
                        boolean catenateNumbers, boolean catenateAll, boolean splitOnCaseChange,
                        boolean preserveOriginal, boolean splitOnNumerics, boolean stemEnglishPossessive,
                        Set<String> protectedWords) {
        this.generateWordParts = generateWordParts;
        this.generateNumberParts = generateNumberParts;
        this.catenateWords = catenateWords;
        this.catenateNumbers = catenateNumbers;
        this.catenateAll = catenateAll;
        this.splitOnCaseChange = splitOnCaseChange;
        this.preserveOriginal = preserveOriginal;
        this.splitOnNumerics = splitOnNumerics;
        this.stemEnglishPossessive = stemEnglishPossessive;
        this.protectedWords = Set.copyOf(protectedWords);
    }

    private record Part(String text, boolean digits, int start, int end) {
    }

    @Override
    public List<Token> filter(List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens.size());
        int shift = 0;
        for (Token token : tokens) {
            int position = token.position() + shift;
            if (protectedWords.contains(token.term())) {
                result.add(token.withPosition(position));
                continue;
            }
            List<Part> parts = split(token);
            if (parts.size() == 1 && parts.get(0).text().equals(token.term())) {
                result.add(token.withPosition(position));
                continue;
            }
            int used = emit(token, position, parts, result);
            shift += Math.max(used, 1) - 1;
        }
        return result;
    }

    /** Emits the original, subwords and catenations of one token; returns how many positions they take. */
    private int emit(Token token, int position, List<Part> parts, List<Token> result) {
        if (preserveOriginal) {
            result.add(token.withPosition(position));
        }
        if (parts.isEmpty()) {
            return preserveOriginal ? 1 : 0;
        }
        if (catenateAll && parts.size() > 1) {
            result.add(catenation(token, parts, 0, parts.size(), position));
        }
        int next = position;
        int index = 0;
        while (index < parts.size()) {
            boolean digits = parts.get(index).digits();
            int runEnd = index;
            while (runEnd < parts.size() && parts.get(runEnd).digits() == digits) {
                runEnd++;
            }
            boolean generate = digits ? generateNumberParts : generateWordParts;
            boolean catenate = digits ? catenateNumbers : catenateWords;
            if (catenate && runEnd - index > 1) {
                result.add(catenation(token, parts, index, runEnd, next));
            }
            for (int part = index; part < runEnd; part++) {
                if (generate) {
                    Part piece = parts.get(part);
                    result.add(new Token(piece.text(), next, token.startOffset() + piece.start(),
                        token.startOffset() + piece.end()));
                    next++;
                }
            }
            if (!generate && catenate && runEnd - index > 1) {
                next++;
            }
            index = runEnd;
        }
        return Math.max(next - position, preserveOriginal || catenateAll ? 1 : 0);
    }

    private static Token catenation(Token token, List<Part> parts, int from, int to, int position) {
        StringBuilder text = new StringBuilder();
        for (int index = from; index < to; index++) {
            text.append(parts.get(index).text());
        }
        return new Token(text.toString(), position, token.startOffset() + parts.get(from).start(),
            token.startOffset() + parts.get(to - 1).end());
    }

    private List<Part> split(Token token) {
        String term = token.term();
        int end = term.length();
        if (stemEnglishPossessive) {
            String stripped = TokenFilters.removePossessive(term);
            end = stripped.length();
        }
        List<Part> parts = new ArrayList<>();
        int start = -1;
        int previousType = 0;
        for (int index = 0; index < end; ) {
            int codePoint = term.codePointAt(index);
            int type = type(codePoint);
            if (type == 0) {
                if (start >= 0) {
                    parts.add(part(term, start, index, previousType));
                    start = -1;
                }
            } else if (start < 0) {
                start = index;
            } else if (isBreak(previousType, type)) {
                parts.add(part(term, start, index, previousType));
                start = index;
            }
            previousType = type;
            index += Character.charCount(codePoint);
        }
        if (start >= 0) {
            parts.add(part(term, start, end, previousType));
        }
        return parts;
    }

    private static Part part(String term, int start, int end, int type) {
        return new Part(term.substring(start, end), type == DIGIT, start, end);
    }

    private static int type(int codePoint) {
        if (Character.isDigit(codePoint)) {
            return DIGIT;
        }
        if (Character.isUpperCase(codePoint) || Character.isTitleCase(codePoint)) {
            return UPPER;
        }
        return Character.isLetter(codePoint) ? LOWER : 0;
    }

    private boolean isBreak(int previous, int current) {
        boolean previousDigit = previous == DIGIT;
        boolean currentDigit = current == DIGIT;
        if (previousDigit != currentDigit) {
            return splitOnNumerics;
        }
        return splitOnCaseChange && previous == LOWER && current == UPPER;
    }
}
