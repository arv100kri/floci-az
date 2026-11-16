package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoringProfileStageTest {

    private static final String DEFINITION = """
        {"name": "scored", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "title", "type": "Edm.String"},
          {"name": "body", "type": "Edm.String"},
          {"name": "rating", "type": "Edm.Int32"},
          {"name": "renovated", "type": "Edm.DateTimeOffset"},
          {"name": "location", "type": "Edm.GeographyPoint"},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "secret", "type": "Edm.Int32", "filterable": false}
        ],
        "defaultScoringProfile": "titleBoost",
        "scoringProfiles": [
          {"name": "titleBoost", "text": {"weights": {"title": 5}}},
          {"name": "plain"},
          %s
        ]}
        """;

    private static String magnitude(String name, String interpolation, int start, int end, boolean beyond) {
        return "{\"name\": \"" + name + "\", \"functions\": [{\"type\": \"magnitude\", \"fieldName\": \"rating\", "
            + "\"boost\": 10, \"interpolation\": \"" + interpolation + "\", \"magnitude\": {\"boostingRangeStart\": "
            + start + ", \"boostingRangeEnd\": " + end + ", \"constantBoostBeyondRange\": " + beyond + "}}]}";
    }

    private static final String PROFILES = String.join(",\n",
        magnitude("linear", "linear", 1, 5, false),
        magnitude("constant", "constant", 1, 5, false),
        magnitude("quadratic", "quadratic", 1, 5, false),
        magnitude("logarithmic", "logarithmic", 1, 5, false),
        magnitude("capped", "linear", 1, 3, false),
        magnitude("beyond", "linear", 1, 3, true),
        magnitude("reversed", "linear", 5, 1, false),
        """
        {"name": "near", "functions": [{"type": "distance", "fieldName": "location", "boost": 5,
          "distance": {"referencePointParameter": "here", "boostingDistance": 10}}]}""",
        """
        {"name": "fresh", "functions": [{"type": "freshness", "fieldName": "renovated", "boost": 3,
          "freshness": {"boostingDuration": "P365D"}}]}""",
        """
        {"name": "tagged", "functions": [{"type": "tag", "fieldName": "tags", "boost": 2,
          "tag": {"tagsParameter": "mytags"}}]}""",
        """
        {"name": "combined", "functionAggregation": "%s", "functions": [
          {"type": "tag", "fieldName": "tags", "boost": 2, "tag": {"tagsParameter": "mytags"}},
          {"type": "magnitude", "fieldName": "rating", "boost": 5, "interpolation": "constant",
           "magnitude": {"boostingRangeStart": 4, "boostingRangeEnd": 5}}]}""");

    private static LiveIndex index(String aggregation, String... documents) {
        return EngineFixtures.index(DEFINITION.formatted(PROFILES.formatted(aggregation)), documents);
    }

    private static final LiveIndex RATED = index("sum",
        "{\"id\":\"r1\",\"title\":\"lake cabin\",\"rating\":1}",
        "{\"id\":\"r3\",\"title\":\"lake cabin\",\"rating\":3}",
        "{\"id\":\"r5\",\"title\":\"lake cabin\",\"rating\":5}",
        "{\"id\":\"none\",\"title\":\"lake cabin\"}");

    private static Map<String, Double> scores(LiveIndex index, SearchRequest request) {
        Map<String, Double> scores = new LinkedHashMap<>();
        SearchPipeline.standard().run(index, request, EngineSettings.DEFAULTS).results()
            .forEach(result -> scores.put(result.document().get("id").asText(), result.hit().score()));
        return scores;
    }

    private static Map<String, Double> scores(LiveIndex index, String search, String profile, String... parameters) {
        return scores(index, SearchRequest.builder().search(search).scoringProfile(profile)
            .scoringParameters(List.of(parameters)).build());
    }

    @Test
    void textWeightsMultiplyTheFieldsScoreAndTheDefaultProfileApplies() {
        LiveIndex index = index("sum",
            "{\"id\":\"in-title\",\"title\":\"harbor view\",\"body\":\"quiet inn\"}",
            "{\"id\":\"in-body\",\"title\":\"quiet inn\",\"body\":\"harbor view\"}");
        Map<String, Double> plain = scores(index, "harbor", "plain");
        Map<String, Double> weighted = scores(index, "harbor", "titleBoost");
        assertThat(weighted.get("in-title"), closeTo(5 * plain.get("in-title"), 1e-9));
        assertThat(weighted.get("in-body"), closeTo(plain.get("in-body"), 1e-9));
        assertThat(scores(index, "harbor", null).keySet(), contains("in-title", "in-body"));
    }

    @Test
    void magnitudeFunctionsInterpolateAcrossTheRange() {
        double base = scores(RATED, "cabin", "plain").get("r1");
        Map<String, Double> linear = scores(RATED, "cabin", "linear");
        assertThat(linear.keySet(), contains("r5", "r3", "r1", "none"));
        assertThat(linear.get("r5"), closeTo(10 * base, 1e-9));
        assertThat(linear.get("r3"), closeTo(5.5 * base, 1e-9));
        assertThat(linear.get("r1"), closeTo(base, 1e-9));
        assertThat(linear.get("none"), closeTo(base, 1e-9));
        assertThat(scores(RATED, "cabin", "constant").get("r3"), closeTo(10 * base, 1e-9));
        assertThat(scores(RATED, "cabin", "quadratic").get("r3"), closeTo(7.75 * base, 1e-9));
        assertThat(scores(RATED, "cabin", "logarithmic").get("r3"),
            closeTo((1 + 9 * (1 - Math.log10(5.5))) * base, 1e-9));
        assertThat(scores(RATED, "cabin", "capped").get("r5"), closeTo(base, 1e-9));
        assertThat(scores(RATED, "cabin", "beyond").get("r5"), closeTo(10 * base, 1e-9));
        assertThat(scores(RATED, "cabin", "reversed").keySet(), contains("r1", "r3", "r5", "none"));
    }

    @Test
    void distanceFreshnessAndTagFunctionsBoostWhatIsNearRecentOrTagged() {
        Instant now = Instant.now();
        LiveIndex index = index("sum",
            "{\"id\":\"near\",\"title\":\"inn\",\"location\":{\"type\":\"Point\",\"coordinates\":[-122.33,47.61]},"
                + "\"renovated\":\"" + now.minus(Duration.ofDays(10)) + "\",\"tags\":[\"pool\"]}",
            "{\"id\":\"far\",\"title\":\"inn\",\"location\":{\"type\":\"Point\",\"coordinates\":[-105.27,40.01]},"
                + "\"renovated\":\"" + now.minus(Duration.ofDays(400)) + "\",\"tags\":[\"gym\"]}");
        double base = scores(index, "inn", "plain").get("near");
        Map<String, Double> near = scores(index, "inn", "near", "here--122.33,47.61");
        assertThat(near.keySet(), contains("near", "far"));
        assertThat(near.get("near"), closeTo(5 * base, 1e-6));
        assertThat(near.get("far"), closeTo(base, 1e-9));
        Map<String, Double> fresh = scores(index, "inn", "fresh");
        assertThat(fresh.get("near"), greaterThan(2.9 * base));
        assertThat(fresh.get("far"), closeTo(base, 1e-9));
        Map<String, Double> tagged = scores(index, "*", "tagged", "mytags-pool,'spa, sauna'");
        assertThat(tagged.get("near"), closeTo(2, 1e-9));
        assertThat(tagged.get("far"), closeTo(1, 1e-9));
    }

    @Test
    void functionAggregationCombinesTheFunctions() {
        String[] documents = {"{\"id\":\"both\",\"title\":\"inn\",\"rating\":5,\"tags\":[\"pool\"]}",
            "{\"id\":\"rated\",\"title\":\"inn\",\"rating\":5}",
            "{\"id\":\"tagged\",\"title\":\"inn\",\"rating\":1,\"tags\":[\"pool\"]}"};
        Map<String, Map<String, Double>> byAggregation = new LinkedHashMap<>();
        for (String aggregation : List.of("sum", "average", "minimum", "maximum", "firstMatching", "product")) {
            byAggregation.put(aggregation, scores(index(aggregation, documents), "*", "combined", "mytags-pool"));
        }
        assertThat(byAggregation.get("sum").get("both"), closeTo(1 + 1 + 4, 1e-9));
        assertThat(byAggregation.get("average").get("both"), closeTo(1 + 2.5, 1e-9));
        assertThat(byAggregation.get("minimum").get("rated"), closeTo(1, 1e-9));
        assertThat(byAggregation.get("maximum").get("both"), closeTo(5, 1e-9));
        assertThat(byAggregation.get("firstMatching").get("both"), closeTo(2, 1e-9));
        assertThat(byAggregation.get("firstMatching").get("rated"), closeTo(5, 1e-9));
        assertThat(byAggregation.get("product").get("both"), closeTo(10, 1e-9));
    }

    @Test
    void unknownProfilesAndMissingParametersAreBadRequests() {
        assertThat(assertThrows(SearchException.class, () -> scores(RATED, "cabin", "nope")).getMessage(),
            containsString("Unknown scoring profile 'nope'"));
        assertThat(assertThrows(SearchException.class, () -> scores(RATED, "cabin", "near")).getMessage(),
            containsString("'here'"));
        assertThrows(SearchException.class, () -> scores(RATED, "cabin", "near", "here-north"));
        assertThrows(SearchException.class, () -> scores(RATED, "cabin", "near", "here--200,10"));
        assertThrows(SearchException.class, () -> scores(RATED, "cabin", "tagged", "nodash"));
        assertThrows(SearchException.class, () -> scores(RATED, "cabin", "tagged", "mytags-'open"));
    }

    @Test
    void invalidProfilesAreRefusedWhenTheIndexIsDefined() {
        for (String profile : List.of(
                "{\"name\": \"p\", \"text\": {\"weights\": {\"nope\": 2}}}",
                "{\"name\": \"p\", \"text\": {\"weights\": {\"rating\": 2}}}",
                "{\"name\": \"p\", \"text\": {\"weights\": {\"title\": 0}}}",
                "{\"name\": \"p\", \"text\": {}}",
                function("Magnitude", "rating", "10", "\"magnitude\": {\"boostingRangeStart\": 1, "
                    + "\"boostingRangeEnd\": 5}"),
                function("magnitude", "title", "10", "\"magnitude\": {\"boostingRangeStart\": 1, "
                    + "\"boostingRangeEnd\": 5}"),
                function("magnitude", "secret", "10", "\"magnitude\": {\"boostingRangeStart\": 1, "
                    + "\"boostingRangeEnd\": 5}"),
                function("magnitude", "rating", "1.0", "\"magnitude\": {\"boostingRangeStart\": 1, "
                    + "\"boostingRangeEnd\": 5}"),
                function("magnitude", "rating", "10", "\"magnitude\": {\"boostingRangeStart\": 1}"),
                function("magnitude", "rating", "10", "\"interpolation\": \"cubic\", \"magnitude\": "
                    + "{\"boostingRangeStart\": 1, \"boostingRangeEnd\": 5}"),
                function("freshness", "renovated", "2", "\"freshness\": {\"boostingDuration\": \"P1Y\"}"),
                function("distance", "location", "2", "\"distance\": {\"referencePointParameter\": \"p\", "
                    + "\"boostingDistance\": 0}"),
                function("tag", "tags", "2", "\"interpolation\": \"quadratic\", \"tag\": {\"tagsParameter\": "
                    + "\"t\"}"),
                function("tag", "tags", "2", "\"tag\": {}"),
                "{\"name\": \"p\", \"functionAggregation\": \"median\"}")) {
            assertThrows(SearchException.class, () -> EngineFixtures.definition(DEFINITION.formatted(profile)),
                profile);
        }
    }

    private static String function(String type, String field, String boost, String settings) {
        return "{\"name\": \"p\", \"functions\": [{\"type\": \"" + type + "\", \"fieldName\": \"" + field
            + "\", \"boost\": " + boost + ", " + settings + "}]}";
    }
}
