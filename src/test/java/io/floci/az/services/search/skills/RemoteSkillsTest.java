package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.az.services.search.indexer.SkillsetRunner.EnrichmentResult;
import io.floci.az.services.search.model.SearchJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/** The skills that call an endpoint, against a local stub server: never the external network. */
class RemoteSkillsTest {

    private HttpServer server;
    private ExecutorService executor;
    private String base;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger skillCalls = new AtomicInteger();
    private volatile Responder skillResponder;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/skill", exchange -> {
            Recorded recorded = record(exchange);
            skillCalls.incrementAndGet();
            skillResponder.respond(exchange, recorded.body());
        });
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode body = record(exchange).body();
            ArrayNode data = SearchJson.NODES.arrayNode();
            ArrayNode input = (ArrayNode) body.get("input");
            for (int index = input.size() - 1; index >= 0; index--) {
                ObjectNode item = data.addObject();
                item.put("object", "embedding");
                item.put("index", index);
                item.putArray("embedding").add(input.get(index).asText().length()).add(index);
            }
            ObjectNode response = SearchJson.object();
            response.put("object", "list");
            response.set("data", data);
            reply(exchange, 200, "application/json", SearchJson.write(response));
        });
        server.createContext("/aml", exchange -> {
            JsonNode body = record(exchange).body();
            reply(exchange, 200, "application/json; charset=utf-8", "{\"detected\": \""
                + body.get("text").asText().toUpperCase(Locale.ROOT) + "\"}");
        });
        server.createContext("/files", exchange -> {
            record(exchange);
            reply(exchange, 200, "text/plain", "downloaded text");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private Recorded record(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = bytes.length == 0 ? null : SearchJson.parse(bytes);
        Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("x-api-key"),
            exchange.getRequestHeaders().getFirst("api-key"), exchange.getRequestHeaders().getFirst("Authorization"),
            body);
        requests.add(recorded);
        return recorded;
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Answers every record with its text uppercased, and a warning for record 1. */
    private static void upperCase(HttpExchange exchange, JsonNode body) throws IOException {
        ObjectNode response = SearchJson.object();
        ArrayNode values = response.putArray("values");
        for (JsonNode value : body.get("values")) {
            ObjectNode record = values.addObject();
            String recordId = value.get("recordId").asText();
            record.put("recordId", recordId);
            record.putObject("data").put("upper", value.at("/data/text").asText().toUpperCase(Locale.ROOT));
            record.putNull("errors");
            if (recordId.equals("1")) {
                record.putArray("warnings").addObject().put("message", "short text");
            } else {
                record.putNull("warnings");
            }
        }
        reply(exchange, 200, "application/json", SearchJson.write(response));
    }

    private EnrichmentResult run(EnrichmentPipeline pipeline, String skills, String document, String mappings) {
        return pipeline.run("svc", (ObjectNode) SearchJson.parse(("{\"skills\": " + skills + "}")
            .getBytes(StandardCharsets.UTF_8)), (ObjectNode) SearchJson.parse(document.getBytes(StandardCharsets
            .UTF_8)), (ArrayNode) SearchJson.parse(mappings.getBytes(StandardCharsets.UTF_8)));
    }

    private EnrichmentPipeline pipeline(String modelEndpoint) {
        return new EnrichmentPipeline(new SkillHttp(), modelEndpoint, (service, index) -> "id");
    }

    private String webApiSkillset(String extra) {
        return """
            [{"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "sentences",
              "inputs": [{"name": "text", "source": "/document/content"}],
              "outputs": [{"name": "textItems", "targetName": "pages"}]},
             {"@odata.type": "#Microsoft.Skills.Custom.WebApiSkill", "name": "upper", "context": "/document/pages/*",
              "uri": "%s/skill?code=secret", "batchSize": 2, "httpHeaders": {"x-api-key": "k1"} %s,
              "inputs": [{"name": "text", "source": "/document/pages/*"}, {"name": "lang", "source": "/document/lang"}],
              "outputs": [{"name": "upper", "targetName": "upperText"}]}]
            """.formatted(base, extra);
    }

    private static final String DOCUMENT = "{\"content\": \"Alpha one. Beta two. Gamma three.\"}";
    private static final String UPPER_MAPPING = "[{\"sourceFieldName\": \"/document/pages/*/upperText\", "
        + "\"targetFieldName\": \"upper\"}]";

    @Test
    void webApiSkillSendsTheDocumentedContractInBatches() {
        skillResponder = RemoteSkillsTest::upperCase;
        EnrichmentResult result = run(pipeline(null), webApiSkillset(""), DOCUMENT, UPPER_MAPPING);
        assertThat(result.errors(), empty());
        assertThat(result.indexFields().get("upper").toString(),
            equalTo("[\"ALPHA ONE.\",\"BETA TWO.\",\"GAMMA THREE.\"]"));
        assertThat(result.warnings(), contains("Enrichment.WebApiSkill.upper: Web API skill response contains "
            + "warnings. short text"));
        assertThat(requests, hasSize(2));
        Recorded first = requests.stream().filter(request -> request.body().get("values").size() == 2).findFirst()
            .orElseThrow();
        assertThat(first.method(), equalTo("POST"));
        assertThat(first.uri(), equalTo("/skill?code=secret"));
        assertThat(first.contentType(), equalTo("application/json"));
        assertThat(first.apiKeyHeader(), equalTo("k1"));
        assertThat(first.body().toString(), equalTo("{\"values\":[{\"recordId\":\"0\",\"data\":{\"text\":"
            + "\"Alpha one.\",\"lang\":null}},{\"recordId\":\"1\",\"data\":{\"text\":\"Beta two.\",\"lang\":null}}]}"));
    }

    @Test
    void webApiFailuresAreTheDocumentedErrors() {
        skillResponder = (exchange, body) -> reply(exchange, 500, "text/plain", "boom");
        assertThat(run(pipeline(null), webApiSkillset(", \"batchSize\": 5"), DOCUMENT, UPPER_MAPPING).errors(),
            contains("Enrichment.WebApiSkill.upper: Could not execute skill because the Web Api request failed. The "
                + "Web Api answered with status 500: boom"));

        skillResponder = (exchange, body) -> reply(exchange, 200, "text/plain", "{}");
        assertThat(run(pipeline(null), webApiSkillset(", \"batchSize\": 5"), DOCUMENT, UPPER_MAPPING).errors().get(0),
            containsString("Web API skill response is invalid. The response Content-Type 'text/plain' is not "
                + "application/json."));

        skillResponder = (exchange, body) -> reply(exchange, 200, "application/json",
            "{\"values\": [{\"recordId\": \"0\", \"data\": {\"upper\": \"X\"}, \"errors\": [{\"message\": \"bad "
                + "input\"}], \"warnings\": null}, {\"recordId\": \"9\", \"data\": {}}]}");
        List<String> errors = run(pipeline(null), webApiSkillset(", \"batchSize\": 5"), DOCUMENT, UPPER_MAPPING)
            .errors();
        assertThat(errors, contains(
            "Enrichment.WebApiSkill.upper: Web API skill response contains errors. bad input",
            "Enrichment.WebApiSkill.upper: Could not execute skill because Web API skill response is invalid. The "
                + "response has no record with recordId '1'.",
            "Enrichment.WebApiSkill.upper: Could not execute skill because Web API skill response is invalid. The "
                + "response has no record with recordId '2'."));
    }

    @Test
    void webApiRetriesUnavailableAndTimesOut() {
        AtomicInteger attempts = new AtomicInteger();
        skillResponder = (exchange, body) -> {
            if (attempts.incrementAndGet() == 1) {
                reply(exchange, 503, "text/plain", "busy");
            } else {
                upperCase(exchange, body);
            }
        };
        EnrichmentResult retried = run(pipeline(null), webApiSkillset(", \"batchSize\": 5"), DOCUMENT, UPPER_MAPPING);
        assertThat(retried.errors(), empty());
        assertThat(attempts.get(), is(2));

        skillResponder = (exchange, body) -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            upperCase(exchange, body);
        };
        EnrichmentResult timedOut = run(pipeline(null), webApiSkillset(", \"batchSize\": 5, \"timeout\": \"PT1S\""),
            DOCUMENT, UPPER_MAPPING);
        assertThat(timedOut.errors(), contains("Enrichment.WebApiSkill.upper: Skill did not execute within the time "
            + "limit. The Web Api request to '" + base + "/skill?code=<redacted>' did not answer within 1 seconds."));
    }

    private static final String EMBEDDING_SKILLSET = """
        [{"@odata.type": "#Microsoft.Skills.Text.SplitSkill", "textSplitMode": "sentences",
          "inputs": [{"name": "text", "source": "/document/content"}],
          "outputs": [{"name": "textItems", "targetName": "pages"}]},
         {"@odata.type": "#Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill", "name": "embed",
          "context": "/document/pages/*", "resourceUri": "https://my.openai.azure.com", "deploymentId": "local-model",
          "modelName": "text-embedding-3-small", "dimensions": 2, "apiKey": "secret-key",
          "inputs": [{"name": "text", "source": "/document/pages/*"}],
          "outputs": [{"name": "embedding", "targetName": "vector"}]}]
        """;

    @Test
    void theEmbeddingSkillCallsTheConfiguredModelEndpoint() {
        EnrichmentResult result = run(pipeline(base + "/v1/"), EMBEDDING_SKILLSET, DOCUMENT,
            "[{\"sourceFieldName\": \"/document/pages/*/vector\", \"targetFieldName\": \"vectors\"}]");
        assertThat(result.errors(), empty());
        assertThat(result.indexFields().get("vectors").toString(), equalTo("[[10,0],[9,1],[12,2]]"));
        assertThat(requests, hasSize(1));
        Recorded call = requests.get(0);
        assertThat(call.uri(), equalTo("/v1/embeddings"));
        assertThat(call.body().toString(), equalTo("{\"input\":[\"Alpha one.\",\"Beta two.\",\"Gamma three.\"],"
            + "\"model\":\"local-model\",\"dimensions\":2}"));
        assertThat(call.apiKey(), equalTo("secret-key"));
        assertThat(call.authorization(), equalTo("Bearer secret-key"));
    }

    @Test
    void withoutAModelEndpointTheEmbeddingSkillFailsTheDocument() {
        EnrichmentResult result = run(pipeline(null), EMBEDDING_SKILLSET, DOCUMENT, "[]");
        assertThat(result.errors(), contains("Enrichment.AzureOpenAIEmbeddingSkill.embed: Could not execute skill "
            + "because the Web Api request failed. The Azure OpenAI resource 'https://my.openai.azure.com' could not "
            + "be reached: floci-az calls an OpenAI-compatible model endpoint instead, and none is configured "
            + "(floci-az.services.search.model-endpoint)."));
        assertThat(requests, empty());
        EnrichmentResult empty = run(pipeline(base + "/v1"), EMBEDDING_SKILLSET, "{\"content\": \" \"}", "[]");
        assertThat(empty.errors(), empty());
    }

    @Test
    void theAmlSkillPostsEachRecordAndTheDocumentExtractionSkillDownloadsUrls() {
        String skills = """
            [{"@odata.type": "#Microsoft.Skills.Custom.AmlSkill", "name": "aml", "uri": "%s/aml", "key": "k2",
              "inputs": [{"name": "text", "source": "/document/content"}], "outputs": [{"name": "detected"}]},
             {"@odata.type": "#Microsoft.Skills.Util.DocumentExtractionSkill", "name": "extract",
              "inputs": [{"name": "file_data", "source": "/document/file"}],
              "outputs": [{"name": "content", "targetName": "extracted"}]}]
            """.formatted(base);
        String document = "{\"content\": \"hola\", \"file\": {\"$type\": \"file\", \"url\": \"" + base
            + "/files/a.txt\", \"sasToken\": \"?sv=1&sig=x\"}}";
        EnrichmentResult result = run(pipeline(null), skills, document,
            "[{\"sourceFieldName\": \"/document/detected\"}, {\"sourceFieldName\": \"/document/extracted\"}]");
        assertThat(result.errors(), empty());
        assertThat(result.indexFields().get("detected").asText(), equalTo("HOLA"));
        assertThat(result.indexFields().get("extracted").asText(), equalTo("downloaded text"));
        Recorded aml = requests.stream().filter(request -> request.uri().equals("/aml")).findFirst().orElseThrow();
        assertThat(aml.body().toString(), equalTo("{\"text\":\"hola\"}"));
        assertThat(aml.authorization(), equalTo("Bearer k2"));
        assertThat(requests.stream().filter(request -> request.uri().startsWith("/files")).findFirst().orElseThrow()
            .uri(), startsWith("/files/a.txt?sv=1&sig=x"));
    }

    /** How the stub answers a call to {@code /skill}. */
    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange, JsonNode body) throws IOException;
    }

    private record Recorded(String method, String uri, String contentType, String apiKeyHeader, String apiKey,
                            String authorization, JsonNode body) {
    }
}
