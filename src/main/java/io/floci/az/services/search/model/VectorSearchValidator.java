package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates and normalises the {@code vectorSearch} section of an index definition, and checks the vector fields
 * against it. The rules and defaults are the documented ones of "Create a vector index", "Compress vectors using scalar
 * or binary quantization", "Truncate dimensions", "Index binary vectors", the vectorizer references and the REST spec:
 * <ul>
 *   <li>algorithms are {@code hnsw} ({@code m} 4 to 10, default 4; {@code efConstruction} and {@code efSearch} 100 to
 *       1000, defaults 400 and 500) or {@code exhaustiveKnn}, each with a metric ({@code cosine} by default,
 *       {@code euclidean}, {@code dotProduct} or {@code hamming});</li>
 *   <li>profiles name an algorithm and optionally a vectorizer and a compression, all of which must exist;</li>
 *   <li>compressions are {@code scalarQuantization} ({@code int8}) or {@code binaryQuantization}, with rescoring
 *       options ({@code defaultOversampling} at least 1 and only with rescoring enabled) and a
 *       {@code truncationDimension};</li>
 *   <li>vectorizers are {@code azureOpenAI}, {@code customWebApi}, {@code aiServicesVision}, {@code aml} or
 *       {@code openAICompatible}, each with its parameters object;</li>
 *   <li>names are unique per kind of configuration;</li>
 *   <li>a vector field's {@code hamming} metric and {@code packedBit} encoding go together, compression applies to
 *       float fields ({@code Edm.Single}, {@code Edm.Half}), and an Azure OpenAI vectorizer's model bounds the
 *       dimensions of the fields it serves.</li>
 * </ul>
 * The normalised section is what GET returns: algorithm parameters with their defaults, rescoring options completed,
 * an Azure OpenAI {@code resourceUri} without its trailing slash, and the four arrays always present.
 */
public final class VectorSearchValidator {

    public static final String HNSW = "hnsw";
    public static final String EXHAUSTIVE_KNN = "exhaustiveKnn";
    public static final String SCALAR_QUANTIZATION = "scalarQuantization";
    public static final String BINARY_QUANTIZATION = "binaryQuantization";
    public static final String AZURE_OPEN_AI = "azureOpenAI";
    public static final String CUSTOM_WEB_API = "customWebApi";
    public static final String AI_SERVICES_VISION = "aiServicesVision";
    public static final String AML = "aml";
    public static final String OPEN_AI_COMPATIBLE = "openAICompatible";
    public static final String PRESERVE_ORIGINALS = "preserveOriginals";
    public static final String DISCARD_ORIGINALS = "discardOriginals";
    public static final String PACKED_BIT = "packedBit";
    public static final String HAMMING = "hamming";
    public static final String DEFAULT_METRIC = "cosine";
    public static final int DEFAULT_M = 4;
    public static final int DEFAULT_EF_CONSTRUCTION = 400;
    public static final int DEFAULT_EF_SEARCH = 500;

    private static final String ALGORITHMS = "algorithms";
    private static final String PROFILES = "profiles";
    private static final String VECTORIZERS = "vectorizers";
    private static final String COMPRESSIONS = "compressions";
    private static final String RESCORING_OPTIONS = "rescoringOptions";
    private static final int MAX_NAME_LENGTH = 128;
    private static final List<String> METRICS = List.of("cosine", "euclidean", "dotProduct", HAMMING);
    private static final Map<String, String> ALGORITHM_PARAMETERS = Map.of(
        HNSW, "hnswParameters", EXHAUSTIVE_KNN, "exhaustiveKnnParameters");
    private static final Map<String, String> VECTORIZER_PARAMETERS = Map.of(
        AZURE_OPEN_AI, "azureOpenAIParameters",
        CUSTOM_WEB_API, "customWebApiParameters",
        AI_SERVICES_VISION, "aiServicesVisionParameters",
        AML, "amlParameters",
        OPEN_AI_COMPATIBLE, "openAICompatibleParameters");
    private static final List<String> COMPRESSION_KINDS = List.of(SCALAR_QUANTIZATION, BINARY_QUANTIZATION);
    private static final List<String> STORAGE_METHODS = List.of(PRESERVE_ORIGINALS, DISCARD_ORIGINALS);
    private static final Set<String> PROHIBITED_HEADERS = Set.of("accept", "accept-charset", "accept-encoding",
        "content-length", "content-type", "cookie", "host", "te", "upgrade", "via");
    private static final Map<String, int[]> AZURE_OPEN_AI_DIMENSIONS = Map.of(
        "text-embedding-ada-002", new int[] {1536, 1536},
        "text-embedding-3-small", new int[] {1, 1536},
        "text-embedding-3-large", new int[] {1, 3072});
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(230);

