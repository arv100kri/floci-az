package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.engine.vector.VectorSearchConfig.Vectorizer;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorizerClientTest {

    private static final float[] EMBEDDING = {0.1f, 0.2f, 0.3f};

    private final VectorizerClient client = new VectorizerClient(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).build());
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private HttpServer server;
    private String base;

    private record Captured(String method, String path, String query, String apiKey, String authorization,
                            String custom, JsonNode body) {
    }

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        reply.set(new Reply(200, "{\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("api-key"),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("x-custom"), SearchJson.parse(request)));
        byte[] body = reply.get().body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.get().status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Vectorizer vectorizer(String kind, String parametersJson) {
        return new Vectorizer("v1", kind, (ObjectNode) SearchJson.parse(parametersJson.getBytes(StandardCharsets.UTF_8)));
    }

    private static VectorInput text(String value) {
        return new VectorInput(VectorQuery.Kind.TEXT, value);
    }

    @Test
    void azureOpenAICallsTheDeploymentsEmbeddingsApi() {
        Vectorizer openAI = vectorizer("azureOpenAI", "{\"resourceUri\":\"" + base + "\",\"deploymentId\":\"dep1\","
            + "\"apiKey\":\"secret\",\"modelName\":\"text-embedding-3-small\"}");
        assertArrayEquals(EMBEDDING, client.embed(openAI, text("hello"), 3, null));
        Captured call = captured.get();
        assertThat(call.method(), is("POST"));
        assertThat(call.path(), is("/openai/deployments/dep1/embeddings"));
        assertThat(call.query(), is("api-version=2024-10-21"));
        assertThat(call.apiKey(), is("secret"));
        assertThat(call.body().get("input").asText(), is("hello"));
        assertThat(call.body().get("dimensions").asInt(), is(3));
    }

    @Test
    void theModelEndpointReplacesTheAzureOpenAIResource() {
        Vectorizer openAI = vectorizer("azureOpenAI", "{\"resourceUri\":\"https://contoso.openai.azure.com\","
            + "\"deploymentId\":\"dep1\",\"modelName\":\"text-embedding-ada-002\"}");
        assertArrayEquals(EMBEDDING, client.embed(openAI, text("hello"), 3, base + "/v1/"));
        Captured call = captured.get();
        assertThat(call.path(), is("/v1/embeddings"));
        assertThat(call.body().toString(), is("{\"input\":\"hello\",\"model\":\"dep1\"}"));
    }

    @Test
    void anAzureHostWithoutAModelEndpointIsUnreachable() {
        Vectorizer openAI = vectorizer("azureOpenAI", "{\"resourceUri\":\"https://contoso.openai.azure.com\","
            + "\"deploymentId\":\"dep1\"}");
        SearchException error = assertThrows(SearchException.class,
            () -> client.embed(openAI, text("hello"), 3, null));
        assertThat(error.errorCode(), is(SearchErrorCode.VECTORIZATION_FAILED));
        assertThat(error.status(), is(400));
        assertThat(error.errorCode().code(), is(""));
        assertThat(error.getMessage(), startsWith("Could not complete vectorization action. Could not reach the "
            + "vectorization endpoint."));
        assertThat(captured.get(), nullValue());
    }

    @Test
    void customWebApiUsesTheSkillContract() {
        reply.set(new Reply(200, "{\"values\":[{\"recordId\":\"0\",\"data\":{\"vector\":[0.1,0.2,0.3]},"
            + "\"errors\":null,\"warnings\":null}]}"));
        Vectorizer custom = vectorizer("customWebApi", "{\"uri\":\"" + base + "/embed\",\"httpMethod\":\"PUT\","
            + "\"httpHeaders\":{\"x-custom\":\"value\"}}");
        assertArrayEquals(EMBEDDING, client.embed(custom, text("hello"), 3, null));
        Captured call = captured.get();
        assertThat(call.method(), is("PUT"));
        assertThat(call.path(), is("/embed"));
        assertThat(call.custom(), is("value"));
        assertThat(call.body().toString(), is("{\"values\":[{\"recordId\":\"0\",\"data\":{\"text\":\"hello\"}}]}"));
        client.embed(custom, new VectorInput(VectorQuery.Kind.IMAGE_BINARY, "aGVsbG8="), 3, null);
        assertThat(captured.get().body().at("/values/0/data/imageBinary/data").asText(), is("aGVsbG8="));
    }

    @Test
    void amlPostsToItsScoringUri() {
        reply.set(new Reply(200, "{\"embeddings\":[[0.1,0.2,0.3]]}"));
        Vectorizer aml = vectorizer("aml", "{\"uri\":\"" + base + "/score\",\"key\":\"k1\"}");
        assertArrayEquals(EMBEDDING, client.embed(aml, text("hello"), 3, null));
        Captured call = captured.get();
        assertThat(call.path(), is("/score"));
        assertThat(call.authorization(), is("Bearer k1"));
        assertThat(call.body().toString(), is("{\"input\":[\"hello\"],\"input_type\":\"query\"}"));
    }

    @Test
    void anEndpointErrorIsReportedWithItsStatus() {
        reply.set(new Reply(403, "{}"));
        Vectorizer custom = vectorizer("customWebApi", "{\"uri\":\"" + base + "\"}");
        SearchException error = assertThrows(SearchException.class,
            () -> client.embed(custom, text("hello"), 3, null));
        assertThat(error.getMessage(), is("Could not complete vectorization action. The vectorization endpoint "
            + "returned status code '403' (Forbidden)."));
    }

    @Test
    void aClosedPortIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Vectorizer custom = vectorizer("customWebApi", "{\"uri\":\"http://127.0.0.1:" + port + "/embed\"}");
        SearchException error = assertThrows(SearchException.class,
            () -> client.embed(custom, text("hello"), 3, null));
        assertThat(error.getMessage(), is("Could not complete vectorization action. Could not reach the "
            + "vectorization endpoint."));
    }

    @Test
    void unsupportedInputsAndVectorizersAreRefused() {
        Vectorizer openAI = vectorizer("azureOpenAI", "{\"resourceUri\":\"" + base + "\",\"deploymentId\":\"d\"}");
        SearchException image = assertThrows(SearchException.class, () -> client.embed(openAI,
            new VectorInput(VectorQuery.Kind.IMAGE_URL, "https://example.com/cat.png"), 3, null));
        assertThat(image.status(), is(400));
        assertThat(image.getMessage(), containsString("'imageUrl' is not supported"));
        Vectorizer vision = vectorizer("aiServicesVision", "{\"resourceUri\":\"" + base + "\",\"modelVersion\":"
            + "\"2023-04-15\"}");
        assertThat(assertThrows(SearchException.class, () -> client.embed(vision, text("cat"), 3, null)).status(),
            is(501));
    }

    @Test
    void textQueriesAreVectorizedBeforeTheSearchRuns() {
        LiveIndex index = EngineFixtures.index("""
            {"name": "texts", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "v", "type": "Collection(Edm.Single)", "dimensions": 3, "vectorSearchProfile": "p"}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "a", "kind": "hnsw"}],
              "profiles": [{"name": "p", "algorithm": "a", "vectorizer": "openai"}],
              "vectorizers": [{"name": "openai", "kind": "azureOpenAI", "azureOpenAIParameters": {
                "resourceUri": "https://contoso.openai.azure.com", "deploymentId": "embed",
                "modelName": "text-embedding-3-small"}}]
            }}
            """, "{\"id\":\"near\",\"v\":[0.1,0.2,0.3]}", "{\"id\":\"far\",\"v\":[-0.3,0.2,-0.1]}");
        SearchRequest request = SearchRequest.builder().vectorQueries(List.of((ObjectNode) SearchJson.parse(
            "{\"kind\":\"text\",\"text\":\"hello\",\"fields\":\"v\",\"k\":1}".getBytes(StandardCharsets.UTF_8))))
            .build();
        SearchOutcome outcome = SearchPipeline.standard().run(index, request,
            new EngineSettings(100_000, true, base));
        assertThat(outcome.results().stream().map(result -> result.document().get("id").asText()).toList(),
            contains("near"));
        assertThat(captured.get().body().toString(), is("{\"input\":\"hello\",\"model\":\"embed\"}"));
        reply.set(new Reply(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}"));
        assertThat(assertThrows(SearchException.class, () -> SearchPipeline.standard().run(index, request,
            new EngineSettings(100_000, true, base))).getMessage(), containsString("mismatch in vector dimensions"));
    }
}
