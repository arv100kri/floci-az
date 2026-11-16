package io.floci.az.services.search.engine.analysis;

import java.util.Locale;
import java.util.Set;

/** Character classes the tokenizers and token filters test. */
final class CharacterClasses {

    /** The {@code TokenCharacterKind} values of the n-gram tokenizers' {@code tokenChars}. */
    static final Set<String> TOKEN_CHARACTER_KINDS = Set.of("letter", "digit", "whitespace", "punctuation", "symbol");

    private CharacterClasses() {
    }

    /**
     * Whether Unicode word segmentation (UAX #29) makes {@code codePoint} a word of its own: ideographs and Hiragana,
     * which have no word-break property that joins them to their neighbours.
     */
    static boolean isSingleCharacterWord(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || Character.isIdeographic(codePoint);
    }

    /** The CJK script of {@code codePoint} as {@code cjk_bigram} names it, or null for any other script. */
    static String cjkScript(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return switch (script) {
            case HAN -> "han";
            case HIRAGANA -> "hiragana";
            case KATAKANA -> "katakana";
            case HANGUL -> "hangul";
            default -> null;
        };
    }

    /** Whether {@code codePoint} belongs to one of {@code kinds} ({@code letter}, {@code digit}, ...). */
    static boolean isOfKind(int codePoint, Set<String> kinds) {
        for (String kind : kinds) {
            boolean member = switch (kind.toLowerCase(Locale.ROOT)) {
                case "letter" -> Character.isLetter(codePoint);
                case "digit" -> Character.isDigit(codePoint);
                case "whitespace" -> Character.isWhitespace(codePoint);
                case "punctuation" -> isPunctuation(codePoint);
                case "symbol" -> isSymbol(codePoint);
                default -> false;
            };
            if (member) {
                return true;
            }
        }
        return false;
    }

    static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION;
    }

    static boolean isSymbol(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL
            || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL;
    }

    static boolean isApostrophe(char character) {
        return character == '\'' || character == '\u2019' || character == '\uFF07';
    }
}
