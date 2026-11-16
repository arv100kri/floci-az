package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Validates and normalizes an indexer definition ({@code SearchIndexer}) the way Create and Create or Update Indexer
 * store it:
 * <ul>
 *   <li>{@code name}, {@code dataSourceName} and {@code targetIndexName} are required, the name following the
 *       documented rule;</li>
 *   <li>{@code schedule.interval} is an XSD {@code dayTimeDuration} between the configured floor
 *       ({@code floci-az.services.search.indexer-min-interval-seconds}, Azure's five minutes by default) and 24 hours;
 *       an omitted {@code startTime} is the current time ("If you omit this value, the current time is used");</li>
 *   <li>{@code parameters} keeps what the caller set, with {@code configuration} defaulting to {@code {}}; the
 *       enumerated configuration settings must hold a documented value, and every setting a primitive one;</li>
 *   <li>field mappings need a {@code sourceFieldName} and name a supported mapping function, if any;</li>
 *   <li>{@code encryptionKey} keeps its stored value when an update leaves it out or sets null.</li>
 * </ul>
 * Properties the parser does not know ({@code cache} and preview additions) are kept as sent.
 */
public final class IndexerDefinitionParser {

    /** The longest schedule interval: 24 hours. */
    static final Duration MAX_INTERVAL = Duration.ofHours(24);

    private static final Set<String> KNOWN = Set.of("name", "description", "dataSourceName", "skillsetName",
        "targetIndexName", "disabled", "schedule", "parameters", "fieldMappings", "outputFieldMappings", "cache",
        "encryptionKey");
    private static final Map<String, Set<String>> ENUMERATED = Map.of(
        "parsingMode", Set.of("default", "text", "delimitedText", "json", "jsonArray", "jsonLines", "markdown"),
        "dataToExtract", Set.of("storageMetadata", "allMetadata", "contentAndMetadata"),
        "imageAction", Set.of("none", "generateNormalizedImages", "generateNormalizedImagePerPage"),
        "executionEnvironment", Set.of("standard", "private"),
        "markdownParsingSubmode", Set.of("oneToMany", "oneToOne"),
        "markdownHeaderDepth", Set.of("h1", "h2", "h3", "h4", "h5", "h6"),
        "pdfTextRotationAlgorithm", Set.of("none", "detectAngles"));

    private IndexerDefinitionParser() {
    }

    /**
     * Parses a request body.
     *
     * @param body        the request body
     * @param nameInUrl   the name the URL names, or null for a create by POST
     * @param previous    the stored definition being replaced, or null
     * @param minInterval the shortest schedule interval allowed
     * @param now         the current time, for an omitted schedule start
     * @return the definition to store, without an ETag
     */
    public static IndexerDefinition parse(JsonNode body, String nameInUrl, IndexerDefinition previous,
                                          Duration minInterval, Instant now) {
        if (!(body instanceof ObjectNode input)) {
            throw invalid("the indexer definition must be a JSON object.");
        }
        String name = resolveName(input, nameInUrl);
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.set("description", copyOrNull(input.get("description")));
        output.put("dataSourceName", requireText(input, "dataSourceName"));
        String skillset = optionalText(input, "skillsetName");
        if (skillset == null) {
            output.putNull("skillsetName");
        } else {
            output.put("skillsetName", skillset);
        }
        output.put("targetIndexName", requireText(input, "targetIndexName"));
        JsonNode disabled = input.get("disabled");
        if (!IndexerJson.isNull(disabled) && !disabled.isBoolean()) {
            throw invalid("disabled must be true, false or null.");
        }
        output.set("disabled", copyOrNull(disabled));
        output.set("schedule", schedule(input.get("schedule"), minInterval, now));
        output.set("parameters", parameters(input.get("parameters"), skillset != null));
        output.set("fieldMappings", fieldMappings(input.get("fieldMappings"), "fieldMappings"));
        output.set("outputFieldMappings", outputFieldMappings(input.get("outputFieldMappings")));
        output.set("cache", copyOrNull(input.get("cache")));
        output.set("encryptionKey", encryptionKey(input.get("encryptionKey"), previous));
        Iterator<Map.Entry<String, JsonNode>> extras = input.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!KNOWN.contains(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                output.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return IndexerDefinition.fromJson(output, null);
    }

    /** Parses an XSD {@code dayTimeDuration} ({@code PT5M}, {@code P1D}); null when it is not one. */
    public static Duration parseInterval(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Duration duration = Duration.parse(text.trim());
            return duration.isNegative() || duration.isZero() ? null : duration;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String resolveName(ObjectNode input, String nameInUrl) {
        String name = SearchJson.text(input, "name");
        if (name == null || name.isBlank()) {
            if (nameInUrl == null) {
                throw invalid("the indexer name is required.");
            }
            name = nameInUrl;
        } else if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the indexer name '" + name + "' in the request body does not match the name '" + nameInUrl
                + "' in the request URL.");
        }
        if (!DataSourceParser.isValidName(name)) {
            throw invalid("Indexer name '" + name + "' is invalid. Indexer names must be 1 to 128 characters long, "
                + "contain only lowercase letters, digits or dashes, and start and end with a letter or digit.");
        }
        return name;
    }

    private static JsonNode schedule(JsonNode node, Duration minInterval, Instant now) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.nullNode();
        }
        if (!(node instanceof ObjectNode input)) {
            throw invalid("schedule must be a JSON object.");
        }
        String intervalText = SearchJson.text(input, "interval");
        Duration interval = parseInterval(intervalText);
        if (interval == null) {
            throw invalid("schedule.interval '" + intervalText + "' is not a valid duration: give an ISO 8601 "
                + "dayTimeDuration such as PT15M or PT2H.");
        }
        if (interval.compareTo(minInterval) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
            throw invalid("schedule.interval '" + intervalText + "' is out of range: the interval must be at least "
                + minInterval + " and at most " + MAX_INTERVAL + ".");
        }
        ObjectNode schedule = input.deepCopy();
        schedule.put("interval", intervalText);
        JsonNode start = input.get("startTime");
        if (IndexerJson.isNull(start)) {
            schedule.put("startTime", IndexerJson.formatTime(now));
        } else if (!start.isTextual() || DocumentCoercer.parseDateTime(start.textValue()) == null) {
            throw invalid("schedule.startTime '" + start.asText() + "' is not a valid date and time.");
        }
        return schedule;
    }

    private static JsonNode parameters(JsonNode node, boolean hasSkillset) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.nullNode();
        }
        if (!(node instanceof ObjectNode input)) {
            throw invalid("parameters must be a JSON object.");
        }
        ObjectNode parameters = input.deepCopy();
        checkInteger(parameters, "batchSize", 1);
        checkInteger(parameters, "maxFailedItems", -1);
        checkInteger(parameters, "maxFailedItemsPerBatch", -1);
        JsonNode configuration = parameters.get("configuration");
        if (IndexerJson.isNull(configuration)) {
            parameters.putObject("configuration");
            return parameters;
        }
        if (!(configuration instanceof ObjectNode settings)) {
            throw invalid("parameters.configuration must be a JSON object.");
        }
        validateConfiguration(settings, hasSkillset);
        return parameters;
    }

    private static void validateConfiguration(ObjectNode settings, boolean hasSkillset) {
        Iterator<Map.Entry<String, JsonNode>> entries = settings.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getValue().isContainerNode()) {
                throw invalid("parameters.configuration." + entry.getKey() + " must be a primitive value: each "
                    + "configuration value must be of a primitive type.");
            }
            Set<String> allowed = ENUMERATED.get(entry.getKey());
            if (allowed != null && !entry.getValue().isNull() && !allowed.contains(entry.getValue().asText())) {
                throw invalid("parameters.configuration." + entry.getKey() + " '" + entry.getValue().asText()
                    + "' is not supported. Supported values are " + String.join(", ", allowed.stream().sorted()
                    .toList()) + ".");
            }
        }
        String delimiter = SearchJson.text(settings, "delimitedTextDelimiter");
        if (delimiter != null && delimiter.length() != 1) {
            throw invalid("parameters.configuration.delimitedTextDelimiter must be a single character.");
        }
        String imageAction = SearchJson.text(settings, "imageAction");
        if (imageAction != null && !"none".equals(imageAction)) {
            if (!hasSkillset) {
                throw invalid("parameters.configuration.imageAction '" + imageAction + "' requires a skillset: "
                    + "setting imageAction to any value other than 'none' requires that a skillset also be attached "
                    + "to the indexer.");
            }
            String dataToExtract = SearchJson.text(settings, "dataToExtract");
            if (dataToExtract != null && !"contentAndMetadata".equals(dataToExtract)) {
                throw invalid("parameters.configuration.imageAction '" + imageAction + "' requires dataToExtract "
                    + "'contentAndMetadata'.");
            }
        }
    }

    private static void checkInteger(ObjectNode parameters, String name, int minimum) {
        JsonNode value = parameters.get(name);
        if (IndexerJson.isNull(value)) {
            return;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum) {
            throw invalid("parameters." + name + " '" + value.asText() + "' is invalid: it must be an integer of at "
                + "least " + minimum + ".");
        }
    }

    private static ArrayNode fieldMappings(JsonNode node, String property) {
        ArrayNode mappings = SearchJson.NODES.arrayNode();
        if (IndexerJson.isNull(node)) {
            return mappings;
        }
        if (!(node instanceof ArrayNode array)) {
            throw invalid(property + " must be a JSON array.");
        }
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode mapping)) {
                throw invalid("each entry of " + property + " must be a JSON object.");
            }
            String source = SearchJson.text(mapping, "sourceFieldName");
            if (source == null || source.isBlank()) {
                throw invalid("each entry of " + property + " needs a sourceFieldName.");
            }
            ObjectNode normalized = mapping.deepCopy();
            normalized.put("sourceFieldName", source);
            normalized.set("targetFieldName", copyOrNull(mapping.get("targetFieldName")));
            JsonNode function = mapping.get("mappingFunction");
            if (IndexerJson.isNull(function)) {
                normalized.putNull("mappingFunction");
            } else if (function instanceof ObjectNode functionNode) {
                JsonNode parameters = functionNode.get("parameters");
                if (!IndexerJson.isNull(parameters) && !parameters.isObject()) {
                    throw invalid("the parameters of a mapping function must be a JSON object.");
                }
                MappingFunctions.validate(new MappingFunction(SearchJson.text(functionNode, "name"),
                    parameters instanceof ObjectNode parameterObject ? parameterObject : null),
                    "The field mapping of '" + source + "'");
                ObjectNode normalizedFunction = functionNode.deepCopy();
                normalizedFunction.set("parameters", copyOrNull(parameters));
                normalized.set("mappingFunction", normalizedFunction);
            } else {
                throw invalid("mappingFunction must be a JSON object.");
            }
            mappings.add(normalized);
        }
        return mappings;
    }

    private static ArrayNode outputFieldMappings(JsonNode node) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.arrayNode();
        }
        if (!(node instanceof ArrayNode array)) {
            throw invalid("outputFieldMappings must be a JSON array.");
        }
        for (JsonNode item : array) {
            String source = SearchJson.text(item, "sourceFieldName");
            if (source == null || source.isBlank()) {
                throw invalid("each entry of outputFieldMappings needs a sourceFieldName.");
            }
        }
        return array.deepCopy();
    }

    private static JsonNode encryptionKey(JsonNode requested, IndexerDefinition previous) {
        if (!IndexerJson.isNull(requested)) {
            if (!requested.isObject()) {
                throw invalid("encryptionKey must be a JSON object.");
            }
            return requested.deepCopy();
        }
        JsonNode stored = previous == null ? null : previous.json().get("encryptionKey");
        return IndexerJson.isNull(stored) ? SearchJson.NODES.nullNode() : stored.deepCopy();
    }

    private static String requireText(ObjectNode input, String field) {
        String value = SearchJson.text(input, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String optionalText(ObjectNode input, String field) {
        JsonNode value = input.get(field);
        if (IndexerJson.isNull(value)) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        return value.textValue().isBlank() ? null : value.textValue();
    }

    private static JsonNode copyOrNull(JsonNode node) {
        return node == null ? SearchJson.NODES.nullNode() : node.deepCopy();
    }

    static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: indexer : " + detail);
    }
}
