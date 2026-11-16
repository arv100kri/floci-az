package io.floci.az.services.search.engine.analysis;

/**
 * Martin Porter's suffix-stripping algorithm for English, written from its published description ("An algorithm for
 * suffix stripping", Program 14(3), 1980, https://tartarus.org/martin/PorterStemmer/def.txt) with the three points
 * where the definitive version on that page differs from the paper: the extra Step 2 rule {@code (m>0) LOGI -> LOG},
 * {@code (m>0) BLI -> BLE} in place of {@code ABLI -> ABLE}, and words of one or two letters left alone.
 *
 * <p>Within each step only the rule with the longest matching suffix is considered, whether or not its condition
 * holds. Letters are tested as written: {@code a e i o u} are vowels and {@code y} is a vowel after a consonant, so
 * input is expected in lower case, as the {@code porter_stem} filter documents. Stateless and thread-safe.</p>
 */
public final class PorterStemmer {

    private static final String[][] STEP_2 = {
        {"ational", "ate"}, {"tional", "tion"}, {"enci", "ence"}, {"anci", "ance"}, {"izer", "ize"}, {"bli", "ble"},
        {"alli", "al"}, {"entli", "ent"}, {"eli", "e"}, {"ousli", "ous"}, {"ization", "ize"}, {"ation", "ate"},
        {"ator", "ate"}, {"alism", "al"}, {"iveness", "ive"}, {"fulness", "ful"}, {"ousness", "ous"},
        {"aliti", "al"}, {"iviti", "ive"}, {"biliti", "ble"}, {"logi", "log"}};
    private static final String[][] STEP_3 = {
        {"icate", "ic"}, {"ative", ""}, {"alize", "al"}, {"iciti", "ic"}, {"ical", "ic"}, {"ful", ""},
        {"ness", ""}};
    private static final String[] STEP_4 = {
        "al", "ance", "ence", "er", "ic", "able", "ible", "ant", "ement", "ment", "ent", "ion", "ou", "ism", "ate",
        "iti", "ous", "ive", "ize"};

    private PorterStemmer() {
    }

    /** The stem of {@code word}. */
    public static String stem(String word) {
        if (word.length() <= 2) {
            return word;
        }
        StringBuilder b = new StringBuilder(word);
        step1a(b);
        step1b(b);
        step1c(b);
        replaceLongest(b, STEP_2, 0);
        replaceLongest(b, STEP_3, 0);
        step4(b);
        step5(b);
        return b.toString();
    }

    private static void step1a(StringBuilder b) {
        if (endsWith(b, "sses")) {
            b.setLength(b.length() - 2);
        } else if (endsWith(b, "ies")) {
            b.setLength(b.length() - 2);
        } else if (endsWith(b, "ss")) {
            return;
        } else if (endsWith(b, "s")) {
            b.setLength(b.length() - 1);
        }
    }

    private static void step1b(StringBuilder b) {
        if (endsWith(b, "eed")) {
            if (measure(b, b.length() - 3) > 0) {
                b.setLength(b.length() - 1);
            }
            return;
        }
        int suffix = endsWith(b, "ed") ? 2 : endsWith(b, "ing") ? 3 : 0;
        if (suffix == 0 || !hasVowel(b, b.length() - suffix)) {
            return;
        }
        b.setLength(b.length() - suffix);
        if (endsWith(b, "at") || endsWith(b, "bl") || endsWith(b, "iz")) {
            b.append('e');
        } else if (endsWithDoubleConsonant(b)) {
            char last = b.charAt(b.length() - 1);
            if (last != 'l' && last != 's' && last != 'z') {
                b.setLength(b.length() - 1);
            }
        } else if (measure(b, b.length()) == 1 && endsCvc(b, b.length())) {
            b.append('e');
        }
    }

    private static void step1c(StringBuilder b) {
        if (endsWith(b, "y") && hasVowel(b, b.length() - 1)) {
            b.setCharAt(b.length() - 1, 'i');
        }
    }

    /** Applies the rule of {@code rules} with the longest suffix {@code b} ends with, when its stem's m > minimum. */
    private static void replaceLongest(StringBuilder b, String[][] rules, int minimumMeasure) {
        String[] best = null;
        for (String[] rule : rules) {
            if (endsWith(b, rule[0]) && (best == null || rule[0].length() > best[0].length())) {
                best = rule;
            }
        }
        if (best == null) {
            return;
        }
        int stemLength = b.length() - best[0].length();
        if (measure(b, stemLength) > minimumMeasure) {
            b.setLength(stemLength);
            b.append(best[1]);
        }
    }

    private static void step4(StringBuilder b) {
        String best = null;
        for (String suffix : STEP_4) {
            if (endsWith(b, suffix) && (best == null || suffix.length() > best.length())) {
                best = suffix;
            }
        }
        if (best == null) {
            return;
        }
        int stemLength = b.length() - best.length();
        if (measure(b, stemLength) <= 1) {
            return;
        }
        if ("ion".equals(best)) {
            char last = stemLength > 0 ? b.charAt(stemLength - 1) : ' ';
            if (last != 's' && last != 't') {
                return;
            }
        }
        b.setLength(stemLength);
    }

    private static void step5(StringBuilder b) {
        if (endsWith(b, "e")) {
            int stemLength = b.length() - 1;
            int measure = measure(b, stemLength);
            if (measure > 1 || (measure == 1 && !endsCvc(b, stemLength))) {
                b.setLength(stemLength);
            }
        }
        if (endsWith(b, "ll") && measure(b, b.length()) > 1) {
            b.setLength(b.length() - 1);
        }
    }

    /** Whether the letter at {@code index} is a consonant: not a vowel, and {@code y} only after a vowel or first. */
    private static boolean isConsonant(CharSequence b, int index) {
        return switch (b.charAt(index)) {
            case 'a', 'e', 'i', 'o', 'u' -> false;
            case 'y' -> index == 0 || !isConsonant(b, index - 1);
            default -> true;
        };
    }

    /** The measure m of {@code b[0, end)}: the number of vowel-consonant sequences in {@code [C](VC){m}[V]}. */
    private static int measure(CharSequence b, int end) {
        int index = 0;
        while (index < end && isConsonant(b, index)) {
            index++;
        }
        int measure = 0;
        while (index < end) {
            while (index < end && !isConsonant(b, index)) {
                index++;
            }
            if (index >= end) {
                break;
            }
            while (index < end && isConsonant(b, index)) {
                index++;
            }
            measure++;
        }
        return measure;
    }

    private static boolean hasVowel(CharSequence b, int end) {
        for (int index = 0; index < end; index++) {
            if (!isConsonant(b, index)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithDoubleConsonant(CharSequence b) {
        int last = b.length() - 1;
        return last >= 1 && b.charAt(last) == b.charAt(last - 1) && isConsonant(b, last);
    }

    /** Whether {@code b[0, end)} ends consonant-vowel-consonant, the last consonant not {@code w}, {@code x} or y. */
    private static boolean endsCvc(CharSequence b, int end) {
        if (end < 3 || !isConsonant(b, end - 1) || isConsonant(b, end - 2) || !isConsonant(b, end - 3)) {
            return false;
        }
        char last = b.charAt(end - 1);
        return last != 'w' && last != 'x' && last != 'y';
    }

    private static boolean endsWith(CharSequence b, String suffix) {
        int offset = b.length() - suffix.length();
        if (offset < 0) {
            return false;
        }
        for (int index = 0; index < suffix.length(); index++) {
            if (b.charAt(offset + index) != suffix.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
