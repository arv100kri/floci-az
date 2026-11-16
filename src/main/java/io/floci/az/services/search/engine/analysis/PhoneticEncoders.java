package io.floci.az.services.search.engine.analysis;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The phonetic encoders of the {@code phonetic} token filter that floci-az implements: American Soundex, as the U.S.
 * National Archives documents it ("The Soundex Indexing System"), and Lawrence Philips' original Metaphone, from its
 * published rules (Computer Language 7(12), 1990), with codes of at most four characters. The other documented
 * encoders are approximated by one of these two ({@link AnalysisApproximations}). Letters outside A to Z are ignored;
 * a word without any yields an empty code. Stateless and thread-safe.
 */
final class PhoneticEncoders {

    private static final int METAPHONE_LENGTH = 4;

    private PhoneticEncoders() {
    }

    /** The encoder a {@code PhoneticEncoder} value stands for. */
    static UnaryOperator<String> encoder(String name) {
        return switch (name) {
            case "soundex", "refinedSoundex", "cologne", "koelnerPhonetik", "haasePhonetik" ->
                PhoneticEncoders::soundex;
            default -> PhoneticEncoders::metaphone;
        };
    }

    /** The four-character Soundex code of {@code word} ({@code Robert} is {@code R163}), or "" without letters. */
    static String soundex(String word) {
        String letters = letters(word);
        if (letters.isEmpty()) {
            return "";
        }
        StringBuilder code = new StringBuilder(4).append(letters.charAt(0));
        int last = soundexDigit(letters.charAt(0));
        for (int index = 1; index < letters.length() && code.length() < 4; index++) {
            int digit = soundexDigit(letters.charAt(index));
            if (digit < 0) {
                continue;
            }
            if (digit > 0 && digit != last) {
                code.append((char) ('0' + digit));
            }
            last = digit;
        }
        while (code.length() < 4) {
            code.append('0');
        }
        return code.toString();
    }

    /** The Soundex digit of a letter: 1 to 6, 0 for a vowel (which separates codes), -1 for H and W (which do not). */
    private static int soundexDigit(char letter) {
        return switch (letter) {
            case 'B', 'F', 'P', 'V' -> 1;
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> 2;
            case 'D', 'T' -> 3;
            case 'L' -> 4;
            case 'M', 'N' -> 5;
            case 'R' -> 6;
            case 'H', 'W' -> -1;
            default -> 0;
        };
    }

    /** The Metaphone code of {@code word}, at most four characters, or "" without letters. */
    static String metaphone(String word) {
        String w = initial(dropDuplicates(letters(word)));
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < w.length() && code.length() < METAPHONE_LENGTH; i++) {
            char c = w.charAt(i);
            char next = at(w, i + 1);
            char previous = at(w, i - 1);
            switch (c) {
                case 'A', 'E', 'I', 'O', 'U' -> {
                    if (i == 0) {
                        code.append(c);
                    }
                }
                case 'B' -> {
                    if (!(previous == 'M' && i == w.length() - 1)) {
                        code.append('B');
                    }
                }
                case 'C' -> {
                    if (next == 'I' && at(w, i + 2) == 'A') {
                        code.append('X');
                    } else if (next == 'H') {
                        code.append(previous == 'S' ? 'K' : 'X');
                        i++;
                    } else if (next == 'I' || next == 'E' || next == 'Y') {
                        code.append('S');
                    } else if (next == 'K') {
                        code.append('K');
                        i++;
                    } else {
                        code.append('K');
                    }
                }
                case 'D' -> {
                    if (next == 'G' && isFrontVowel(at(w, i + 2))) {
                        code.append('J');
                        i++;
                    } else {
                        code.append('T');
                    }
                }
                case 'G' -> metaphoneG(w, i, code);
                case 'H' -> {
                    if (!(isVowel(previous) && !isVowel(next)) && !"CGPST".contains(String.valueOf(previous))) {
                        code.append('H');
                    }
                }
                case 'K' -> code.append('K');
                case 'P' -> {
                    if (next == 'H') {
                        code.append('F');
                        i++;
                    } else {
                        code.append('P');
                    }
                }
                case 'Q' -> code.append('K');
                case 'S' -> {
                    if (next == 'H' || (next == 'I' && (at(w, i + 2) == 'O' || at(w, i + 2) == 'A'))) {
                        code.append('X');
                        if (next == 'H') {
                            i++;
                        }
                    } else {
                        code.append('S');
                    }
                }
                case 'T' -> {
                    if (next == 'I' && (at(w, i + 2) == 'A' || at(w, i + 2) == 'O')) {
                        code.append('X');
                    } else if (next == 'H') {
                        code.append('0');
                        i++;
                    } else if (!(next == 'C' && at(w, i + 2) == 'H')) {
                        code.append('T');
                    }
                }
                case 'V' -> code.append('F');
                case 'W', 'Y' -> {
                    if (isVowel(next)) {
                        code.append(c);
                    }
                }
                case 'X' -> code.append(i == 0 ? "S" : "KS");
                case 'Z' -> code.append('S');
                default -> code.append(c);
            }
        }
        return code.length() > METAPHONE_LENGTH ? code.substring(0, METAPHONE_LENGTH) : code.toString();
    }

    private static void metaphoneG(String w, int i, StringBuilder code) {
        char next = at(w, i + 1);
        boolean silentBeforeH = next == 'H' && i + 2 < w.length() && !isVowel(at(w, i + 2));
        boolean silentAtEnd = (next == 'N' && i + 2 == w.length())
            || (next == 'N' && w.startsWith("ED", i + 2) && i + 4 == w.length());
        if (silentBeforeH || silentAtEnd) {
            return;
        }
        code.append(isFrontVowel(next) ? 'J' : 'K');
    }

    /** {@code word}'s letters A to Z, upper-cased; everything else dropped. */
    private static String letters(String word) {
        StringBuilder letters = new StringBuilder(word.length());
        for (char character : word.toUpperCase(Locale.ROOT).toCharArray()) {
            if (character >= 'A' && character <= 'Z') {
                letters.append(character);
            }
        }
        return letters.toString();
    }

    /** Metaphone's first rule: adjacent duplicate letters count once, except C. */
    private static String dropDuplicates(String letters) {
        StringBuilder result = new StringBuilder(letters.length());
        for (int index = 0; index < letters.length(); index++) {
            char letter = letters.charAt(index);
            if (index == 0 || letter != letters.charAt(index - 1) || letter == 'C') {
                result.append(letter);
            }
        }
        return result.toString();
    }

    /** Metaphone's initial-letter rules: KN, GN, PN, AE and WR lose their first letter, WH becomes W, X becomes S. */
    private static String initial(String w) {
        if (w.startsWith("KN") || w.startsWith("GN") || w.startsWith("PN") || w.startsWith("AE")
                || w.startsWith("WR")) {
            return w.substring(1);
        }
        if (w.startsWith("WH")) {
            return "W" + w.substring(2);
        }
        return w;
    }

    private static char at(String w, int index) {
        return index >= 0 && index < w.length() ? w.charAt(index) : 0;
    }

    private static boolean isVowel(char letter) {
        return letter == 'A' || letter == 'E' || letter == 'I' || letter == 'O' || letter == 'U';
    }

    private static boolean isFrontVowel(char letter) {
        return letter == 'E' || letter == 'I' || letter == 'Y';
    }
}
