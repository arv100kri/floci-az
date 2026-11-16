package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.MatchAll;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleQueryParserTest {

    private final SimpleQueryParser parser = new SimpleQueryParser();

    private static Term any(String text) {
        return new Term(text, Occur.SHOULD);
    }

    private static Term all(String text) {
        return new Term(text, Occur.MUST);
    }

    private static QueryNode not(QueryNode node) {
        return new Bool(List.of(new Clause(Occur.SHOULD, new MatchAll()), new Clause(Occur.MUST_NOT, node)));
    }

    private static Bool bool(Occur occur, QueryNode... nodes) {
        return new Bool(Arrays.stream(nodes).map(node -> new Clause(occur, node)).toList());
    }

    @Test
    void termsCombineWithTheSearchModesDefaultOperator() {
        assertThat(parser.parse("pool ocean", SearchMode.ANY), equalTo(bool(Occur.SHOULD, any("pool"), any("ocean"))));
        assertThat(parser.parse("pool ocean", SearchMode.ALL), equalTo(bool(Occur.MUST, all("pool"), all("ocean"))));
    }

    @Test
    void explicitOperatorsApplyLeftToRight() {
        assertThat(parser.parse("budget hotel +pool", SearchMode.ANY),
            equalTo(bool(Occur.MUST, bool(Occur.SHOULD, any("budget"), any("hotel")), any("pool"))));
        assertThat(parser.parse("a | b + c", SearchMode.ANY),
            equalTo(bool(Occur.MUST, bool(Occur.SHOULD, any("a"), any("b")), any("c"))));
        assertThat(parser.parse("pool+ocean", SearchMode.ANY), equalTo(bool(Occur.MUST, any("pool"), any("ocean"))));
    }

    @Test
    void negationFollowsTheSearchMode() {
        assertThat(parser.parse("pool -ocean", SearchMode.ANY),
            equalTo(bool(Occur.SHOULD, any("pool"), not(any("ocean")))));
        assertThat(parser.parse("pool - ocean", SearchMode.ALL),
            equalTo(bool(Occur.MUST, all("pool"), not(all("ocean")))));
        assertThat(parser.parse("--pool", SearchMode.ANY), equalTo(any("pool")));
    }

    @Test
    void phrasesPrefixesAndGroups() {
        assertThat(parser.parse("\"Roach Motel\"", SearchMode.ANY), equalTo(new Phrase("Roach Motel", 0)));
        assertThat(parser.parse("lingui*", SearchMode.ANY), equalTo(new Prefix("lingui")));
        assertThat(parser.parse("motel+(wifi|luxury)", SearchMode.ANY),
            equalTo(bool(Occur.MUST, any("motel"), bool(Occur.SHOULD, any("wifi"), any("luxury")))));
    }

    @Test
    void escapingAndTheSpecialPlacementsOfMinusAndStar() {
        assertThat(parser.parse("luxury\\+hotel", SearchMode.ANY), equalTo(any("luxury+hotel")));
        assertThat(parser.parse("3352CDD0-EF30-4A2E", SearchMode.ANY), equalTo(any("3352CDD0-EF30-4A2E")));
        assertThat(parser.parse("4*4=16", SearchMode.ANY), equalTo(any("4*4=16")));
        assertThat(parser.parse("wild\\*", SearchMode.ANY), equalTo(any("wild*")));
        assertThat(parser.parse("\\-minus", SearchMode.ANY), equalTo(any("-minus")));
    }

    @Test
    void emptyAndMalformedInputIsInterpreted() {
        assertThat(parser.parse("*", SearchMode.ANY), instanceOf(MatchAll.class));
        assertThat(parser.parse("   ", SearchMode.ANY), instanceOf(MatchAll.class));
        assertThat(parser.parse("(pool", SearchMode.ANY), equalTo(any("pool")));
        assertThat(parser.parse("pool)", SearchMode.ANY), equalTo(any("pool")));
        assertThat(parser.parse("\"open phrase", SearchMode.ANY), equalTo(new Phrase("open phrase", 0)));
        assertThat(parser.parse("fuzzy~1", SearchMode.ANY), equalTo(any("fuzzy~1")));
    }

    @Test
    void tooManyClausesAreRefused() {
        assertThrows(SearchException.class, () -> parser.parse("a ".repeat(1025), SearchMode.ANY));
    }
}
