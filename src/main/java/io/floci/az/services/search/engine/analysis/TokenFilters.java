package io.floci.az.services.search.engine.analysis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The token filters of "Add custom analyzers to string fields". Each is stateless and thread-safe. Filters that add
 * tokens put them at the position of the token they derive from; filters that drop tokens leave the positions of the
 * others unchanged. Stemmers leave tokens a filter marked as keywords alone. The filters that are approximations are
 * listed in {@link AnalysisApproximations}.
 */
public final class TokenFilters {

    /** The English stop words of the {@code stop} analyzer and the {@code stopwords} filter's default list. */
    public static final Set<String> ENGLISH_STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it", "no", "not",
        "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they", "this", "to", "was",
        "will", "with");

    /** The default articles of the {@code elision} filter. */
    public static final Set<String> DEFAULT_ELISION_ARTICLES = Set.of(
        "l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu");

    private static final Map<Character, String> FOLDING_SPECIAL_CASES = Map.ofEntries(
        Map.entry('ß', "ss"), Map.entry('æ', "ae"), Map.entry('Æ', "AE"), Map.entry('ø', "o"), Map.entry('Ø', "O"),
        Map.entry('œ', "oe"), Map.entry('Œ', "OE"), Map.entry('đ', "d"), Map.entry('Đ', "D"), Map.entry('ł', "l"),
        Map.entry('Ł', "L"), Map.entry('þ', "th"), Map.entry('Þ', "TH"), Map.entry('ð', "d"), Map.entry('Ð', "D"),
        Map.entry('ı', "i"), Map.entry('ħ', "h"), Map.entry('Ħ', "H"), Map.entry('\u2018', "'"),
        Map.entry('\u2019', "'"), Map.entry('\u201A', "'"), Map.entry('\u201B', "'"), Map.entry('\u201C', "\""),
        Map.entry('\u201D', "\""), Map.entry('\u201E', "\""), Map.entry('\u00AB', "\""), Map.entry('\u00BB', "\""),
        Map.entry('\u2013', "-"), Map.entry('\u2014', "-"), Map.entry('\u2010', "-"), Map.entry('\u2011', "-"));
    private static final Pattern ACRONYM = Pattern.compile("\\p{L}(?:\\.\\p{L})+\\.?");

    private TokenFilters() {
    }

    public static TokenFilter lowercase() {
        return normalizing(term -> term.toLowerCase(Locale.ROOT));
    }

    public static TokenFilter uppercase() {
        return normalizing(term -> term.toUpperCase(Locale.ROOT));
    }

    /** Folds letters with diacritics and other non-ASCII letters to their ASCII equivalents. */
    public static TokenFilter asciiFolding() {
        return normalizing(TokenFilters::foldToAscii);
    }

    /**
     * {@code asciifolding}; with {@code preserveOriginal} a token that folding changes is followed, at the same
     * position, by its original form.
     */
    public static TokenFilter asciiFolding(boolean preserveOriginal) {
        if (!preserveOriginal) {
            return asciiFolding();
        }
        return new TokenFilter() {
            @Override
            public List<Token> filter(List<Token> tokens) {
                List<Token> result = new ArrayList<>(tokens.size());
                for (Token token : tokens) {
                    String folded = foldToAscii(token.term());
                    result.add(token.withTerm(folded));
                    if (!folded.equals(token.term())) {
                        result.add(token);
                    }
                }
                return result;
            }

            @Override
            public String normalizeTerm(String term) {
                return foldToAscii(term);
            }
        };
    }

    /** Removes elided articles, such as the {@code l'} of {@code l'avion}. */
    public static TokenFilter elision(Set<String> articles) {
        return normalizing(term -> removeElision(term, articles));
    }

    /** Drops tokens whose term is one of {@code stopWords}, keeping the positions of the others. */
    public static TokenFilter stop(Set<String> stopWords) {
        return stop(stopWords, false, true);
    }

    /**
     * {@code stopwords}: drops the stop words; with {@code ignoreCase} the comparison ignores case, and without
     * {@code removeTrailing} the last token is kept even when it is a stop word.
     */
    public static TokenFilter stop(Set<String> stopWords, boolean ignoreCase, boolean removeTrailing) {
        Set<String> words;
        if (ignoreCase) {
            Set<String> lowered = new HashSet<>();
            stopWords.forEach(word -> lowered.add(word.toLowerCase(Locale.ROOT)));
            words = Set.copyOf(lowered);
        } else {
            words = Set.copyOf(stopWords);
        }
        return tokens -> {
            List<Token> kept = new ArrayList<>(tokens.size());
            for (int index = 0; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                String term = ignoreCase ? token.term().toLowerCase(Locale.ROOT) : token.term();
                boolean last = index == tokens.size() - 1;
                if (!words.contains(term) || (last && !removeTrailing)) {
                    kept.add(token);
                }
            }
            return kept;
        };
    }

    /** A filter that rewrites each term with {@code function} and normalizes query terms the same way. */
    public static TokenFilter normalizing(UnaryOperator<String> function) {
        return new TokenFilter() {
            @Override
            public List<Token> filter(List<Token> tokens) {
                List<Token> rewritten = new ArrayList<>(tokens.size());
                for (Token token : tokens) {
                    String term = function.apply(token.term());
                    if (!term.isEmpty()) {
                        rewritten.add(token.withTerm(term));
                    }
                }
                return rewritten;
            }

            @Override
            public String normalizeTerm(String term) {
                return function.apply(term);
            }
        };
    }

    /** A filter that rewrites each term with {@code function} but leaves query terms of multi-term queries alone. */
    static TokenFilter rewriting(UnaryOperator<String> function) {
        return tokens -> {
            List<Token> rewritten = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                rewritten.add(token.withTerm(function.apply(token.term())));
            }
            return rewritten;
        };
    }

    /** A filter that changes nothing: the documented filters floci-az accepts but does not emulate. */
    public static TokenFilter passThrough() {
        return tokens -> tokens;
    }

    /**
     * {@code phonetic}: each token's phonetic code from {@code encoder}, replacing the token, or with
     * {@code replace} false added after it at its position. A token without letters to encode is kept as it is.
     */
    public static TokenFilter phonetic(UnaryOperator<String> encoder, boolean replace) {
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size() * 2);
            for (Token token : tokens) {
                String code = encoder.apply(token.term());
                if (code.isEmpty()) {
                    result.add(token);
                } else if (replace) {
                    result.add(token.withTerm(code));
                } else {
                    result.add(token);
                    if (!code.equals(token.term())) {
                        result.add(token.withTerm(code));
                    }
                }
            }
            return result;
        };
    }

    /** Folds {@code text} to ASCII: decomposes accented characters and drops the combining marks. */
    public static String foldToAscii(String text) {
        boolean ascii = true;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return text;
        }
        StringBuilder folded = new StringBuilder(text.length());
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        for (int index = 0; index < decomposed.length(); index++) {
            char character = decomposed.charAt(index);
            if (Character.getType(character) == Character.NON_SPACING_MARK) {
                continue;
            }
            String special = FOLDING_SPECIAL_CASES.get(character);
            folded.append(special != null ? special : String.valueOf(character));
        }
        return folded.toString();
    }

    private static String removeElision(String term, Set<String> articles) {
        int apostrophe = indexOfApostrophe(term);
        if (apostrophe <= 0) {
            return term;
        }
        String article = term.substring(0, apostrophe).toLowerCase(Locale.ROOT);
        return articles.contains(article) ? term.substring(apostrophe + 1) : term;
    }

    private static int indexOfApostrophe(String term) {
        for (int index = 0; index < term.length(); index++) {
            if (CharacterClasses.isApostrophe(term.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    /** {@code apostrophe}: strips everything from the first apostrophe on ({@code Türkiye'de} becomes Türkiye). */
    public static TokenFilter apostrophe() {
        return normalizing(term -> {
            int apostrophe = indexOfApostrophe(term);
            return apostrophe < 0 ? term : term.substring(0, apostrophe);
        });
    }

    /** {@code classic}: removes English possessives ({@code 's}) and the dots of acronyms ({@code I.B.M.}). */
    public static TokenFilter classic() {
        return rewriting(term -> {
            if (ACRONYM.matcher(term).matches()) {
                return term.replace(".", "");
            }
            return removePossessive(term);
        });
    }

    /** {@code stemmer} with the {@code possessiveEnglish} language: removes a trailing {@code 's}. */
    public static TokenFilter possessiveEnglish() {
        return rewriting(TokenFilters::removePossessive);
    }

    static String removePossessive(String term) {
        int length = term.length();
        if (length >= 2 && (term.charAt(length - 1) == 's' || term.charAt(length - 1) == 'S')
                && CharacterClasses.isApostrophe(term.charAt(length - 2))) {
            return term.substring(0, length - 2);
        }
        return term;
    }

    /** {@code cjk_width}: folds full-width ASCII variants to Basic Latin and half-width Katakana to full width. */
    public static TokenFilter cjkWidth() {
        return normalizing(TokenFilters::foldWidth);
    }

    private static String foldWidth(String term) {
        boolean wide = false;
        for (int index = 0; index < term.length(); index++) {
            char character = term.charAt(index);
            if (character >= '\uFF01' && character <= '\uFFEF') {
                wide = true;
                break;
            }
        }
        return wide ? Normalizer.normalize(term, Normalizer.Form.NFKC) : term;
    }

    /**
     * {@code arabic_normalization}: the orthographic normalization of Larkey, Ballesteros and Connell's light stemming
     * for Arabic: alef variants to bare alef, teh marbuta to heh, alef maksura to yeh, and tatweel and diacritics
     * removed.
     */
    public static TokenFilter arabicNormalization() {
        return normalizing(term -> {
            StringBuilder normalized = new StringBuilder(term.length());
            for (int index = 0; index < term.length(); index++) {
                char character = term.charAt(index);
                switch (character) {
                    case '\u0622', '\u0623', '\u0625' -> normalized.append('\u0627');
                    case '\u0629' -> normalized.append('\u0647');
                    case '\u0649' -> normalized.append('\u064A');
                    case '\u0640' -> {
                        // Tatweel, a stretching character, is dropped.
                    }
                    default -> {
                        if (character < '\u064B' || character > '\u0652') {
                            normalized.append(character);
                        }
                    }
                }
            }
            return normalized.toString();
        });
    }

    /** {@code persian_normalization}, approximated: Arabic yeh and kaf to their Persian forms, heh variants to heh. */
    public static TokenFilter persianNormalization() {
        return normalizing(term -> term.replace('\u064A', '\u06CC').replace('\u0643', '\u06A9')
            .replace('\u06C0', '\u0647').replace('\u06C1', '\u0647').replace('\u0629', '\u0647'));
    }

    /**
     * {@code german_normalization}, per the German2 Snowball heuristics: {@code ß} to {@code ss}, the umlauts to their
     * base vowels, and {@code ae}, {@code oe} and {@code ue} (except after {@code q}) to {@code a}, {@code o} and
     * {@code u}.
     */
    public static TokenFilter germanNormalization() {
        return normalizing(term -> {
            StringBuilder normalized = new StringBuilder(term.length());
            for (int index = 0; index < term.length(); index++) {
                char character = term.charAt(index);
                char next = index + 1 < term.length() ? term.charAt(index + 1) : 0;
                char previous = index > 0 ? term.charAt(index - 1) : 0;
                switch (character) {
                    case 'ß' -> normalized.append("ss");
                    case 'ä' -> normalized.append('a');
                    case 'ö' -> normalized.append('o');
                    case 'ü' -> normalized.append('u');
                    case 'Ä' -> normalized.append('A');
                    case 'Ö' -> normalized.append('O');
                    case 'Ü' -> normalized.append('U');
                    default -> {
                        normalized.append(character);
                        boolean vowelPair = (character == 'a' || character == 'o'
                            || (character == 'u' && previous != 'q')) && next == 'e';
                        if (vowelPair) {
                            index++;
                        }
                    }
                }
            }
            return normalized.toString();
        });
    }

    /**
     * {@code scandinavian_folding}: {@code åÅäæÄÆ} to {@code a}, {@code öÖøØ} to {@code o}, and the double vowels
     * {@code aa}, {@code ae}, {@code ao}, {@code oe} and {@code oo} to their first letter.
     */
    public static TokenFilter scandinavianFolding() {
        return normalizing(term -> {
            StringBuilder folded = new StringBuilder(term.length());
            for (int index = 0; index < term.length(); index++) {
                char character = term.charAt(index);
                char lower = Character.toLowerCase(character);
                char next = index + 1 < term.length() ? Character.toLowerCase(term.charAt(index + 1)) : 0;
                switch (character) {
                    case 'å', 'ä', 'æ' -> folded.append('a');
                    case 'Å', 'Ä', 'Æ' -> folded.append('A');
                    case 'ö', 'ø' -> folded.append('o');
                    case 'Ö', 'Ø' -> folded.append('O');
                    default -> {
                        folded.append(character);
                        if ((lower == 'a' && (next == 'a' || next == 'e' || next == 'o'))
                                || (lower == 'o' && (next == 'e' || next == 'o'))) {
                            index++;
                        }
                    }
                }
            }
            return folded.toString();
        });
    }

    /**
     * {@code scandinavian_normalization}: the interchangeable Scandinavian letters to one form each: {@code ä} and
     * {@code ae} to {@code æ}, {@code ö}, {@code oe} and {@code oo} to {@code ø}, {@code aa} and {@code ao} to
     * {@code å}.
     */
    public static TokenFilter scandinavianNormalization() {
        return normalizing(term -> {
            StringBuilder normalized = new StringBuilder(term.length());
            for (int index = 0; index < term.length(); index++) {
                char character = term.charAt(index);
                char next = index + 1 < term.length() ? Character.toLowerCase(term.charAt(index + 1)) : 0;
                boolean upper = Character.isUpperCase(character);
                char lower = Character.toLowerCase(character);
                if (lower == 'ä') {
                    normalized.append(upper ? 'Æ' : 'æ');
                } else if (lower == 'ö') {
                    normalized.append(upper ? 'Ø' : 'ø');
                } else if (lower == 'a' && (next == 'a' || next == 'o')) {
                    normalized.append(upper ? 'Å' : 'å');
                    index++;
                } else if (lower == 'a' && next == 'e') {
                    normalized.append(upper ? 'Æ' : 'æ');
                    index++;
                } else if (lower == 'o' && (next == 'e' || next == 'o')) {
                    normalized.append(upper ? 'Ø' : 'ø');
                    index++;
                } else {
                    normalized.append(character);
                }
            }
            return normalized.toString();
        });
    }

    /** {@code keep}: keeps only the tokens in {@code words}; with {@code lowercase} the words are lowercased first. */
    public static TokenFilter keep(Set<String> words, boolean lowercase) {
        Set<String> kept = lowercase ? lowercaseAll(words) : Set.copyOf(words);
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                if (kept.contains(token.term())) {
                    result.add(token);
                }
            }
            return result;
        };
    }

    /** {@code keyword_marker}: marks the tokens in {@code keywords} so stemmers leave them alone. */
    public static TokenFilter keywordMarker(Set<String> keywords, boolean ignoreCase) {
        Set<String> marked = ignoreCase ? lowercaseAll(keywords) : Set.copyOf(keywords);
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                String term = ignoreCase ? token.term().toLowerCase(Locale.ROOT) : token.term();
                result.add(marked.contains(term) ? token.asKeyword(true) : token);
            }
            return result;
        };
    }

    /** {@code keyword_repeat}: each token twice at its position, first marked as a keyword, then not. */
    public static TokenFilter keywordRepeat() {
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size() * 2);
            for (Token token : tokens) {
                result.add(token.asKeyword(true));
                result.add(token.asKeyword(false));
            }
            return result;
        };
    }

    /** {@code length}: keeps the tokens of {@code min} to {@code max} characters. */
    public static TokenFilter length(int min, int max) {
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                int length = token.term().length();
                if (length >= min && length <= max) {
                    result.add(token);
                }
            }
            return result;
        };
    }

    /** {@code limit}: keeps the first {@code maxTokenCount} tokens. */
    public static TokenFilter limit(int maxTokenCount) {
        return tokens -> tokens.size() <= maxTokenCount ? tokens : List.copyOf(tokens.subList(0, maxTokenCount));
    }

    /** {@code nGram_v2}: every n-gram of {@code minGram} to {@code maxGram} characters of each token, at its place. */
    public static TokenFilter nGram(int minGram, int maxGram) {
        return tokens -> {
            List<Token> result = new ArrayList<>();
            for (Token token : tokens) {
                int[] codePoints = token.term().codePoints().toArray();
                for (int start = 0; start < codePoints.length; start++) {
                    for (int length = minGram; length <= maxGram && start + length <= codePoints.length; length++) {
                        result.add(token.withTerm(new String(codePoints, start, length)));
                    }
                }
            }
            return result;
        };
    }

    /** {@code edgeNGram_v2}: the n-grams at the front (or with {@code back}, the end) of each token, at its place. */
    public static TokenFilter edgeNGram(int minGram, int maxGram, boolean back) {
        return tokens -> {
            List<Token> result = new ArrayList<>();
            for (Token token : tokens) {
                int[] codePoints = token.term().codePoints().toArray();
                for (int length = minGram; length <= maxGram && length <= codePoints.length; length++) {
                    int start = back ? codePoints.length - length : 0;
                    result.add(token.withTerm(new String(codePoints, start, length)));
                }
            }
            return result;
        };
    }

    /**
     * {@code pattern_capture}: for each token, the groups every match of every pattern captures, at the token's
     * position, after the token itself when {@code preserveOriginal} is set (or when no pattern matches).
     */
    public static TokenFilter patternCapture(List<Pattern> patterns, boolean preserveOriginal) {
        return tokens -> {
            List<Token> result = new ArrayList<>();
            for (Token token : tokens) {
                Set<String> captures = new LinkedHashSet<>();
                for (Pattern pattern : patterns) {
                    Matcher matcher = pattern.matcher(token.term());
                    while (matcher.find()) {
                        for (int group = 1; group <= matcher.groupCount(); group++) {
                            String captured = matcher.group(group);
                            if (captured != null && !captured.isEmpty()) {
                                captures.add(captured);
                            }
                        }
                    }
                }
                if (preserveOriginal || captures.isEmpty()) {
                    result.add(token);
                    captures.remove(token.term());
                }
                for (String captured : captures) {
                    result.add(token.withTerm(captured));
                }
            }
            return result;
        };
    }

    /** {@code pattern_replace}: replaces every match of {@code pattern} in each token with {@code replacement}. */
    public static TokenFilter patternReplace(Pattern pattern, String replacement) {
        return rewriting(term -> {
            Matcher matcher = pattern.matcher(term);
            StringBuilder result = new StringBuilder();
            int copied = 0;
            while (matcher.find()) {
                result.append(term, copied, matcher.start()).append(Replacements.expand(replacement, matcher));
                copied = matcher.end();
            }
            return result.append(term, copied, term.length()).toString();
        });
    }

    /** A stemming filter: rewrites every token not marked as a keyword with {@code stemmer}. */
    public static TokenFilter stemming(UnaryOperator<String> stemmer) {
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                result.add(token.keyword() ? token : token.withTerm(stemmer.apply(token.term())));
            }
            return result;
        };
    }

    /** {@code porter_stem}: the Porter stemming algorithm. */
    public static TokenFilter porterStem() {
        return stemming(PorterStemmer::stem);
    }

    /**
     * Harman's "S" stemmer ("How effective is suffixing?", JASIS 42(1), 1991): {@code -ies} to {@code -y} (not after
     * {@code e} or {@code a}), {@code -es} to {@code -e} (not after {@code a}, {@code e} or {@code o}), and a final
     * {@code -s} removed (not after {@code u} or {@code s}); only the first rule that applies.
     */
    public static String minimalEnglishStem(String term) {
        int length = term.length();
        if (length > 3 && term.endsWith("ies") && !term.endsWith("eies") && !term.endsWith("aies")) {
            return term.substring(0, length - 3) + "y";
        }
        if (length > 2 && term.endsWith("es") && !term.endsWith("aes") && !term.endsWith("ees")
                && !term.endsWith("oes")) {
            return term.substring(0, length - 1);
        }
        if (length > 1 && term.endsWith("s") && !term.endsWith("us") && !term.endsWith("ss")) {
            return term.substring(0, length - 1);
        }
        return term;
    }

    /** {@code stemmer_override}: replaces the tokens {@code rules} name with their stems and marks them keywords. */
    public static TokenFilter stemmerOverride(Map<String, String> rules) {
        Map<String, String> overrides = Map.copyOf(rules);
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                String stem = token.keyword() ? null : overrides.get(token.term());
                result.add(stem == null ? token : token.withTerm(stem).asKeyword(true));
            }
            return result;
        };
    }

    /** {@code trim}: removes leading and trailing whitespace. */
    public static TokenFilter trim() {
        return normalizing(String::strip);
    }

    /** {@code truncate}: cuts terms to {@code length} characters. */
    public static TokenFilter truncate(int length) {
        return rewriting(term -> term.length() <= length ? term : term.substring(0, length));
    }

    /** {@code reverse}: reverses each term. */
    public static TokenFilter reverse() {
        return rewriting(term -> new StringBuilder(term).reverse().toString());
    }

    /**
     * {@code unique}: drops a token whose term an earlier token already had; with {@code onlyOnSamePosition}, only
     * when the earlier token is at the same position.
     */
    public static TokenFilter unique(boolean onlyOnSamePosition) {
        return tokens -> {
            List<Token> result = new ArrayList<>(tokens.size());
            Set<String> seen = new HashSet<>();
            int position = Integer.MIN_VALUE;
            for (Token token : tokens) {
                if (onlyOnSamePosition && token.position() != position) {
                    seen.clear();
                    position = token.position();
                }
                if (seen.add(token.term())) {
                    result.add(token);
                }
            }
            return result;
        };
    }

    /**
     * {@code dictionary_decompounder}: after each token of at least {@code minWordSize} characters, the words of
     * {@code wordList} it contains, {@code minSubwordSize} to {@code maxSubwordSize} characters long, at its position;
     * with {@code onlyLongestMatch} only the longest word found at each offset.
     */
    public static TokenFilter dictionaryDecompounder(Set<String> wordList, int minWordSize, int minSubwordSize,
                                                     int maxSubwordSize, boolean onlyLongestMatch) {
        Set<String> dictionary = lowercaseAll(wordList);
        return tokens -> {
            List<Token> result = new ArrayList<>();
            for (Token token : tokens) {
                result.add(token);
                String term = token.term().toLowerCase(Locale.ROOT);
                if (term.length() < minWordSize) {
                    continue;
                }
                for (int start = 0; start + minSubwordSize <= term.length(); start++) {
                    String longest = null;
                    for (int length = minSubwordSize; length <= maxSubwordSize && start + length <= term.length();
                         length++) {
                        String candidate = term.substring(start, start + length);
                        if (!dictionary.contains(candidate)) {
                            continue;
                        }
                        if (onlyLongestMatch) {
                            longest = candidate;
                        } else {
                            result.add(token.withTerm(token.term().substring(start, start + length)));
                        }
                    }
                    if (longest != null) {
                        result.add(token.withTerm(token.term().substring(start, start + longest.length())));
                    }
                }
            }
            return result;
        };
    }

    static Set<String> lowercaseAll(Set<String> words) {
        Set<String> lowered = new HashSet<>();
        words.forEach(word -> lowered.add(word.toLowerCase(Locale.ROOT)));
        return Set.copyOf(lowered);
    }
}
