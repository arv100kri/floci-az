package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.skills.SkillTypes.SkillType;
import org.jboss.logging.Logger;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validates and normalises a skillset definition (Create Skillset, Create or Update Skillset) per the REST
 * specification and the skill reference: the name rules, 1 to 30 skills of known {@code @odata.type}, unique skill
 * names, valid paths and expressions in contexts and inputs, the documented parameter ranges of the skills the emulator
 * runs and the input and output names they accept, {@code cognitiveServices} of a known type, {@code indexProjections}
 * whose target index exists with a {@code keyword} key and a filterable string parent key field, and the Web API
 * skill's secret placeholders. {@code knowledgeStore} and {@code encryptionKey} are stored, not acted on.
 *
 * <p>The result has two forms, as the service answers: the response to the write is the definition as sent, with
 * every documented property present ({@code null} when not given), and the stored form, which GET and the indexer see,
 * adds the defaults the service applies: skill names ({@code #1}, {@code #2}, ... by position), the {@code /document}
 * context and the {@code en} default language.</p>
 */
public final class SkillsetDefinitions {

    private static final Logger LOG = Logger.getLogger(SkillsetDefinitions.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?");
    private static final int MAX_SKILLS = 30;
    private static final Set<String> PROHIBITED_HEADERS = Set.of("accept", "accept-charset", "accept-encoding",
        "content-length", "content-type", "cookie", "host", "te", "upgrade", "via");
    private static final Map<String, int[]> EMBEDDING_DIMENSIONS = Map.of(
        "text-embedding-ada-002", new int[] {1536, 1536},
        "text-embedding-3-large", new int[] {1, 3072},
        "text-embedding-3-small", new int[] {1, 1536});
    private static final Map<String, List<String>> COGNITIVE_SERVICES = Map.of(
        "#Microsoft.Azure.Search.DefaultCognitiveServices", List.of(),
        "#Microsoft.Azure.Search.CognitiveServicesByKey", List.of("key"),
        "#Microsoft.Azure.Search.AIServicesByKey", List.of("key", "subdomainUrl"),
        "#Microsoft.Azure.Search.AIServicesByIdentity", List.of("subdomainUrl"));
    private static final Set<String> PROJECTION_MODES = Set.of("skipindexingparentdocuments",
        "includeindexingparentdocuments");

    private SkillsetDefinitions() {
    }

    /**
     * A copy of a stored definition fit for GET and LIST: the Custom Web API skills' secret query values and header
     * values replaced by {@code <redacted>}, as documented.
     */
    public static ObjectNode redacted(ObjectNode definition) {
        ObjectNode copy = definition.deepCopy();
        WebApiSecrets.redact(copy);
        return copy;
    }

    /**
     * Validates and normalises {@code body}.
     *
     * @param body      the request body
     * @param nameInUrl the skillset name the URL names, or null for a create by POST
     * @param previous  the stored definition being replaced, or null; its Web API secrets and encryption key are kept
     *                  where the request asks for them
     * @param indexes   the indexes of the service, for index projections
     * @param strict    whether custom skill URIs must use https without exception, as Azure requires; otherwise plain
     *                  http is also accepted for {@linkplain #isLocalHost local} endpoints
     * @throws SearchException a 400 describing the first problem found
     */
    public static ParsedSkillset parse(JsonNode body, String nameInUrl, ObjectNode previous, IndexLookup indexes,
                                       boolean strict) {
        if (!(body instanceof ObjectNode input)) {
            throw invalid("the skillset definition must be a JSON object.");
        }
        ObjectNode request = input.deepCopy();
        WebApiSecrets.restore(request, previous);
        String name = name(request, nameInUrl);
        JsonNode skillsNode = request.get("skills");
        if (!(skillsNode instanceof ArrayNode skillArray) || skillArray.isEmpty()) {
            throw invalid("the skillset must have at least one skill.");
        }
        if (skillArray.size() > MAX_SKILLS) {
            throw invalid("the skillset has " + skillArray.size() + " skills; the maximum is " + MAX_SKILLS + ".");
        }
        ArrayNode responseSkills = NODES.arrayNode();
        ArrayNode storedSkills = NODES.arrayNode();
        List<SkillDefinition> definitions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        ArrayNode sentSkills = (ArrayNode) input.get("skills");
        for (int index = 0; index < skillArray.size(); index++) {
            ObjectNode normalized = normalizeSkill(skillArray.get(index), index + 1);
            ObjectNode stored = withDefaults(normalized, index + 1);
            String skillName = stored.get("name").textValue();
            if (!names.add(skillName)) {
                throw invalidSkills("Skill names must be unique within a skillset; the name '" + skillName
                    + "' is used more than once.");
            }
            SkillDefinition definition;
            try {
                definition = SkillDefinition.parse(stored, index + 1);
            } catch (IllegalArgumentException e) {
                throw skillError(skillName, capitalize(e.getMessage()) + ".");
            }
            validateSkill(definition, strict ? SchemeRule.HTTPS : SchemeRule.HTTPS_OR_LOCAL_HTTP);
            definitions.add(definition);
            // The answer to the write echoes the skill as sent: placeholders stay placeholders, not restored secrets.
            responseSkills.add(normalizeSkill(sentSkills.get(index), index + 1));
            storedSkills.add(stored);
        }
        List<String> cycle = SkillOrder.cycle(definitions);
        if (!cycle.isEmpty()) {
            throw invalidSkills("The skills " + cycle + " depend on each other's outputs in a cycle.");
        }
        ObjectNode response = NODES.objectNode();
        response.put("name", name);
        response.set("description", copyOrNull(request.get("description")));
        response.set("skills", responseSkills);
        response.set("cognitiveServices", cognitiveServices(request.get("cognitiveServices")));
        response.set("knowledgeStore", knowledgeStore(request.get("knowledgeStore")));
        response.set("indexProjections", indexProjections(request.get("indexProjections"), indexes));
        response.set("encryptionKey", encryptionKey(request.get("encryptionKey"), previous));
        Iterator<Map.Entry<String, JsonNode>> extras = request.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!response.has(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                response.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        ObjectNode stored = response.deepCopy();
        stored.set("skills", storedSkills);
        return new ParsedSkillset(name, response, stored);
    }

    private static String name(ObjectNode request, String nameInUrl) {
        String name = SkillDefinition.text(request, "name");
        if (name == null || name.isEmpty()) {
            if (nameInUrl == null) {
                throw invalid("the skillset name is required.");
            }
            name = nameInUrl;
        } else if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the skillset name '" + name + "' in the request body does not match the name '" + nameInUrl
                + "' in the request URL.");
        }
        if (!NAME.matcher(name).matches()) {
            throw invalid("Skillset name '" + name + "' is invalid. Skillset names must be 1 to 128 characters long, "
                + "contain only lowercase letters, digits or dashes, and start and end with a letter or digit.");
        }
        return name;
    }

    /** The skill as written, with every documented property of its type present. */
    private static ObjectNode normalizeSkill(JsonNode node, int position) {
        if (!(node instanceof ObjectNode skill)) {
            throw invalidSkills("Skill #" + position + " is not a JSON object.");
        }
        String type = SkillDefinition.text(skill, "@odata.type");
        String label = SkillDefinition.text(skill, "name") == null ? "#" + position
            : SkillDefinition.text(skill, "name");
        if (type == null) {
            throw skillError(label, "The skill has no '@odata.type'.");
        }
        SkillType skillType = SkillTypes.find(type).orElseThrow(() -> skillError(label, "'" + type
            + "' is not a known skill type."));
        ObjectNode normalized = NODES.objectNode();
        normalized.put("@odata.type", type);
        normalized.set("name", copyOrNull(skill.get("name")));
        normalized.set("description", copyOrNull(skill.get("description")));
        normalized.set("context", copyOrNull(skill.get("context")));
        for (String parameter : skillType.parameters()) {
            normalized.set(parameter, copyOrNull(skill.get(parameter)));
        }
        normalized.set("inputs", normalizeInputs(skill.get("inputs"), label));
        normalized.set("outputs", normalizeOutputs(skill.get("outputs"), label));
        for (String parameter : skillType.trailing()) {
            normalized.set(parameter, copyOrNull(skill.get(parameter)));
        }
        Iterator<Map.Entry<String, JsonNode>> extras = skill.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!normalized.has(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                normalized.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return normalized;
    }

    private static ArrayNode normalizeInputs(JsonNode inputs, String skill) {
        ArrayNode normalized = NODES.arrayNode();
        if (inputs == null || inputs.isNull()) {
            return normalized;
        }
        if (!(inputs instanceof ArrayNode array)) {
            throw skillError(skill, "'inputs' must be an array.");
        }
        for (JsonNode input : array) {
            if (!(input instanceof ObjectNode entry)) {
                throw skillError(skill, "Each input must be a JSON object.");
            }
            ObjectNode copy = NODES.objectNode();
            copy.set("name", copyOrNull(entry.get("name")));
            copy.set("source", copyOrNull(entry.get("source")));
            copy.set("sourceContext", copyOrNull(entry.get("sourceContext")));
            copy.set("inputs", normalizeInputs(entry.get("inputs"), skill));
            normalized.add(copy);
        }
        return normalized;
    }

    private static ArrayNode normalizeOutputs(JsonNode outputs, String skill) {
        ArrayNode normalized = NODES.arrayNode();
        if (outputs == null || outputs.isNull()) {
            return normalized;
        }
        if (!(outputs instanceof ArrayNode array)) {
            throw skillError(skill, "'outputs' must be an array.");
        }
        for (JsonNode output : array) {
            if (!(output instanceof ObjectNode entry)) {
                throw skillError(skill, "Each output must be a JSON object.");
            }
            ObjectNode copy = NODES.objectNode();
            copy.set("name", copyOrNull(entry.get("name")));
            copy.set("targetName", copyOrNull(entry.get("targetName")));
            normalized.add(copy);
        }
        return normalized;
    }

    /** The stored form of a normalised skill: its default name, context and language applied. */
    private static ObjectNode withDefaults(ObjectNode normalized, int position) {
        ObjectNode stored = normalized.deepCopy();
        if (isNullOrEmpty(stored.get("name"))) {
            stored.put("name", "#" + position);
        }
        if (isNullOrEmpty(stored.get("context"))) {
            stored.put("context", "/document");
        }
        boolean languageAware = SkillTypes.find(stored.get("@odata.type").textValue())
            .map(SkillType::hasDefaultLanguage).orElse(false);
        if (languageAware && isNullOrEmpty(stored.get("defaultLanguageCode"))) {
            stored.put("defaultLanguageCode", "en");
        }
        return stored;
    }

    private static void validateSkill(SkillDefinition skill, SchemeRule customSkillScheme) {
        SkillType type = SkillTypes.find(skill.odataType()).orElseThrow();
        validateNames(skill, type);
        ObjectNode json = skill.json();
        switch (skill.odataType()) {
            case SkillTypes.SPLIT -> validateSplit(skill, json);
            case SkillTypes.WEB_API -> validateWebApi(skill, json, customSkillScheme);
            case SkillTypes.AML -> validateAml(skill, json, customSkillScheme);
            case SkillTypes.AZURE_OPENAI_EMBEDDING -> validateEmbedding(skill, json);
            default -> LOG.tracev("Skill type {0} has no parameters the emulator validates", skill.odataType());
        }
    }

    private static void validateNames(SkillDefinition skill, SkillType type) {
        if (type.inputs() != null) {
            Set<String> unsupported = new TreeSet<>();
            skill.inputs().forEach(input -> {
                if (!type.inputs().contains(input.name())) {
                    unsupported.add(input.name());
                }
            });
            if (!unsupported.isEmpty()) {
                throw skillError(skill.name(), "Inputs are not supported by skill: " + String.join(", ", unsupported));
            }
        }
        if (type.outputs() != null) {
            Set<String> unsupported = new TreeSet<>();
            skill.outputs().forEach(output -> {
                if (!type.outputs().contains(output.name())) {
                    unsupported.add(output.name());
                }
            });
            if (!unsupported.isEmpty()) {
                throw skillError(skill.name(), "Outputs are not supported by skill: "
                    + String.join(", ", unsupported));
            }
        }
    }

    private static void validateSplit(SkillDefinition skill, ObjectNode json) {
        String mode = SkillDefinition.text(json, "textSplitMode");
        if (!isNullOrEmpty(json.get("textSplitMode"))
                && !("pages".equalsIgnoreCase(mode) || "sentences".equalsIgnoreCase(mode))) {
            throw skillError(skill.name(), "'textSplitMode' must be 'pages' or 'sentences'.");
        }
        String unit = SkillDefinition.text(json, "unit");
        if (!isNullOrEmpty(json.get("unit"))
                && !("characters".equalsIgnoreCase(unit) || "azureOpenAITokens".equalsIgnoreCase(unit))) {
            throw skillError(skill.name(), "'unit' must be 'characters' or 'azureOpenAITokens'.");
        }
        boolean characters = unit == null || "characters".equalsIgnoreCase(unit);
        Integer maximum = integer(skill, json, "maximumPageLength");
        if (maximum != null && characters && (maximum < 300 || maximum > 50000)) {
            throw skillError(skill.name(), "'maximumPageLength' must be between 300 and 50000.");
        }
        if (maximum != null && maximum < 1) {
            throw skillError(skill.name(), "'maximumPageLength' must be greater than 0.");
        }
        Integer overlap = integer(skill, json, "pageOverlapLength");
        int effectiveMaximum = maximum == null ? SplitSkill.DEFAULT_MAXIMUM_PAGE_LENGTH : maximum;
        if (overlap != null && (overlap < 0 || overlap >= effectiveMaximum)) {
            throw skillError(skill.name(), "'pageOverlapLength' must be at least 0 and less than "
                + "'maximumPageLength'.");
        }
        Integer pages = integer(skill, json, "maximumPagesToTake");
        if (pages != null && pages < 0) {
            throw skillError(skill.name(), "'maximumPagesToTake' must be at least 0.");
        }
        String language = SkillDefinition.text(json, "defaultLanguageCode");
        if (language != null && !SplitSkill.LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            throw skillError(skill.name(), "'" + language + "' is not a supported 'defaultLanguageCode'.");
        }
    }

    private static void validateWebApi(SkillDefinition skill, ObjectNode json, SchemeRule scheme) {
        validateEndpoint(skill, SkillDefinition.text(json, "uri"), "uri", true, scheme);
        String method = SkillDefinition.text(json, "httpMethod");
        if (!isNullOrEmpty(json.get("httpMethod"))
                && !("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
            throw skillError(skill.name(), "'httpMethod' must be 'POST' or 'PUT'.");
        }
        validateTimeout(skill, json);
        Integer batchSize = integer(skill, json, "batchSize");
        if (batchSize != null && batchSize < 1) {
            throw skillError(skill.name(), "'batchSize' must be at least 1.");
        }
        validateParallelism(skill, json);
        JsonNode headers = json.get("httpHeaders");
        if (headers != null && !headers.isNull()) {
            if (!(headers instanceof ObjectNode headerObject)) {
                throw skillError(skill.name(), "'httpHeaders' must be an object of header names and values.");
            }
            Iterator<Map.Entry<String, JsonNode>> entries = headerObject.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (PROHIBITED_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    throw skillError(skill.name(), "The header '" + entry.getKey() + "' cannot be set in "
                        + "'httpHeaders'.");
                }
                if (!entry.getValue().isTextual()) {
                    throw skillError(skill.name(), "The value of the header '" + entry.getKey()
                        + "' must be a string.");
                }
            }
        }
    }

    private static void validateAml(SkillDefinition skill, ObjectNode json, SchemeRule scheme) {
        String uri = SkillDefinition.text(json, "uri");
        if (uri == null && SkillDefinition.text(json, "resourceId") == null) {
            throw skillError(skill.name(), "Either 'uri' or 'resourceId' is required.");
        }
        validateEndpoint(skill, uri, "uri", false, scheme);
        validateTimeout(skill, json);
        validateParallelism(skill, json);
    }

    private static void validateEmbedding(SkillDefinition skill, ObjectNode json) {
        validateEndpoint(skill, SkillDefinition.text(json, "resourceUri"), "resourceUri", false,
            SchemeRule.HTTP_OR_HTTPS);
        String model = SkillDefinition.text(json, "modelName");
        if (model != null && !EMBEDDING_DIMENSIONS.containsKey(model)) {
            throw skillError(skill.name(), "'" + model + "' is not a supported 'modelName'; use one of "
                + new TreeSet<>(EMBEDDING_DIMENSIONS.keySet()) + ".");
        }
        Integer dimensions = integer(skill, json, "dimensions");
        if (dimensions != null) {
            int[] range = model == null ? new int[] {1, Integer.MAX_VALUE} : EMBEDDING_DIMENSIONS.get(model);
            if (dimensions < range[0] || dimensions > range[1]) {
                throw skillError(skill.name(), "'dimensions' " + dimensions + " is not supported"
                    + (model == null ? "" : " by '" + model + "', which supports " + range[0] + " to " + range[1])
                    + ".");
            }
        }
    }

    private static void validateEndpoint(SkillDefinition skill, String value, String parameter, boolean required,
                                         SchemeRule rule) {
        if (value == null) {
            if (required) {
                throw skillError(skill.name(), "'" + parameter + "' is required.");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw skillError(skill.name(), "'" + parameter + "' is not a valid URI.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw skillError(skill.name(), "'" + parameter + "' must be an absolute http or https URI.");
        }
        boolean refused = switch (rule) {
            case HTTP_OR_HTTPS -> false;
            case HTTPS_OR_LOCAL_HTTP -> scheme.equals("http") && !isLocalHost(uri.getHost());
            case HTTPS -> scheme.equals("http");
        };
        if (refused) {
            throw skillError(skill.name(), "'" + parameter + "' must use https: only the https URI scheme is "
                + "allowed.");
        }
    }

    /**
     * Whether {@code host} is on the emulator's own machine or network, where a plain http endpoint is accepted: a
     * loopback, private or link-local address literal, {@code localhost} and {@code *.localhost},
     * {@code host.docker.internal}, or a single-label name such as a Docker Compose service. No name is resolved.
     */
    static boolean isLocalHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("host.docker.internal")) {
            return true;
        }
        InetAddress literal = addressLiteral(lower);
        if (literal != null) {
            return literal.isLoopbackAddress() || literal.isSiteLocalAddress() || literal.isLinkLocalAddress()
                || (literal instanceof Inet6Address && (literal.getAddress()[0] & 0xFE) == 0xFC);
        }
        return !lower.contains(".");
    }

    private static InetAddress addressLiteral(String host) {
        boolean ipv4 = host.chars().allMatch(character -> character == '.' || Character.isDigit(character))
            && host.chars().filter(character -> character == '.').count() == 3;
        if (!ipv4 && !host.contains(":")) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void validateTimeout(SkillDefinition skill, ObjectNode json) {
        String timeout = SkillDefinition.text(json, "timeout");
        if (timeout == null) {
            if (!isNullOrEmpty(json.get("timeout"))) {
                throw skillError(skill.name(), "'timeout' must be an ISO 8601 duration such as 'PT60S'.");
            }
            return;
        }
        Duration duration;
        try {
            duration = Duration.parse(timeout.trim());
        } catch (DateTimeParseException e) {
            throw skillError(skill.name(), "'timeout' must be an ISO 8601 duration such as 'PT60S'.");
        }
        if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofSeconds(230)) > 0) {
            throw skillError(skill.name(), "'timeout' must be between 1 and 230 seconds.");
        }
    }

    private static void validateParallelism(SkillDefinition skill, ObjectNode json) {
        Integer parallelism = integer(skill, json, "degreeOfParallelism");
        if (parallelism != null && (parallelism < 1 || parallelism > 10)) {
            throw skillError(skill.name(), "'degreeOfParallelism' must be between 1 and 10.");
        }
    }

    private static Integer integer(SkillDefinition skill, ObjectNode json, String parameter) {
        JsonNode value = json.get(parameter);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw skillError(skill.name(), "'" + parameter + "' must be an integer.");
        }
        return value.intValue();
    }

    private static JsonNode cognitiveServices(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (!(node instanceof ObjectNode account)) {
            throw invalid("'cognitiveServices' must be a JSON object.");
        }
        String type = SkillDefinition.text(account, "@odata.type");
        List<String> required = type == null ? null : COGNITIVE_SERVICES.get(type);
        if (required == null) {
            throw invalid("'cognitiveServices' has an unknown @odata.type '" + type + "'.");
        }
        ObjectNode normalized = NODES.objectNode();
        normalized.put("@odata.type", type);
        normalized.set("description", copyOrNull(account.get("description")));
        for (String property : required) {
            if (isNullOrEmpty(account.get(property))) {
                throw invalid("'cognitiveServices' of type '" + type + "' requires '" + property + "'.");
            }
            normalized.set(property, account.get(property).deepCopy());
        }
        Iterator<Map.Entry<String, JsonNode>> extras = account.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!normalized.has(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                normalized.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return normalized;
    }

    private static JsonNode knowledgeStore(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (!(node instanceof ObjectNode store)) {
            throw invalid("'knowledgeStore' must be a JSON object.");
        }
        if (SkillDefinition.text(store, "storageConnectionString") == null) {
            throw invalid("'knowledgeStore' requires a 'storageConnectionString'.");
        }
        JsonNode projections = store.get("projections");
        if (projections != null && !projections.isNull() && !projections.isArray()) {
            throw invalid("'knowledgeStore.projections' must be an array.");
        }
        return store.deepCopy();
    }

    private static JsonNode indexProjections(JsonNode node, IndexLookup indexes) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (!(node instanceof ObjectNode projections)) {
            throw invalid("'indexProjections' must be a JSON object.");
        }
        if (!(projections.get("selectors") instanceof ArrayNode selectors) || selectors.isEmpty()) {
            throw invalidProjections("At least one selector is required.");
        }
        ArrayNode normalizedSelectors = NODES.arrayNode();
        for (JsonNode selector : selectors) {
            normalizedSelectors.add(selector(selector, indexes));
        }
        JsonNode parameters = projections.get("parameters");
        if (parameters != null && !parameters.isNull() && !parameters.isObject()) {
            throw invalidProjections("'parameters' must be a JSON object.");
        }
        String mode = SkillDefinition.text(parameters, "projectionMode");
        if (mode != null && !PROJECTION_MODES.contains(mode.toLowerCase(Locale.ROOT))) {
            throw invalidProjections("'projectionMode' must be 'skipIndexingParentDocuments' or "
                + "'includeIndexingParentDocuments'.");
        }
        ObjectNode normalized = NODES.objectNode();
        normalized.set("selectors", normalizedSelectors);
        normalized.set("parameters", parameters == null || parameters.isNull() ? NODES.objectNode()
            : parameters.deepCopy());
        return normalized;
    }

    private static ObjectNode selector(JsonNode node, IndexLookup indexes) {
        if (!(node instanceof ObjectNode selector)) {
            throw invalidProjections("Each selector must be a JSON object.");
        }
        String target = SkillDefinition.text(selector, "targetIndexName");
        if (target == null) {
            throw invalidProjections("'targetIndexName' is required.");
        }
        SearchIndexDefinition index = indexes.find(target).orElseThrow(() -> invalidProjections(
            "The target index '" + target + "' does not exist on this search service. Create the index first."));
        SearchFieldDefinition key = index.keyField();
        if (!"keyword".equals(key.effectiveIndexAnalyzer())) {
            throw invalidProjections("The key field '" + key.name() + "' of the target index '" + target
                + "' must use the 'keyword' analyzer.");
        }
        String parentKey = SkillDefinition.text(selector, "parentKeyFieldName");
        if (parentKey == null) {
            throw invalidProjections("'parentKeyFieldName' is required.");
        }
        SearchFieldDefinition parentField = topLevelField(index, parentKey).orElseThrow(() -> invalidProjections(
            "The parent key field '" + parentKey + "' does not exist in the target index '" + target + "'."));
        if (parentField.key() || parentField.type().element() != EdmType.STRING || parentField.type().collection()
                || !parentField.filterable()) {
            throw invalidProjections("The parent key field '" + parentKey + "' must be a filterable Edm.String field "
                + "that is not the key field.");
        }
        String sourceContext = SkillDefinition.text(selector, "sourceContext");
        if (sourceContext == null) {
            throw invalidProjections("'sourceContext' is required.");
        }
        EnrichmentPath context;
        List<SkillDefinition.InputEntry> mappings;
        try {
            context = SkillDefinition.parsePath(sourceContext, "sourceContext");
            mappings = SkillDefinition.InputEntry.parseAll(selector.get("mappings"), context);
        } catch (IllegalArgumentException e) {
            throw invalidProjections(capitalize(e.getMessage()) + ".");
        }
        for (SkillDefinition.InputEntry mapping : mappings) {
            Optional<SearchFieldDefinition> field = topLevelField(index, mapping.name());
            if (field.isEmpty()) {
                throw invalidProjections("The mapping '" + mapping.name() + "' names no field of the target index '"
                    + target + "'.");
            }
            if (field.get().key()) {
                throw invalidProjections("The mapping '" + mapping.name() + "' targets the key field of '" + target
                    + "', which the index projection generates.");
            }
        }
        ObjectNode normalized = NODES.objectNode();
        normalized.put("targetIndexName", target);
        normalized.put("parentKeyFieldName", parentKey);
        normalized.put("sourceContext", sourceContext);
        normalized.set("mappings", normalizeInputs(selector.get("mappings"), target));
        return normalized;
    }

    private static Optional<SearchFieldDefinition> topLevelField(SearchIndexDefinition index, String name) {
        return index.fields().stream().filter(field -> field.name().equals(name)).findFirst();
    }

    private static JsonNode encryptionKey(JsonNode node, ObjectNode previous) {
        if (node == null || node.isNull()) {
            JsonNode kept = previous == null ? null : previous.get("encryptionKey");
            return kept == null || kept.isNull() ? NullNode.getInstance() : kept.deepCopy();
        }
        if (!node.isObject()) {
            throw invalid("'encryptionKey' must be a JSON object.");
        }
        return node.deepCopy();
    }

    private static JsonNode copyOrNull(JsonNode node) {
        return node == null ? NullNode.getInstance() : node.deepCopy();
    }

    private static boolean isNullOrEmpty(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && node.textValue().isEmpty());
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? String.valueOf(text)
            : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: skillset : " + detail);
    }

    private static SearchException invalidSkills(String detail) {
        return SearchException.badRequest("One or more skills are invalid. Details: " + detail);
    }

    private static SearchException skillError(String skill, String detail) {
        return invalidSkills("Error in skill '" + skill + "': " + detail);
    }

    private static SearchException invalidProjections(String detail) {
        return SearchException.badRequest("One or more index projection selectors are invalid. Details: " + detail);
    }

    /** The indexes of the service a skillset belongs to. */
    @FunctionalInterface
    public interface IndexLookup {

        /** The definition of index {@code name}. */
        Optional<SearchIndexDefinition> find(String name);
    }

    /** Which URI schemes an endpoint parameter accepts. */
    private enum SchemeRule {
        /** Either scheme: an endpoint the emulator never calls as written, such as the embedding resource. */
        HTTP_OR_HTTPS,
        /** Azure's https, plus plain http to a local endpoint: custom skills outside {@code parity: strict}. */
        HTTPS_OR_LOCAL_HTTP,
        /** Azure's rule for custom skills: https only. */
        HTTPS
    }

    /**
     * A validated skillset.
     *
     * @param name     the skillset name
     * @param response the definition to answer the write with: as sent, normalised, without defaults
     * @param stored   the definition to store and answer GET with: the defaults the service adds applied
     */
    public record ParsedSkillset(String name, ObjectNode response, ObjectNode stored) {
    }
}
