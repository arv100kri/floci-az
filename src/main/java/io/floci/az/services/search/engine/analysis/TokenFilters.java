package io.floci.az.services.search.engine.analysis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** The built-in token filters. Each is stateless and thread-safe. */
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
        Map.entry('ı', "i"), Map.entry('ħ', "h"), Map.entry('Ħ', "H"));

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

    /** Removes elided articles, such as the {@code l'} of {@code l'avion}. */
    public static TokenFilter elision(Set<String> articles) {
        return normalizing(term -> removeElision(term, articles));
    }

    /** Drops tokens whose term is one of {@code stopWords}, keeping the positions of the others. */
    public static TokenFilter stop(Set<String> stopWords) {
        return tokens -> {
            List<Token> kept = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                if (!stopWords.contains(token.term())) {
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
            char character = term.charAt(index);
            if (character == '\'' || character == '\u2019') {
                return index;
            }
        }
        return -1;
    }
}
