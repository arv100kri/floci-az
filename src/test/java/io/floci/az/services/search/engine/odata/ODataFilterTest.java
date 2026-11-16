package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataFilter.FullTextMatches;
import io.floci.az.services.search.model.SearchException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ODataFilterTest {

    private static final String DEFINITION = """
        {"name": "products", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "name", "type": "Edm.String"},
          {"name": "code", "type": "Edm.String", "normalizer": "lowercase"},
          {"name": "price", "type": "Edm.Double"},
          {"name": "stock", "type": "Edm.Int64"},
          {"name": "active", "type": "Edm.Boolean"},
          {"name": "added", "type": "Edm.DateTimeOffset"},
          {"name": "tags", "type": "Collection(Edm.String)"},
          {"name": "sizes", "type": "Collection(Edm.Int32)"},
          {"name": "hidden", "type": "Edm.String", "filterable": false},
          {"name": "spot", "type": "Edm.GeographyPoint"},
          {"name": "variants", "type": "Collection(Edm.ComplexType)", "fields": [
            {"name": "color", "type": "Edm.String"}, {"name": "price", "type": "Edm.Double"}]}
        ]}
        """;

    private static final LiveIndex INDEX = EngineFixtures.index(DEFINITION,
        "{\"id\":\"a\",\"name\":\"Apple\",\"code\":\"AP-1\",\"price\":1.5,\"stock\":10,\"active\":true,"
            + "\"added\":\"2024-01-01T00:00:00Z\",\"tags\":[\"fruit\",\"red\"],\"sizes\":[1,2],"
            + "\"spot\":{\"type\":\"Point\",\"coordinates\":[2.35,48.85]},"
            + "\"variants\":[{\"color\":\"red\",\"price\":1.5},{\"color\":\"green\",\"price\":1.2}]}",
        "{\"id\":\"b\",\"name\":\"Banana\",\"code\":\"BA-2\",\"price\":\"NaN\",\"stock\":0,\"active\":false,"
            + "\"added\":\"2023-06-01T12:00:00+02:00\",\"tags\":[\"fruit\",\"yellow\"],\"sizes\":[3],"
            + "\"variants\":[{\"color\":\"yellow\",\"price\":0.9}]}",
        "{\"id\":\"c\",\"name\":\"Carrot\",\"tags\":[],\"sizes\":[]}");

    private static List<String> matching(String filter) {
        ODataFilter compiled = ODataFilter.compile(filter, INDEX.definition(), INDEX.analysis());
        return INDEX.read(index -> {
            List<String> keys = new ArrayList<>();
            for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
                IndexedDocument document = index.document(ordinal);
                if (document != null && compiled.matches(document, FullTextMatches.NONE)) {
                    keys.add(document.key());
                }
            }
            return keys;
        });
    }

    @Test
    void comparisonsWithConstantsOnEitherSide() {
        assertThat(matching("price gt 1"), contains("a"));
        assertThat(matching("1 lt price"), contains("a"));
        assertThat(matching("stock ge 0 and stock le 10"), contains("a", "b"));
        assertThat(matching("stock eq 10.0"), contains("a"));
        assertThat(matching("name eq 'Apple'"), contains("a"));
        assertThat(matching("name eq 'apple'"), empty());
        assertThat(matching("name lt 'B'"), contains("a"));
    }

    @Test
    void nullAndNaNFollowTheDocumentedRules() {
        assertThat(matching("price eq null"), contains("c"));
        assertThat(matching("price ne null"), contains("a", "b"));
        assertThat(matching("price lt 100"), contains("a"));
        assertThat(matching("price ne 1.5"), contains("b", "c"));
        assertThat(matching("price eq NaN"), empty());
    }

    @Test
    void logicalOperatorsAndPrecedence() {
        assertThat(matching("active or stock eq 0 and name eq 'Carrot'"), contains("a"));
        assertThat(matching("(active or stock eq 0) and name ne 'Apple'"), contains("b"));
        assertThat(matching("not active"), contains("b", "c"));
        assertThat(matching("not (price gt 1)"), contains("b", "c"));
        assertThat(matching("true"), hasSize(3));
        assertThat(matching("active eq true"), contains("a"));
        assertThat(matching("active EQ TRUE"), contains("a"));
    }

    @Test
    void dateTimeOffsetsCompareInUtc() {
        assertThat(matching("added gt 2023-06-01T09:00:00Z"), contains("a", "b"));
        assertThat(matching("added gt 2023-06-01T10:00:00Z"), contains("a"));
        assertThat(matching("added eq 2023-06-01T10:00:00.000Z"), contains("b"));
    }

    @Test
    void normalizersApplyToFieldsAndLiterals() {
        assertThat(matching("code eq 'ap-1'"), contains("a"));
        assertThat(matching("code eq 'AP-1'"), contains("a"));
        assertThat(matching("search.in(code, 'ba-2|xx', '|')"), contains("b"));
    }

    @Test
    void collectionsThroughLambdas() {
        assertThat(matching("tags/any(t: t eq 'red')"), contains("a"));
        assertThat(matching("tags/any(t: search.in(t, 'red, yellow'))"), contains("a", "b"));
        assertThat(matching("tags/all(t: t ne 'red' and t ne 'yellow')"), contains("c"));
        assertThat(matching("tags/all(t: not search.in(t, 'red'))"), contains("b", "c"));
        assertThat(matching("sizes/any(s: s gt 2)"), contains("b"));
        assertThat(matching("sizes/all(s: s lt 3)"), contains("a", "c"));
        assertThat(matching("variants/any(v: v/color eq 'green' and v/price lt 1.3)"), contains("a"));
        assertThat(matching("variants/all(v: v/price lt 1)"), contains("b", "c"));
        assertThat(matching("variants/any()"), contains("a", "b"));
    }

    @Test
    void geographyFunctions() {
        assertThat(matching("geo.distance(spot, geography'POINT(2.29 48.86)') lt 5"), contains("a"));
        assertThat(matching("geo.distance(geography'POINT(2.29 48.86)', spot) gt 5"), empty());
        assertThat(matching("geo.intersects(spot, geography'POLYGON((2 48, 3 48, 3 49, 2 49, 2 48))')"),
            contains("a"));
    }

    @Test
    void invalidExpressionsAreRefused() {
        assertRefused("hidden eq 'x'", "not filterable");
        assertRefused("missing eq 1", "Could not find a property named 'missing'");
        assertRefused("ACTIVE eq true", "Could not find a property named 'ACTIVE'");
        assertRefused("price gt null", "null can only be compared");
        assertRefused("tags eq 'red'", "not a single value");
        assertRefused("variants/color eq 'red'", "not a single value");
        assertRefused("tags/any(t: t ne 'red')", "'any' lambda over a string collection");
        assertRefused("tags/all(t: t eq 'red')", "'all' lambda over a string collection");
        assertRefused("stock eq NaN", "cannot be compared");
        assertRefused("name eq 1", "cannot be compared");
        assertRefused("active gt true", "cannot be compared");
        assertRefused("geo.distance(spot, geography'POINT(1 2)') eq 3", "geo.distance");
        assertRefused("variants/any(v: search.ismatch('x'))", "inside a lambda");
        assertRefused("name eq 'unterminated", "Syntax error");
        assertRefused("price gt 1 and", "Syntax error");
        assertRefused("unknown.function(name)", "Unknown function");
        assertRefused("1 eq 1", "two constants");
        assertRefused("name eq price", "constant on one side");
        assertRefused("geo.intersects(spot, geography'POLYGON((2 48, 3 48, 3 49))')", "closed");
    }

    private static void assertRefused(String filter, String message) {
        SearchException failure = assertThrows(SearchException.class, () -> matching(filter), filter);
        assertThat(filter, failure.status(), is(400));
        assertThat(filter, failure.getMessage(), containsString(message));
    }

    @Test
    void orderByClausesSortWithNullsFirstAscending() {
        ODataOrderBy orderBy = ODataOrderBy.parse("price desc, name", INDEX.definition());
        List<String> sorted = INDEX.read(index -> {
            List<IndexedDocument> documents = new ArrayList<>();
            for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
                documents.add(index.document(ordinal));
            }
            Comparator<IndexedDocument> comparator = orderBy.comparator(document -> document, document -> 0);
            documents.sort(comparator);
            return documents.stream().map(IndexedDocument::key).toList();
        });
        assertThat(sorted, contains("b", "a", "c"));
        assertThat(ODataOrderBy.parse("search.score() desc, geo.distance(spot, geography'POINT(0 0)')",
            INDEX.definition()).clauses(), hasSize(2));
        assertThrows(SearchException.class, () -> ODataOrderBy.parse("tags", INDEX.definition()));
        assertThrows(SearchException.class, () -> ODataOrderBy.parse("variants/color", INDEX.definition()));
        assertThrows(SearchException.class, () -> ODataOrderBy.parse("search.score(1)", INDEX.definition()));
        assertThrows(SearchException.class, () -> ODataOrderBy.parse("name sideways", INDEX.definition()));
    }
}
