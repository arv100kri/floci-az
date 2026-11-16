package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.FieldSettings;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchRequest;
import io.floci.az.services.search.model.VectorSearchValidator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and validates the {@code vectorQueries} of a search request against the index: the kind and its input, the
 * fields (vector fields of the index, at most ten), {@code k}, {@code weight}, {@code oversampling} (only on compressed
 * fields), {@code threshold} and {@code filterOverride}. A query vector must have the length of every field it searches.
 */
public final class VectorQueryParser {

    /** The {@code k} of a query that sets none: the documented default of 50 results. */
    public static final int DEFAULT_K = 50;

    /** The most fields one vector query can name ("up to 10 per query"). */
    public static final int MAX_FIELDS = 10;


    private VectorQueryParser() {
    }

    /** The validated vector queries of {@code request}, in request order. */
    public static List<VectorQuery> parse(SearchRequest request, SearchIndexDefinition definition,
                                          VectorSearchConfig config) {
        List<VectorQuery> queries = new ArrayList<>();
        List<ObjectNode> entries = request.vectorQueries();
        for (int position = 0; position < entries.size(); position++) {
            queries.add(parseOne(entries.get(position), position, definition, config));
        }
        return queries;
    }

    private static VectorQuery parseOne(ObjectNode entry, int position, SearchIndexDefinition definition,
                                        VectorSearchConfig config) {
        String label = "vectorQueries[" + position + "]";
        JsonNode kindNode = entry.get("kind");
        String kindName = kindNode != null && kindNode.isTextual() ? kindNode.textValue() : null;
        if (kindName == null) {
            throw invalid("The vector query " + label + " must set 'kind' to 'vector', 'text', 'imageUrl' or "
                + "'imageBinary'.");
        }
        VectorQuery.Kind kind = VectorQuery.Kind.fromWireName(kindName).orElseThrow(() -> invalid("The vector query "
            + label + " has an invalid kind '" + kindName + "'; valid kinds are 'vector', 'text', 'imageUrl' and "
            + "'imageBinary'."));
        List<SearchFieldDefinition> fields = fields(entry, label, definition);
        float[] vector = null;
        String text = null;
        String imageUrl = null;
        String imageBase64 = null;
        switch (kind) {
            case VECTOR -> vector = vector(entry, label);
            case TEXT -> text = requiredText(entry, "text", label);
            case IMAGE_URL -> imageUrl = requiredText(entry, "url", label);
            case IMAGE_BINARY -> imageBase64 = requiredText(entry, "base64Image", label);
        }
        int k = k(entry, label);
        boolean exhaustive = bool(entry, "exhaustive", label);
        Double oversampling = oversampling(entry, label, fields, config);
        double weight = weight(entry, label);
        VectorQuery.Threshold threshold = threshold(entry, label, fields, config);
        String filterOverride = optionalText(entry, "filterOverride", label);
        if (vector != null) {
            for (SearchFieldDefinition field : fields) {
                checkVector(vector, field);
            }
        }
        return new VectorQuery(position, kind, vector, text, imageUrl, imageBase64, fields, k, exhaustive,
            oversampling, weight, threshold, filterOverride);
    }

    private static List<SearchFieldDefinition> fields(ObjectNode entry, String label,
                                                      SearchIndexDefinition definition) {
        JsonNode value = entry.get("fields");
        Set<String> paths = new LinkedHashSet<>();
        if (value != null && value.isTextual()) {
            for (String path : value.textValue().split(",")) {
                if (!path.isBlank()) {
                    paths.add(path.trim());
                }
            }
        } else if (value != null && value.isArray()) {
            for (JsonNode path : value) {
                if (!path.isTextual()) {
                    throw invalid("The 'fields' of the vector query " + label + " must be a comma-separated list "
                        + "of vector field names.");
                }
                paths.add(path.textValue().trim());
            }
        } else if (value != null && !value.isNull()) {
            throw invalid("The 'fields' of the vector query " + label + " must be a comma-separated list of vector "
                + "field names.");
        }
        if (paths.isEmpty()) {
            throw invalid("The vector query " + label + " must name the vector fields to search in 'fields'.");
        }
        if (paths.size() > MAX_FIELDS) {
            throw invalid("The vector query " + label + " names " + paths.size() + " fields; a vector query can "
                + "search at most " + MAX_FIELDS + " vector fields.");
        }
        List<SearchFieldDefinition> fields = new ArrayList<>();
        for (String path : paths) {
            SearchFieldDefinition field = definition.field(path).orElseThrow(() -> invalid("Invalid search "
                + "request: Could not find a property named '" + path + "' on type 'search.document'.\r\n"
                + "Parameter name: vectorQueries.fields"));
            if (!field.isVector() || !field.searchable()) {
                throw invalid("The field '" + path + "' named by the vector query " + label + " is not a searchable "
                    + "vector field; vector queries can only search fields of a vector type with a "
                    + "vectorSearchProfile.");
            }
            fields.add(field);
        }
        return fields;
    }

