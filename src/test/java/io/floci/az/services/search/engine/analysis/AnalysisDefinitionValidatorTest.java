package io.floci.az.services.search.engine.analysis;

import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchIndexDefinition;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisDefinitionValidatorTest {

    private static final String FIELDS = """
        "fields": [{"name": "id", "type": "Edm.String", "key": true},
                   {"name": "text", "type": "Edm.String", "searchable": true, "analyzer": "my_analyzer"}]
        """;

    private static String index(String sections) {
        return "{\"name\": \"idx\", " + FIELDS + ", " + sections + "}";
    }

    private static String rejection(String sections) {
        SearchException error = assertThrows(SearchException.class,
            () -> EngineFixtures.definition(index(sections)));
        assertThat(error.status(), is(400));
        return error.getMessage();
    }

    @Test
    void theDocumentedCustomAnalyzerExampleIsAccepted() {
        SearchIndexDefinition definition = EngineFixtures.definition(index("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "charFilters": ["map_dash", "remove_whitespace"], "tokenizer": "my_standard_tokenizer",
              "tokenFilters": ["my_asciifolding", "lowercase"]}],
            "charFilters": [
              {"name": "map_dash", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter", "mappings": ["-=>_"]},
              {"name": "remove_whitespace", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
               "mappings": ["\\\\u0020=>"]}],
            "tokenizers": [{"name": "my_standard_tokenizer", "@odata.type":
              "#Microsoft.Azure.Search.StandardTokenizerV2", "maxTokenLength": 20}],
            "tokenFilters": [{"name": "my_asciifolding", "@odata.type":
              "#Microsoft.Azure.Search.AsciiFoldingTokenFilter", "preserveOriginal": true}]
            """));
        assertThat(definition.json().path("analyzers").size(), is(1));
    }

    @Test
    void namesFollowTheNamingRuleAndAvoidBuiltInNames() {
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "standard_v2"}],
            "tokenFilters": [{"name": "-bad", "@odata.type": "#Microsoft.Azure.Search.LengthTokenFilter"}]
            """), containsString("'-bad'"));
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "standard_v2"},
              {"name": "whitespace", "@odata.type": "#Microsoft.Azure.Search.StopAnalyzer"}]
            """), containsString("built-in"));
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "standard_v2"}],
            "tokenFilters": [{"name": "lowercase", "@odata.type": "#Microsoft.Azure.Search.LengthTokenFilter"}]
            """), containsString("built-in"));
    }

    @Test
    void referencesMustResolveAndTypesMustBeKnown() {
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "missing_tokenizer"}]
            """), containsString("missing_tokenizer"));
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "standard_v2", "tokenFilters": ["lowercase", "nope"]}]
            """), containsString("'nope'"));
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer"}]
            """), containsString("'tokenizer' is required"));
        assertThat(rejection("""
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.FancyAnalyzer"}]
            """), containsString("FancyAnalyzer"));
    }

    @Test
    void parametersHaveTheirDocumentedTypesAndLimits() {
        String analyzer = """
            "analyzers": [{"name": "my_analyzer", "@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer",
              "tokenizer": "standard_v2"}],
            """;
        assertThat(rejection(analyzer + """
            "tokenizers": [{"name": "grams", "@odata.type": "#Microsoft.Azure.Search.NGramTokenizer", "minGram": 3,
              "maxGram": 2}]
            """), containsString("'minGram'"));
        assertThat(rejection(analyzer + """
            "tokenFilters": [{"name": "stops", "@odata.type": "#Microsoft.Azure.Search.StopwordsTokenFilter",
              "stopwords": ["a"], "stopwordsList": "english"}]
            """), containsString("cannot both be set"));
        assertThat(rejection(analyzer + """
            "tokenFilters": [{"name": "stem", "@odata.type": "#Microsoft.Azure.Search.StemmerTokenFilter",
              "language": "klingon"}]
            """), containsString("klingon"));
        assertThat(rejection(analyzer + """
            "charFilters": [{"name": "map", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
              "mappings": ["no arrow"]}]
            """), containsString("a=>b"));
        assertThat(rejection(analyzer + """
            "tokenizers": [{"name": "regex", "@odata.type": "#Microsoft.Azure.Search.PatternTokenizer",
              "pattern": "([a-z", "flags": "CASE_INSENSITIVE"}]
            """), containsString("regular expression"));
        assertThat(rejection(analyzer + """
            "tokenFilters": [{"name": "syn", "@odata.type": "#Microsoft.Azure.Search.SynonymTokenFilter",
              "synonyms": ["a => b => c"]}]
            """), containsString("more than one '=>'"));
        assertThat(rejection(analyzer + """
            "tokenFilters": [{"name": "replace", "@odata.type": "#Microsoft.Azure.Search.PatternReplaceTokenFilter",
              "pattern": "a", "replacement": "$3"}]
            """), containsString("$3"));
    }

    @Test
    void normalizersUseOnlyTheSupportedFilters() {
        String fields = """
            {"name": "idx", "fields": [{"name": "id", "type": "Edm.String", "key": true},
              {"name": "city", "type": "Edm.String", "filterable": true, "normalizer": "my_normalizer"}],
            """;
        EngineFixtures.definition(fields + """
            "normalizers": [{"name": "my_normalizer", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer",
              "charFilters": ["dash"], "tokenFilters": ["lowercase", "my_folding"]}],
            "charFilters": [{"name": "dash", "@odata.type": "#Microsoft.Azure.Search.MappingCharFilter",
              "mappings": ["-=>_"]}],
            "tokenFilters": [{"name": "my_folding", "@odata.type": "#Microsoft.Azure.Search.AsciiFoldingTokenFilter"}]}
            """);
        SearchException stemming = assertThrows(SearchException.class, () -> EngineFixtures.definition(fields + """
            "normalizers": [{"name": "my_normalizer", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer",
              "tokenFilters": ["porter_stem"]}]}
            """));
        assertThat(stemming.getMessage(), containsString("'porter_stem' cannot be used in a normalizer"));
        SearchException html = assertThrows(SearchException.class, () -> EngineFixtures.definition(fields + """
            "normalizers": [{"name": "my_normalizer", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer",
              "charFilters": ["html_strip"]}]}
            """));
        assertThat(html.getMessage(), containsString("html_strip"));
        SearchException builtInName = assertThrows(SearchException.class, () -> EngineFixtures.definition(
            fields.replace("my_normalizer", "lowercase") + """
            "normalizers": [{"name": "lowercase", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer"}]}
            """));
        assertThat(builtInName.getMessage(), containsString("built-in"));
    }
}
