package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Boost;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.FieldScoped;
import io.floci.az.services.search.engine.query.QueryNode.Fuzzy;
import io.floci.az.services.search.engine.query.QueryNode.MatchAll;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Regex;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.engine.query.QueryNode.Wildcard;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuceneQueryParserTest {

    private final LuceneQueryParser parser = new LuceneQueryParser();

    private QueryNode any(String query) {
        return parser.parse(query, SearchMode.ANY);
    }

    private QueryNode all(String query) {
        return parser.parse(query, SearchMode.ALL);
    }

    private static Term term(String text) {
        return new Term(text, Occur.SHOULD);
    }

    private static Clause should(QueryNode node) {
        return new Clause(Occur.SHOULD, node);
    }

    private static Clause must(QueryNode node) {
        return new Clause(Occur.MUST, node);
    }

    private static Clause mustNot(QueryNode node) {
        return new Clause(Occur.MUST_NOT, node);
    }

    private static Bool bool(Clause... clauses) {
        return new Bool(List.of(clauses));
    }

    @Test
    void wordsCombineWithTheSearchModesOperator() {
        assertThat(any("pool view"), equalTo(bool(should(term("pool")), should(term("view")))));
        assertThat(all("pool view"), equalTo(bool(must(new Term("pool", Occur.MUST)),
            must(new Term("view", Occur.MUST)))));
    }

    @Test
    void textAndCharacterOperatorsAreEquivalent() {
        Bool and = bool(must(term("pool")), must(term("view")));
        assertThat(any("pool AND view"), equalTo(and));
        assertThat(any("pool && view"), equalTo(and));
        Bool or = bool(should(term("pool")), should(term("view")));
        assertThat(any("pool OR view"), equalTo(or));
        assertThat(any("pool || view"), equalTo(or));
        Bool not = bool(should(term("pool")), mustNot(term("view")));
        assertThat(any("pool NOT view"), equalTo(not));
        assertThat(any("pool !view"), equalTo(not));
        assertThat(any("pool -view"), equalTo(not));
        assertThat(any("+pool view"), equalTo(bool(must(term("pool")), should(term("view")))));
    }

    @Test
    void operatorsApplyLeftToRightLikeTheLuceneParser() {
        assertThat(any("a AND b OR c"), equalTo(bool(must(term("a")), must(term("b")), should(term("c")))));
        Term a = new Term("a", Occur.MUST);
        Term b = new Term("b", Occur.MUST);
        Term c = new Term("c", Occur.MUST);
        assertThat(all("a AND b OR c"), equalTo(bool(must(a), should(b), should(c))));
        assertThat(all("a OR b c"), equalTo(bool(should(a), should(b), must(c))));
        assertThat(any("wifi AND NOT luxury"), equalTo(bool(must(term("wifi")), mustNot(term("luxury")))));
    }

    @Test
    void aNegationAloneMatchesNothing() {
        assertThat(any("-luxury"), equalTo(bool(mustNot(term("luxury")))));
        assertThat(any("NOT luxury"), equalTo(bool(mustNot(term("luxury")))));
    }

    @Test
    void groupsAndFieldPrefixes() {
        assertThat(any("motel AND (wifi OR luxury)"),
            equalTo(bool(must(term("motel")), must(bool(should(term("wifi")), should(term("luxury")))))));
        assertThat(any("title:hotel"), equalTo(new FieldScoped("title", term("hotel"))));
        assertThat(any("artists:(\"Miles Davis\" \"John Coltrane\")"), equalTo(new FieldScoped("artists",
            bool(should(new Phrase("Miles Davis", 0)), should(new Phrase("John Coltrane", 0))))));
        assertThat(any("Address/City:Seattle"), equalTo(new FieldScoped("Address/City", term("Seattle"))));
        assertThat(any("Description: \"hotel airport\"~5"),
            equalTo(new FieldScoped("Description", new Phrase("hotel airport", 5))));
        assertThat(any("title:The Right Way"),
            equalTo(bool(should(new FieldScoped("title", term("The"))), should(term("Right")), should(term("Way")))));
    }

    @Test
    void proximityFuzzyBoostsRegexAndWildcards() {
        assertThat(any("\"hotel airport\"~5"), equalTo(new Phrase("hotel airport", 5)));
        assertThat(any("blue~"), equalTo(new Fuzzy("blue", 2)));
        assertThat(any("blue~1"), equalTo(new Fuzzy("blue", 1)));
        assertThat(any("blue~1^3"), equalTo(new Boost(new Fuzzy("blue", 1), 3)));
        assertThat(any("tax^2"), equalTo(new Boost(term("tax"), 2)));
        assertThat(any("\"deferred tax\"^2"), equalTo(new Boost(new Phrase("deferred tax", 0), 2)));
        assertThat(any("(deferred OR tax)^2"),
            equalTo(new Boost(bool(should(term("deferred")), should(term("tax"))), 2)));
        assertThat(any("/[mh]otel/"), equalTo(new Regex("[mh]otel")));
        assertThat(any("/.*microsoft.com\\/azure\\/.*/"), equalTo(new Regex(".*microsoft.com/azure/.*")));
        assertThat(any("alpha*"), equalTo(new Prefix("alpha")));
        assertThat(any("non*al"), equalTo(new Wildcard("non*al")));
        assertThat(any("te?t"), equalTo(new Wildcard("te?t")));
        assertThat(any("HotelName:sc*"), equalTo(new FieldScoped("HotelName", new Prefix("sc"))));
    }

    @Test
    void escapesKeepSpecialCharactersInTerms() {
        assertThat(any("\\+1 \\(800\\) 642\\-7676"),
            equalTo(bool(should(term("+1")), should(term("(800)")), should(term("642-7676")))));
        assertThat(any("https\\:\\/\\/*"), equalTo(new Prefix("https://")));
        assertThat(any("wi-fi"), equalTo(term("wi-fi")));
        assertThat(any("a|b"), equalTo(term("a|b")));
        assertThat(any("wild\\*"), equalTo(term("wild*")));
    }

    @Test
    void everythingMatchesOnlyAsAWholeQuery() {
        assertThat(any("*"), instanceOf(MatchAll.class));
        assertThat(any("*:*"), instanceOf(MatchAll.class));
        assertThat(any("   "), instanceOf(MatchAll.class));
    }

    @Test
    void malformedQueriesAreBadRequestsWithAPosition() {
        for (String query : List.of("*abc", "?bc", "pool AND", "AND pool", "(pool", "pool)", "\"open", "/open",
                "[a TO b]", "blue~3", "blue~1.5", "tax^", "title:", "a OR OR b", "pool *", "-luxury *", "()")) {
            SearchException error = assertThrows(SearchException.class, () -> any(query), query);
            assertThat(query, error.getMessage(), startsWith("Failed to parse query string at line 1, column "));
        }
        assertThat(assertThrows(SearchException.class, () -> any("pool AND")).getMessage(),
            startsWith("Failed to parse query string at line 1, column 9."));
        assertThat(assertThrows(SearchException.class, () -> any("pool\n*abc")).getMessage(),
            startsWith("Failed to parse query string at line 2, column 1."));
    }

    @Test
    void overlongPatternsAreRefused() {
        assertThrows(SearchException.class, () -> any("a".repeat(1001) + "*"));
        assertThrows(SearchException.class, () -> any("/" + "a".repeat(1001) + "/"));
    }
}