    private static float[] vector(ObjectNode entry, String label) {
        JsonNode value = entry.get("vector");
        if (value == null || !value.isArray()) {
            throw invalid("The vector query " + label + " of kind 'vector' must set 'vector' to an array of numbers.");
        }
        float[] vector = new float[value.size()];
        for (int index = 0; index < vector.length; index++) {
            JsonNode component = value.get(index);
            if (!component.isNumber() || !Double.isFinite(component.doubleValue())) {
                throw invalid("The 'vector' of the vector query " + label + " must contain only numbers; element "
                    + index + " is '" + component.asText() + "'.");
            }
            vector[index] = (float) component.doubleValue();
        }
        return vector;
    }

    /** Checks that {@code vector} fits {@code field}: its length, and bytes for a packed binary field. */
    public static void checkVector(float[] vector, SearchFieldDefinition field) {
        if (vector.length != VectorDimensions.expectedLength(field)) {
            throw invalid(VectorDimensions.mismatch(field, vector.length));
        }
        if (VectorSearchValidator.PACKED_BIT.equals(field.vectorEncoding())) {
            for (float component : vector) {
                if (component != Math.rint(component) || component < 0 || component > 255) {
                    throw invalid("The vector for the packed binary field '" + field.path() + "' must contain bytes "
                        + "(integers from 0 to 255); '" + component + "' is not one.");
                }
            }
        }
    }

    private static int k(ObjectNode entry, String label) {
        JsonNode value = entry.get("k");
        if (value == null || value.isNull()) {
            return DEFAULT_K;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw invalid("The 'k' of the vector query " + label + " must be a positive integer.");
        }
        return value.intValue();
    }

    private static boolean bool(ObjectNode entry, String property, String label) {
        JsonNode value = entry.get(property);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid("The '" + property + "' of the vector query " + label + " must be true or false.");
        }
        return value.booleanValue();
    }

    private static Double oversampling(ObjectNode entry, String label, List<SearchFieldDefinition> fields,
                                       VectorSearchConfig config) {
        JsonNode value = entry.get("oversampling");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() || value.doubleValue() < 1) {
            throw invalid("The 'oversampling' of the vector query " + label + " must be a number of at least 1.");
        }
        for (SearchFieldDefinition field : fields) {
            if (config.settings(field).compression() == null) {
                throw invalid("The vector query " + label + " sets 'oversampling', which is only permitted when the "
                    + "vector field '" + field.path() + "' uses a compression method.");
            }
        }
        return value.doubleValue();
    }

    private static double weight(ObjectNode entry, String label) {
        JsonNode value = entry.get("weight");
        if (value == null || value.isNull()) {
            return 1.0;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0) || !Double.isFinite(value.doubleValue())) {
            throw invalid("The 'weight' of the vector query " + label + " must be a positive number larger than "
                + "zero.");
        }
        return value.doubleValue();
    }

    private static VectorQuery.Threshold threshold(ObjectNode entry, String label, List<SearchFieldDefinition> fields,
                                                   VectorSearchConfig config) {
        JsonNode value = entry.get("threshold");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalid("The 'threshold' of the vector query " + label + " must be an object.");
        }
        String kind = value.path("kind").isTextual() ? value.get("kind").textValue() : null;
        JsonNode number = value.get("value");
        if (number == null || !number.isNumber()) {
            throw invalid("The 'threshold' of the vector query " + label + " must set a numeric 'value'.");
        }
        if ("searchScore".equalsIgnoreCase(kind)) {
            return new VectorQuery.Threshold(true, number.doubleValue());
        }
        if (!"vectorSimilarity".equalsIgnoreCase(kind)) {
            throw invalid("The 'threshold' of the vector query " + label + " has an invalid kind '" + kind + "'; "
                + "valid kinds are 'vectorSimilarity' and 'searchScore'.");
        }
        Set<VectorMetric> metrics = new LinkedHashSet<>();
        for (SearchFieldDefinition field : fields) {
            FieldSettings settings = config.settings(field);
            metrics.add(settings.metric());
        }
        if (metrics.size() > 1) {
            throw invalid("The vector query " + label + " sets a 'vectorSimilarity' threshold, which can only be set "
                + "when all of its fields use the same similarity metric.");
        }
        return new VectorQuery.Threshold(false, number.doubleValue());
    }

    private static String requiredText(ObjectNode entry, String property, String label) {
        String value = optionalText(entry, property, label);
        if (value == null || value.isEmpty()) {
            throw invalid("The vector query " + label + " must set '" + property + "'.");
        }
        return value;
    }

    private static String optionalText(ObjectNode entry, String property, String label) {
        JsonNode value = entry.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("The '" + property + "' of the vector query " + label + " must be a string.");
        }
        return value.textValue();
    }

    private static SearchException invalid(String message) {
        return SearchException.badRequest(message);
    }
}
