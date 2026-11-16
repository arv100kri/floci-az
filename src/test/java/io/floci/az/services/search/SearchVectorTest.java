package io.floci.az.services.search;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Search data plane: vector and hybrid search")
class SearchVectorTest {

    private static final String API = "?api-version=2025-09-01";
    private static final String SERVICE = "/vectors-search";
    private static final String DOCS = SERVICE + "/indexes('vectors')/docs";

    private final AtomicReference<String> vectorizerRequest = new AtomicReference<>();
    private final AtomicReference<String> vectorizerCredentials = new AtomicReference<>();
    private HttpServer embeddings;
    private boolean embeddingsStopped;

    @BeforeEach
    void reset() throws IOException {
        given().post("/_admin/reset").then().statusCode(204);
        embeddings = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        embeddings.createContext("/embed", exchange -> {
            vectorizerRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            vectorizerCredentials.set(exchange.getRequestURI().getQuery() + " "
                + exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = "{\"values\":[{\"recordId\":\"0\",\"data\":{\"vector\":[1,0,0]},\"errors\":null,\"warnings\":null}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        embeddings.start();
        embeddingsStopped = false;
        admin().body(definition("http://127.0.0.1:" + embeddings.getAddress().getPort() + "/embed?code=fn-key"))
            .post(SERVICE + "/indexes" + API).then().statusCode(201);
        admin().body("""
            {"value": [
              {"id": "1", "title": "red apple", "category": "fruit", "embedding": [1, 0, 0], "hidden": [1, 0, 0]},
              {"id": "2", "title": "green apple", "category": "fruit", "embedding": [0.9, 0.1, 0], "hidden": [0, 1, 0]},
              {"id": "3", "title": "blue car", "category": "vehicle", "embedding": [0.2, 1, 0], "hidden": [0, 0, 1]},
              {"id": "4", "title": "red car", "category": "vehicle", "embedding": [0, 0, 1], "hidden": [0, 0, 1]}
            ]}
            """).post(DOCS + "/search.index" + API).then().statusCode(200);
    }

    @AfterEach
    void stopEmbeddings() {
        stopEmbeddingServer();
    }

    private void stopEmbeddingServer() {
        if (!embeddingsStopped) {
            embeddings.stop(0);
            embeddingsStopped = true;
        }
    }

    private static String definition(String vectorizerUri) {
        return """
            {"name": "vectors", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "title", "type": "Edm.String"},
              {"name": "category", "type": "Edm.String"},
              {"name": "embedding", "type": "Collection(Edm.Single)", "dimensions": 3, "retrievable": true,
               "vectorSearchProfile": "with-vectorizer"},
              {"name": "hidden", "type": "Collection(Edm.Single)", "dimensions": 3, "stored": false,
               "vectorSearchProfile": "plain"}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "graph", "kind": "hnsw", "hnswParameters": {"metric": "cosine"}}],
              "profiles": [
                {"name": "with-vectorizer", "algorithm": "graph", "vectorizer": "local"},
                {"name": "plain", "algorithm": "graph"}
              ],
              "vectorizers": [{"name": "local", "kind": "customWebApi",
                "customWebApiParameters": {"uri": "%s", "httpMethod": "POST",
                  "httpHeaders": {"x-api-key": "header-secret"}}}]
            }}
            """.formatted(vectorizerUri);
    }

    private static RequestSpecification admin() {
        return given().header("api-key", SearchServiceRegistry.DEV_PRIMARY_ADMIN_KEY).contentType("application/json");
    }

    private static ValidatableResponse search(String body) {
        return admin().body(body).post(DOCS + "/search.post.search" + API).then();
    }

    @Test
    void theVectorSearchSectionRoundTripsWithItsDefaults() {
        admin().get(SERVICE + "/indexes('vectors')" + API).then().statusCode(200)
            .body("vectorSearch.algorithms[0].hnswParameters.m", equalTo(4))
            .body("vectorSearch.algorithms[0].hnswParameters.efConstruction", equalTo(400))
            .body("vectorSearch.algorithms[0].hnswParameters.efSearch", equalTo(500))
            .body("vectorSearch.compressions.size()", equalTo(0))
            .body("vectorSearch.vectorizers[0].customWebApiParameters.httpMethod", equalTo("POST"))
            .body("fields[4].stored", equalTo(false))
            .body("fields[4].retrievable", equalTo(false));
    }

    @Test
    void vectorQueriesReturnTheNearestDocumentsWithSimilarityScores() {
        search("{\"count\": true, \"select\": \"id, embedding\", \"vectorQueries\": [{\"kind\": \"vector\", "
            + "\"vector\": [1, 0, 0], \"fields\": \"embedding\", \"k\": 2}]}")
            .statusCode(200)
            .body("'@odata.count'", equalTo(2))
            .body("value.id", contains("1", "2"))
            .body("value[0].'@search.score'", equalTo(1.0f))
            .body("value[0].embedding.size()", equalTo(3));
    }

    @Test
    void hybridQueriesAreFusedWithReciprocalRankFusion() {
        search("{\"search\": \"red\", \"searchFields\": \"title\", \"vectorQueries\": [{\"kind\": \"vector\", "
            + "\"vector\": [1, 0, 0], \"fields\": \"embedding\", \"k\": 1}]}")
            .statusCode(200)
            .body("value.id", contains("1", "4"))
            .body("value[0].'@search.score'", equalTo((float) (2.0 / 61)));
    }

    @Test
    void textQueriesAreVectorizedByTheProfilesVectorizer() {
        search("{\"vectorQueries\": [{\"kind\": \"text\", \"text\": \"something red\", \"fields\": \"embedding\", "
            + "\"k\": 1}]}")
            .statusCode(200)
            .body("value.id", contains("1"));
        assertThat(vectorizerRequest.get(),
            equalTo("{\"values\":[{\"recordId\":\"0\",\"data\":{\"text\":\"something red\"}}]}"));
    }

    @Test
    void getRedactsVectorizerSecretsAndAnUpdateCanSendThemBack() {
        String redacted = admin().get(SERVICE + "/indexes('vectors')" + API).then().statusCode(200)
            .body("vectorSearch.vectorizers[0].customWebApiParameters.httpHeaders.x-api-key", equalTo("<redacted>"))
            .body("vectorSearch.vectorizers[0].customWebApiParameters.uri", endsWith("/embed?code=<redacted>"))
            .extract().asString();
        admin().body(redacted).put(SERVICE + "/indexes('vectors')" + API).then().statusCode(204);
        search("{\"vectorQueries\": [{\"kind\": \"text\", \"text\": \"red\", \"fields\": \"embedding\", \"k\": 1}]}")
            .statusCode(200);
        assertThat(vectorizerCredentials.get(), equalTo("code=fn-key header-secret"));
    }
    @Test
    void aFailingVectorizerAnswersWithTheVectorizationError() {
        stopEmbeddingServer();
        search("{\"vectorQueries\": [{\"kind\": \"text\", \"text\": \"red\", \"fields\": \"embedding\"}]}")
            .statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", startsWith("Could not complete vectorization action."));
    }

    @Test
    void unstoredVectorsCannotBeSelectedAndAreLostByAMergeWithoutThem() {
        search("{\"select\": \"id, hidden\", \"vectorQueries\": [{\"kind\": \"vector\", \"vector\": [0, 1, 0], "
            + "\"fields\": \"hidden\", \"k\": 1}]}")
            .statusCode(400);
        search("{\"vectorQueries\": [{\"kind\": \"vector\", \"vector\": [0, 1, 0], \"fields\": \"hidden\", \"k\": 1}]}")
            .statusCode(200)
            .body("value.id", contains("2"))
            .body("value[0]", not(hasKey("hidden")));
        admin().body("{\"value\": [{\"@search.action\": \"merge\", \"id\": \"2\", \"title\": \"pale apple\"}]}")
            .post(DOCS + "/search.index" + API).then().statusCode(200);
        search("{\"vectorQueries\": [{\"kind\": \"vector\", \"vector\": [0, 1, 0], \"fields\": \"hidden\", \"k\": 1}]}")
            .statusCode(200)
            .body("value.id", contains("1"));
    }

    @Test
    void debugVectorReturnsSubscores() {
        search("{\"search\": \"apple\", \"debug\": \"vector\", \"vectorQueries\": [{\"kind\": \"vector\", "
            + "\"vector\": [1, 0, 0], \"fields\": \"embedding\", \"k\": 1}]}")
            .statusCode(200)
            .body("value[0].'@search.documentDebugInfo'.vectors.subscores.vectors[0].embedding.vectorSimilarity",
                equalTo(1.0f))
            .body("value[0].'@search.documentDebugInfo'.vectors.subscores", hasKey("text"));
    }

    @Test
    void invalidVectorQueriesAreRefusedWithTheAzureEnvelope() {
        search("{\"vectorQueries\": [{\"kind\": \"vector\", \"vector\": [1, 0], \"fields\": \"embedding\"}]}")
            .statusCode(400)
            .body("error.code", equalTo("InvalidRequestParameter"))
            .body("error.message", containsString("mismatch in vector dimensions"));
        admin().body("{\"value\": [{\"id\": \"9\", \"embedding\": [1, 0]}]}").post(DOCS + "/search.index" + API)
            .then().statusCode(400)
            .body("error.message", containsString("There's a mismatch in vector dimensions. The vector field "
                + "'embedding', with dimension of '3', expects a length of '3'. However, the provided vector has a "
                + "length of '2'."));
    }
}
