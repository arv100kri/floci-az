package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchMode;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

class Bm25RankingTest {

    private static final String DEFINITION = """
        {"name": "texts", "fields": [
          {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
          {"name": "title", "type": "Edm.String"},
          {"name": "body", "type": "Edm.String"}
        ]%s}
        """;

    private static LiveIndex index(String similarity, String... documents) {
        return EngineFixtures.index(DEFINITION.formatted(similarity), documents);
    }

    private static Map<Integer, Double> scores(LiveIndex index, String query, SearchMode mode) {
        return index.read(live -> {
            List<SearchFieldDefinition> fields = live.definition().fullTextSearchableFields();
            ScoredDocs docs = new QueryExecutor(live, fields, Map.of())
                .execute(new SimpleQueryParser().parse(query, mode));
            return docs == null ? Map.<Integer, Double>of() : docs.asMap();
        });
    }

    private static List<String> ranking(LiveIndex index, String query) {
        Map<Integer, Double> scores = scores(index, query, SearchMode.ANY);
        return index.read(live -> scores.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> live.document(entry.getKey()).key())
            .toList());
    }

    @Test
    void theScoreFollowsTheBm25Formula() {
        Bm25 bm25 = Bm25.of(null, null);
        assertThat(bm25.k1(), is(1.2));
        assertThat(bm25.b(), is(0.75));
        double idf = bm25.idf(4, 1);
        assertThat(idf, closeTo(Math.log(1 + 3.5 / 1.5), 1e-12));
        assertThat(bm25.score(idf, 1, 4, 4.0), closeTo(idf, 1e-12));
        assertThat(bm25.score(idf, 2, 2, 4.0), closeTo(idf * 2 * 2.2 / (2 + 1.2 * (0.25 + 0.75 * 0.5)), 1e-12));
    }

    @Test
    void aSingleMatchScoresExactlyItsBm25() {
        LiveIndex index = index("", "{\"id\":\"1\",\"title\":\"quiet beach hotel\"}", "{\"id\":\"2\",\"title\":\"x\"}",
            "{\"id\":\"3\",\"title\":\"y\"}", "{\"id\":\"4\",\"title\":\"z\"}");
        double expected = Bm25.of(null, null).score(Bm25.of(null, null).idf(4, 1), 1, 3, 1.5);
        assertThat(scores(index, "beach", SearchMode.ANY).get(0), closeTo(expected, 1e-9));
    }

    @Test
    void termFrequencyAndLengthDecideTheOrder() {
        LiveIndex index = index("",
            "{\"id\":\"short\",\"title\":\"hotel pool\"}",
            "{\"id\":\"twice\",\"title\":\"hotel hotel\"}",
            "{\"id\":\"long\",\"title\":\"hotel with a pool and a beach\"}",
            "{\"id\":\"none\",\"title\":\"pool only\"}");
        assertThat(ranking(index, "hotel"), contains("twice", "short", "long"));
    }

    @Test
    void rarerTermsWeighMore() {
        LiveIndex index = index("",
            "{\"id\":\"common\",\"title\":\"hotel\"}",
            "{\"id\":\"rare\",\"title\":\"castle\"}",
            "{\"id\":\"other\",\"title\":\"hotel\"}");
        assertThat(ranking(index, "hotel castle").get(0), is("rare"));
    }

    @Test
    void scoresOfSeveralFieldsAdd() {
        LiveIndex index = index("",
            "{\"id\":\"both\",\"title\":\"hotel\",\"body\":\"hotel\"}",
            "{\"id\":\"title\",\"title\":\"hotel\",\"body\":\"inn\"}");
        Map<Integer, Double> scores = scores(index, "hotel", SearchMode.ANY);
        assertThat(scores.get(0), greaterThan(scores.get(1)));
    }

    @Test
    void theIndexSimilarityOverridesTheDefaults() {
        String noLengthNorm = ", \"similarity\": {\"@odata.type\": \"#Microsoft.Azure.Search.BM25Similarity\", "
            + "\"k1\": 1.2, \"b\": 0}";
        LiveIndex index = index(noLengthNorm,
            "{\"id\":\"short\",\"title\":\"hotel\"}",
            "{\"id\":\"long\",\"title\":\"hotel with many many other words\"}");
        Map<Integer, Double> scores = scores(index, "hotel", SearchMode.ANY);
        assertThat(scores.get(0), closeTo(scores.get(1), 1e-12));
    }

    @Test
    void searchModeAllRequiresEveryTermInSomeField() {
        LiveIndex index = index("",
            "{\"id\":\"1\",\"title\":\"budget hotel\",\"body\":\"with pool\"}",
            "{\"id\":\"2\",\"title\":\"budget hotel\"}");
        assertThat(scores(index, "budget pool", SearchMode.ALL).keySet(), contains(0));
        assertThat(scores(index, "budget pool", SearchMode.ANY).size(), is(2));
    }

    @Test
    void phrasesNeedAdjacentTermsInOrderButMayCrossNoValueBoundary() {
        LiveIndex index = EngineFixtures.index("""
            {"name": "phrases", "fields": [{"name": "id", "type": "Edm.String", "key": true},
              {"name": "tags", "type": "Collection(Edm.String)"}]}
            """,
            "{\"id\":\"1\",\"tags\":[\"free wifi\",\"pool\"]}",
            "{\"id\":\"2\",\"tags\":[\"wifi\",\"free parking\"]}",
            "{\"id\":\"3\",\"tags\":[\"free\",\"wifi\"]}");
        Map<Integer, Double> scores = index.read(live -> new QueryExecutor(live,
            live.definition().fullTextSearchableFields(), Map.of())
            .execute(new QueryNode.Phrase("free wifi", 0)).asMap());
        assertThat(scores.keySet(), contains(0));
    }

    @Test
    void sloppyPhrasesAllowMoves() {
        int[][] positions = {{0}, {2}};
        assertThat(PhraseMatcher.count(positions, new int[] {0, 1}, 0), is(0));
        assertThat(PhraseMatcher.count(positions, new int[] {0, 1}, 1), is(1));
        int[][] swapped = {{1}, {0}};
        assertThat(PhraseMatcher.count(swapped, new int[] {0, 1}, 1), is(0));
        assertThat(PhraseMatcher.count(swapped, new int[] {0, 1}, 2), is(1));
    }

    @Test
    void fuzzyMatchingCountsTranspositionsAsOneEdit() {
        assertThat(QueryExecutor.withinEdits("hotel", "hotle", 1), is(true));
        assertThat(QueryExecutor.withinEdits("hotel", "motel", 1), is(true));
        assertThat(QueryExecutor.withinEdits("hotel", "hostel", 1), is(true));
        assertThat(QueryExecutor.withinEdits("hotel", "hostels", 1), is(false));
    }
}
