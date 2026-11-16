package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisComponentsTest {

    private static AnalysisComponents components(String sections) {
        JsonNode index = SearchJson.parse(("{" + sections + "}").getBytes(StandardCharsets.UTF_8));
        return AnalysisComponents.of(index);
    }

    private static Analyzer chain(AnalysisComponents components, String tokenizer, List<String> tokenFilters,
                                  List<String> charFilters) {
        return new ComposedAnalyzer("test", components.charFilters(charFilters), components.tokenizer(tokenizer),
            components.tokenFilters(tokenFilters));
    }

    private static List<String> terms(Analyzer analyzer, String text) {
        return analyzer.analyze(text).stream().map(Token::term).toList();
    }

    private static List<String> builtIn(String tokenizer, List<String> filters, String text) {
        return terms(chain(components(""), tokenizer, filters, List.of()), text);
    }

    @Test
    void theDocumentedCustomAnalyzerExampleProducesTheDocumentedTokens() {
        AnalysisComponents components = components("""
            "charFilters": [
              {"name": "map_dash", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter", "mappings": ["-=>_"]},
              {"name": "remove_whitespace", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
               "mappings": ["\\\\u0020=>"]}],
            "tokenFilters": [{"name": "my_asciifolding",
              "@odata.type": "#Microsoft.Azure.Search.AsciiFoldingTokenFilter",
              "preserveOriginal": true}]
            """);
        Analyzer analyzer = chain(components, "standard_v2", List.of("my_asciifolding", "lowercase"),
            List.of("map_dash"));
        List<Token> tokens = analyzer.analyze("Vis-à-vis means Opposite");
        assertThat(tokens, contains(new Token("vis_a_vis", 0, 0, 9), new Token("vis_à_vis", 0, 0, 9),
            new Token("means", 1, 10, 15), new Token("opposite", 2, 16, 24)));
        assertThat(terms(chain(components, "keyword_v2", List.of(), List.of("remove_whitespace")), "a b c"),
            contains("abc"));
    }

    @Test
    void patternReplaceCharFilterKeepsOffsetsIntoTheOriginalText() {
        AnalysisComponents components = components("""
            "charFilters": [{"name": "joined", "@odata.type": "#Microsoft.Azure.Search.PatternReplaceCharFilter",
              "pattern": "(aa)\\\\s+(bb)", "replacement": "$1#$2"}]
            """);
        List<Token> tokens = chain(components, "whitespace", List.of(), List.of("joined")).analyze("aa  bb aa bb");
        assertThat(tokens, contains(new Token("aa#bb", 0, 0, 6), new Token("aa#bb", 1, 7, 12)));
    }

    @Test
    void htmlStripRemovesMarkupAndDecodesEntities() {
        List<Token> tokens = chain(components(""), "standard_v2", List.of("lowercase"), List.of("html_strip"))
            .analyze("<p>Fish &amp; <b>Chips</b></p><!-- hidden --><script>var x;</script>");
        assertThat(tokens.stream().map(Token::term).toList(), contains("fish", "chips"));
        assertThat(tokens.get(1).startOffset(), is(17));
        assertThat(tokens.get(1).endOffset(), is(22));
    }

    @Test
    void nGramAndEdgeNGramTokenizersHonourTheirCharacterClasses() {
        AnalysisComponents components = components("""
            "tokenizers": [
              {"name": "grams", "@odata.type": "#Microsoft.Azure.Search.NGramTokenizer", "minGram": 2, "maxGram": 3,
               "tokenChars": ["letter"]},
              {"name": "edges", "@odata.type": "#Microsoft.Azure.Search.EdgeNGramTokenizer", "minGram": 1,
               "maxGram": 3, "tokenChars": ["letter", "digit"]}]
            """);
        assertThat(terms(chain(components, "grams", List.of(), List.of()), "abcd e"),
            contains("ab", "abc", "bc", "bcd", "cd"));
        assertThat(terms(chain(components, "edges", List.of(), List.of()), "ab12 cd"),
            contains("a", "ab", "ab1", "c", "cd"));
    }

    @Test
    void pathHierarchyTokenizerEmitsEveryPrefixOrSuffix() {
        AnalysisComponents components = components("""
            "tokenizers": [
              {"name": "reversed", "@odata.type": "#Microsoft.Azure.Search.PathHierarchyTokenizerV2", "delimiter": ".",
               "reverse": true},
              {"name": "skipping", "@odata.type": "#Microsoft.Azure.Search.PathHierarchyTokenizerV2", "skip": 1,
               "replacement": "|"}]
            """);
        assertThat(builtIn("path_hierarchy_v2", List.of(), "/usr/local/bin"),
            contains("/usr", "/usr/local", "/usr/local/bin"));
        assertThat(terms(chain(components, "reversed", List.of(), List.of()), "www.site.co.uk"),
            contains("www.site.co.uk", "site.co.uk", "co.uk", "uk"));
        assertThat(terms(chain(components, "skipping", List.of(), List.of()), "/a/b/c"), contains("|b", "|b|c"));
    }

    @Test
    void patternTokenizerSplitsOrExtractsAGroup() {
        AnalysisComponents components = components("""
            "tokenizers": [{"name": "quoted", "@odata.type": "#Microsoft.Azure.Search.PatternTokenizer",
              "pattern": "'([^']+)'", "group": 1}]
            """);
        assertThat(terms(chain(components, "quoted", List.of(), List.of()), "say 'hello' and 'bye'"),
            contains("hello", "bye"));
        assertThat(builtIn("pattern", List.of(), "a,b;;c"), contains("a", "b", "c"));
    }

    @Test
    void keywordLetterAndWhitespaceTokenizersSplitLongTokens() {
        assertThat(builtIn("keyword_v2", List.of(), "x".repeat(300)).get(0).length(), is(256));
        assertThat(builtIn("letter", List.of(), "a".repeat(300)).size(), is(2));
        assertThat(builtIn("lowercase", List.of(), "Hello World2"), contains("hello", "world"));
    }

    @Test
    void classicAndUrlEmailTokenizersKeepCompoundTokens() {
        assertThat(builtIn("classic", List.of("classic"), "I.B.M. sold O'Reilly's AT&T to bob@example.com"),
            contains("IBM", "sold", "O'Reilly", "AT&T", "to", "bob@example.com"));
        assertThat(builtIn("uax_url_email", List.of(), "mail bob@example.com or see https://example.com/a?b=c."),
            contains("mail", "bob@example.com", "or", "see", "https://example.com/a?b=c"));
    }

    @Test
    void theStandardTokenizerMakesEachIdeographAToken() {
        assertThat(builtIn("standard_v2", List.of(), "東京タワー"), contains("東", "京", "タワー"));
        assertThat(builtIn("standard_v2", List.of("cjk_bigram"), "東京都 tower"), contains("東京", "京都", "tower"));
    }

    @Test
    void wordDelimiterSplitsAndCatenates() {
        AnalysisComponents components = components("""
            "tokenFilters": [{"name": "delimiter", "@odata.type": "#Microsoft.Azure.Search.WordDelimiterTokenFilter",
              "catenateWords": true, "preserveOriginal": true}]
            """);
        List<Token> tokens = chain(components, "whitespace", List.of("delimiter"), List.of())
            .analyze("Azure-Search next");
        assertThat(tokens.stream().map(Token::term).toList(),
            contains("Azure-Search", "AzureSearch", "Azure", "Search", "next"));
        assertThat(tokens.get(4).position(), is(2));
        assertThat(builtIn("whitespace", List.of("word_delimiter"), "PowerShot500 O'Neil's"),
            contains("Power", "Shot", "500", "O", "Neil"));
    }

    @Test
    void shingleJoinsAdjacentTokensAndFillsGaps() {
        assertThat(builtIn("whitespace", List.of("shingle"), "please divide this"),
            contains("please", "please divide", "divide", "divide this", "this"));
        assertThat(builtIn("standard_v2", List.of("stopwords", "shingle"), "the quick fox"),
            contains("quick", "quick fox", "fox"));
    }

    @Test
    void synonymFilterExpandsEquivalentsAndRewritesExplicitMappings() {
        AnalysisComponents components = components("""
            "tokenFilters": [{"name": "syn", "@odata.type": "#Microsoft.Azure.Search.SynonymTokenFilter",
              "synonyms": ["usa, united states", "incredible, unbelievable => amazing"], "ignoreCase": true}]
            """);
        Analyzer analyzer = chain(components, "standard_v2", List.of("lowercase", "syn"), List.of());
        assertThat(terms(analyzer, "USA trip"), contains("usa", "united", "states", "trip"));
        assertThat(terms(analyzer, "an incredible view"), contains("an", "amazing", "view"));
        assertThat(terms(analyzer, "United States"), contains("usa", "united", "states"));
    }

    @Test
    void stopwordFilterUsesPredefinedListsAndTheTrailingRule() {
        AnalysisComponents components = components("""
            "tokenFilters": [
              {"name": "french", "@odata.type": "#Microsoft.Azure.Search.StopwordsTokenFilter",
               "stopwordsList": "french"},
              {"name": "keepLast", "@odata.type": "#Microsoft.Azure.Search.StopwordsTokenFilter",
               "stopwords": ["the", "of"], "ignoreCase": true, "removeTrailing": false}]
            """);
        assertThat(terms(chain(components, "standard_v2", List.of("lowercase", "french"), List.of()),
            "Le chat et la souris"), contains("chat", "souris"));
        assertThat(terms(chain(components, "whitespace", List.of("keepLast"), List.of()), "The best of"),
            contains("best", "of"));
    }

    @Test
    void stemmerOverridesAndKeywordsAreNotStemmed() {
        AnalysisComponents components = components("""
            "tokenFilters": [
              {"name": "override", "@odata.type": "#Microsoft.Azure.Search.StemmerOverrideTokenFilter",
               "rules": ["ran => run", "mice, mouses => mouse"]},
              {"name": "protect", "@odata.type": "#Microsoft.Azure.Search.KeywordMarkerTokenFilter",
               "keywords": ["running"]},
              {"name": "english", "@odata.type": "#Microsoft.Azure.Search.StemmerTokenFilter", "language": "english"}]
            """);
        assertThat(terms(chain(components, "whitespace", List.of("override", "protect", "english"), List.of()),
            "ran mice running jumping"), contains("run", "mouse", "running", "jump"));
        assertThat(builtIn("whitespace", List.of("keyword_repeat", "porter_stem", "unique"), "jumping"),
            contains("jumping", "jump"));
    }

    @Test
    void simpleTokenFiltersRewriteDropAndLimit() {
        assertThat(builtIn("whitespace", List.of("apostrophe"), "Türkiye'de"), contains("Türkiye"));
        assertThat(builtIn("whitespace", List.of("reverse"), "abc"), contains("cba"));
        assertThat(builtIn("whitespace", List.of("edgeNGram_v2"), "abc"), contains("a", "ab"));
        assertThat(builtIn("whitespace", List.of("nGram_v2"), "abc"), contains("a", "ab", "b", "bc", "c"));
        assertThat(builtIn("whitespace", List.of("limit"), "one two"), contains("one"));
        assertThat(builtIn("whitespace", List.of("elision"), "l'avion"), contains("avion"));
        assertThat(builtIn("whitespace", List.of("scandinavian_folding"), "blåbærsyltetøj"),
            contains("blabarsyltetoj"));
        assertThat(builtIn("whitespace", List.of("german_normalization"), "Strasse Straße Müller Mueller"),
            contains("Strasse", "Strasse", "Muller", "Muller"));
        assertThat(builtIn("whitespace", List.of("cjk_width"), "ＡＢＣ ｶﾞ"), contains("ABC", "ガ"));
        assertThat(builtIn("whitespace", List.of("unique"), "a b a"), contains("a", "b"));
    }

    @Test
    void patternCaptureEmitsTheOriginalAndEachGroup() {
        AnalysisComponents components = components("""
            "tokenFilters": [{"name": "parts", "@odata.type": "#Microsoft.Azure.Search.PatternCaptureTokenFilter",
              "patterns": ["([a-z]+)(\\\\d+)"]}]
            """);
        assertThat(terms(chain(components, "whitespace", List.of("parts"), List.of()), "abc123"),
            contains("abc123", "abc", "123"));
    }

    @Test
    void commonGramsAddsBigramsAroundCommonWords() {
        AnalysisComponents components = components("""
            "tokenFilters": [{"name": "grams", "@odata.type": "#Microsoft.Azure.Search.CommonGramTokenFilter",
              "commonWords": ["the"]}]
            """);
        assertThat(terms(chain(components, "whitespace", List.of("grams"), List.of()), "over the hill"),
            contains("over", "over_the", "the", "the_hill", "hill"));
    }

    @Test
    void customNormalizersApplyTheirFiltersToTheWholeValue() {
        AnalysisComponents components = components("""
            "charFilters": [{"name": "dash", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
              "mappings": ["-=>_"]}]
            """);
        LexicalNormalizer normalizer = components.normalizer(SearchJson.parse("""
            {"name": "norm", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer", "charFilters": ["dash"],
             "tokenFilters": ["asciifolding", "lowercase"]}
            """.getBytes(StandardCharsets.UTF_8)));
        assertThat(normalizer.normalize("Vis-à-Vis"), equalTo("vis_a_vis"));
    }

    @Test
    void invalidComponentsNameTheProblem() {
        AnalysisComponents components = components("""
            "tokenizers": [{"name": "long", "@odata.type": "#Microsoft.Azure.Search.StandardTokenizerV2",
              "maxTokenLength": 400}],
            "tokenFilters": [{"name": "unknown", "@odata.type": "#Microsoft.Azure.Search.NoSuchTokenFilter"}]
            """);
        assertThat(assertThrows(IllegalArgumentException.class, () -> components.tokenizer("long")).getMessage(),
            containsString("'maxTokenLength' is 400"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> components.tokenFilter("unknown"))
            .getMessage(), containsString("NoSuchTokenFilter"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> components.tokenFilter("no_such_filter"))
            .getMessage(), containsString("neither built in nor defined"));
    }

    @Test
    void theNamesLearnUsesForBuiltInAnalyzersAreAccepted() {
        AnalyzerRegistry registry = AnalyzerRegistry.defaultRegistry();
        assertThat(terms(registry.analyzer("standard").orElseThrow(), "SVP10-NOR-00"), contains("svp10", "nor", "00"));
        assertThat(terms(registry.analyzer("asciifolding").orElseThrow(), "Café"), contains("cafe"));
        assertThat(registry.isLanguageAnalyzer("pt-Pt.microsoft"), is(true));
        assertThat(registry.isLanguageAnalyzer("standard"), is(false));
    }

    @Test
    void englishLanguageAnalyzersRemovePossessivesStopWordsAndStem() {
        AnalyzerRegistry registry = AnalyzerRegistry.defaultRegistry();
        assertThat(terms(registry.analyzer("en.lucene").orElseThrow(), "The dog's running connections"),
            contains("dog", "run", "connect"));
        assertThat(terms(registry.analyzer("en.microsoft").orElseThrow(), "They were running"),
            contains("were", "run"));
        assertThat(terms(registry.analyzer("fr.lucene").orElseThrow(), "L'avion et le train"),
            contains("avion", "train"));
        assertThat(terms(registry.analyzer("pt-Br.microsoft").orElseThrow(), "o carro"), contains("carro"));
        assertThat(terms(registry.analyzer("th.lucene").orElseThrow(), "ภาษาไทย"), contains("ภาษา", "ไทย"));
        assertThat(terms(registry.analyzer("ja.lucene").orElseThrow(), "東京都"), contains("東京", "京都"));
        assertThat(terms(registry.analyzer("hy.lucene").orElseThrow(), ""), empty());
    }
}
