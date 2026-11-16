package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.dataplane.SearchRequestParser;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorQueryPipelineTest {

    private static final String DEFINITION = """
        {"name": "vectors", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "title", "type": "Edm.String"},
          {"name": "category", "type": "Edm.String"},
          {"name": "titleVector", "type": "Collection(Edm.Single)", "dimensions": 3,
           "vectorSearchProfile": "exact"},
          {"name": "contentVector", "type": "Collection(Edm.Single)", "dimensions": 3,
           "vectorSearchProfile": "graph"},
          {"name": "l2Vector", "type": "Collection(Edm.Single)", "dimensions": 3, "vectorSearchProfile": "l2"},
          {"name": "binaryVector", "type": "Collection(Edm.Single)", "dimensions": 3,
           "vectorSearchProfile": "bq-norescore"}
        ],
        "vectorSearch": {
          "algorithms": [
            {"name": "eknn", "kind": "exhaustiveKnn"},
            {"name": "hnsw", "kind": "hnsw", "hnswParameters": {"metric": "cosine"}},
            {"name": "euclid", "kind": "exhaustiveKnn", "exhaustiveKnnParameters": {"metric": "euclidean"}}
          ],
          "compressions": [
            {"name": "bq", "kind": "binaryQuantization", "rescoringOptions": {"enableRescoring": false}}
          ],
          "profiles": [
            {"name": "exact", "algorithm": "eknn"},
            {"name": "graph", "algorithm": "hnsw"},
            {"name": "l2", "algorithm": "euclid"},
            {"name": "bq-norescore", "algorithm": "hnsw", "compression": "bq"}
          ]
        }}
        """;

    private static final String[] DOCUMENTS = {
        doc("1", "red apple", "fruit", "[1, 0, 0]", "[1, 0, 0]"),
        doc("2", "green apple", "fruit", "[0.9, 0.1, 0]", "[0, 1, 0]"),
        doc("3", "blue car", "vehicle", "[0.2, 1, 0]", "[0.9, 0.1, 0]"),
        doc("4", "red car", "vehicle", "[0, 0, 1]", "[0, 0, 1]"),
        doc("5", "yellow banana", "fruit", "[0.5, 0.5, 0]", "[0.3, 0.3, 0.9]")
    };

    private static final SearchPipeline PIPELINE = SearchPipeline.standard();

    private static String doc(String id, String title, String category, String titleVector, String contentVector) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"category\":\"" + category + "\",\"titleVector\":"
            + titleVector + ",\"contentVector\":" + contentVector + ",\"l2Vector\":" + titleVector
            + ",\"binaryVector\":" + titleVector + "}";
    }

    private static LiveIndex index() {
        return EngineFixtures.index(DEFINITION, DOCUMENTS);
    }

    private static SearchOutcome search(String body) {
        ObjectNode json = (ObjectNode) SearchJson.parse(body.getBytes(StandardCharsets.UTF_8));
        return PIPELINE.run(index(), SearchRequestParser.fromBody(json), EngineSettings.DEFAULTS);
    }

    private static List<String> keys(SearchOutcome outcome) {
        return outcome.results().stream().map(result -> result.document().get("id").asText()).toList();
    }

    private static double score(SearchOutcome outcome, int position) {
        return outcome.results().get(position).hit().score();
    }

    private static String query(String vector, String fields, int k) {
        return "{\"kind\":\"vector\",\"vector\":" + vector + ",\"fields\":\"" + fields + "\",\"k\":" + k + "}";
    }

    @Test
    void aVectorQueryReturnsTheKNearestWithCosineScores() {
        SearchOutcome outcome = search("{\"count\":true,\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 3)
            + "]}");
        assertThat(keys(outcome), contains("1", "2", "5"));
        assertThat(outcome.count(), is(3L));
        double cosine = 0.9 / Math.sqrt(0.82);
        assertThat(score(outcome, 0), closeTo(1.0, 1e-6));
        assertThat(score(outcome, 1), closeTo(1.0 / (1.0 + (1.0 - cosine)), 1e-6));
    }

    @Test
    void hnswAndExhaustiveFieldsAreBothSearchedExactly() {
        assertThat(keys(search("{\"vectorQueries\":[" + query("[1,0,0]", "contentVector", 2) + "]}")),
            contains("1", "3"));
    }

    @Test
    void euclideanScoresDecreaseWithDistance() {
        SearchOutcome outcome = search("{\"vectorQueries\":[" + query("[1,0,0]", "l2Vector", 2) + "]}");
        assertThat(keys(outcome), contains("1", "2"));
        assertThat(score(outcome, 0), closeTo(1.0, 1e-6));
        assertThat(score(outcome, 1), closeTo(1.0 / (1.0 + Math.sqrt(0.02)), 1e-6));
    }

    @Test
    void preFilterChoosesTheNeighboursAmongFilteredDocuments() {
        assertThat(keys(search("{\"filter\":\"category eq 'vehicle'\",\"vectorQueries\":["
            + query("[1,0,0]", "titleVector", 2) + "]}")), contains("3", "4"));
    }

    @Test
    void postFilterKeepsTheFilteredPartOfTheUnfilteredTopK() {
        for (String mode : List.of("postFilter", "strictPostFilter")) {
            SearchOutcome outcome = search("{\"filter\":\"category eq 'vehicle'\",\"vectorFilterMode\":\"" + mode
                + "\",\"count\":true,\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 3) + "]}");
            assertThat(keys(outcome).isEmpty(), is(true));
            assertThat(outcome.count(), is(0L));
        }
    }

    @Test
    void multipleVectorQueriesAreFusedWithReciprocalRankFusion() {
        SearchOutcome outcome = search("{\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 3) + ","
            + query("[1,0,0]", "contentVector", 3) + "]}");
        assertThat(keys(outcome), contains("1", "5", "2", "3"));
        assertThat(score(outcome, 0), closeTo(2.0 / 61, 1e-9));
        assertThat(score(outcome, 1), closeTo(2.0 / 63, 1e-9));
        assertThat(score(outcome, 2), closeTo(1.0 / 62, 1e-9));
    }

    @Test
    void oneQueryOverTwoFieldsIsFusedLikeTwoQueries() {
        SearchOutcome outcome = search("{\"vectorQueries\":[" + query("[1,0,0]", "titleVector, contentVector", 3)
            + "]}");
        assertThat(keys(outcome), contains("1", "5", "2", "3"));
    }

    @Test
    void weightsScaleEachListsContribution() {
        SearchOutcome outcome = search("{\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 2) + ","
            + "{\"kind\":\"vector\",\"vector\":[1,0,0],\"fields\":\"contentVector\",\"k\":2,\"weight\":3}]}");
        assertThat(keys(outcome), contains("1", "3", "2"));
        assertThat(score(outcome, 1), closeTo(3.0 / 62, 1e-9));
    }

    @Test
    void hybridQueriesFuseTheTextRanking() {
        SearchOutcome outcome = search("{\"search\":\"red apple\",\"searchFields\":\"title\",\"count\":true,"
            + "\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 3) + "]}");
        assertThat(keys(outcome).subList(0, 2), contains("1", "2"));
        assertThat(keys(outcome), containsInAnyOrder("1", "2", "4", "5"));
        assertThat(outcome.count(), is(4L));
        assertThat(score(outcome, 0), closeTo(2.0 / 61, 1e-9));
    }

    @Test
    void aStarSearchAddsATextListMatchingEveryDocumentButBlankTextDoesNot() {
        assertThat(keys(search("{\"search\":\"*\",\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 1)
            + "]}")), contains("1", "2", "3", "4", "5"));
        SearchOutcome blank = search("{\"search\":\"\",\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 1)
            + "]}");
        assertThat(keys(blank), contains("1"));
        assertThat(score(blank, 0), closeTo(1.0, 1e-6));
    }

    @Test
    void maxTextRecallSizeCutsTheTextListAndTheCountFollowsTheMode() {
        String vector = query("[0,0,1]", "titleVector", 1);
        SearchOutcome all = search("{\"search\":\"*\",\"count\":true,\"hybridSearch\":{\"maxTextRecallSize\":2},"
            + "\"facets\":[\"category\"],\"vectorQueries\":[" + vector + "]}");
        assertThat(keys(all), containsInAnyOrder("1", "2", "4"));
        assertThat(all.count(), is(5L));
        assertThat(facetCounts(all, "category"), is(Map.of("fruit", 3, "vehicle", 2)));
        SearchOutcome retrievable = search("{\"search\":\"*\",\"count\":true,\"hybridSearch\":{\"maxTextRecallSize\":2,"
            + "\"countAndFacetMode\":\"countRetrievableResults\"},\"facets\":[\"category\"],\"vectorQueries\":["
            + vector + "]}");
        assertThat(retrievable.count(), is(3L));
        assertThat(facetCounts(retrievable, "category"), is(Map.of("fruit", 2, "vehicle", 1)));
    }

    private static Map<String, Integer> facetCounts(SearchOutcome outcome, String facet) {
        Map<String, Integer> counts = new HashMap<>();
        outcome.facets().get(facet).forEach(bucket -> counts.put(bucket.get("value").asText(),
            bucket.get("count").intValue()));
        return counts;
    }

    @Test
    void thresholdsDropWeakMatchesBeforeFusion() {
        SearchOutcome similarity = search("{\"vectorQueries\":[{\"kind\":\"vector\",\"vector\":[1,0,0],"
            + "\"fields\":\"titleVector\",\"k\":5,\"threshold\":{\"kind\":\"vectorSimilarity\",\"value\":0.7}}]}");
        assertThat(keys(similarity), contains("1", "2", "5"));
        SearchOutcome distance = search("{\"vectorQueries\":[{\"kind\":\"vector\",\"vector\":[1,0,0],"
            + "\"fields\":\"l2Vector\",\"k\":5,\"threshold\":{\"kind\":\"vectorSimilarity\",\"value\":0.5}}]}");
        assertThat(keys(distance), contains("1", "2"));
        SearchOutcome score = search("{\"vectorQueries\":[{\"kind\":\"vector\",\"vector\":[1,0,0],"
            + "\"fields\":\"titleVector\",\"k\":5,\"threshold\":{\"kind\":\"searchScore\",\"value\":0.9}}]}");
        assertThat(keys(score), contains("1", "2"));
    }

    @Test
    void filterOverrideReplacesTheRequestFilterForItsQuery() {
        SearchOutcome outcome = search("{\"search\":\"apple\",\"searchFields\":\"title\","
            + "\"filter\":\"category eq 'fruit'\",\"vectorQueries\":[{\"kind\":\"vector\",\"vector\":[1,0,0],"
            + "\"fields\":\"titleVector\",\"k\":1,\"filterOverride\":\"category eq 'vehicle'\"}]}");
        assertThat(keys(outcome), containsInAnyOrder("1", "2", "3"));
    }

    @Test
    void binaryQuantizationWithoutRescoringScoresTheCompressedVectors() {
        SearchOutcome outcome = search("{\"vectorQueries\":[" + query("[1,0.2,0]", "binaryVector", 1) + "]}");
        assertThat(keys(outcome), contains("2"));
        assertThat(score(outcome, 0), closeTo(1.0, 1e-6));
        SearchOutcome rescored = search("{\"vectorQueries\":[{\"kind\":\"vector\",\"vector\":[1,0.2,0],"
            + "\"fields\":\"binaryVector\",\"k\":1,\"oversampling\":5}]}");
        assertThat(keys(rescored), contains("2"));
        double exact = (0.9 + 0.02) / (Math.sqrt(1.04) * Math.sqrt(0.82));
        assertThat(score(rescored, 0), closeTo(1.0 / (2.0 - exact), 1e-6));
    }

    @Test
    void debugVectorReturnsTheSubscores() {
        SearchOutcome outcome = search("{\"search\":\"apple\",\"searchFields\":\"title\",\"debug\":\"vector\","
            + "\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 1) + "]}");
        JsonNode first = outcome.results().getFirst().hit().documentDebugInfo().get("vectors").get("subscores");
        assertThat(first.get("text").get("searchScore").isNumber(), is(true));
        ArrayNode vectors = (ArrayNode) first.get("vectors");
        assertThat(vectors.size(), is(1));
        assertThat(vectors.get(0).get("titleVector").get("vectorSimilarity").doubleValue(), closeTo(1.0, 1e-6));
        assertThat(search("{\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 1) + "]}").results().getFirst()
            .hit().documentDebugInfo(), nullValue());
    }

    @Test
    void vectorAndSemanticDebugInformationShareOneDocumentDebugInfo() {
        LiveIndex index = EngineFixtures.index("""
            {"name": "both", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "title", "type": "Edm.String"},
              {"name": "v", "type": "Collection(Edm.Single)", "dimensions": 2, "vectorSearchProfile": "p"}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "a", "kind": "exhaustiveKnn"}],
              "profiles": [{"name": "p", "algorithm": "a"}]
            },
            "semantic": {"configurations": [{"name": "titles", "prioritizedFields": {
              "titleField": {"fieldName": "title"}}}]}}
            """, "{\"id\":\"1\",\"title\":\"red apple\",\"v\":[1,0]}",
            "{\"id\":\"2\",\"title\":\"green pear\",\"v\":[0,1]}");
        for (String debug : List.of("vector|semantic", "semantic|vector", "all")) {
            ObjectNode body = (ObjectNode) SearchJson.parse(("{\"search\":\"apple\",\"queryType\":\"semantic\","
                + "\"semanticConfiguration\":\"titles\",\"debug\":\"" + debug + "\",\"vectorQueries\":["
                + query("[1,0]", "v", 2) + "]}").getBytes(StandardCharsets.UTF_8));
            SearchOutcome outcome = PIPELINE.run(index, SearchRequestParser.fromBody(body), EngineSettings.DEFAULTS);
            assertThat(outcome.results().size(), is(2));
            for (SearchOutcome.Result result : outcome.results()) {
                JsonNode info = result.hit().documentDebugInfo();
                assertThat(debug, info.get("vectors").get("subscores").get("vectors").isArray(), is(true));
                assertThat(debug, info.get("semantic").isObject(), is(true));
            }
        }
    }

    @Test
    void invalidVectorQueriesAreRejected() {
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":["
            + query("[1,0]", "titleVector", 1) + "]}")).getMessage(),
            containsString("with dimension of '3', expects a length of '3'. However, the provided vector has a "
                + "length of '2'"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":["
            + query("[1,0,0]", "nope", 1) + "]}")).getMessage(), containsString("'nope'"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":["
            + query("[1,0,0]", "title", 1) + "]}")).getMessage(), containsString("not a searchable vector field"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":[{\"kind\":\"text\","
            + "\"text\":\"apple\",\"fields\":\"titleVector\"}]}")).getMessage(), containsString("has no vectorizer"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":[{\"kind\":\"vector\","
            + "\"vector\":[1,0,0],\"fields\":\"titleVector\",\"oversampling\":2}]}")).getMessage(),
            containsString("only permitted when the vector field 'titleVector' uses a compression"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":[{\"kind\":\"vector\","
            + "\"vector\":[1,0,0],\"fields\":\"titleVector\",\"weight\":0}]}")).getMessage(),
            containsString("'weight'"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorFilterMode\":\"sideFilter\","
            + "\"vectorQueries\":[" + query("[1,0,0]", "titleVector", 1) + "]}")).getMessage(),
            containsString("vectorFilterMode"));
        assertThat(assertThrows(SearchException.class, () -> search("{\"vectorQueries\":[{\"kind\":\"vector\","
            + "\"vector\":[1,0,0],\"fields\":\"titleVector,l2Vector\",\"threshold\":{\"kind\":\"vectorSimilarity\","
            + "\"value\":0.5}}]}")).getMessage(), containsString("same similarity metric"));
    }

    @Test
    void packedBinaryVectorsAreComparedByHammingDistance() {
        LiveIndex binary = EngineFixtures.index("""
            {"name": "bits", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "code", "type": "Collection(Edm.Byte)", "vectorEncoding": "packedBit", "dimensions": 16,
               "vectorSearchProfile": "p"}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "a", "kind": "exhaustiveKnn", "exhaustiveKnnParameters": {"metric": "hamming"}}],
              "profiles": [{"name": "p", "algorithm": "a"}]
            }}
            """, "{\"id\":\"same\",\"code\":[255,0]}", "{\"id\":\"one-bit\",\"code\":[254,0]}",
            "{\"id\":\"far\",\"code\":[0,255]}");
        SearchRequest request = SearchRequest.builder().vectorQueries(List.of((ObjectNode) SearchJson.parse(
            query("[255,0]", "code", 3).getBytes(StandardCharsets.UTF_8)))).build();
        SearchOutcome outcome = PIPELINE.run(binary, request, EngineSettings.DEFAULTS);
        assertThat(keys(outcome), contains("same", "one-bit", "far"));
        assertThat(score(outcome, 1), closeTo(1.0 / 2, 1e-9));
        assertThat(score(outcome, 2), closeTo(1.0 / 17, 1e-9));
        assertThat(assertThrows(SearchException.class, () -> PIPELINE.run(binary, SearchRequest.builder()
            .vectorQueries(List.of((ObjectNode) SearchJson.parse(query("[255,0.5]", "code", 1)
                .getBytes(StandardCharsets.UTF_8)))).build(), EngineSettings.DEFAULTS)).getMessage(),
            containsString("must contain bytes"));
    }

    @Test
    void dotProductRanksByMagnitudeAndAngleWithScoresBetweenZeroAndOne() {
        LiveIndex dots = EngineFixtures.index("""
            {"name": "dots", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "v", "type": "Collection(Edm.Single)", "dimensions": 2, "vectorSearchProfile": "p"}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "a", "kind": "hnsw", "hnswParameters": {"metric": "dotProduct"}}],
              "profiles": [{"name": "p", "algorithm": "a"}]
            }}
            """, "{\"id\":\"long\",\"v\":[3,0]}", "{\"id\":\"unit\",\"v\":[1,0]}", "{\"id\":\"opposite\",\"v\":[-2,0]}");
        SearchRequest request = SearchRequest.builder().vectorQueries(List.of((ObjectNode) SearchJson.parse(
            query("[1,0]", "v", 3).getBytes(StandardCharsets.UTF_8)))).build();
        SearchOutcome outcome = PIPELINE.run(dots, request, EngineSettings.DEFAULTS);
        assertThat(keys(outcome), contains("long", "unit", "opposite"));
        for (int position = 0; position < 3; position++) {
            assertThat(score(outcome, position) > 0 && score(outcome, position) < 1, is(true));
        }
    }
    @Test
    void aVectorFieldInAComplexCollectionMatchesThroughItsClosestVector() {
        LiveIndex chunked = EngineFixtures.index("""
            {"name": "chunked", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "chunks", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "text", "type": "Edm.String"},
                {"name": "vector", "type": "Collection(Edm.Single)", "dimensions": 2, "vectorSearchProfile": "p"}
              ]}
            ],
            "vectorSearch": {
              "algorithms": [{"name": "a", "kind": "exhaustiveKnn"}],
              "profiles": [{"name": "p", "algorithm": "a"}]
            }}
            """, "{\"id\":\"diagonal\",\"chunks\":[{\"text\":\"d\",\"vector\":[0.7,0.7]}]}",
            "{\"id\":\"axes\",\"chunks\":[{\"text\":\"y\",\"vector\":[0,1]},{\"text\":\"x\",\"vector\":[1,0]}]}");
        SearchRequest request = SearchRequest.builder().vectorQueries(List.of((ObjectNode) SearchJson.parse(
            query("[1,0]", "chunks/vector", 2).getBytes(StandardCharsets.UTF_8)))).build();
        SearchOutcome outcome = PIPELINE.run(chunked, request, EngineSettings.DEFAULTS);
        assertThat(keys(outcome), contains("axes", "diagonal"));
        assertThat(score(outcome, 0), closeTo(1.0, 1e-6));
    }
    @Test
    void theNextPageEchoesTheVectorQueriesAsSent() {
        SearchOutcome outcome = search("{\"top\":1,\"skip\":0,\"vectorQueries\":["
            + query("[1,0,0]", "titleVector", 3) + "]}");
        assertThat(keys(outcome), contains("1"));
        SearchRequest next = SearchRequest.builder().vectorQueries(List.of((ObjectNode) SearchJson.parse(
            query("[1,0,0]", "titleVector", 3).getBytes(StandardCharsets.UTF_8)))).skip(1).top(1).build();
        assertThat(keys(PIPELINE.run(index(), next, EngineSettings.DEFAULTS)), contains("2"));
    }
}
