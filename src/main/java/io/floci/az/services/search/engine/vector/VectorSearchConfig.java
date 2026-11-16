package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.VectorSearchValidator;

import java.util.HashMap;
import java.util.Map;

/**
 * The query-time view of an index's {@code vectorSearch} section, read from the definition {@link VectorSearchValidator}
 * validated and normalised: the algorithms, profiles, compressions and vectorizers by name, and the settings each vector
 * field resolves to through its profile.
 */
public final class VectorSearchConfig {

    /** The oversampling a compression applies when neither it nor the query sets one ("Default is 4"). */
    public static final double DEFAULT_OVERSAMPLING = 4.0;

    private final Map<String, VectorMetric> metrics = new HashMap<>();
    private final Map<String, String> algorithmKinds = new HashMap<>();
    private final Map<String, ObjectNode> profiles = new HashMap<>();
    private final Map<String, Compression> compressions = new HashMap<>();
    private final Map<String, Vectorizer> vectorizers = new HashMap<>();

    private VectorSearchConfig(JsonNode section) {
        if (section == null || !section.isObject()) {
            return;
        }
        for (JsonNode algorithm : section.path("algorithms")) {
            String name = SearchJson.text(algorithm, "name");
            String kind = SearchJson.text(algorithm, "kind");
            JsonNode parameters = VectorSearchValidator.HNSW.equals(kind) ? algorithm.get("hnswParameters")
                : algorithm.get("exhaustiveKnnParameters");
            String metric = parameters == null ? null : SearchJson.text(parameters, "metric");
            metrics.put(name, VectorMetric.fromWireName(metric).orElse(VectorMetric.COSINE));
            algorithmKinds.put(name, kind);
        }
        for (JsonNode profile : section.path("profiles")) {
            profiles.put(SearchJson.text(profile, "name"), (ObjectNode) profile);
        }
        for (JsonNode compression : section.path("compressions")) {
            Compression parsed = Compression.of(compression);
            compressions.put(parsed.name(), parsed);
        }
        for (JsonNode vectorizer : section.path("vectorizers")) {
            String kind = SearchJson.text(vectorizer, "kind");
            JsonNode parameters = vectorizer.get(parametersProperty(kind));
            vectorizers.put(SearchJson.text(vectorizer, "name"), new Vectorizer(SearchJson.text(vectorizer, "name"),
                kind, parameters instanceof ObjectNode object ? object : SearchJson.object()));
        }
    }

    /** The configuration of {@code definition}. */
    public static VectorSearchConfig of(SearchIndexDefinition definition) {
        return new VectorSearchConfig(definition.json().get("vectorSearch"));
    }

    /** What the vector field {@code field} resolves to through its profile. */
    public FieldSettings settings(SearchFieldDefinition field) {
        ObjectNode profile = field.vectorSearchProfile() == null ? null : profiles.get(field.vectorSearchProfile());
        if (profile == null) {
            return new FieldSettings(field, defaultMetric(field), false, null, null);
        }
        String algorithm = SearchJson.text(profile, "algorithm");
        VectorMetric metric = metrics.getOrDefault(algorithm, defaultMetric(field));
        boolean hnsw = VectorSearchValidator.HNSW.equals(algorithmKinds.get(algorithm));
        String compression = SearchJson.text(profile, "compression");
        String vectorizer = SearchJson.text(profile, "vectorizer");
        return new FieldSettings(field, metric, hnsw, compression == null ? null : compressions.get(compression),
            vectorizer == null ? null : vectorizers.get(vectorizer));
    }

    private static VectorMetric defaultMetric(SearchFieldDefinition field) {
        return VectorSearchValidator.PACKED_BIT.equals(field.vectorEncoding()) ? VectorMetric.HAMMING
            : VectorMetric.COSINE;
    }

    private static String parametersProperty(String kind) {
        return switch (kind == null ? "" : kind) {
            case VectorSearchValidator.AZURE_OPEN_AI -> "azureOpenAIParameters";
            case VectorSearchValidator.CUSTOM_WEB_API -> "customWebApiParameters";
            case VectorSearchValidator.AI_SERVICES_VISION -> "aiServicesVisionParameters";
            case VectorSearchValidator.AML -> "amlParameters";
            default -> "openAICompatibleParameters";
        };
    }

    /**
     * The vector settings of one field.
     *
     * @param field       the vector field
     * @param metric      the similarity metric of its algorithm
     * @param hnsw        whether its algorithm is HNSW (compression only applies to HNSW; exhaustive KNN fields hold
     *                    full-precision vectors)
     * @param compression its compression, or null
     * @param vectorizer  its vectorizer, or null
     */
    public record FieldSettings(SearchFieldDefinition field, VectorMetric metric, boolean hnsw, Compression compression,
                                Vectorizer vectorizer) {

        /** The compression that shapes candidate retrieval: the profile's, on an HNSW field; null otherwise. */
        public Compression effectiveCompression() {
            return hnsw ? compression : null;
        }
    }

    /**
     * A compression configuration, with the rescoring options of the current API versions or their 2024-07-01
     * predecessors ({@code rerankWithOriginalVectors}, a top-level {@code defaultOversampling}).
     *
     * @param name                the configuration name
     * @param binary              whether it is binary quantization (otherwise scalar quantization to int8)
     * @param rescoring           whether rescoring is enabled
     * @param defaultOversampling the oversampling factor queries use by default
     * @param preserveOriginals   whether the full-precision vectors are kept for rescoring
     * @param truncationDimension the dimensions the compressed vectors keep, or null for all
     */
    public record Compression(String name, boolean binary, boolean rescoring, double defaultOversampling,
                              boolean preserveOriginals, Integer truncationDimension) {

        static Compression of(JsonNode node) {
            JsonNode options = node.get("rescoringOptions");
            boolean hasOptions = options != null && options.isObject();
            JsonNode enable = hasOptions ? options.get("enableRescoring") : node.get("rerankWithOriginalVectors");
            JsonNode oversampling = hasOptions ? options.get("defaultOversampling") : node.get("defaultOversampling");
            String method = hasOptions ? SearchJson.text(options, "rescoreStorageMethod") : null;
            JsonNode truncation = node.get("truncationDimension");
            return new Compression(SearchJson.text(node, "name"),
                VectorSearchValidator.BINARY_QUANTIZATION.equals(SearchJson.text(node, "kind")),
                enable == null || enable.isNull() || enable.asBoolean(),
                oversampling != null && oversampling.isNumber() ? oversampling.doubleValue() : DEFAULT_OVERSAMPLING,
                !VectorSearchValidator.DISCARD_ORIGINALS.equals(method),
                truncation != null && truncation.isIntegralNumber() ? truncation.intValue() : null);
        }
    }

    /**
     * A vectorizer.
     *
     * @param name       the vectorizer name
     * @param kind       {@code azureOpenAI}, {@code customWebApi}, {@code aiServicesVision}, {@code aml} or
     *                   {@code openAICompatible}
     * @param parameters its parameters object
     */
    public record Vectorizer(String name, String kind, ObjectNode parameters) {
    }
}