    private final Map<String, ObjectNode> algorithms = new LinkedHashMap<>();
    private final Map<String, ObjectNode> profiles = new LinkedHashMap<>();
    private final Map<String, ObjectNode> vectorizers = new LinkedHashMap<>();
    private final Map<String, ObjectNode> compressions = new LinkedHashMap<>();

    private VectorSearchValidator() {
    }

    /**
     * Validates {@code section}, the {@code vectorSearch} object of a definition (a JSON object or null), and the
     * vector fields of {@code index} against it; returns the normalised section, or a JSON null when there is none.
     */
    public static JsonNode normalize(JsonNode section, SearchIndexDefinition index) {
        if (!(section instanceof ObjectNode input)) {
            return NullNode.getInstance();
        }
        VectorSearchValidator validator = new VectorSearchValidator();
        ObjectNode output = validator.normalizeSection(input);
        validator.validateFields(index);
        return output;
    }

    /**
     * The rules an update must also follow: a vector field the update adds cannot set {@code stored} to false, and
     * the {@code rescoreStorageMethod} of an existing compression cannot change.
     */
    public static void validateUpdate(SearchIndexDefinition existing, SearchIndexDefinition updated) {
        for (SearchFieldDefinition field : updated.allFields()) {
            if (field.isVector() && !field.stored() && existing.field(field.path()).isEmpty()) {
                throw invalid("the field '" + field.path() + "' sets 'stored' to false; 'stored' can only be set to "
                    + "false on vector fields created with the index.");
            }
        }
        Map<String, String> before = storageMethods(existing.json().get("vectorSearch"));
        Map<String, String> after = storageMethods(updated.json().get("vectorSearch"));
        before.forEach((name, method) -> {
            String replacement = after.get(name);
            if (replacement != null && !replacement.equals(method)) {
                throw invalid("the 'rescoreStorageMethod' of the existing compression '" + name + "' cannot be "
                    + "changed.");
            }
        });
    }

    private static Map<String, String> storageMethods(JsonNode section) {
        Map<String, String> methods = new HashMap<>();
        JsonNode list = section == null ? null : section.get(COMPRESSIONS);
        if (list != null && list.isArray()) {
            for (JsonNode compression : list) {
                JsonNode options = compression.get(RESCORING_OPTIONS);
                String method = options == null ? null : SearchJson.text(options, "rescoreStorageMethod");
                methods.put(SearchJson.text(compression, "name"), method == null ? PRESERVE_ORIGINALS : method);
            }
        }
        return methods;
    }

