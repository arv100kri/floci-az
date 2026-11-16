package io.floci.az.services.search.engine.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The character filters of "Add custom analyzers": {@code html_strip}, {@code mapping} and {@code pattern_replace}.
 * Each keeps offsets into the original text through {@link OffsetTrackingText}. Stateless and thread-safe.
 */
public final class CharFilters {

    private static final Pattern MARKUP = Pattern.compile(
        "(?is)<!--.*?(?:-->|\\z)|<(script|style)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)|<![^>]*>|</?[A-Za-z][^>]*>");
    private static final Pattern ENTITY = Pattern.compile(
        "&(?:#[xX]([0-9A-Fa-f]{1,6})|#([0-9]{1,7})|([A-Za-z]{2,8}));");
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
        Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
        Map.entry("apos", "'"), Map.entry("nbsp", "\u00A0"), Map.entry("copy", "\u00A9"), Map.entry("reg", "\u00AE"),
        Map.entry("ndash", "\u2013"), Map.entry("mdash", "\u2014"), Map.entry("hellip", "\u2026"),
        Map.entry("lsquo", "\u2018"), Map.entry("rsquo", "\u2019"), Map.entry("ldquo", "\u201C"),
        Map.entry("rdquo", "\u201D"), Map.entry("euro", "\u20AC"), Map.entry("trade", "\u2122"));

    private CharFilters() {
    }

    /**
     * {@code html_strip}: removes comments, {@code script} and {@code style} elements and tags, each replaced by spaces
     * so words around a tag stay apart and offsets stay exact, and decodes character entities.
     */
    public static CharFilter htmlStrip() {
        return input -> {
            if (input.indexOf('<') < 0 && input.indexOf('&') < 0) {
                return CharFilter.Result.unchanged(input);
            }
            OffsetTrackingText output = new OffsetTrackingText(input);
            Matcher markup = MARKUP.matcher(input);
            int copied = 0;
            while (markup.find()) {
                decodeEntities(input, copied, markup.start(), output);
                output.replace(" ".repeat(markup.end() - markup.start()), markup.start(), markup.end());
                copied = markup.end();
            }
            decodeEntities(input, copied, input.length(), output);
            return output.result();
        };
    }

    private static void decodeEntities(String input, int from, int to, OffsetTrackingText output) {
        Matcher entity = ENTITY.matcher(input).region(from, to);
        int copied = from;
        while (entity.find()) {
            String decoded = decode(entity);
            if (decoded == null) {
                continue;
            }
            output.copy(copied, entity.start());
            output.replace(decoded, entity.start(), entity.end());
            copied = entity.end();
        }
        output.copy(copied, to);
    }

    private static String decode(Matcher entity) {
        try {
            if (entity.group(1) != null) {
                return Character.toString(Integer.parseInt(entity.group(1), 16));
            }
            if (entity.group(2) != null) {
                return Character.toString(Integer.parseInt(entity.group(2)));
            }
        } catch (IllegalArgumentException e) {
            // Not a valid code point: the text is kept as written, as for any unknown entity.
            return null;
        }
        return NAMED_ENTITIES.get(entity.group(3).toLowerCase(Locale.ROOT));
    }

    /**
     * {@code mapping}: replaces each occurrence of a mapping's left side with its right side, the longest left side
     * winning at each point. {@code mappings} are {@code a=>b} rules as {@link #parseMapping} reads them.
     */
    public static CharFilter mapping(List<String> mappings) {
        Map<String, String> rules = new HashMap<>();
        int longest = 0;
        for (String mapping : mappings) {
            String[] rule = parseMapping(mapping);
            rules.put(rule[0], rule[1]);
            longest = Math.max(longest, rule[0].length());
        }
        int maxLength = longest;
        return input -> {
            OffsetTrackingText output = new OffsetTrackingText(input);
            int index = 0;
            int copied = 0;
            while (index < input.length()) {
                int matched = 0;
                String replacement = null;
                for (int length = Math.min(maxLength, input.length() - index); length > 0; length--) {
                    replacement = rules.get(input.substring(index, index + length));
                    if (replacement != null) {
                        matched = length;
                        break;
                    }
                }
                if (matched == 0) {
                    index++;
                    continue;
                }
                output.copy(copied, index);
                output.replace(replacement, index, index + matched);
                index += matched;
                copied = index;
            }
            output.copy(copied, input.length());
            return output.result();
        };
    }

    /**
     * Reads one mapping rule, {@code a=>b}: the left side must not be empty, the right side may be. Both sides accept
     * the escapes {@code \\uXXXX}, {@code \\n}, {@code \\t}, {@code \\r}, {@code \\b}, {@code \\f} and a backslash
     * before any other character, which stands for that character.
     *
     * @return the left and right sides, unescaped
     * @throws IllegalArgumentException when the rule has no {@code =>} or an empty left side
     */
    public static String[] parseMapping(String mapping) {
        int arrow = mapping == null ? -1 : mapping.indexOf("=>");
        if (arrow < 0) {
            throw new IllegalArgumentException("the mapping '" + mapping + "' is not of the form 'a=>b'");
        }
        String from = unescape(mapping.substring(0, arrow).strip());
        String to = unescape(mapping.substring(arrow + 2).strip());
        if (from.isEmpty()) {
            throw new IllegalArgumentException("the mapping '" + mapping + "' has nothing to replace on its left side");
        }
        return new String[] {from, to};
    }

    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\\' || index + 1 == text.length()) {
                result.append(character);
                continue;
            }
            char escaped = text.charAt(++index);
            switch (escaped) {
                case 'u' -> {
                    if (index + 4 >= text.length()) {
                        throw new IllegalArgumentException("the escape '\\u" + text.substring(index + 1)
                            + "' is incomplete");
                    }
                    try {
                        result.append((char) Integer.parseInt(text.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("the escape '\\u" + text.substring(index + 1, index + 5)
                            + "' is not a hexadecimal code unit", e);
                    }
                    index += 4;
                }
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case 'r' -> result.append('\r');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    /** {@code pattern_replace}: replaces every match of {@code pattern} with {@code replacement} ({@code $1} refs). */
    public static CharFilter patternReplace(Pattern pattern, String replacement) {
        return input -> {
            Matcher matcher = pattern.matcher(input);
            OffsetTrackingText output = new OffsetTrackingText(input);
            int copied = 0;
            while (matcher.find()) {
                output.copy(copied, matcher.start());
                output.replace(Replacements.expand(replacement, matcher), matcher.start(), matcher.end());
                copied = matcher.end();
            }
            output.copy(copied, input.length());
            return output.result();
        };
    }
}
