package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.Vectorizer;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.VectorSearchValidator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls the embedding model behind a vectorizer at query time, per the vectorizer references:
 * <ul>
 *   <li>{@code azureOpenAI}: the Azure OpenAI embeddings API, {@code POST {resourceUri}/openai/deployments/
 *       {deploymentId}/embeddings} with the {@code api-key} header;</li>
 *   <li>{@code customWebApi}: the custom Web API skill contract, one record in {@code values} whose {@code data}
 *       carries {@code text}, {@code imageUrl} or {@code imageBinary}, and the vector in the response's
 *       {@code data.vector};</li>
 *   <li>{@code aml}: the endpoint's scoring URI, with the Azure AI model inference embeddings shape
 *       ({@code {"input": [...], "input_type": "query"}});</li>
 *   <li>{@code openAICompatible}: its URI unchanged, with an OpenAI embeddings body that omits {@code model};</li>
 *   <li>{@code aiServicesVision}: not emulated (501).</li>
 * </ul>
 * When {@code floci-az.services.search.model-endpoint} is set, the Azure OpenAI vectorizer calls that
 * OpenAI-compatible endpoint ({@code POST {modelEndpoint}/embeddings} with {@code input} and the deployment or model
 * name as {@code model}) instead of its resource. The emulator never calls Azure itself: a vectorizer whose endpoint is
 * an Azure host uses the model endpoint when one is set (text queries) and otherwise fails as an unreachable
 * endpoint, with the error Azure answers a failed vectorization with.
 */
