package io.floci.az.services.search.engine.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

class AnalyzersTest {

    private final AnalyzerRegistry registry = AnalyzerRegistry.defaultRegistry();

    private List<String> terms(String analyzer, String text) {
        return registry.analyzer(analyzer).orElseThrow().analyze(text).stream().map(Token::term).toList();
    }

    @Test
    void theStandardAnalyzerSplitsOnWordBoundariesAndLowercases() {
        assertThat(terms("standard.lucene", "Air-Conditioned rooms, FREE Wi-Fi!"),
            contains("air", "conditioned", "rooms", "free", "wi", "fi"));
    }

    @Test
    void theStandardAnalyzerRecordsPositionsAndOffsets() {
        List<Token> tokens = registry.standard().analyze("The quick  fox");
        assertThat(tokens, hasSize(3));
        assertThat(tokens.get(2), equalTo(new Token("fox", 2, 11, 14)));
    }

    @Test
    void theStandardAnalyzerSplitsTokensLongerThan255Characters() {
        List<Token> tokens = registry.standard().analyze("a".repeat(300));
        assertThat(tokens, hasSize(2));
        assertThat(tokens.get(0).term().length(), is(255));
        assertThat(tokens.get(1).term().length(), is(45));
    }

    @Test
    void asciiFoldingFoldsDiacritics() {
        assertThat(terms("standardasciifolding.lucene", "Café Ærø Straße"), contains("cafe", "aero", "strasse"));
        assertThat(terms("standard.lucene", "Café"), contains("café"));
    }

    @Test
    void theSimpleWhitespaceKeywordStopAndPatternAnalyzers() {
        assertThat(terms("simple", "Room 101, floor2"), contains("room", "floor"));
        assertThat(terms("whitespace", "Hello, World"), contains("Hello,", "World"));
        assertThat(terms("keyword", "New York City"), contains("New York City"));
        assertThat(terms("stop", "The view of the sea"), contains("view", "sea"));
        assertThat(terms("pattern", "foo_bar-Baz"), contains("foo_bar", "baz"));
        assertThat(terms("keyword", ""), empty());
    }

    @Test
    void stopWordsLeaveAPositionGap() {
        List<Token> tokens = registry.analyzer("stop").orElseThrow().analyze("view of sea");
        assertThat(tokens.get(1).position(), is(2));
    }

    @Test
    void multiTermNormalizationAppliesOnlyNormalizingFilters() {
        assertThat(registry.standard().normalizeTerm("LUX"), is("lux"));
        assertThat(registry.analyzer("standardasciifolding.lucene").orElseThrow().normalizeTerm("CAFÉ"), is("cafe"));
        assertThat(registry.analyzer("whitespace").orElseThrow().normalizeTerm("LUX"), is("LUX"));
    }

    @Test
    void everyDocumentedAnalyzerNameIsKnown() {
        assertThat(registry.isBuiltInAnalyzer("en.lucene"), is(true));
        assertThat(registry.isBuiltInAnalyzer("zh-Hans.microsoft"), is(true));
        assertThat(registry.isLanguageAnalyzer("fr.microsoft"), is(true));
        assertThat(registry.isLanguageAnalyzer("standard.lucene"), is(false));
        assertThat(registry.isBuiltInAnalyzer("no.such.analyzer"), is(false));
    }

    @Test
    void builtInNormalizers() {
        assertThat(registry.normalizer("lowercase").orElseThrow().normalize("ÉLAN"), is("élan"));
        assertThat(registry.normalizer("standard").orElseThrow().normalize("Élan"), is("elan"));
        assertThat(registry.normalizer("uppercase").orElseThrow().normalize("abc"), is("ABC"));
        assertThat(registry.normalizer("elision").orElseThrow().normalize("l'avion"), is("avion"));
    }
}
