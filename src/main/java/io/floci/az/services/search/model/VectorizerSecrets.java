package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The secrets of an index's vectorizers, per the vectorizer references and the REST spec's Get Index example: GET and
 * LIST answer {@code <redacted>} for API keys ({@code azureOpenAI}, {@code aiServicesVision}), the {@code aml} key,
 * every {@code customWebApi} header value and the {@code sig} and {@code code} values of its URI, and never return the
 * headers of an {@code openAICompatible} vectorizer. An update may send the {@code <redacted>} or {@code <unchanged>}
 * placeholders back (or the whole {@code uri} as {@code <unchanged>}, or omit {@code openAICompatible} headers) to keep
 * the stored secrets of the same vectorizer (same name and kind) at the same endpoint; a placeholder with no such source
 * is a 400, and placeholders cannot create a vectorizer.
 */
public final class VectorizerSecrets {

    public static final String REDACTED = "<redacted>";
    public static final String UNCHANGED = "<unchanged>";

    private static final Map<String, String> PARAMETERS = Map.of(
        VectorSearchValidator.AZURE_OPEN_AI, "azureOpenAIParameters",
        VectorSearchValidator.AI_SERVICES_VISION, "aiServicesVisionParameters",
        VectorSearchValidator.AML, "amlParameters",
        VectorSearchValidator.CUSTOM_WEB_API, "customWebApiParameters",
        VectorSearchValidator.OPEN_AI_COMPATIBLE, "openAICompatibleParameters");
    private static final Map<String, String> SECRET = Map.of(
        VectorSearchValidator.AZURE_OPEN_AI, "apiKey",
        VectorSearchValidator.AI_SERVICES_VISION, "apiKey",
        VectorSearchValidator.AML, "key");
    private static final Map<String, String> ENDPOINT = Map.of(
        VectorSearchValidator.AZURE_OPEN_AI, "resourceUri",
        VectorSearchValidator.AI_SERVICES_VISION, "resourceUri",
        VectorSearchValidator.AML, "uri",
        VectorSearchValidator.CUSTOM_WEB_API, "uri",
        VectorSearchValidator.OPEN_AI_COMPATIBLE, "uri");
    private static final Set<String> SECRET_QUERY_PARAMETERS = Set.of("sig", "code");

    private VectorizerSecrets() {
    }

    /** A copy of {@code index} (an index definition as GET returns it) with its vectorizer secrets redacted. */
    public static ObjectNode redact(ObjectNode index) {
        ObjectNode copy = index.deepCopy();
        for (ObjectNode vectorizer : vectorizers(copy)) {
            String kind = SearchJson.text(vectorizer, "kind");
            ObjectNode parameters = parameters(vectorizer, kind);
            if (parameters == null) {
                continue;
            }
            String secret = SECRET.get(kind == null ? "" : kind);
            if (secret != null && parameters.hasNonNull(secret)) {
                parameters.put(secret, REDACTED);
            }
            if (VectorSearchValidator.CUSTOM_WEB_API.equals(kind)) {
                if (parameters.get("httpHeaders") instanceof ObjectNode headers) {
                    List<String> names = new ArrayList<>();
                    headers.fieldNames().forEachRemaining(names::add);
                    names.forEach(name -> headers.put(name, REDACTED));
                }
                String uri = SearchJson.text(parameters, "uri");
                if (uri != null) {
                    parameters.put("uri", redactQuery(uri));
                }
            }
            if (VectorSearchValidator.OPEN_AI_COMPATIBLE.equals(kind)) {
                parameters.remove("headers");
            }
        }
        return copy;
    }