    private ObjectNode normalizeSection(ObjectNode input) {
        ArrayNode normalizedAlgorithms = SearchJson.NODES.arrayNode();
        for (ObjectNode algorithm : entries(input, ALGORITHMS)) {
            ObjectNode normalized = normalizeAlgorithm(algorithm);
            register(algorithms, ALGORITHMS, normalized);
            normalizedAlgorithms.add(normalized);
        }
        ArrayNode normalizedVectorizers = SearchJson.NODES.arrayNode();
        for (ObjectNode vectorizer : entries(input, VECTORIZERS)) {
            ObjectNode normalized = normalizeVectorizer(vectorizer);
            register(vectorizers, VECTORIZERS, normalized);
            normalizedVectorizers.add(normalized);
        }
        ArrayNode normalizedCompressions = SearchJson.NODES.arrayNode();
        for (ObjectNode compression : entries(input, COMPRESSIONS)) {
            ObjectNode normalized = normalizeCompression(compression);
            register(compressions, COMPRESSIONS, normalized);
            normalizedCompressions.add(normalized);
        }
        ArrayNode normalizedProfiles = SearchJson.NODES.arrayNode();
        for (ObjectNode profile : entries(input, PROFILES)) {
            ObjectNode normalized = normalizeProfile(profile);
            register(profiles, PROFILES, normalized);
            normalizedProfiles.add(normalized);
        }
        ObjectNode output = SearchJson.object();
        output.set(ALGORITHMS, normalizedAlgorithms);
        output.set(PROFILES, normalizedProfiles);
        output.set(VECTORIZERS, normalizedVectorizers);
        output.set(COMPRESSIONS, normalizedCompressions);
        copyOthers(input, output, Set.of(ALGORITHMS, PROFILES, VECTORIZERS, COMPRESSIONS));
        return output;
    }

    private ObjectNode normalizeAlgorithm(ObjectNode input) {
        String name = name(input, ALGORITHMS);
        String kind = kind(input, "vector search algorithm", name, List.copyOf(ALGORITHM_PARAMETERS.keySet()));
        String parametersName = ALGORITHM_PARAMETERS.get(kind);
        rejectForeignParameters(input, "vector search algorithm", name, parametersName, ALGORITHM_PARAMETERS);
        JsonNode parameters = input.get(parametersName);
        if (parameters != null && !parameters.isNull() && !parameters.isObject()) {
            throw invalid("'" + parametersName + "' of the vector search algorithm '" + name + "' must be an object.");
        }
        ObjectNode normalizedParameters = SearchJson.object();
        String metric = metric(parameters, name);
        normalizedParameters.put("metric", metric);
        if (HNSW.equals(kind)) {
            normalizedParameters.put("m", boundedInt(parameters, "m", DEFAULT_M, 4, 10, name));
            normalizedParameters.put("efConstruction",
                boundedInt(parameters, "efConstruction", DEFAULT_EF_CONSTRUCTION, 100, 1000, name));
            normalizedParameters.put("efSearch", boundedInt(parameters, "efSearch", DEFAULT_EF_SEARCH, 100, 1000, name));
        }
        if (parameters instanceof ObjectNode parameterObject) {
            copyOthers(parameterObject, normalizedParameters, Set.of("metric", "m", "efConstruction", "efSearch"));
        }
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.put("kind", kind);
        output.set(parametersName, normalizedParameters);
        copyOthers(input, output, Set.of("name", "kind", "hnswParameters", "exhaustiveKnnParameters"));
        return output;
    }

    private static String metric(JsonNode parameters, String algorithm) {
        JsonNode value = parameters == null ? null : parameters.get("metric");
        if (value == null || value.isNull()) {
            return DEFAULT_METRIC;
        }
        String canonical = value.isTextual() ? canonical(value.textValue(), METRICS) : null;
        if (canonical == null) {
            throw invalid("the vector search algorithm '" + algorithm + "' has an invalid metric '" + value.asText()
                + "'; valid metrics are 'cosine', 'euclidean', 'dotProduct' and 'hamming'.");
        }
        return canonical;
    }

