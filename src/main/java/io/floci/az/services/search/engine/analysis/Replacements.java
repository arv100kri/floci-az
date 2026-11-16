package io.floci.az.services.search.engine.analysis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a {@code java.util.regex} replacement string for one match: {@code $n} and {@code ${name}} insert a group,
 * and a backslash makes the next character literal, as {@link Matcher#appendReplacement} reads it. The pattern
 * replace filters need the expansion of each match on its own to keep offsets.
 */
final class Replacements {

    private Replacements() {
    }

    /**
     * Checks that {@code replacement} only references groups {@code pattern} has.
     *
     * @throws IllegalArgumentException naming the first bad reference
     */
    static void validate(String replacement, Pattern pattern) {
        int groups = pattern.matcher("").groupCount();
        for (int index = 0; index < replacement.length(); index++) {
            char character = replacement.charAt(index);
            if (character == '\\') {
                index++;
            } else if (character == '$') {
                if (index + 1 >= replacement.length()) {
                    throw new IllegalArgumentException("'$' at the end of the replacement '" + replacement
                        + "' is not followed by a group reference");
                }
                char next = replacement.charAt(index + 1);
                if (next == '{') {
                    int close = replacement.indexOf('}', index);
                    if (close < 0 || !pattern.namedGroups().containsKey(replacement.substring(index + 2, close))) {
                        throw new IllegalArgumentException("the replacement '" + replacement + "' names a group the "
                            + "pattern does not have");
                    }
                    index = close;
                } else if (!Character.isDigit(next) || next - '0' > groups) {
                    throw new IllegalArgumentException("the replacement '" + replacement + "' references group '"
                        + next + "', which the pattern does not have");
                }
            }
        }
    }

    static String expand(String replacement, Matcher match) {
        if (replacement.indexOf('$') < 0 && replacement.indexOf('\\') < 0) {
            return replacement;
        }
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < replacement.length()) {
            char character = replacement.charAt(index);
            if (character == '\\' && index + 1 < replacement.length()) {
                result.append(replacement.charAt(index + 1));
                index += 2;
            } else if (character == '$' && index + 1 < replacement.length()) {
                index = appendGroup(replacement, index + 1, match, result);
            } else {
                result.append(character);
                index++;
            }
        }
        return result.toString();
    }

    /** Appends the group named or numbered at {@code start}; returns the index after its reference. */
    private static int appendGroup(String replacement, int start, Matcher match, StringBuilder result) {
        if (replacement.charAt(start) == '{') {
            int close = replacement.indexOf('}', start);
            if (close < 0) {
                throw new IllegalArgumentException("the group reference in '" + replacement + "' is not closed");
            }
            String group = match.group(replacement.substring(start + 1, close));
            result.append(group == null ? "" : group);
            return close + 1;
        }
        if (!Character.isDigit(replacement.charAt(start))) {
            throw new IllegalArgumentException("'$' in '" + replacement + "' is not followed by a group reference");
        }
        int number = replacement.charAt(start) - '0';
        int index = start + 1;
        while (index < replacement.length() && Character.isDigit(replacement.charAt(index))) {
            int longer = number * 10 + (replacement.charAt(index) - '0');
            if (longer > match.groupCount()) {
                break;
            }
            number = longer;
            index++;
        }
        if (number > match.groupCount()) {
            throw new IllegalArgumentException("the replacement '" + replacement + "' names group " + number
                + ", which the pattern does not have");
        }
        String group = match.group(number);
        result.append(group == null ? "" : group);
        return index;
    }
}