    /**
     * {@code body}, an update of the index whose current definition is {@code existing}, with the vectorizer secret
     * placeholders replaced by the stored secrets they stand for. Placeholders without a source are left for
     * {@link #rejectPlaceholders} to refuse.
     */
    public static JsonNode restore(JsonNode body, SearchIndexDefinition existing) {
        if (!(body instanceof ObjectNode input) || vectorizers(input).isEmpty()) {
            return body;
        }
        Map<String, ObjectNode> stored = new HashMap<>();
        for (ObjectNode vectorizer : vectorizers(existing.json())) {
            stored.put(SearchJson.text(vectorizer, "name"), vectorizer);
        }
        ObjectNode copy = input.deepCopy();
        for (ObjectNode vectorizer : vectorizers(copy)) {
            String kind = SearchJson.text(vectorizer, "kind");
            ObjectNode previous = stored.get(SearchJson.text(vectorizer, "name"));
            ObjectNode parameters = parameters(vectorizer, kind);
            if (previous == null || parameters == null || kind == null
                    || !kind.equalsIgnoreCase(SearchJson.text(previous, "kind"))) {
                continue;
            }
            restoreParameters(parameters, parameters(previous, SearchJson.text(previous, "kind")), kind);
        }
        return copy;
    }

    private static void restoreParameters(ObjectNode parameters, ObjectNode previous, String kind) {
        if (previous == null) {
            return;
        }
        String endpointProperty = ENDPOINT.get(kind);
        String storedEndpoint = endpointProperty == null ? null : SearchJson.text(previous, endpointProperty);
        String endpoint = endpointProperty == null ? null : SearchJson.text(parameters, endpointProperty);
        if (endpoint != null && isPlaceholder(endpoint) && storedEndpoint != null) {
            parameters.put(endpointProperty, storedEndpoint);
            endpoint = storedEndpoint;
        }
        if (VectorSearchValidator.CUSTOM_WEB_API.equals(kind) && endpoint != null && storedEndpoint != null) {
            String restored = restoreQuery(endpoint, storedEndpoint);
            parameters.put("uri", restored);
            endpoint = restored;
        }
        if (!sameEndpoint(endpoint, storedEndpoint)) {
            return;
        }
        String secret = SECRET.get(kind);
        if (secret != null && isPlaceholder(SearchJson.text(parameters, secret)) && previous.hasNonNull(secret)) {
            parameters.set(secret, previous.get(secret).deepCopy());
        }
        if (VectorSearchValidator.CUSTOM_WEB_API.equals(kind)
                && parameters.get("httpHeaders") instanceof ObjectNode headers
                && previous.get("httpHeaders") instanceof ObjectNode storedHeaders) {
            Iterator<Map.Entry<String, JsonNode>> entries = headers.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> header = entries.next();
                JsonNode value = storedHeaders.get(header.getKey());
                if (header.getValue().isTextual() && isPlaceholder(header.getValue().textValue()) && value != null) {
                    header.setValue(value.deepCopy());
                }
            }
        }
        if (VectorSearchValidator.OPEN_AI_COMPATIBLE.equals(kind) && !parameters.has("headers")
                && previous.has("headers") && sameProperty(parameters, previous, "modelName")
                && sameProperty(parameters, previous, "dimensions")) {
            parameters.set("headers", previous.get("headers").deepCopy());
        }
    }

    /** Refuses a definition that still holds a secret placeholder: there was no stored secret for it. */
    public static void rejectPlaceholders(String vectorizer, String kind, ObjectNode parameters) {
        String secret = SECRET.get(kind);
        if (secret != null && isPlaceholder(SearchJson.text(parameters, secret))) {
            throw unresolved(vectorizer, secret);
        }
        String endpointProperty = ENDPOINT.get(kind);
        String endpoint = endpointProperty == null ? null : SearchJson.text(parameters, endpointProperty);
        if (endpoint != null && (isPlaceholder(endpoint) || endpoint.contains(REDACTED)
                || endpoint.contains(UNCHANGED))) {
            throw unresolved(vectorizer, endpointProperty);
        }
        for (String headersProperty : List.of("httpHeaders", "headers")) {
            if (parameters.get(headersProperty) instanceof ObjectNode headers) {
                Iterator<Map.Entry<String, JsonNode>> entries = headers.fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> header = entries.next();
                    if (header.getValue().isTextual() && isPlaceholder(header.getValue().textValue())) {
                        throw unresolved(vectorizer, headersProperty + "." + header.getKey());
                    }
                }
            }
        }
    }

    private static SearchException unresolved(String vectorizer, String property) {
        return SearchException.badRequest("The request is invalid. Details: definition : the vectorizer '" + vectorizer
            + "' sends a placeholder for '" + property + "', but no stored value of the same vectorizer at the same "
            + "endpoint exists to keep. Provide the actual value.");
    }

    private static boolean isPlaceholder(String value) {
        return REDACTED.equals(value) || UNCHANGED.equals(value);
    }

    private static boolean sameEndpoint(String endpoint, String stored) {
        if (endpoint == null || stored == null) {
            return endpoint == null && stored == null;
        }
        return trimSlash(endpoint).equalsIgnoreCase(trimSlash(stored));
    }

    private static boolean sameProperty(ObjectNode left, ObjectNode right, String property) {
        JsonNode a = left.get(property);
        JsonNode b = right.get(property);
        return a == null || a.isNull() ? b == null || b.isNull() : a.equals(b);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** {@code uri} with the values of its {@code sig} and {@code code} query parameters replaced by the placeholder. */
    private static String redactQuery(String uri) {
        int question = uri.indexOf('?');
        if (question < 0) {
            return uri;
        }
        String[] pairs = uri.substring(question + 1).split("&", -1);
        StringBuilder result = new StringBuilder(uri.substring(0, question + 1));
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                result.append('&');
            }
            String pair = pairs[index];
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (equals >= 0 && SECRET_QUERY_PARAMETERS.contains(decodedName(name))) {
                result.append(name).append('=').append(REDACTED);
            } else {
                result.append(pair);
            }
        }
        return result.toString();
    }

    /**
     * {@code uri} with each placeholder query value taken from the same parameter, by occurrence, of {@code stored},
     * when both name the same scheme, host and path.
     */
    private static String restoreQuery(String uri, String stored) {
        int question = uri.indexOf('?');
        int storedQuestion = stored.indexOf('?');
        if (question < 0 || storedQuestion < 0
                || !uri.substring(0, question).equalsIgnoreCase(stored.substring(0, storedQuestion))) {
            return uri;
        }
        Map<String, List<String>> storedValues = new HashMap<>();
        for (String pair : stored.substring(storedQuestion + 1).split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals >= 0) {
                storedValues.computeIfAbsent(decodedName(pair.substring(0, equals)), name -> new ArrayList<>())
                    .add(pair.substring(equals + 1));
            }
        }
        Map<String, Integer> seen = new HashMap<>();
        String[] pairs = uri.substring(question + 1).split("&", -1);
        StringBuilder result = new StringBuilder(uri.substring(0, question + 1));
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                result.append('&');
            }
            String pair = pairs[index];
            int equals = pair.indexOf('=');
            if (equals < 0) {
                result.append(pair);
                continue;
            }
            String name = decodedName(pair.substring(0, equals));
            int occurrence = seen.merge(name, 1, Integer::sum) - 1;
            List<String> candidates = storedValues.getOrDefault(name, List.of());
            if (isPlaceholder(pair.substring(equals + 1)) && occurrence < candidates.size()) {
                result.append(pair, 0, equals + 1).append(candidates.get(occurrence));
            } else {
                result.append(pair);
            }
        }
        return result.toString();
    }

    private static String decodedName(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    private static List<ObjectNode> vectorizers(JsonNode index) {
        List<ObjectNode> vectorizers = new ArrayList<>();
        JsonNode list = index == null ? null : index.path("vectorSearch").get("vectorizers");
        if (list != null && list.isArray()) {
            for (JsonNode vectorizer : list) {
                if (vectorizer instanceof ObjectNode object) {
                    vectorizers.add(object);
                }
            }
        }
        return vectorizers;
    }

    private static ObjectNode parameters(JsonNode vectorizer, String kind) {
        String property = kind == null ? null : PARAMETERS.get(kind);
        return property != null && vectorizer.get(property) instanceof ObjectNode parameters ? parameters : null;
    }
}
