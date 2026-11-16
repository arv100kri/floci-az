package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the {@code semantic} section of an index definition (the REST spec's {@code SemanticSearch}) as Learn's
 * "Configure semantic ranker" and the search service limits describe it: at most 100 configurations with distinct
 * names; each with {@code prioritizedFields} that set at least one of {@code titleField},
 * {@code prioritizedContentFields} and {@code prioritizedKeywordsFields}; every field named there a searchable and
 * retrievable {@code Edm.String} or {@code Collection(Edm.String)} field, at the top level or in a complex field;
 * {@code rankingOrder} {@code BoostedRerankerScore} or {@code RerankerScore} (matched ignoring case and answered in
 * the spec's spelling); {@code flightingOptIn} a boolean; and a {@code defaultConfiguration} that names one of the
 * configurations. Properties it does not name are kept, so the section round-trips.
 */
public final class SemanticDefinitionValidator {

    /** The most semantic configurations an index may define. */
    static final int MAX_CONFIGURATIONS = 100;

    private static final List<String> RANKING_ORDERS = List.of("BoostedRerankerScore", "RerankerScore");

    private SemanticDefinitionValidator() {
    }

    /**
     * Validates {@code semantic} against the fields of {@code fields}; returns the section to store, with
     * {@code rankingOrder} in the spec's spelling, or a null node when the definition has none.
     */
    public static JsonNode validate(JsonNode semantic, SearchIndexDefinition fields) {
        if (semantic == null || semantic.isNull()) {
            return NullNode.getInstance();
        }
        if (!(semantic instanceof ObjectNode input)) {
            throw invalid("'semantic' must be an object.");
        }
        ObjectNode section = input.deepCopy();
        JsonNode configurations = section.get("configurations");
        Set<String> names = new HashSet<>();
        if (configurations != null && !configurations.isNull()) {
            if (!(configurations instanceof ArrayNode array)) {
                throw invalid("'semantic.configurations' must be an array.");
            }
            if (array.size() > MAX_CONFIGURATIONS) {
                throw invalid("the index defines " + array.size() + " semantic configurations; the maximum is "
                    + MAX_CONFIGURATIONS + ".");
            }
            for (JsonNode configuration : array) {
                validateConfiguration(configuration, fields, names);
            }
        }
        JsonNode defaultConfiguration = section.get("defaultConfiguration");
        if (defaultConfiguration != null && !defaultConfiguration.isNull()) {
            if (!defaultConfiguration.isTextual()) {
                throw invalid("'semantic.defaultConfiguration' must be a string.");
            }
            if (!names.contains(defaultConfiguration.textValue())) {
                throw invalid("the default semantic configuration '" + defaultConfiguration.textValue() + "' is not "
                    + "defined in 'semantic.configurations'.");
            }
        }
        return section;
    }

    private static void validateConfiguration(JsonNode node, SearchIndexDefinition fields, Set<String> names) {
        if (!(node instanceof ObjectNode configuration)) {
            throw invalid("each semantic configuration must be a JSON object.");
        }
        JsonNode nameNode = configuration.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.textValue().isBlank()) {
            throw invalid("each semantic configuration must have a name.");
        }
        String name = nameNode.textValue();
        if (!names.add(name)) {
            throw invalid("the semantic configuration name '" + name + "' is used more than once.");
        }
        JsonNode prioritized = configuration.get("prioritizedFields");
        if (!(prioritized instanceof ObjectNode prioritizedFields)) {
            throw invalid("the semantic configuration '" + name + "' must set 'prioritizedFields'.");
        }
        boolean title = validateTitle(prioritizedFields.get("titleField"), name, fields);
        int content = validateList(prioritizedFields.get("prioritizedContentFields"), "prioritizedContentFields", name,
            fields);
        int keywords = validateList(prioritizedFields.get("prioritizedKeywordsFields"), "prioritizedKeywordsFields",
            name, fields);
        if (!title && content == 0 && keywords == 0) {
            throw invalid("the semantic configuration '" + name + "' must set at least one of 'titleField', "
                + "'prioritizedContentFields' and 'prioritizedKeywordsFields'.");
        }
        normalizeRankingOrder(configuration, name);
        JsonNode flighting = configuration.get("flightingOptIn");
        if (flighting != null && !flighting.isNull() && !flighting.isBoolean()) {
            throw invalid("'flightingOptIn' of the semantic configuration '" + name + "' must be true or false.");
        }
    }

    private static boolean validateTitle(JsonNode title, String configuration, SearchIndexDefinition fields) {
        if (title == null || title.isNull()) {
            return false;
        }
        validateField(title, "titleField", configuration, fields);
        return true;
    }

    private static int validateList(JsonNode list, String property, String configuration,
                                    SearchIndexDefinition fields) {
        if (list == null || list.isNull()) {
            return 0;
        }
        if (!list.isArray()) {
            throw invalid("'" + property + "' of the semantic configuration '" + configuration + "' must be an array.");
        }
        for (JsonNode field : list) {
            validateField(field, property, configuration, fields);
        }
        return list.size();
    }

    private static void validateField(JsonNode entry, String property, String configuration,
                                      SearchIndexDefinition fields) {
        String path = SearchJson.text(entry, "fieldName");
        if (!(entry instanceof ObjectNode) || path == null || path.isBlank()) {
            throw invalid("each entry of '" + property + "' of the semantic configuration '" + configuration
                + "' must be an object with a 'fieldName'.");
        }
        SearchFieldDefinition field = fields.field(path).orElseThrow(() -> invalid("the semantic configuration '"
            + configuration + "' names the field '" + path + "' in '" + property + "', which is not a field of the "
            + "index."));
        if (!field.type().isText()) {
            throw invalid("the semantic configuration '" + configuration + "' names the field '" + path + "' of type '"
                + field.type().edmName() + "'; semantic fields must be of type Edm.String or Collection(Edm.String).");
        }
        if (!field.searchable() || !field.retrievable()) {
            throw invalid("the semantic configuration '" + configuration + "' names the field '" + path + "', which "
                + "is not searchable and retrievable; semantic fields must be both.");
        }
    }

    private static void normalizeRankingOrder(ObjectNode configuration, String name) {
        JsonNode rankingOrder = configuration.get("rankingOrder");
        if (rankingOrder == null || rankingOrder.isNull()) {
            return;
        }
        if (rankingOrder.isTextual()) {
            String lower = rankingOrder.textValue().toLowerCase(Locale.ROOT);
            for (String known : RANKING_ORDERS) {
                if (known.toLowerCase(Locale.ROOT).equals(lower)) {
                    configuration.put("rankingOrder", known);
                    return;
                }
            }
        }
        throw invalid("the semantic configuration '" + name + "' has 'rankingOrder' " + rankingOrder + "; valid "
            + "values are 'BoostedRerankerScore' and 'RerankerScore'.");
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