    private static int boundedInt(JsonNode parameters, String parameter, int defaultValue, int min, int max,
                                  String algorithm) {
        JsonNode value = parameters == null ? null : parameters.get(parameter);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min
                || value.intValue() > max) {
            throw invalid("the HNSW parameter '" + parameter + "' of the vector search algorithm '" + algorithm
                + "' must be an integer between " + min + " and " + max + ".");
        }
        return value.intValue();
    }

    private ObjectNode normalizeProfile(ObjectNode input) {
        String name = name(input, PROFILES);
        String algorithm = optionalName(input, "algorithm", name);
        if (algorithm == null) {
            throw invalid("the vector search profile '" + name + "' must name an 'algorithm'.");
        }
        if (!algorithms.containsKey(algorithm)) {
            throw invalid("the vector search profile '" + name + "' names the algorithm '" + algorithm + "', which "
                + "'vectorSearch.algorithms' does not define.");
        }
        String vectorizer = optionalName(input, "vectorizer", name);
        if (vectorizer != null && !vectorizers.containsKey(vectorizer)) {
            throw invalid("the vector search profile '" + name + "' names the vectorizer '" + vectorizer + "', which "
                + "'vectorSearch.vectorizers' does not define.");
        }
        String compression = optionalName(input, "compression", name);
        if (compression != null && !compressions.containsKey(compression)) {
            throw invalid("the vector search profile '" + name + "' names the compression '" + compression + "', "
                + "which 'vectorSearch.compressions' does not define.");
        }
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.put("algorithm", algorithm);
        putIfPresent(input, output, "vectorizer");
        putIfPresent(input, output, "compression");
        copyOthers(input, output, Set.of("name", "algorithm", "vectorizer", "compression"));
        return output;
    }

    private static String optionalName(ObjectNode input, String property, String profile) {
        JsonNode value = input.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid("the '" + property + "' of the vector search profile '" + profile + "' must be a name.");
        }
        return value.textValue();
    }

    private ObjectNode normalizeCompression(ObjectNode input) {
        String name = name(input, COMPRESSIONS);
        String kind = kind(input, "vector compression", name, COMPRESSION_KINDS);
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.put("kind", kind);
        JsonNode truncation = input.get("truncationDimension");
        if (truncation != null) {
            if (!truncation.isNull() && (!truncation.isIntegralNumber() || !truncation.canConvertToInt()
                    || truncation.intValue() < 1)) {
                throw invalid("the 'truncationDimension' of the vector compression '" + name + "' must be a "
                    + "positive integer.");
            }
            output.set("truncationDimension", truncation.deepCopy());
        }
        JsonNode scalarParameters = input.get("scalarQuantizationParameters");
        if (scalarParameters != null && !scalarParameters.isNull()) {
            if (!SCALAR_QUANTIZATION.equals(kind)) {
                throw invalid("the vector compression '" + name + "' of kind '" + kind + "' cannot set "
                    + "'scalarQuantizationParameters'.");
            }
            output.set("scalarQuantizationParameters", normalizeScalarParameters(scalarParameters, name));
        }
        JsonNode rescoring = input.get(RESCORING_OPTIONS);
        if (rescoring != null) {
            output.set(RESCORING_OPTIONS, rescoring.isNull() ? NullNode.getInstance()
                : normalizeRescoring(rescoring, name));
        }
        JsonNode legacyRerank = input.get("rerankWithOriginalVectors");
        if (legacyRerank != null && !legacyRerank.isNull() && !legacyRerank.isBoolean()) {
            throw invalid("'rerankWithOriginalVectors' of the vector compression '" + name + "' must be true or "
                + "false.");
        }
        JsonNode legacyOversampling = input.get("defaultOversampling");
        if (legacyOversampling != null && !legacyOversampling.isNull()) {
            oversampling(legacyOversampling, name);
            if (legacyRerank != null && legacyRerank.isBoolean() && !legacyRerank.booleanValue()) {
                throw invalid("'defaultOversampling' of the vector compression '" + name + "' can only be set when "
                    + "'rerankWithOriginalVectors' is true.");
            }
        }
        copyOthers(input, output, Set.of("name", "kind", "truncationDimension", "scalarQuantizationParameters",
            RESCORING_OPTIONS));
        return output;
    }

    private static ObjectNode normalizeScalarParameters(JsonNode parameters, String compression) {
        if (!(parameters instanceof ObjectNode input)) {
            throw invalid("'scalarQuantizationParameters' of the vector compression '" + compression + "' must be an "
                + "object.");
        }
        JsonNode type = input.get("quantizedDataType");
        ObjectNode output = SearchJson.object();
        if (type == null || type.isNull()) {
            output.put("quantizedDataType", "int8");
        } else if (type.isTextual() && "int8".equalsIgnoreCase(type.textValue())) {
            output.put("quantizedDataType", "int8");
        } else {
            throw invalid("the 'quantizedDataType' of the vector compression '" + compression + "' must be 'int8'.");
        }
        copyOthers(input, output, Set.of("quantizedDataType"));
        return output;
    }

    private static ObjectNode normalizeRescoring(JsonNode rescoring, String compression) {
        if (!(rescoring instanceof ObjectNode input)) {
            throw invalid("'rescoringOptions' of the vector compression '" + compression + "' must be an object.");
        }
        JsonNode enable = input.get("enableRescoring");
        if (enable != null && !enable.isNull() && !enable.isBoolean()) {
            throw invalid("'enableRescoring' of the vector compression '" + compression + "' must be true or false.");
        }
        boolean enabled = enable == null || enable.isNull() || enable.booleanValue();
        JsonNode oversampling = input.get("defaultOversampling");
        Double defaultOversampling = oversampling == null || oversampling.isNull() ? null
            : oversampling(oversampling, compression);
        if (defaultOversampling != null && !enabled) {
            throw invalid("'defaultOversampling' of the vector compression '" + compression + "' can only be set "
                + "when 'enableRescoring' is true.");
        }
        JsonNode method = input.get("rescoreStorageMethod");
        String storageMethod = PRESERVE_ORIGINALS;
        if (method != null && !method.isNull()) {
            storageMethod = method.isTextual() ? canonical(method.textValue(), STORAGE_METHODS) : null;
            if (storageMethod == null) {
                throw invalid("the 'rescoreStorageMethod' of the vector compression '" + compression + "' must be "
                    + "'preserveOriginals' or 'discardOriginals'.");
            }
        }
        ObjectNode output = SearchJson.object();
        output.put("enableRescoring", enabled);
        if (defaultOversampling == null) {
            output.putNull("defaultOversampling");
        } else {
            output.put("defaultOversampling", defaultOversampling.doubleValue());
        }
        output.put("rescoreStorageMethod", storageMethod);
        copyOthers(input, output, Set.of("enableRescoring", "defaultOversampling", "rescoreStorageMethod"));
        return output;
    }

    private static double oversampling(JsonNode value, String compression) {
        if (!value.isNumber() || value.doubleValue() < 1) {
            throw invalid("'defaultOversampling' of the vector compression '" + compression + "' must be a number of "
                + "at least 1.");
        }
        return value.doubleValue();
    }

    private ObjectNode normalizeVectorizer(ObjectNode input) {
        String name = name(input, VECTORIZERS);
        String kind = kind(input, "vectorizer", name, List.copyOf(VECTORIZER_PARAMETERS.keySet()));
        String parametersName = VECTORIZER_PARAMETERS.get(kind);
        rejectForeignParameters(input, "vectorizer", name, parametersName, VECTORIZER_PARAMETERS);
        JsonNode parameters = input.get(parametersName);
        if (!(parameters instanceof ObjectNode parameterObject)) {
            throw invalid("the vectorizer '" + name + "' of kind '" + kind + "' must set '" + parametersName + "'.");
        }
        ObjectNode normalizedParameters = parameterObject.deepCopy();
        VectorizerSecrets.rejectPlaceholders(name, kind, normalizedParameters);
        switch (kind) {
            case AZURE_OPEN_AI -> validateAzureOpenAI(normalizedParameters, name);
            case CUSTOM_WEB_API -> validateCustomWebApi(normalizedParameters, name);
            case AI_SERVICES_VISION -> validateVision(normalizedParameters, name);
            case AML -> validateAml(normalizedParameters, name);
            default -> validateOpenAICompatible(normalizedParameters, name);
        }
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.put("kind", kind);
        output.set(parametersName, normalizedParameters);
        copyOthers(input, output, Set.copyOf(withNameAndKind(VECTORIZER_PARAMETERS.values())));
        return output;
    }

    private static void validateAzureOpenAI(ObjectNode parameters, String vectorizer) {
        String resourceUri = requiredUri(parameters, "resourceUri", vectorizer);
        if (resourceUri.endsWith("/")) {
            parameters.put("resourceUri", resourceUri.substring(0, resourceUri.length() - 1));
        }
        requiredText(parameters, "deploymentId", vectorizer);
        String model = optionalText(parameters, "modelName", vectorizer);
        if (model != null && !AZURE_OPEN_AI_DIMENSIONS.containsKey(model)) {
            throw invalid("the vectorizer '" + vectorizer + "' names the model '" + model + "'; the Azure OpenAI "
                + "vectorizer supports 'text-embedding-ada-002', 'text-embedding-3-large' and "
                + "'text-embedding-3-small'.");
        }
        optionalText(parameters, "apiKey", vectorizer);
    }

    private static void validateCustomWebApi(ObjectNode parameters, String vectorizer) {
        requiredUri(parameters, "uri", vectorizer);
        String method = optionalText(parameters, "httpMethod", vectorizer);
        if (method != null && !"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            throw invalid("the 'httpMethod' of the vectorizer '" + vectorizer + "' must be 'POST' or 'PUT'.");
        }
        timeout(parameters, vectorizer);
        JsonNode headers = parameters.get("httpHeaders");
        if (headers != null && !headers.isNull()) {
            if (!(headers instanceof ObjectNode headerObject)) {
                throw invalid("the 'httpHeaders' of the vectorizer '" + vectorizer + "' must be an object.");
            }
            Iterator<Map.Entry<String, JsonNode>> entries = headerObject.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> header = entries.next();
                if (PROHIBITED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    throw invalid("the vectorizer '" + vectorizer + "' cannot set the HTTP header '"
                        + header.getKey() + "'.");
                }
                if (!header.getValue().isTextual()) {
                    throw invalid("the HTTP header '" + header.getKey() + "' of the vectorizer '" + vectorizer
                        + "' must be a string.");
                }
            }
        }
    }

    private static void validateVision(ObjectNode parameters, String vectorizer) {
        requiredUri(parameters, "resourceUri", vectorizer);
        optionalText(parameters, "modelVersion", vectorizer);
    }

    private static void validateAml(ObjectNode parameters, String vectorizer) {
        String uri = parameters.hasNonNull("uri") ? requiredUri(parameters, "uri", vectorizer) : null;
        String resourceId = optionalText(parameters, "resourceId", vectorizer);
        if (uri == null && resourceId == null) {
            throw invalid("the vectorizer '" + vectorizer + "' must set 'uri' (key authentication or none) or "
                + "'resourceId' (token authentication).");
        }
        timeout(parameters, vectorizer);
    }

    private static void validateOpenAICompatible(ObjectNode parameters, String vectorizer) {
        requiredUri(parameters, "uri", vectorizer);
        requiredText(parameters, "modelName", vectorizer);
        JsonNode dimensions = parameters.get("dimensions");
        if (dimensions != null && !dimensions.isNull() && (!dimensions.isIntegralNumber()
                || !dimensions.canConvertToInt() || dimensions.intValue() < 1)) {
            throw invalid("the 'dimensions' of the vectorizer '" + vectorizer + "' must be a positive integer.");
        }
    }

    private static String requiredUri(ObjectNode parameters, String property, String vectorizer) {
        String value = requiredText(parameters, property, vectorizer);
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
                throw invalid("the '" + property + "' of the vectorizer '" + vectorizer + "' must be an absolute "
                    + "https URI.");
            }
        } catch (URISyntaxException e) {
            throw invalid("the '" + property + "' of the vectorizer '" + vectorizer + "' is not a valid URI: "
                + e.getReason() + ".");
        }
        return value;
    }

    private static String requiredText(ObjectNode parameters, String property, String vectorizer) {
        String value = optionalText(parameters, property, vectorizer);
        if (value == null || value.isBlank()) {
            throw invalid("the vectorizer '" + vectorizer + "' must set '" + property + "'.");
        }
        return value;
    }

    private static String optionalText(ObjectNode parameters, String property, String vectorizer) {
        JsonNode value = parameters.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("the '" + property + "' of the vectorizer '" + vectorizer + "' must be a string.");
        }
        return value.textValue();
    }

    private static void timeout(ObjectNode parameters, String vectorizer) {
        String value = optionalText(parameters, "timeout", vectorizer);
        if (value == null) {
            return;
        }
        Duration timeout;
        try {
            timeout = Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid("the 'timeout' of the vectorizer '" + vectorizer + "' must be an ISO 8601 duration such as "
                + "'PT30S'.");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw invalid("the 'timeout' of the vectorizer '" + vectorizer + "' must be between 1 and 230 seconds.");
        }
    }

    private void validateFields(SearchIndexDefinition index) {
        if (index == null) {
            return;
        }
        for (SearchFieldDefinition field : index.allFields()) {
            if (!field.isVector() || field.vectorSearchProfile() == null) {
                continue;
            }
            ObjectNode profile = profiles.get(field.vectorSearchProfile());
            if (profile == null) {
                continue;
            }
            ObjectNode algorithm = algorithms.get(SearchJson.text(profile, "algorithm"));
            String metric = algorithmMetric(algorithm);
            boolean packedBits = PACKED_BIT.equals(field.vectorEncoding());
            if (HAMMING.equals(metric) && !packedBits) {
                throw invalid("the vector field '" + field.path() + "' uses the 'hamming' metric, which is only valid "
                    + "for Collection(Edm.Byte) fields with vectorEncoding 'packedBit'.");
            }
            if (packedBits && !HAMMING.equals(metric)) {
                throw invalid("the vector field '" + field.path() + "' has vectorEncoding 'packedBit', which needs a "
                    + "vector search algorithm with the 'hamming' metric; its profile uses '" + metric + "'.");
            }
            String compression = SearchJson.text(profile, "compression");
            if (compression != null && field.type().element() != EdmType.SINGLE
                    && field.type().element() != EdmType.HALF) {
                throw invalid("the vector field '" + field.path() + "' of type " + field.type().edmName() + " cannot "
                    + "use the compression '" + compression + "'; quantization applies to Collection(Edm.Single) "
                    + "and Collection(Edm.Half) fields.");
            }
            String vectorizer = SearchJson.text(profile, "vectorizer");
            if (vectorizer != null) {
                validateVectorizerDimensions(field, vectorizers.get(vectorizer));
            }
        }
    }

    private static String algorithmMetric(ObjectNode algorithm) {
        for (String parameters : ALGORITHM_PARAMETERS.values()) {
            JsonNode node = algorithm.get(parameters);
            if (node != null && node.isObject()) {
                return SearchJson.text(node, "metric");
            }
        }
        return DEFAULT_METRIC;
    }

    private static void validateVectorizerDimensions(SearchFieldDefinition field, ObjectNode vectorizer) {
        String kind = SearchJson.text(vectorizer, "kind");
        String name = SearchJson.text(vectorizer, "name");
        int dimensions = field.dimensions();
        if (AZURE_OPEN_AI.equals(kind) || OPEN_AI_COMPATIBLE.equals(kind)) {
            JsonNode parameters = vectorizer.get(VECTORIZER_PARAMETERS.get(kind));
            String model = SearchJson.text(parameters, "modelName");
            int[] range = model == null ? null : AZURE_OPEN_AI_DIMENSIONS.get(model);
            if (range != null && (dimensions < range[0] || dimensions > range[1])) {
                throw invalid("the vector field '" + field.path() + "' has " + dimensions + " dimensions, but the "
                    + "model '" + model + "' of its vectorizer '" + name
                    + "' produces " + (range[0] == range[1] ? String.valueOf(range[0])
                    : "between " + range[0] + " and " + range[1]) + ".");
            }
            JsonNode configured = parameters.get("dimensions");
            if (configured != null && configured.isIntegralNumber() && configured.intValue() != dimensions) {
                throw invalid("the vector field '" + field.path() + "' has " + dimensions + " dimensions, but its "
                    + "vectorizer '" + name + "' produces " + configured.intValue() + ".");
            }
        }
    }

    private static List<ObjectNode> entries(ObjectNode section, String property) {
        JsonNode value = section.get(property);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!(value instanceof ArrayNode array)) {
            throw invalid("'vectorSearch." + property + "' must be an array.");
        }
        List<ObjectNode> objects = new ArrayList<>();
        for (JsonNode entry : array) {
            if (!(entry instanceof ObjectNode object)) {
                throw invalid("each entry of 'vectorSearch." + property + "' must be a JSON object.");
            }
            objects.add(object);
        }
        return objects;
    }

    private static String name(ObjectNode entry, String section) {
        JsonNode value = entry.get("name");
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("each entry of 'vectorSearch." + section + "' must have a name.");
        }
        if (value.textValue().length() > MAX_NAME_LENGTH) {
            throw invalid("the name '" + value.textValue() + "' in 'vectorSearch." + section + "' is longer than "
                + MAX_NAME_LENGTH + " characters.");
        }
        return value.textValue();
    }

    private static void register(Map<String, ObjectNode> names, String section, ObjectNode entry) {
        String name = entry.get("name").textValue();
        if (names.putIfAbsent(name, entry) != null) {
            throw invalid("the name '" + name + "' is used more than once in 'vectorSearch." + section + "'.");
        }
    }

    private static String kind(ObjectNode entry, String what, String name, List<String> kinds) {
        String value = SearchJson.text(entry, "kind");
        String canonical = canonical(value, kinds);
        if (canonical == null) {
            throw invalid("the " + what + " '" + name + "' has an invalid kind '" + value + "'; valid kinds are "
                + quoted(kinds) + ".");
        }
        return canonical;
    }

    private static void rejectForeignParameters(ObjectNode entry, String what, String name, String own,
                                                Map<String, String> parametersByKind) {
        for (String parameters : parametersByKind.values()) {
            JsonNode value = entry.get(parameters);
            if (!parameters.equals(own) && value != null && !value.isNull()) {
                throw invalid("the " + what + " '" + name + "' cannot set '" + parameters + "'; its kind uses '"
                    + own + "'.");
            }
        }
    }

    private static String canonical(String value, List<String> allowed) {
        if (value == null) {
            return null;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }

    private static String quoted(List<String> values) {
        List<String> sorted = values.stream().sorted().map(value -> "'" + value + "'").toList();
        return String.join(", ", sorted);
    }

    private static List<String> withNameAndKind(Collection<String> parameters) {
        List<String> names = new ArrayList<>(parameters);
        names.add("name");
        names.add("kind");
        return names;
    }

    private static void putIfPresent(ObjectNode input, ObjectNode output, String property) {
        if (input.has(property)) {
            output.set(property, input.get(property).deepCopy());
        }
    }

    private static void copyOthers(ObjectNode input, ObjectNode output, Set<String> handled) {
        Iterator<Map.Entry<String, JsonNode>> entries = input.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!handled.contains(entry.getKey()) && !output.has(entry.getKey())) {
                output.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
