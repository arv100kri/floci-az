package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.model.SearchException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneRegExpTest {

    private static boolean matches(String pattern, String term) {
        return LuceneRegExp.compile(pattern).matches(term);
    }

    @Test
    void learnsRegexExamplesMatchWholeTerms() {
        assertTrue(matches("[mh]otel", "motel"));
        assertTrue(matches("[mh]otel", "hotel"));
        assertFalse(matches("[mh]otel", "hostel"));
        assertFalse(matches("[mh]otel", "motels"), "a regex matches the whole term");
        assertTrue(matches("be.", "bee"));
        assertTrue(matches("be.", "bet"));
        assertTrue(matches("be*", "be"));
        assertTrue(matches("be*", "beee"));
        assertFalse(matches("be*", "bet"));
        assertTrue(matches("be.*", "better"));
        assertTrue(matches(".*numeric.*", "alphanumerical"));
        assertTrue(matches("(mo|ho)tel", "hotel"));
        assertTrue(matches(".*microsoft.com/azure/.*", "www.microsoft.com/azure/search"));
    }

    @Test
    void repetitionsAndClasses() {
        assertTrue(matches("a{2}", "aa"));
        assertFalse(matches("a{2}", "aaa"));
        assertTrue(matches("a{2,}", "aaaaa"));
        assertFalse(matches("a{2,}", "a"));
        assertTrue(matches("a{1,3}b", "aaab"));
        assertFalse(matches("a{1,3}b", "aaaab"));
        assertTrue(matches("ab+", "abbb"));
        assertFalse(matches("ab+", "a"));
        assertTrue(matches("colou?r", "color"));
        assertTrue(matches("colou?r", "colour"));
        assertTrue(matches("[^0-9]+", "abc"));
        assertFalse(matches("[^0-9]+", "a1"));
        assertTrue(matches("[a-c\\-]+", "a-b"));
        assertTrue(matches("a()b", "ab"));
    }

    @Test
    void theOptionalOperatorsAreEnabled() {
        assertTrue(matches(".*a.*&.*b.*", "cab"));
        assertFalse(matches(".*a.*&.*b.*", "caa"));
        assertTrue(matches("~(foo)", "bar"));
        assertFalse(matches("~(foo)", "foo"));
        assertTrue(matches("fo~(o)", "fox"));
        assertFalse(matches("fo~(o)", "foo"));
        assertFalse(matches("#", ""));
        assertTrue(matches("a|#", "a"));
        assertTrue(matches("@", "anything at all"));
        assertTrue(matches("ab@", "abc"));
        assertTrue(matches("\"a.b\"", "a.b"));
        assertFalse(matches("\"a.b\"", "axb"));
    }

    @Test
    void numericIntervalsFollowTheDocumentedWidthRule() {
        for (String inside : List.of("1", "5", "10", "007")) {
            assertTrue(matches("<1-10>", inside), inside);
        }
        for (String outside : List.of("0", "11", "100", "x")) {
            assertFalse(matches("<1-10>", outside), outside);
        }
        assertTrue(matches("<01-10>", "05"));
        assertFalse(matches("<01-10>", "5"), "same-width bounds fix the length");
        assertTrue(matches("v<0-255>", "v128"));
        assertFalse(matches("v<0-255>", "v256"));
    }

    @Test
    void escapesMakeReservedCharactersLiteral() {
        assertTrue(matches("a\\.b", "a.b"));
        assertFalse(matches("a\\.b", "axb"));
        assertTrue(matches("\\*", "*"));
        assertTrue(matches("x\\[1\\]", "x[1]"));
    }

    @Test
    void malformedExpressionsAreBadRequests() {
        for (String pattern : List.of("[abc", "(a", "a{2", "*a", "a)", "<name>", "", "a|", "[z-a]", "a{3,1}", "a\\",
                "[.]")) {
            assertThrows(SearchException.class, () -> LuceneRegExp.compile(pattern), pattern);
        }
    }

    @Test
    void wildcardPatterns() {
        assertTrue(LuceneRegExp.wildcard("te?t").matches("test"));
        assertFalse(LuceneRegExp.wildcard("te?t").matches("tet"));
        assertTrue(LuceneRegExp.wildcard("te*t").matches("tet"));
        assertTrue(LuceneRegExp.wildcard("te*t").matches("tempest"));
        assertTrue(LuceneRegExp.wildcard("980?2*").matches("98052"));
        assertFalse(LuceneRegExp.wildcard("980?2*").matches("9802"));
        assertTrue(LuceneRegExp.wildcard("a\\*b").matches("a*b"));
        assertFalse(LuceneRegExp.wildcard("a\\*b").matches("axb"));
    }
}
