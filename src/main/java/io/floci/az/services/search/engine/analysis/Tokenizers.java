package io.floci.az.services.search.engine.analysis;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tokenizers of "Add custom analyzers to string fields". Each is stateless and thread-safe. Tokens get consecutive
 * positions from 0 unless a tokenizer documents otherwise ({@code path_hierarchy_v2} puts every token at position 0).
 * The complex grammars ({@code classic}, {@code uax_url_email}) and the Microsoft tokenizers are approximations, listed
 * in {@link AnalysisApproximations}.
 */
public final class Tokenizers {

    /** The default maximum token length of the standard tokenizer; longer tokens are split. */
    public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;

    /** The largest {@code maxTokenLength} any tokenizer accepts. */
    public static final int MAX_TOKEN_LENGTH_LIMIT = 300;

    /** The default {@code maxTokenLength} of {@code keyword_v2}. */
    public static final int DEFAULT_KEYWORD_MAX_TOKEN_LENGTH = 256;

    private static final Pattern URL_OR_EMAIL = Pattern.compile(
        "(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s<>\"'(){}\\[\\]]+"
            + "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    private static final Pattern CLASSIC_EMAIL = Pattern.compile(
        "[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    private static final Pattern CLASSIC_ACRONYM = Pattern.compile("\\p{L}(?:\\.\\p{L})+\\.?");
    private static final Pattern CLASSIC_COMPANY = Pattern.compile("\\p{L}+[&@]\\p{L}+");
    private static final Pattern CLASSIC_APOSTROPHE = Pattern.compile("\\p{L}+(?:'\\p{L}+)+");
    private static final Pattern CLASSIC_HOST = Pattern.compile("[\\p{L}\\p{N}]+(?:\\.[\\p{L}\\p{N}]+)+");
    private static final Pattern CLASSIC_NUMBER = Pattern.compile("[\\p{L}\\p{N}]+(?:[-_/.,][\\p{L}\\p{N}]+)+");
    private static final Pattern TRAILING_URL_PUNCTUATION = Pattern.compile("[.,;:!?]+$");

    private Tokenizers() {
    }

    /**
     * Unicode word boundaries (UAX #29): the JDK's {@link BreakIterator} segments, split further where UAX #29 breaks
     * and the JDK does not (dash punctuation such as the hyphen, double quotes, and between ideographs and Hiragana,
     * which are words of one character each). Segments without a letter or digit are dropped, and tokens longer than
     * {@code maxTokenLength} are split at that length.
     */
    public static Tokenizer standard(int maxTokenLength) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            appendStandard(text, 0, text.length(), maxTokenLength, tokens, 0, Locale.ROOT);
            return tokens;
        };
    }

    /**
     * The standard tokenizer with the JDK's Thai word boundaries, which split runs of Thai script into dictionary
     * words; the Thai language analyzers use it.
     */
    public static Tokenizer thai(int maxTokenLength) {
        Locale thai = Locale.forLanguageTag("th");
        return text -> {
            List<Token> tokens = new ArrayList<>();
            appendStandard(text, 0, text.length(), maxTokenLength, tokens, 0, thai);
            return tokens;
        };
    }

    /** Appends the standard tokens of {@code text[from, to)}, positions from {@code position}; returns the next one. */
    private static int appendStandard(String text, int from, int to, int maxTokenLength, List<Token> tokens,
                                      int position, Locale locale) {
        BreakIterator words = BreakIterator.getWordInstance(locale);
        words.setText(text.substring(from, to));
        int start = words.first();
        int next = position;
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            int pieceStart = from + start;
            for (int index = from + start; index < from + end; ) {
                int codePoint = text.codePointAt(index);
                int width = Character.charCount(codePoint);
                if (isWordSplit(codePoint)) {
                    next = addWord(text, pieceStart, index, maxTokenLength, tokens, next);
                    pieceStart = index + width;
                } else if (CharacterClasses.isSingleCharacterWord(codePoint)) {
                    next = addWord(text, pieceStart, index, maxTokenLength, tokens, next);
                    next = addWord(text, index, index + width, maxTokenLength, tokens, next);
                    pieceStart = index + width;
                }
                index += width;
            }
            next = addWord(text, pieceStart, from + end, maxTokenLength, tokens, next);
        }
        return next;
    }

    private static boolean isWordSplit(int codePoint) {
        return codePoint == '"' || Character.getType(codePoint) == Character.DASH_PUNCTUATION;
    }

    /** Adds the word {@code [start, end)} in chunks of at most {@code maxTokenLength}; returns the next position. */
    private static int addWord(String text, int start, int end, int maxTokenLength, List<Token> tokens,
                               int position) {
        if (start >= end || !containsLetterOrDigit(text, start, end)) {
            return position;
        }
        return addChunks(text, start, end, maxTokenLength, tokens, position);
    }

    private static int addChunks(String text, int start, int end, int maxTokenLength, List<Token> tokens,
                                 int position) {
        int next = position;
        for (int chunk = start; chunk < end; chunk += maxTokenLength) {
            int chunkEnd = Math.min(end, chunk + maxTokenLength);
            tokens.add(new Token(text.substring(chunk, chunkEnd), next++, chunk, chunkEnd));
        }
        return next;
    }

    /** Maximal runs of letters, split at 255 characters. */
    public static Tokenizer letter() {
        return text -> runs(text, true, DEFAULT_MAX_TOKEN_LENGTH);
    }

    /** {@code lowercase}: maximal runs of letters, lowercased, split at 255 characters. */
    public static Tokenizer lowercase() {
        TokenFilter lower = TokenFilters.lowercase();
        return text -> lower.filter(runs(text, true, DEFAULT_MAX_TOKEN_LENGTH));
    }

    /** Maximal runs of non-whitespace characters, split at 255 characters. */
    public static Tokenizer whitespace() {
        return text -> runs(text, false, DEFAULT_MAX_TOKEN_LENGTH);
    }

    /** The whole input as one token, however long: the {@code keyword} analyzer. */
    public static Tokenizer keyword() {
        return text -> text.isEmpty() ? List.of() : List.of(new Token(text, 0, 0, text.length()));
    }

    /** {@code keyword_v2}: the whole input as one token, split at {@code maxTokenLength} characters. */
    public static Tokenizer keyword(int maxTokenLength) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            addChunks(text, 0, text.length(), maxTokenLength, tokens, 0);
            return tokens;
        };
    }

    /** The text between the matches of {@code separator}. */
    public static Tokenizer pattern(Pattern separator) {
        return pattern(separator, -1);
    }

    /**
     * {@code pattern}: with {@code group} -1 the text between the matches of {@code pattern}; otherwise group
     * {@code group} of every match. Empty tokens are skipped.
     */
    public static Tokenizer pattern(Pattern pattern, int group) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            Matcher matcher = pattern.matcher(text);
            int position = 0;
            if (group >= 0) {
                while (matcher.find()) {
                    if (group <= matcher.groupCount() && matcher.start(group) >= 0
                            && matcher.end(group) > matcher.start(group)) {
                        tokens.add(new Token(matcher.group(group), position++, matcher.start(group),
                            matcher.end(group)));
                    }
                }
                return tokens;
            }
            int start = 0;
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

    /**
     * {@code nGram}: every n-gram of {@code minGram} to {@code maxGram} characters of each run of {@code tokenChars}
     * (all characters when empty), ordered by start then length, each at its own position.
     */
    public static Tokenizer nGram(int minGram, int maxGram, Set<String> tokenChars) {
        return text -> grams(text, minGram, maxGram, tokenChars, false);
    }

    /** {@code edgeNGram}: the n-grams that start each run of {@code tokenChars}. */
    public static Tokenizer edgeNGram(int minGram, int maxGram, Set<String> tokenChars) {
        return text -> grams(text, minGram, maxGram, tokenChars, true);
    }

    private static List<Token> grams(String text, int minGram, int maxGram, Set<String> tokenChars, boolean edge) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        int index = 0;
        while (index < text.length()) {
            int wordStart = index;
            while (index < text.length() && isTokenChar(text.codePointAt(index), tokenChars)) {
                index += Character.charCount(text.codePointAt(index));
            }
            int wordEnd = index;
            if (wordEnd > wordStart) {
                int[] boundaries = text.substring(wordStart, wordEnd).codePoints().map(Character::charCount).toArray();
                int starts = edge ? 1 : boundaries.length;
                int startOffset = wordStart;
                for (int first = 0; first < starts; first++) {
                    int endOffset = startOffset;
                    for (int length = 1; length <= maxGram && first + length <= boundaries.length; length++) {
                        endOffset += boundaries[first + length - 1];
                        if (length >= minGram) {
                            tokens.add(new Token(text.substring(startOffset, endOffset), position++, startOffset,
                                endOffset));
                        }
                    }
                    startOffset += boundaries[first];
                }
            }
            if (index < text.length()) {
                index += Character.charCount(text.codePointAt(index));
            }
        }
        return tokens;
    }

    private static boolean isTokenChar(int codePoint, Set<String> tokenChars) {
        return tokenChars.isEmpty() || CharacterClasses.isOfKind(codePoint, tokenChars);
    }

    /**
     * {@code path_hierarchy_v2}: every prefix of a delimited path ({@code /a}, {@code /a/b}, {@code /a/b/c}), or with
     * {@code reverse} every suffix ({@code a.b.c}, {@code b.c}, {@code c}), skipping {@code skip} elements and
     * ignoring tokens longer than {@code maxTokenLength}. The delimiter is written as {@code replacement}; every token
     * is at position 0.
     */
    public static Tokenizer pathHierarchy(char delimiter, char replacement, int maxTokenLength, boolean reverse,
                                          int skip) {
        return text -> {
            List<Integer> delimiters = new ArrayList<>();
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == delimiter) {
                    delimiters.add(index);
                }
            }
            List<int[]> spans = new ArrayList<>();
            if (!reverse) {
                int start = 0;
                int skipped = 0;
                List<Integer> ends = new ArrayList<>(delimiters);
                ends.add(text.length());
                for (int end : ends) {
                    if (end == 0) {
                        continue;
                    }
                    if (skipped < skip) {
                        skipped++;
                        start = end;
                        continue;
                    }
                    spans.add(new int[] {start, end});
                }
            } else {
                int end = text.length();
                int skipped = 0;
                List<Integer> starts = new ArrayList<>();
                starts.add(0);
                delimiters.forEach(delimiterIndex -> starts.add(delimiterIndex + 1));
                for (int index = starts.size() - 1; index >= 0 && skipped < skip; index--) {
                    end = starts.get(index) == 0 ? 0 : starts.get(index) - 1;
                    skipped++;
                }
                for (int start : starts) {
                    if (start < end) {
                        spans.add(new int[] {start, end});
                    }
                }
            }
            List<Token> tokens = new ArrayList<>();
            for (int[] span : spans) {
                if (span[1] - span[0] <= maxTokenLength && span[1] > span[0]) {
                    String term = text.substring(span[0], span[1]).replace(delimiter, replacement);
                    tokens.add(new Token(term, 0, span[0], span[1]));
                }
            }
            return tokens;
        };
    }

    /**
     * {@code uax_url_email}: the standard tokenizer, except that URLs (with a scheme or starting {@code www.}) and
     * e-mail addresses are single tokens.
     */
    public static Tokenizer uaxUrlEmail(int maxTokenLength) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            Matcher matcher = URL_OR_EMAIL.matcher(text);
            int position = 0;
            int previous = 0;
            while (matcher.find()) {
                int end = matcher.end();
                Matcher trailing = TRAILING_URL_PUNCTUATION.matcher(matcher.group());
                if (trailing.find()) {
                    end -= trailing.end() - trailing.start();
                }
                if (end <= matcher.start()) {
                    continue;
                }
                position = appendStandard(text, previous, matcher.start(), maxTokenLength, tokens, position,
                    Locale.ROOT);
                position = addChunks(text, matcher.start(), end, maxTokenLength, tokens, position);
                previous = end;
            }
            appendStandard(text, previous, text.length(), maxTokenLength, tokens, position, Locale.ROOT);
            return tokens;
        };
    }

    /**
     * {@code classic}: the grammar of the classic (pre-UAX #29) standard tokenizer, approximated: within each
     * whitespace-separated chunk, e-mail addresses, acronyms ({@code I.B.M.}), company names ({@code AT&T}), words with
     * apostrophes ({@code O'Reilly's}), host names and numbers with punctuation ({@code 1-800-555}) stay whole; other
     * chunks split into runs of letters and digits, and ideographs are single tokens.
     */
    public static Tokenizer classic(int maxTokenLength) {
        return text -> {
            List<Token> tokens = new ArrayList<>();
            int position = 0;
            int index = 0;
            while (index < text.length()) {
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                int start = index;
                while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                if (index > start) {
                    position = classicChunk(text, start, index, maxTokenLength, tokens, position);
                }
            }
            return tokens;
        };
    }

    private static int classicChunk(String text, int from, int to, int maxTokenLength, List<Token> tokens,
                                    int position) {
        int start = from;
        int end = to;
        while (start < end && !Character.isLetterOrDigit(text.codePointAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1)) && text.charAt(end - 1) != '.') {
            end--;
        }
        String chunk = text.substring(start, end);
        if (CLASSIC_ACRONYM.matcher(chunk).matches()) {
            return addChunks(text, start, end, maxTokenLength, tokens, position);
        }
        while (end > start && !Character.isLetterOrDigit(text.charAt(end - 1))) {
            end--;
        }
        chunk = text.substring(start, end);
        boolean whole = CLASSIC_EMAIL.matcher(chunk).matches() || CLASSIC_COMPANY.matcher(chunk).matches()
            || CLASSIC_APOSTROPHE.matcher(chunk).matches()
            || (CLASSIC_HOST.matcher(chunk).matches() && chunk.chars().anyMatch(Character::isLetter))
            || (CLASSIC_NUMBER.matcher(chunk).matches() && chunk.chars().anyMatch(Character::isDigit));
        if (whole && chunk.codePoints().noneMatch(CharacterClasses::isSingleCharacterWord)) {
            return addChunks(text, start, end, maxTokenLength, tokens, position);
        }
        int next = position;
        int runStart = -1;
        for (int index = start; index < end; ) {
            int codePoint = text.codePointAt(index);
            int width = Character.charCount(codePoint);
            boolean member = Character.isLetterOrDigit(codePoint);
            if (member && CharacterClasses.isSingleCharacterWord(codePoint)) {
                if (runStart >= 0) {
                    next = addChunks(text, runStart, index, maxTokenLength, tokens, next);
                    runStart = -1;
                }
                next = addChunks(text, index, index + width, maxTokenLength, tokens, next);
            } else if (member && runStart < 0) {
                runStart = index;
            } else if (!member && runStart >= 0) {
                next = addChunks(text, runStart, index, maxTokenLength, tokens, next);
                runStart = -1;
            }
            index += width;
        }
        if (runStart >= 0) {
            next = addChunks(text, runStart, end, maxTokenLength, tokens, next);
        }
        return next;
    }

    /**
     * {@code microsoft_language_tokenizer} and {@code microsoft_language_stemming_tokenizer}, approximated: Unicode
     * word boundaries; the stemming tokenizer for English also reduces each word with the Porter stemmer. Every other
     * language's words are left as they are.
     */
    public static Tokenizer microsoftLanguage(String language, int maxTokenLength, boolean stemming) {
        Tokenizer words = standard(maxTokenLength);
        if (!stemming || !"english".equalsIgnoreCase(language)) {
            return words;
        }
        return text -> {
            List<Token> tokens = words.tokenize(text);
            List<Token> stemmed = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                String lower = token.term().toLowerCase(Locale.ROOT);
                stemmed.add(lower.equals(token.term()) ? token.withTerm(PorterStemmer.stem(lower)) : token);
            }
            return stemmed;
        };
    }

    private static List<Token> runs(String text, boolean letters, int maxTokenLength) {
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
                position = addChunks(text, start, index, maxTokenLength, tokens, position);
                start = -1;
            }
            index += Character.charCount(codePoint);
        }
        if (start >= 0) {
            addChunks(text, start, text.length(), maxTokenLength, tokens, position);
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
