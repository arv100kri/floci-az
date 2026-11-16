package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The parameters of one analysis component an index defines (an entry of {@code analyzers}, {@code tokenizers},
 * {@code tokenFilters}, {@code charFilters} or {@code normalizers}), read with their documented defaults and checked
 * against their documented types and limits. A violation is an {@link IllegalArgumentException} whose message names
 * the component and the parameter; the index definition validator turns it into a 400.
 */
final class ComponentDefinition {

    /** The documented {@code RegexFlags} values. */
    static final Set<String> REGEX_FLAGS = Set.of(
        "CANON_EQ", "CASE_INSENSITIVE", "COMMENTS", "DOTALL", "LITERAL", "MULTILINE", "UNICODE_CASE", "UNIX_LINES");

    private final JsonNode node;
    private final String kind;
    private final String name;

    ComponentDefinition(JsonNode node, String kind) {
        this.node = node;
        this.kind = kind;
        JsonNode nameNode = node.get("name");
        this.name = nameNode == null || !nameNode.isTextual() ? null : nameNode.textValue();
    }

    String name() {
        return name;
    }

    JsonNode node() {
        return node;
    }

    boolean has(String property) {
        JsonNode value = node.get(property);
        return value != null && !value.isNull();
    }

    int integer(String property, int defaultValue, int min, int max) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("'" + property + "' must be an integer");
        }
        int number = value.intValue();
        if (number < min || number > max) {
            throw invalid("'" + property + "' is " + number + "; it must be between " + min + " and " + max);
        }
        return number;
    }

    boolean bool(String property, boolean defaultValue) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw invalid("'" + property + "' must be true or false");
        }
        return value.booleanValue();
    }

    String string(String property, String defaultValue) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw invalid("'" + property + "' must be a string");
        }
        return value.textValue();
    }

    String requiredString(String property) {
        String value = string(property, null);
        if (value == null) {
            throw invalid("'" + property + "' is required");
        }
        return value;
    }

    List<String> strings(String property) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw invalid("'" + property + "' must be an array of strings");
        }
        List<String> strings = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalid("'" + property + "' must be an array of strings");
            }
            strings.add(element.textValue());
        }
        return strings;
    }

    List<String> requiredStrings(String property) {
        if (!has(property)) {
            throw invalid("'" + property + "' is required");
        }
        return strings(property);
    }

    /** A value of {@code allowed}, compared ignoring case and returned as {@code allowed} spells it. */
    String choice(String property, String defaultValue, Set<String> allowed) {
        String value = string(property, null);
        if (value == null) {
            return defaultValue;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw invalid("'" + property + "' has the unsupported value '" + value + "'");
    }

    String requiredChoice(String property, Set<String> allowed) {
        if (!has(property)) {
            throw invalid("'" + property + "' is required");
        }
        return choice(property, null, allowed);
    }

    /** The {@code flags} parameter: {@code RegexFlags} values separated by {@code |}, as {@link Pattern} bits. */
    int regexFlags() {
        String flags = string("flags", null);
        if (flags == null || flags.isBlank()) {
            return 0;
        }
        int bits = 0;
        for (String flag : flags.split("\\|")) {
            String upper = flag.strip().toUpperCase(Locale.ROOT);
            bits |= switch (upper) {
                case "CANON_EQ" -> Pattern.CANON_EQ;
                case "CASE_INSENSITIVE" -> Pattern.CASE_INSENSITIVE;
                case "COMMENTS" -> Pattern.COMMENTS;
                case "DOTALL" -> Pattern.DOTALL;
                case "LITERAL" -> Pattern.LITERAL;
                case "MULTILINE" -> Pattern.MULTILINE;
                case "UNICODE_CASE" -> Pattern.UNICODE_CASE;
                case "UNIX_LINES" -> Pattern.UNIX_LINES;
                default -> throw invalid("'flags' has the unsupported flag '" + flag.strip() + "'; the flags are "
                    + String.join(", ", REGEX_FLAGS.stream().sorted().toList()));
            };
        }
        return bits;
    }

    Pattern pattern(String property, String defaultValue, int flags) {
        String regex = string(property, defaultValue);
        if (regex == null) {
            throw invalid("'" + property + "' is required");
        }
        return compile(property, regex, flags);
    }

    Pattern compile(String property, String regex, int flags) {
        try {
            return Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw invalid("'" + property + "' is not a valid regular expression: " + e.getDescription());
        }
    }

    IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("the " + kind + " '" + name + "': " + detail + ".");
    }
}
