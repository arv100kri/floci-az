package io.floci.az.services.search.engine.analysis;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The built-in tokenizers. Each is stateless and thread-safe. */
public final class Tokenizers {

    /** The default maximum token length of the standard tokenizer; longer tokens are split. */
    public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;

    private Tokenizers() {
    }

    /**
     * Unicode word boundaries (UAX #29): the JDK's {@link BreakIterator} segments, split further where UAX #29 breaks
     * and the JDK does not (dash punctuation such as the hyphen, and double quotes). Segments without a letter or
     * digit are dropped, and tokens longer than {@code maxTokenLength} are split at that length.
     */
    public static Tokenizer standard(int maxTokenLength) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
            words.setText(text);
            int start = words.first();
            int position = 0;
            for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
                int pieceStart = start;
                for (int index = start; index < end; index++) {
                    if (isWordSplit(text.charAt(index))) {
                        position = addWord(text, pieceStart, index, maxTokenLength, tokens, position);
                        pieceStart = index + 1;
                    }
                }
                position = addWord(text, pieceStart, end, maxTokenLength, tokens, position);
            }
            return tokens;
        };
    }

    private static boolean isWordSplit(char character) {
        return character == '"' || Character.getType(character) == Character.DASH_PUNCTUATION;
    }

    /** Adds the word {@code [start, end)} in chunks of at most {@code maxTokenLength}; returns the next position. */
    private static int addWord(String text, int start, int end, int maxTokenLength, List<Token> tokens,
                               int position) {
        if (start >= end || !containsLetterOrDigit(text, start, end)) {
            return position;
        }
        int next = position;
        for (int chunk = start; chunk < end; chunk += maxTokenLength) {
            int chunkEnd = Math.min(end, chunk + maxTokenLength);
            tokens.add(new Token(text.substring(chunk, chunkEnd), next++, chunk, chunkEnd));
        }
        return next;
    }

    /** Maximal runs of letters. */
    public static Tokenizer letter() {
        return text -> runs(text, true);
    }

    /** Maximal runs of non-whitespace characters. */
    public static Tokenizer whitespace() {
        return text -> runs(text, false);
    }

    /** The whole input as one token. */
    public static Tokenizer keyword() {
        return text -> text.isEmpty() ? List.of() : List.of(new Token(text, 0, 0, text.length()));
    }

    /** The text between the matches of {@code separator}. */
    public static Tokenizer pattern(Pattern separator) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            Matcher matcher = separator.matcher(text);
            int start = 0;
            int position = 0;
            while (matcher.find()) {
                if (matcher.start() > start) {
                    tokens.add(new Token(text.substring(start, matcher.start()), position++, start, matcher.start()));
                }
                start = matcher.end();
            }
            if (start < text.length()) {
                tokens.add(new Token(text.substring(start), position, start, text.length()));
            }
            return tokens;
        };
    }

    private static List<Token> runs(String text, boolean letters) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        int start = -1;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            boolean member = letters ? Character.isLetter(codePoint) : !Character.isWhitespace(codePoint);
            if (member && start < 0) {
                start = index;
            } else if (!member && start >= 0) {
                tokens.add(new Token(text.substring(start, index), position++, start, index));
                start = -1;
            }
            index += Character.charCount(codePoint);
        }
        if (start >= 0) {
            tokens.add(new Token(text.substring(start), position, start, text.length()));
        }
        return tokens;
    }

    private static boolean containsLetterOrDigit(String text, int start, int end) {
        for (int index = start; index < end; ) {
            int codePoint = text.codePointAt(index);
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }
}
