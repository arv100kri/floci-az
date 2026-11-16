package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parameters of a Suggest or Autocomplete request, validated against the index's suggester per the Suggest and
 * Autocomplete references: {@code search} (for suggestions 1 to 100 characters) and {@code suggesterName} are required,
 * {@code searchFields} must be source fields of the suggester, {@code top} is 1 to 100 (5 by default),
 * {@code minimumCoverage} 0 to 100, and the highlight tags are set together. Values may come typed (a POST body) or as
 * strings (a GET query string).
 *
 * @param search          the partial query text
 * @param fields          the source fields to complete from: {@code searchFields}, else the suggester's
 * @param fuzzy           whether to allow one edit
 * @param preTag          the tag before highlights, or null for no highlighting
 * @param postTag         the tag after highlights, or null
 * @param minimumCoverage the coverage asked for, or null
 * @param filter          the {@code $filter}, or null
 * @param top             the most results
 * @param orderBy         the {@code $orderby} of suggestions, or null
 * @param select          the {@code $select} of suggestions; null means the key only
 * @param mode            the autocomplete mode
 */
record SuggestParameters(String search, List<SearchFieldDefinition> fields, boolean fuzzy, String preTag,
                         String postTag, Double minimumCoverage, String filter, int top, String orderBy,
                         List<String> select, Mode mode) {

    static final int DEFAULT_TOP = 5;
    static final int MAX_TOP = 100;
    static final int MAX_SUGGEST_LENGTH = 100;

    /** The autocomplete modes. */
    enum Mode {
        ONE_TERM, TWO_TERMS, ONE_TERM_WITH_CONTEXT
    }

    static SuggestParameters parse(ObjectNode parameters, SearchIndexDefinition definition, boolean suggest) {
        String search = text(parameters, "search");
        if (search == null) {
            throw invalid("The search parameter is required.", "search");
        }
        if (suggest && (search.isEmpty() || search.length() > MAX_SUGGEST_LENGTH)) {
            throw invalid("The search text of a suggestion request must be between 1 and " + MAX_SUGGEST_LENGTH
                + " characters long.", "search");
        }
        String suggesterName = text(parameters, "suggesterName");
        JsonNode suggester = null;
        for (JsonNode candidate : definition.json().path("suggesters")) {
            if (suggesterName != null && suggesterName.equals(SearchJson.text(candidate, "name"))) {
                suggester = candidate;
            }
        }
        if (suggester == null) {
            throw invalid("The specified suggester name '" + (suggesterName == null ? "" : suggesterName)
                + "' does not exist in this index definition.", "suggesterName");
        }
        List<String> sourceFields = new ArrayList<>();
        suggester.path("sourceFields").forEach(field -> sourceFields.add(field.asText()));
        List<String> searchFields = list(parameters, "searchFields");
        List<SearchFieldDefinition> fields = new ArrayList<>();
        for (String path : searchFields.isEmpty() ? sourceFields : searchFields) {
            if (!sourceFields.contains(path)) {
                throw invalid("The field '" + path + "' in searchFields is not a source field of the suggester '"
                    + suggesterName + "'.", "searchFields");
            }
            definition.field(path).ifPresent(fields::add);
        }
        String preTag = text(parameters, "highlightPreTag");
        String postTag = text(parameters, "highlightPostTag");
        if ((preTag == null) != (postTag == null)) {
            throw invalid("highlightPreTag and highlightPostTag must be set together.", preTag == null
                ? "highlightPreTag" : "highlightPostTag");
        }
        Double coverage = number(parameters, "minimumCoverage");
        if (coverage != null && (coverage < 0 || coverage > 100)) {
            throw invalid("Invalid value " + coverage + " for parameter minimumCoverage. It must be between 0 and "
                + "100.", "minimumCoverage");
        }
        Double top = number(parameters, "top");
        if (top != null && (top != Math.floor(top) || top < 1 || top > MAX_TOP)) {
            throw invalid("Invalid value " + parameters.get("top").asText() + " for parameter $top. It must be "
                + "between 1 and " + MAX_TOP + ".", "$top");
        }
        String orderBy = suggest ? String.join(",", list(parameters, "orderby")) : null;
        List<String> select = suggest && parameters.hasNonNull("select") ? list(parameters, "select") : null;
        return new SuggestParameters(search, fields, bool(parameters, "fuzzy"), preTag, postTag, coverage,
            text(parameters, "filter"), top == null ? DEFAULT_TOP : top.intValue(),
            orderBy == null || orderBy.isBlank() ? null : orderBy, select, mode(parameters));
    }

    private static Mode mode(ObjectNode parameters) {
        String mode = text(parameters, "autocompleteMode");
        if (mode == null) {
            return Mode.ONE_TERM;
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "oneterm" -> Mode.ONE_TERM;
            case "twoterms" -> Mode.TWO_TERMS;
            case "onetermwithcontext" -> Mode.ONE_TERM_WITH_CONTEXT;
            default -> throw invalid("Invalid value '" + mode + "' for parameter autocompleteMode. Valid values are "
                + "'oneTerm', 'twoTerms' and 'oneTermWithContext'.", "autocompleteMode");
        };
    }

    private static String text(ObjectNode parameters, String name) {
        JsonNode value = parameters.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("The parameter '" + name + "' must be a string.", name);
        }
        return value.textValue();
    }

    private static List<String> list(ObjectNode parameters, String name) {
        JsonNode value = parameters.get(name);
        List<String> values = new ArrayList<>();
        if (value == null || value.isNull()) {
            return values;
        }
        List<String> raw = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(element -> raw.add(element.asText()));
        } else if (value.isTextual()) {
            raw.addAll(List.of(value.textValue().split(",")));
        } else {
            throw invalid("The parameter '" + name + "' must be a comma-separated string.", name);
        }
        for (String entry : raw) {
            if (!entry.isBlank()) {
                values.add(entry.trim());
            }
        }
        return values;
    }

    private static boolean bool(ObjectNode parameters, String name) {
        JsonNode value = parameters.get(name);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if (!text.equals("true") && !text.equals("false")) {
            throw invalid("The parameter '" + name + "' must be true or false.", name);
        }
        return text.equals("true");
    }

    private static Double number(ObjectNode parameters, String name) {
        JsonNode value = parameters.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        try {
            return Double.parseDouble(value.asText().trim());
        } catch (NumberFormatException e) {
            throw invalid("The parameter '" + name + "' must be a number.", name);
        }
    }

    private static SearchException invalid(String message, String parameter) {
        return SearchException.badRequest(message + "\r\nParameter name: " + parameter);
    }
}
