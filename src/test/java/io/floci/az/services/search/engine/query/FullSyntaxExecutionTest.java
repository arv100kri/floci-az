package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.HotelsCorpus;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FullSyntaxExecutionTest {

    private static final LiveIndex HOTELS = HotelsCorpus.index();

    private static List<String> lucene(String query) {
        return HotelsCorpus.lucene(HOTELS, query);
    }

    private static Map<String, Double> scores(String query, SearchMode mode) {
        SearchOutcome outcome = HotelsCorpus.run(HOTELS, SearchRequest.builder().search(query)
            .queryType(QueryType.FULL).searchMode(mode).build());
        return outcome.results().stream().collect(Collectors.toMap(result -> result.document().get("id").asText(),
            result -> result.hit().score()));
    }

    @Test
    void fieldedAndBooleanQueries() {
        assertThat(lucene("name:lodge"), contains("4"));
        assertThat(lucene("pool AND view"), contains("1"));
        assertThat(lucene("pool OR view"), containsInAnyOrder("1", "3", "6"));
        assertThat(lucene("pool -rooftop"), contains("1"));
        assertThat(lucene("pool NOT rooftop"), contains("1"));
        assertThat(lucene("description:(pool OR terminal) AND NOT name:resort"), containsInAnyOrder("3", "5"));
        assertThat(lucene("-pool"), empty());
    }

    @Test
    void negationsApplyRegardlessOfTheSearchMode() {
        assertThat(scores("wifi -suites", SearchMode.ANY).keySet(), containsInAnyOrder("1", "2"));
        assertThat(scores("wifi -suites", SearchMode.ALL).keySet(), containsInAnyOrder("1", "2"));
    }

    @Test
    void wildcardRegexFuzzyAndProximity() {
        assertThat(lucene("mo*n"), containsInAnyOrder("3", "4"));
        assertThat(lucene("l?dge"), contains("4"));
        assertThat(lucene("/.*port/"), containsInAnyOrder("2", "5"));
        assertThat(lucene("/[mh]otel/"), containsInAnyOrder("2", "3", "6"));
        assertThat(lucene("/[MH]otel/"), containsInAnyOrder("2", "3", "6"));
        assertThat(lucene("montain~1"), contains("4"));
        assertThat(lucene("montan~1"), empty());
        assertThat(lucene("montan~"), contains("4"));
        assertThat(lucene("\"hotel harbor\"~5"), contains("6"));
        assertThat(lucene("\"hotel harbor\""), empty());
        assertThat(lucene("Moun*"), contains("4"));
    }

    @Test
    void patternQueriesScoreOneAndFuzzyQueriesAreRanked() {
        assertThat(scores("/[mh]otel/", SearchMode.ANY).values(), everyItem(is(1.0)));
        assertThat(scores("air*", SearchMode.ANY).values(), everyItem(is(1.0)));
        Map<String, Double> fuzzy = scores("hotle~", SearchMode.ANY);
        assertThat(fuzzy.keySet(), containsInAnyOrder("2", "3", "6"));
        assertThat(fuzzy.values(), everyItem(not(is(1.0))));
    }

    @Test
    void boostsMultiplyTheScoreOfWhatTheyFollow() {
        double plain = scores("pool", SearchMode.ANY).get("1");
        assertThat(scores("pool^2", SearchMode.ANY).get("1"), closeTo(2 * plain, 1e-9));
        double group = scores("pool OR view", SearchMode.ANY).get("3");
        assertThat(scores("(pool OR view)^0.5", SearchMode.ANY).get("3"), closeTo(0.5 * group, 1e-9));
        Map<String, Double> boosted = scores("description:view^4 pool", SearchMode.ANY);
        assertThat(boosted.get("6"), greaterThan(boosted.get("3")));
    }

    @Test
    void fieldPrefixesMustNameSearchableFields() {
        assertThrows(SearchException.class, () -> lucene("category:Budget"));
        assertThrows(SearchException.class, () -> lucene("nope:x"));
    }
}
