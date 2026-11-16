package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates the {@code scoringProfiles} of an index definition beyond their names, per Create Index (REST) and Learn's
 * "Add scoring profiles": at most 100 profiles; text weights on searchable string fields, each a positive number;
 * functions of type {@code magnitude}, {@code freshness}, {@code distance} or {@code tag} (lower case), each on one
 * filterable field of the matching type ({@code Edm.Double}/{@code Edm.Int32}/{@code Edm.Int64},
 * {@code Edm.DateTimeOffset}, {@code Edm.GeographyPoint}, {@code Edm.String} or {@code Collection(Edm.String)}), with a
 * positive {@code boost} other than 1, a known interpolation (not {@code quadratic} or {@code logarithmic} for tags) and
 * the parameters of its type; and a known {@code functionAggregation}.
 */
public final class ScoringProfileValidator {

    /** The most scoring profiles an index may define. */
    public static final int MAX_PROFILES = 100;

    private static final Set<String> INTERPOLATIONS = Set.of("linear", "constant", "quadratic", "logarithmic");
    private static final Set<String> AGGREGATIONS = Set.of("sum", "average", "minimum", "maximum", "firstmatching",
        "product");

    private ScoringProfileValidator() {
    }

    /**
     * Validates the {@code scoringProfiles} of {@code index}, a normalised definition.
     *
     * @param fields the index's fields
     */
    public static void validate(ObjectNode index, SearchIndexDefinition fields) {
        JsonNode profiles = index.get("scoringProfiles");
        if (profiles.size() > MAX_PROFILES) {
            throw invalid("the index defines " + profiles.size() + " scoring profiles; the maximum is "
                + MAX_PROFILES + ".");
        }
        for (JsonNode profile : profiles) {
            if (!profile.isObject()) {
                throw invalid("each scoring profile must be a JSON object.");
            }
            String name = SearchJson.text(profile, "name");
            validateText(name, profile.get("text"), fields);
            JsonNode functions = profile.get("functions");
            if (functions != null && !functions.isNull()) {
                if (!functions.isArray()) {
                    throw invalid("the 'functions' of the scoring profile '" + name + "' must be an array.");
                }
                for (JsonNode function : functions) {
                    validateFunction(name, function, fields);
                }
            }
            JsonNode aggregation = profile.get("functionAggregation");
            if (aggregation != null && !aggregation.isNull() && (!aggregation.isTextual()
                    || !AGGREGATIONS.contains(aggregation.textValue().toLowerCase(Locale.ROOT)))) {
                throw invalid("the scoring profile '" + name + "' has an invalid functionAggregation '"
                    + aggregation.asText() + "'; use sum, average, minimum, maximum or firstMatching.");
            }
        }
    }

    private static void validateText(String profile, JsonNode text, SearchIndexDefinition fields) {
        if (text == null || text.isNull()) {
            return;
        }
        JsonNode weights = text.get("weights");
        if (!(weights instanceof ObjectNode weightObject)) {
            throw invalid("the 'text' of the scoring profile '" + profile + "' must set 'weights' to an object of "
                + "field names and weights.");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = weightObject.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            SearchFieldDefinition field = fields.field(entry.getKey()).orElseThrow(() -> invalid("the scoring "
                + "profile '" + profile + "' weights the field '" + entry.getKey() + "', which the index does not "
                + "define."));
            if (!field.isFullTextSearchable()) {
                throw invalid("the scoring profile '" + profile + "' weights the field '" + entry.getKey()
                    + "', which is not a searchable string field.");
            }
            if (!entry.getValue().isNumber() || !(entry.getValue().doubleValue() > 0)) {
                throw invalid("the weight of the field '" + entry.getKey() + "' in the scoring profile '" + profile
                    + "' must be a positive number.");
            }
        }
    }

