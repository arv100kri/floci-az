package io.floci.az.services.search.engine.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTextTest {

    @Test
    void queryTermsDropStopWordsAndFoldPluralsAndAccents() {
        assertEquals(List.of("hotel", "rooftop", "pool"), SemanticText.queryTerms("Which hotels have a rooftop pool?"));
        assertEquals(List.of("cafe", "city"), SemanticText.queryTerms("Café cities"));
        assertEquals(List.of("hotel"), SemanticText.queryTerms("the hotel's"));
        assertEquals(List.of("the", "who"), SemanticText.queryTerms("The Who"), "only stop words: keep them all");
        assertEquals(List.of(), SemanticText.queryTerms("*"));
    }

    @Test
    void theSStemmerFoldsPluralEndings() {
        assertEquals("query", SemanticText.stem("queries"));
        assertEquals("house", SemanticText.stem("houses"));
        assertEquals("hotel", SemanticText.stem("hotels"));
        assertEquals("class", SemanticText.stem("class"));
        assertEquals("campus", SemanticText.stem("campus"));
        assertEquals("gas", SemanticText.stem("gas"));
    }

    @Test
    void questionsEndWithAQuestionMarkOrOpenWithAQuestionWord() {
        assertTrue(SemanticText.isQuestion("how do clouds form"));
        assertTrue(SemanticText.isQuestion("Is there a pool"));
        assertTrue(SemanticText.isQuestion("rooftop pool?"));
        assertFalse(SemanticText.isQuestion("rooftop pool"));
        assertFalse(SemanticText.isQuestion("   "));
    }

    @Test
    void sentencesEndAtTerminalPunctuationFollowedBySpace() {
        String text = "First one. Second one!  Version 2.5 is out? Last without stop";
        List<String> sentences = SemanticText.sentences(text).stream()
            .map(sentence -> text.substring(sentence.start(), sentence.end())).toList();
        assertEquals(List.of("First one.", "Second one!", "Version 2.5 is out?", "Last without stop"), sentences);
    }

    @Test
    void highlightsWrapRunsOfMatchingWords() {
        Set<String> terms = Set.of("rooftop", "pool", "hotel");
        assertEquals("A <b>rooftop pool</b> at the <b>hotel</b>.",
            SemanticText.highlight("A rooftop pool at the hotel.", terms, "<b>", "</b>"));
        assertEquals("", SemanticText.highlight("Nothing here.", terms, "<b>", "</b>"));
    }

    @Test
    void truncationKeepsWholeWords() {
        assertEquals("one two", SemanticText.truncate("one two three", 9));
        assertEquals("one two three", SemanticText.firstWords("one two three four", 3));
        assertEquals("one", SemanticText.firstWords("one", 3));
    }
}
