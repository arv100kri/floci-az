package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticStageTest {

    private static final String DEFINITION = """
        {"name": "hotels", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "name", "type": "Edm.String"},
          {"name": "description", "type": "Edm.String"},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "rating", "type": "Edm.Int32", "sortable": true}
        ],
        "scoringProfiles": [{"name": "boost", "text": {"weights": {"name": 2}}}],
        "semantic": {"defaultConfiguration": "default", "configurations": [
          {"name": "default", "prioritizedFields": {"titleField": {"fieldName": "name"},
            "prioritizedContentFields": [{"fieldName": "description"}],
            "prioritizedKeywordsFields": [{"fieldName": "tags"}]}},
          {"name": "plain", "rankingOrder": "RerankerScore", "prioritizedFields": {"titleField": {"fieldName": "name"},
            "prioritizedContentFields": [{"fieldName": "description"}],
            "prioritizedKeywordsFields": [{"fieldName": "tags"}]}},
          {"name": "titles", "prioritizedFields": {"titleField": {"fieldName": "name"}}}
        ]}}
        """;

    private static final String[] HOTELS = {
        "{\"id\": \"1\", \"name\": \"Ocean View Resort\", \"description\": \"Luxury resort with a pool and an ocean "
            + "view. Breakfast is served on the terrace.\", \"tags\": [\"pool\", \"view\", \"wifi\"], \"rating\": 5}",
        "{\"id\": \"2\", \"name\": \"Budget Inn\", \"description\": \"Cheap motel near the airport with free "
            + "parking.\", \"tags\": [\"parking\", \"wifi\"], \"rating\": 2}",
        "{\"id\": \"3\", \"name\": \"City Center Hotel\", \"description\": \"Modern hotel in the city center with a "
            + "rooftop pool. The rooftop pool is open all year.\", \"tags\": [\"pool\", \"gym\"], \"rating\": 4}",
        "{\"id\": \"4\", \"name\": \"Mountain Lodge\", \"description\": \"Rustic lodge with hiking trails and a stone "
            + "fireplace.\", \"tags\": [\"hiking\", \"fireplace\"], \"rating\": 3}",
        "{\"id\": \"5\", \"name\": \"Airport Suites\", \"description\": \"Convenient suites next to the airport "
            + "terminal. A shuttle runs to the airport every hour.\", \"tags\": [\"wifi\", \"shuttle\"], "
            + "\"rating\": 3}",
        "{\"id\": \"6\", \"name\": \"Harbor Boutique Hotel\", \"description\": \"Small boutique hotel on the harbor "
            + "with a view of the boats.\", \"tags\": [\"boutique\", \"view\"], \"rating\": 4}"
    };

    private static final LiveIndex INDEX = EngineFixtures.index(DEFINITION, HOTELS);

    private static SearchOutcome run(SearchRequest request) {
        return SearchPipeline.standard().run(INDEX, request, EngineSettings.DEFAULTS);
    }

    private static SearchRequest.Builder semantic(String search) {
        return SearchRequest.builder().search(search).queryType(QueryType.SEMANTIC);
    }

    private static List<String> keys(SearchOutcome outcome) {
        return outcome.results().stream().map(result -> result.document().get("id").asText()).toList();
    }

    private static SearchOutcome.Result result(SearchOutcome outcome, String key) {
        return outcome.results().stream().filter(result -> result.document().get("id").asText().equals(key))
            .findFirst().orElseThrow();
    }

    @Test
    void theStandInReranksByThePrioritizedFields() {
        SearchOutcome outcome = run(semantic("hotel with a rooftop pool").build());
        assertThat(keys(outcome).subList(0, 3), contains("3", "6", "1"));
        assertEquals("stand-in", outcome.headers().get(SemanticStage.STAND_IN_HEADER));
        List<Double> scores = outcome.results().stream().map(result -> result.hit().rerankerScore()).toList();
        assertThat(scores, everyItem(notNullValue()));
        assertThat(scores, everyItem(greaterThanOrEqualTo(0.0)));
        assertThat(scores, everyItem(lessThanOrEqualTo(4.0)));
        for (int index = 1; index < scores.size(); index++) {
            assertTrue(scores.get(index - 1) >= scores.get(index), "reranker scores must descend: " + scores);
        }
        assertThat(result(outcome, "3").hit().rerankerScore(), closeTo(4.0 * (3 * (1.0 / 3) + 2 * 1.0 + 1.0 / 3) / 6,
            1e-9));
        assertThat(result(outcome, "3").hit().rerankerBoostedScore(), nullValue());
    }

    @Test
    void theStandInIsDeterministic() {
        SearchRequest request = semantic("pool with a view").captions("extractive").build();
        SearchOutcome first = run(request);
        SearchOutcome second = run(request);
        assertEquals(keys(first), keys(second));
        for (int index = 0; index < first.results().size(); index++) {
            SearchHit a = first.results().get(index).hit();
            SearchHit b = second.results().get(index).hit();
            assertEquals(a.rerankerScore(), b.rerankerScore());
            assertEquals(a.captions().toString(), b.captions().toString());
        }
    }

    @Test
    void equalRerankerScoresKeepTheL1Order() {
        SearchOutcome outcome = run(semantic("wifi").build());
        List<String> keys = keys(outcome);
        assertThat(keys, contains("2", "5", "1"));
        SearchOutcome l1 = run(SearchRequest.builder().search("wifi").build());
        assertThat(keys(l1), contains("2", "5", "1"));
    }

    @Test
    void captionsAreTheBestPassageWithHighlights() {
        SearchOutcome outcome = run(semantic("hotel with a rooftop pool").captions("extractive|highlight-true")
            .highlightPreTag("<b>").highlightPostTag("</b>").build());
        List<ObjectNode> captions = result(outcome, "3").hit().captions();
        assertThat(captions, hasSize(1));
        assertEquals("Modern hotel in the city center with a rooftop pool. The rooftop pool is open all year.",
            captions.get(0).get("text").asText());
        assertEquals("Modern <b>hotel</b> in the city center with a <b>rooftop pool</b>. The <b>rooftop pool</b> is "
            + "open all year.", captions.get(0).get("highlights").asText());
        ObjectNode unmatched = result(outcome, "4").hit().captions().get(0);
        assertEquals("Rustic lodge with hiking trails and a stone fireplace.", unmatched.get("text").asText());
        assertEquals("", unmatched.get("highlights").asText());

        SearchOutcome plain = run(semantic("rooftop pool").captions("extractive|highlight-false,maxcharlength-30")
            .build());
        ObjectNode caption = result(plain, "3").hit().captions().get(0);
        assertEquals("Modern hotel in the city", caption.get("text").asText());
        assertTrue(caption.get("highlights").isNull());
    }

    @Test
    void answersComeFromQuestionsAboveTheThreshold() {
        SearchOutcome outcome = run(semantic("Which hotel has a rooftop pool?").answers("extractive|count-3").build());
        ArrayNode answers = outcome.answers();
        assertThat(answers.size(), is(1));
        JsonNode answer = answers.get(0);
        assertEquals("3", answer.get("key").asText());
        assertEquals("Modern hotel in the city center with a rooftop pool. The rooftop pool is open all year.",
            answer.get("text").asText());
        assertThat(answer.get("highlights").asText(), containsString("<em>rooftop pool</em>"));
        assertEquals(1.0, answer.get("score").asDouble());

        ArrayNode lowThreshold = run(semantic("Which hotel has a rooftop pool?")
            .answers("extractive|count-2,threshold-0.3").build()).answers();
        assertThat(lowThreshold.size(), is(2));
        assertEquals("3", lowThreshold.get(0).get("key").asText());

        assertThat(run(semantic("hotel with a rooftop pool").answers("extractive").build()).answers().size(), is(0));
        assertThat(run(semantic("hotel").build()).answers(), nullValue());
    }

    @Test
    void semanticQueryReranksAnotherQueryType() {
        SearchOutcome outcome = run(SearchRequest.builder().search("pool").semanticQuery("rooftop pool in the city")
            .build());
        assertThat(keys(outcome), contains("3", "1"));
        assertThat(result(outcome, "1").hit().rerankerScore(), notNullValue());
        assertEquals("stand-in", outcome.headers().get(SemanticStage.STAND_IN_HEADER));
    }

    @Test
    void anEmptySearchIsNotReranked() {
        SearchOutcome outcome = run(semantic("*").answers("extractive").captions("extractive").build());
        assertThat(outcome.results(), hasSize(6));
        assertTrue(outcome.results().stream().allMatch(result -> result.hit().rerankerScore() == null));
        assertTrue(outcome.results().stream().allMatch(result -> result.hit().captions() == null));
        assertThat(outcome.answers().size(), is(0));
        assertEquals("stand-in", outcome.headers().get(SemanticStage.STAND_IN_HEADER));
    }

    @Test
    void onlyTheTopFiftyAreReranked() {
        List<String> documents = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            documents.add("{\"id\": \"" + String.format("%02d", index) + "\", \"name\": \"Item " + index
                + "\", \"description\": \"An item.\"}");
        }
        LiveIndex items = EngineFixtures.index(DEFINITION, documents.toArray(String[]::new));
        SearchOutcome outcome = SearchPipeline.standard().run(items, semantic("item").top(60).count(true).build(),
            EngineSettings.DEFAULTS);
        assertThat(outcome.results(), hasSize(60));
        assertThat(outcome.count(), is(60L));
        List<SearchOutcome.Result> results = outcome.results();
        assertTrue(results.subList(0, 50).stream().allMatch(result -> result.hit().rerankerScore() != null));
        assertTrue(results.subList(50, 60).stream().allMatch(result -> result.hit().rerankerScore() == null));
    }

    @Test
    void pagesFollowTheRerankedOrder() {
        List<String> all = keys(run(semantic("hotel with a rooftop pool").top(4).build()));
        List<String> paged = new ArrayList<>(keys(run(semantic("hotel with a rooftop pool").top(2).build())));
        paged.addAll(keys(run(semantic("hotel with a rooftop pool").top(2).skip(2).build())));
        assertEquals(all, paged);
    }

    @Test
    void aScoringProfileAddsTheBoostedScoreAndRanksByIt() {
        SearchStage functionBoost = new SearchStage() {
            @Override
            public void apply(SearchExecution execution) {
                for (SearchHit hit : execution.hits()) {
                    if (execution.index().document(hit.ordinal()).key().equals("1")) {
                        hit.score(hit.score() * 10);
                    }
                }
            }
        };
        SearchPipeline pipeline = new SearchPipeline(List.of(new FilterStage(), new TextRetrievalStage(),
            new VectorRetrievalStage(), new FusionStage(), new ScoringProfileStage(), functionBoost,
            new FacetStage(), new SemanticStage(), new SortAndPageStage(), new HighlightStage()),
            new NotImplementedSuggestStage());

        SearchOutcome boosted = pipeline.run(INDEX, semantic("hotel with a rooftop pool").scoringProfile("boost")
            .build(), EngineSettings.DEFAULTS);
        SearchHit resort = result(boosted, "1").hit();
        assertThat(resort.rerankerBoostedScore(), closeTo(resort.rerankerScore() * 10, 1e-9));
        assertEquals("1", keys(boosted).get(0));
        SearchHit hotel = result(boosted, "3").hit();
        assertEquals(hotel.rerankerScore(), hotel.rerankerBoostedScore());

        SearchOutcome plain = pipeline.run(INDEX, semantic("hotel with a rooftop pool").scoringProfile("boost")
            .semanticConfiguration("plain").build(), EngineSettings.DEFAULTS);
        assertEquals("3", keys(plain).get(0));
        assertThat(result(plain, "1").hit().rerankerBoostedScore(), notNullValue());
    }

    @Test
    void debugSemanticReportsTheInputs() {
        SearchOutcome outcome = run(semantic("rooftop pool").debug("semantic").build());
        JsonNode semantic = result(outcome, "3").hit().documentDebugInfo().get("semantic");
        assertEquals("name", semantic.get("titleField").get("name").asText());
        assertEquals("used", semantic.get("titleField").get("state").asText());
        assertEquals("description", semantic.get("contentFields").get(0).get("name").asText());
        assertEquals("tags", semantic.get("keywordFields").get(0).get("name").asText());
        assertEquals("City Center Hotel", semantic.get("rerankerInput").get("title").asText());
        assertEquals("pool, gym", semantic.get("rerankerInput").get("keywords").asText());
    }

    @Test
    void queryRewritesReportTheOriginalQueryOnly() {
        SearchOutcome outcome = run(semantic("rooftop pool").queryRewrites("generative|count-5").queryLanguage("en-us")
            .debug("queryRewrites").build());
        assertEquals("originalQueryOnly", outcome.annotations().get("@search.semanticQueryRewritesResultType")
            .asText());
        JsonNode debug = outcome.annotations().get("@search.debug");
        assertTrue(debug.get("semantic").isNull());
        assertThat(debug.get("queryRewrites").get("text").get("rewrites").size(), is(0));
        assertThat(debug.get("queryRewrites").get("vectors").size(), is(0));
        assertThrows(SearchException.class, () -> run(semantic("rooftop pool").queryRewrites("generative").build()),
            "query rewrites need queryLanguage");
    }

    @Test
    void theConfigurationComesFromTheRequestOrTheDefault() {
        SearchOutcome titles = run(semantic("hotel with a rooftop pool").semanticConfiguration("titles").build());
        assertThat(result(titles, "1").hit().rerankerScore(), is(0.0));
        assertThat(badRequest(semantic("pool").semanticConfiguration("missing").build()),
            containsString("'missing' is not defined"));
        LiveIndex noDefault = EngineFixtures.index(DEFINITION.replace("\"defaultConfiguration\": \"default\", ", ""),
            HOTELS);
        SearchException missing = assertThrows(SearchException.class, () -> SearchPipeline.standard().run(noDefault,
            semantic("pool").build(), EngineSettings.DEFAULTS));
        assertThat(missing.getMessage(), containsString("semanticConfiguration"));
    }

    @Test
    void invalidSemanticRequestsAre400() {
        assertThat(badRequest(semantic("pool").orderBy("rating desc").build()), containsString("$orderby"));
        assertThat(badRequest(SearchRequest.builder().search("pool").answers("extractive").build()),
            containsString("only valid for semantic queries"));
        assertThat(badRequest(SearchRequest.builder().search("pool").captions("extractive").build()),
            containsString("only valid for semantic queries"));
        assertThat(badRequest(semantic("pool").answers("extractive|count-11").build()), containsString("count"));
        assertThat(badRequest(semantic("pool").answers("extractive|threshold-2").build()), containsString("threshold"));
        assertThat(badRequest(semantic("pool").answers("abstractive").build()), containsString("answers"));
        assertThat(badRequest(semantic("pool").captions("extractive|highlight-maybe").build()),
            containsString("highlight"));
        assertThat(badRequest(semantic("pool").semanticErrorHandling("ignore").build()),
            containsString("semanticErrorHandling"));
        assertThat(badRequest(semantic("pool").semanticMaxWaitInMilliseconds(500).build()), containsString("700"));
        assertThat(badRequest(semantic("pool").semanticFields(List.of("nope")).build()),
            containsString("semanticFields"));
        SearchOutcome fine = run(semantic("pool").semanticErrorHandling("partial").semanticMaxWaitInMilliseconds(700)
            .answers("none").captions("none").queryRewrites("none").orderBy("").build());
        assertThat(fine.results().get(0).hit().rerankerScore(), notNullValue());
    }

    @Test
    void semanticFieldsKeepOnlyTheFieldsTheyName() {
        SearchOutcome outcome = run(semantic("rooftop pool").semanticFields(List.of("name")).build());
        assertTrue(outcome.results().stream().map(result -> result.hit().rerankerScore()).filter(Objects::nonNull)
            .allMatch(score -> score == 0.0), "no title holds the query's terms");
    }

    @Test
    void aDisabledSemanticRankerRefusesSemanticQueries() {
        EngineSettings disabled = new EngineSettings(100_000, false, null);
        SearchException refused = assertThrows(SearchException.class, () -> SearchPipeline.standard().run(INDEX,
            semantic("pool").build(), disabled));
        assertEquals(400, refused.status());
        assertThat(refused.getMessage(), containsString("Semantic search is not enabled for this service."));
        SearchException semanticQuery = assertThrows(SearchException.class, () -> SearchPipeline.standard().run(INDEX,
            SearchRequest.builder().search("pool").semanticQuery("pool").build(), disabled));
        assertThat(semanticQuery.getMessage(), containsString("semanticQuery"));
        assertThat(SearchPipeline.standard().run(INDEX, SearchRequest.builder().search("pool").build(), disabled)
            .results(), hasSize(2));
    }

    @Test
    void inputsAreCutAtTheDocumentedTokenLimits() {
        String title = "word ".repeat(130).strip();
        String description = "text ".repeat(2000).strip();
        LiveIndex lengthy = EngineFixtures.index(DEFINITION, "{\"id\": \"1\", \"name\": \"" + title
            + "\", \"description\": \"" + description + "\", \"tags\": [\"word\"]}");
        SearchOutcome outcome = SearchPipeline.standard().run(lengthy, semantic("word").debug("semantic").build(),
            EngineSettings.DEFAULTS);
        JsonNode semantic = outcome.results().get(0).hit().documentDebugInfo().get("semantic");
        assertEquals("partial", semantic.get("titleField").get("state").asText());
        assertEquals(128, semantic.get("rerankerInput").get("title").asText().split(" ").length);
        assertEquals("used", semantic.get("keywordFields").get(0).get("state").asText());
        assertEquals("partial", semantic.get("contentFields").get(0).get("state").asText());
        assertEquals(2048 - 128 - 1, semantic.get("rerankerInput").get("content").asText().split(" ").length);
    }

    private static String badRequest(SearchRequest request) {
        SearchException exception = assertThrows(SearchException.class, () -> run(request));
        assertEquals(400, exception.status());
        return exception.getMessage();
    }
}