    private static void validateFunction(String profile, JsonNode function, SearchIndexDefinition fields) {
        if (!function.isObject()) {
            throw invalid("each function of the scoring profile '" + profile + "' must be a JSON object.");
        }
        String type = SearchJson.text(function, "type");
        if (type == null || !Set.of("magnitude", "freshness", "distance", "tag").contains(type)) {
            throw invalid("the scoring profile '" + profile + "' has a function of type '" + type + "'; the type "
                + "must be magnitude, freshness, distance or tag, in lower case.");
        }
        String fieldName = SearchJson.text(function, "fieldName");
        if (fieldName == null || fieldName.isBlank()) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' must set "
                + "'fieldName'.");
        }
        SearchFieldDefinition field = fields.field(fieldName).orElseThrow(() -> invalid("the " + type
            + " function of the scoring profile '" + profile + "' names the field '" + fieldName + "', which the "
            + "index does not define."));
        if (!field.filterable()) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' names the field '"
                + fieldName + "', which is not filterable; scoring functions apply only to filterable fields.");
        }
        if (!fieldTypeFits(type, field.type())) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' cannot use the field '"
                + fieldName + "' of type " + field.type().edmName() + ".");
        }
        JsonNode boost = function.get("boost");
        if (boost == null || !boost.isNumber() || !(boost.doubleValue() > 0) || boost.doubleValue() == 1.0) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' must set 'boost' to a "
                + "positive number other than 1.0.");
        }
        String interpolation = SearchJson.text(function, "interpolation");
        if (function.hasNonNull("interpolation") && (interpolation == null
                || !INTERPOLATIONS.contains(interpolation.toLowerCase(Locale.ROOT)))) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' has an invalid "
                + "interpolation; use linear, constant, quadratic or logarithmic.");
        }
        if (type.equals("tag") && interpolation != null && Set.of("quadratic", "logarithmic")
                .contains(interpolation.toLowerCase(Locale.ROOT))) {
            throw invalid("the tag function of the scoring profile '" + profile + "' cannot use the "
                + interpolation + " interpolation.");
        }
        JsonNode parameters = function.get(type);
        if (!(parameters instanceof ObjectNode)) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' must set '" + type
                + "' to an object of its parameters.");
        }
        switch (type) {
            case "magnitude" -> {
                requireNumber(profile, type, parameters, "boostingRangeStart");
                requireNumber(profile, type, parameters, "boostingRangeEnd");
                JsonNode constant = parameters.get("constantBoostBeyondRange");
                if (constant != null && !constant.isNull() && !constant.isBoolean()) {
                    throw invalid("'constantBoostBeyondRange' of the magnitude function of the scoring profile '"
                        + profile + "' must be true or false.");
                }
            }
            case "freshness" -> {
                String duration = SearchJson.text(parameters, "boostingDuration");
                if (duration == null || parseDuration(duration) == null) {
                    throw invalid("the freshness function of the scoring profile '" + profile + "' must set "
                        + "'boostingDuration' to a dayTimeDuration such as P30D or PT12H.");
                }
            }
            case "distance" -> {
                requireText(profile, type, parameters, "referencePointParameter");
                double distance = requireNumber(profile, type, parameters, "boostingDistance");
                if (!(distance > 0)) {
                    throw invalid("'boostingDistance' of the distance function of the scoring profile '" + profile
                        + "' must be greater than zero.");
                }
            }
            default -> requireText(profile, type, parameters, "tagsParameter");
        }
    }

    /** Parses an XSD {@code dayTimeDuration} ({@code P[nD][T[nH][nM][nS]]}, optionally negative), or null. */
    public static Duration parseDuration(String text) {
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean fieldTypeFits(String type, FieldType fieldType) {
        EdmType element = fieldType.element();
        return switch (type) {
            case "magnitude" -> element.isNumeric() && !fieldType.collection();
            case "freshness" -> element == EdmType.DATE_TIME_OFFSET && !fieldType.collection();
            case "distance" -> element == EdmType.GEOGRAPHY_POINT && !fieldType.collection();
            default -> element == EdmType.STRING;
        };
    }

    private static double requireNumber(String profile, String type, JsonNode parameters, String name) {
        JsonNode value = parameters.get(name);
        if (value == null || !value.isNumber()) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' must set '" + name
                + "' to a number.");
        }
        return value.doubleValue();
    }

    private static void requireText(String profile, String type, JsonNode parameters, String name) {
        String value = SearchJson.text(parameters, name);
        if (value == null || value.isBlank()) {
            throw invalid("the " + type + " function of the scoring profile '" + profile + "' must set '" + name
                + "'.");
        }
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