public final class VectorizerClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(VectorizerClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String AZURE_OPENAI_API_VERSION = "2024-10-21";
    private static final String FAILED = "Could not complete vectorization action. ";
    private static final String UNREACHABLE = FAILED + "Could not reach the vectorization endpoint.";

    private volatile HttpClient http;

    /**
     * A client that builds its HTTP client on its first vectorizer call, so a client that never calls one starts no
     * thread. Its owner closes it.
     */
    public VectorizerClient() {
        this(null);
    }

    public VectorizerClient(HttpClient http) {
        this.http = http;
    }

    /**
     * Stops the HTTP client's threads. The selector thread of an HTTP client still in use holds the context class
     * loader it was started with, so a client the application never stops keeps that application's classes loaded.
     */
    @Override
    public void close() {
        HttpClient client = http;
        if (client != null) {
            client.shutdownNow();
        }
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                client = http;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                    http = client;
                }
            }
        }
        return client;
    }

    /**
     * The embedding {@code vectorizer} computes for {@code input}.
     *
     * @param vectorizer    the vectorizer of the searched field's profile
     * @param input         the text or image to vectorize
     * @param dimensions    the dimensions of the searched field
     * @param modelEndpoint the configured OpenAI-compatible endpoint, or null
     */
    public float[] embed(Vectorizer vectorizer, VectorInput input, int dimensions, String modelEndpoint) {
        ObjectNode parameters = vectorizer.parameters();
        return switch (vectorizer.kind()) {
            case VectorSearchValidator.AZURE_OPEN_AI -> azureOpenAI(vectorizer, input, dimensions, modelEndpoint);
            case VectorSearchValidator.CUSTOM_WEB_API -> {
                supports(vectorizer, input, VectorQuery.Kind.TEXT, VectorQuery.Kind.IMAGE_URL,
                    VectorQuery.Kind.IMAGE_BINARY);
                URI uri = uri(SearchJson.text(parameters, "uri"));
                yield isAzure(uri) ? viaModelEndpoint(vectorizer, input, modelEndpoint, vectorizer.name())
                    : customWebApi(uri, parameters, input);
            }
            case VectorSearchValidator.AML -> {
                supports(vectorizer, input, VectorQuery.Kind.TEXT, VectorQuery.Kind.IMAGE_BINARY);
                String target = SearchJson.text(parameters, "uri");
                URI uri = target == null ? null : uri(target);
                yield uri == null || isAzure(uri)
                    ? viaModelEndpoint(vectorizer, input, modelEndpoint, SearchJson.text(parameters, "modelName"))
                    : aml(uri, parameters, input);
            }
            case VectorSearchValidator.OPEN_AI_COMPATIBLE -> {
                supports(vectorizer, input, VectorQuery.Kind.TEXT);
                URI uri = uri(SearchJson.text(parameters, "uri"));
                yield isAzure(uri) ? viaModelEndpoint(vectorizer, input, modelEndpoint, null)
                    : openAICompatible(uri, parameters, input.value());
            }
            case VectorSearchValidator.AI_SERVICES_VISION -> throw SearchException.notImplemented(
                "The aiServicesVision vectorizer (Azure AI Vision multimodal embeddings)");
            default -> throw SearchException.badRequest("The vectorizer '" + vectorizer.name() + "' has an unknown "
                + "kind '" + vectorizer.kind() + "'.");
        };
    }

    private float[] azureOpenAI(Vectorizer vectorizer, VectorInput input, int dimensions, String modelEndpoint) {
        supports(vectorizer, input, VectorQuery.Kind.TEXT);
        ObjectNode parameters = vectorizer.parameters();
        String deployment = SearchJson.text(parameters, "deploymentId");
        String model = SearchJson.text(parameters, "modelName");
        if (modelEndpoint != null) {
            return openAIEmbeddings(modelEndpointUri(modelEndpoint), input.value(), deployment != null ? deployment
                : model, Map.of());
        }
        URI resource = uri(SearchJson.text(parameters, "resourceUri"));
        if (isAzure(resource)) {
            throw unreachableAzure();
        }
        String base = resource.toString().endsWith("/") ? resource.toString() : resource + "/";
        URI target = uri(base + "openai/deployments/" + URLEncoder.encode(deployment, StandardCharsets.UTF_8)
            + "/embeddings?api-version=" + AZURE_OPENAI_API_VERSION);
        ObjectNode body = SearchJson.object();
        body.put("input", input.value());
        if (model != null && model.startsWith("text-embedding-3")) {
            body.put("dimensions", dimensions);
        }
        String apiKey = SearchJson.text(parameters, "apiKey");
        Map<String, String> headers = apiKey == null ? Map.of() : Map.of("api-key", apiKey);
        return openAIEmbedding(send(target, "POST", headers, body, DEFAULT_TIMEOUT));
    }

    private float[] viaModelEndpoint(Vectorizer vectorizer, VectorInput input, String modelEndpoint, String model) {
        if (modelEndpoint == null) {
            throw unreachableAzure();
        }
        if (input.kind() != VectorQuery.Kind.TEXT) {
            throw SearchException.badRequest("The vectorizer '" + vectorizer.name() + "' calls the configured model "
                + "endpoint (floci-az.services.search.model-endpoint), which vectorizes text only; '"
                + input.kind().wireName() + "' vector queries are not supported.");
        }
        return openAIEmbeddings(modelEndpointUri(modelEndpoint), input.value(),
            model == null ? vectorizer.name() : model, Map.of());
    }

    private float[] openAIEmbeddings(URI target, String text, String model, Map<String, String> headers) {
        ObjectNode body = SearchJson.object();
        body.put("input", text);
        if (model != null) {
            body.put("model", model);
        }
        return openAIEmbedding(send(target, "POST", headers, body, DEFAULT_TIMEOUT));
    }

    private float[] openAICompatible(URI uri, ObjectNode parameters, String text) {
        ObjectNode body = SearchJson.object();
        body.put("input", text);
        JsonNode dimensions = parameters.get("dimensions");
        if (dimensions != null && dimensions.isIntegralNumber()) {
            body.put("dimensions", dimensions.intValue());
        }
        return openAIEmbedding(send(uri, "POST", headers(parameters.get("headers")), body, DEFAULT_TIMEOUT));
    }

    private float[] customWebApi(URI uri, ObjectNode parameters, VectorInput input) {
        ObjectNode data = SearchJson.object();
        switch (input.kind()) {
            case TEXT -> data.put("text", input.value());
            case IMAGE_URL -> data.put("imageUrl", input.value());
            default -> data.putObject("imageBinary").put("data", input.value());
        }
        ObjectNode body = SearchJson.object();
        ObjectNode record = body.putArray("values").addObject();
        record.put("recordId", "0");
        record.set("data", data);
        String method = SearchJson.text(parameters, "httpMethod");
        JsonNode response = send(uri, method == null ? "POST" : method.toUpperCase(Locale.ROOT),
            headers(parameters.get("httpHeaders")), body, timeout(parameters));
        JsonNode values = response.get("values");
        if (values != null && values.isArray()) {
            JsonNode chosen = null;
            for (JsonNode value : values) {
                if (chosen == null || "0".equals(SearchJson.text(value, "recordId"))) {
                    chosen = value;
                }
            }
            if (chosen != null) {
                float[] vector = numbers(chosen.path("data").get("vector"));
                if (vector != null) {
                    return vector;
                }
            }
        }
        throw invalidResponse();
    }

    private float[] aml(URI uri, ObjectNode parameters, VectorInput input) {
        ObjectNode body = SearchJson.object();
        ArrayNode inputs = body.putArray("input");
        if (input.kind() == VectorQuery.Kind.TEXT) {
            inputs.add(input.value());
        } else {
            inputs.addObject().put("image", input.value());
        }
        body.put("input_type", "query");
        String key = SearchJson.text(parameters, "key");
        Map<String, String> headers = key == null ? Map.of() : Map.of("Authorization", "Bearer " + key);
        return anyEmbedding(send(uri, "POST", headers, body, timeout(parameters)));
    }

    private JsonNode send(URI target, String method, Map<String, String> headers, ObjectNode body, Duration timeout) {
        HttpResponse<String> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(SearchJson.write(body), StandardCharsets.UTF_8));
            headers.forEach(request::header);
            response = http().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugv("Vectorizer endpoint {0} could not be reached: {1}", target, e.toString());
            throw failure(UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(UNREACHABLE);
        }
        LOG.debugv("Vectorizer endpoint {0} answered {1}", target, response.statusCode());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw failure(FAILED + "The vectorization endpoint returned status code '" + response.statusCode()
                + "' (" + reason(response.statusCode()) + ").");
        }
        try {
            return SearchJson.MAPPER.readTree(response.body());
        } catch (IOException e) {
            LOG.debugv("Vectorizer endpoint {0} answered with a body that is not JSON: {1}", target, e.getMessage());
            throw invalidResponse();
        }
    }

    private static float[] openAIEmbedding(JsonNode response) {
        float[] vector = numbers(response.path("data").path(0).get("embedding"));
        if (vector == null) {
            throw invalidResponse();
        }
        return vector;
    }

    /** An embedding in any of the shapes embedding endpoints answer with. */
    private static float[] anyEmbedding(JsonNode response) {
        List<JsonNode> candidates = List.of(
            response.path("data").path(0).path("embedding"),
            response.path("embeddings").path(0),
            response.path("embeddings").path("float").path(0),
            response.path("values").path(0).path("data").path("vector"),
            response.path("vector"),
            response.path(0),
            response);
        for (JsonNode candidate : candidates) {
            float[] vector = numbers(candidate);
            if (vector != null) {
                return vector;
            }
        }
        throw invalidResponse();
    }

    private static float[] numbers(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        float[] vector = new float[node.size()];
        for (int index = 0; index < vector.length; index++) {
            JsonNode component = node.get(index);
            if (!component.isNumber()) {
                return null;
            }
            vector[index] = (float) component.doubleValue();
        }
        return vector;
    }

    private static void supports(Vectorizer vectorizer, VectorInput input, VectorQuery.Kind... kinds) {
        for (VectorQuery.Kind kind : kinds) {
            if (kind == input.kind()) {
                return;
            }
        }
        List<String> names = Arrays.stream(kinds).map(kind -> "'" + kind.wireName() + "'").toList();
        throw SearchException.badRequest("The vector query kind '" + input.kind().wireName() + "' is not supported "
            + "by the vectorizer '" + vectorizer.name() + "' of kind '" + vectorizer.kind() + "', which supports "
            + String.join(", ", names) + " vector queries.");
    }

    private static Map<String, String> headers(JsonNode headers) {
        if (!(headers instanceof ObjectNode object)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = object.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getValue().isTextual()) {
                values.put(entry.getKey(), entry.getValue().textValue());
            }
        }
        return values;
    }

    private static Duration timeout(ObjectNode parameters) {
        String value = SearchJson.text(parameters, "timeout");
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            return DEFAULT_TIMEOUT;
        }
    }

    private static URI modelEndpointUri(String modelEndpoint) {
        String trimmed = modelEndpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return uri(trimmed.endsWith("/embeddings") ? trimmed : trimmed + "/embeddings");
    }

    private static URI uri(String value) {
        URI uri;
        try {
            uri = value == null ? null : new URI(value);
        } catch (URISyntaxException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null) {
            throw failure(UNREACHABLE);
        }
        return uri;
    }

    private static boolean isAzure(URI uri) {
        return CloudCatalog.isAzureHost(uri.getHost());
    }

    private static SearchException unreachableAzure() {
        return failure(UNREACHABLE + " The floci-az emulator does not call models hosted in Azure; set "
            + "floci-az.services.search.model-endpoint (FLOCI_AZ_SERVICES_SEARCH_MODEL_ENDPOINT) to an "
            + "OpenAI-compatible embeddings endpoint.");
    }

    private static SearchException invalidResponse() {
        return failure(FAILED + "The vectorization endpoint returned a response without an embedding.");
    }

    private static SearchException failure(String message) {
        return new SearchException(SearchErrorCode.VECTORIZATION_FAILED, message);
    }

    /** The name .NET gives an HTTP status, which Azure's vectorization errors quote. */
    private static String reason(int status) {
        return switch (status) {
            case 400 -> "BadRequest";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "NotFound";
            case 405 -> "MethodNotAllowed";
            case 408 -> "RequestTimeout";
            case 409 -> "Conflict";
            case 413 -> "RequestEntityTooLarge";
            case 415 -> "UnsupportedMediaType";
            case 422 -> "UnprocessableEntity";
            case 429 -> "TooManyRequests";
            case 500 -> "InternalServerError";
            case 501 -> "NotImplemented";
            case 502 -> "BadGateway";
            case 503 -> "ServiceUnavailable";
            case 504 -> "GatewayTimeout";
            default -> String.valueOf(status);
        };
    }
}
