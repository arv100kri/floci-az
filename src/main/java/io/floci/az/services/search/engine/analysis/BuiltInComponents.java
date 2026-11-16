package io.floci.az.services.search.engine.analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The analysis components every index can name without defining them: the {@code LexicalTokenizerName},
 * {@code TokenFilterName} and {@code CharFilterName} values, each with its documented defaults, and the subset of
 * token filters a custom normalizer may use.
 */
final class BuiltInComponents {

    /** The built-in tokenizers ({@code LexicalTokenizerName}); {@code keyword} is also read as {@code keyword_v2}. */
    static final Set<String> TOKENIZER_NAMES = Set.of(
        "classic", "edgeNGram", "keyword_v2", "keyword", "letter", "lowercase", "microsoft_language_tokenizer",
        "microsoft_language_stemming_tokenizer", "nGram", "path_hierarchy_v2", "pattern", "standard_v2",
        "uax_url_email", "whitespace");

    /** The built-in token filters ({@code TokenFilterName}). */
    static final Set<String> TOKEN_FILTER_NAMES = Set.of(
        "arabic_normalization", "apostrophe", "asciifolding", "cjk_bigram", "cjk_width", "classic", "common_grams",
        "edgeNGram_v2", "elision", "german_normalization", "hindi_normalization", "indic_normalization",
        "keyword_repeat", "kstem", "length", "limit", "lowercase", "nGram_v2", "persian_normalization", "phonetic",
        "porter_stem", "reverse", "scandinavian_normalization", "scandinavian_folding", "shingle", "snowball",
        "sorani_normalization", "stemmer", "stopwords", "trim", "truncate", "unique", "uppercase", "word_delimiter");

    /** The built-in character filters ({@code CharFilterName}). */
    static final Set<String> CHAR_FILTER_NAMES = Set.of("html_strip");

    /** The built-in token filters a custom normalizer may name ("Text normalization", supported token filters). */
    static final Set<String> NORMALIZER_TOKEN_FILTER_NAMES = Set.of(
        "arabic_normalization", "asciifolding", "cjk_width", "elision", "german_normalization", "hindi_normalization",
        "indic_normalization", "persian_normalization", "scandinavian_normalization", "scandinavian_folding",
        "sorani_normalization", "lowercase", "uppercase");

    /** The {@code language} values of {@code StemmerTokenFilter}, each with how floci-az stems it. */
    static final Map<String, Stemming> STEMMER_LANGUAGES = stemmerLanguages();

    /** The {@code language} values of {@code SnowballTokenFilter}. */
    static final Set<String> SNOWBALL_LANGUAGES = Set.of(
        "armenian", "basque", "catalan", "danish", "dutch", "english", "finnish", "french", "german", "german2",
        "hungarian", "italian", "kp", "lovins", "norwegian", "porter", "portuguese", "romanian", "russian",
        "spanish", "swedish", "turkish");

    private BuiltInComponents() {
    }

    /** How a stemmer language is emulated. */
    enum Stemming {
        /** The Porter stemmer: {@code english}, and the stand-in for the other English algorithms. */
        PORTER,
        /** Harman's S stemmer: {@code minimalEnglish}, and the stand-in for {@code lightEnglish} and kstem. */
        MINIMAL_ENGLISH,
        /** Possessive removal: {@code possessiveEnglish}. */
        POSSESSIVE,
        /** Not emulated: tokens pass through unchanged. */
        NONE
    }

    private static Map<String, Stemming> stemmerLanguages() {
        Map<String, Stemming> languages = new HashMap<>();
        for (String language : new String[] {
            "arabic", "armenian", "basque", "brazilian", "bulgarian", "catalan", "czech", "danish", "dutch", "dutchKp",
            "finnish", "lightFinnish", "french", "lightFrench", "minimalFrench", "galician", "minimalGalician",
            "german", "german2", "lightGerman", "minimalGerman", "greek", "hindi", "hungarian", "lightHungarian",
            "indonesian", "irish", "italian", "lightItalian", "sorani", "latvian", "norwegian", "lightNorwegian",
            "minimalNorwegian", "lightNynorsk", "minimalNynorsk", "portuguese", "lightPortuguese",
            "minimalPortuguese", "portugueseRslp", "romanian", "russian", "lightRussian", "spanish", "lightSpanish",
            "swedish", "lightSwedish", "turkish"}) {
            languages.put(language, Stemming.NONE);
        }
        languages.put("english", Stemming.PORTER);
        languages.put("porter2", Stemming.PORTER);
        languages.put("lovins", Stemming.PORTER);
        languages.put("lightEnglish", Stemming.MINIMAL_ENGLISH);
        languages.put("minimalEnglish", Stemming.MINIMAL_ENGLISH);
        languages.put("possessiveEnglish", Stemming.POSSESSIVE);
        return Map.copyOf(languages);
    }

    /** The stemming filter for {@code stemming}. */
    static TokenFilter stemmer(Stemming stemming) {
        return switch (stemming) {
            case PORTER -> TokenFilters.porterStem();
            case MINIMAL_ENGLISH -> TokenFilters.stemming(TokenFilters::minimalEnglishStem);
            case POSSESSIVE -> TokenFilters.possessiveEnglish();
            case NONE -> TokenFilters.passThrough();
        };
    }

    /** The stemming of a Snowball {@code language}: Porter for the English algorithms, nothing for the others. */
    static Stemming snowball(String language) {
        return switch (language) {
            case "english", "porter", "lovins" -> Stemming.PORTER;
            default -> Stemming.NONE;
        };
    }

    /** The built-in tokenizer {@code name} with its defaults, or null. */
    static Tokenizer tokenizer(String name) {
        return switch (name) {
            case "classic" -> Tokenizers.classic(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
            case "edgeNGram" -> Tokenizers.edgeNGram(1, 2, Set.of());
            case "keyword_v2", "keyword" -> Tokenizers.keyword(Tokenizers.DEFAULT_KEYWORD_MAX_TOKEN_LENGTH);
            case "letter" -> Tokenizers.letter();
            case "lowercase" -> Tokenizers.lowercase();
            case "microsoft_language_tokenizer" ->
                Tokenizers.microsoftLanguage("english", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH, false);
            case "microsoft_language_stemming_tokenizer" ->
                Tokenizers.microsoftLanguage("english", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH, true);
            case "nGram" -> Tokenizers.nGram(1, 2, Set.of());
            case "path_hierarchy_v2" -> Tokenizers.pathHierarchy('/', '/', Tokenizers.MAX_TOKEN_LENGTH_LIMIT, false, 0);
            case "pattern" -> Tokenizers.pattern(Pattern.compile("\\W+"), -1);
            case "standard_v2" -> Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
            case "uax_url_email" -> Tokenizers.uaxUrlEmail(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
            case "whitespace" -> Tokenizers.whitespace();
            default -> null;
        };
    }

    /** The built-in token filter {@code name} with its defaults, or null. */
    static TokenFilter tokenFilter(String name) {
        return switch (name) {
            case "arabic_normalization" -> TokenFilters.arabicNormalization();
            case "apostrophe" -> TokenFilters.apostrophe();
            case "asciifolding" -> TokenFilters.asciiFolding();
            case "cjk_bigram" -> new CjkBigramFilter(Set.of(), false);
            case "cjk_width" -> TokenFilters.cjkWidth();
            case "classic" -> TokenFilters.classic();
            case "common_grams" -> new CommonGramsFilter(Set.of(), false, false);
            case "edgeNGram_v2" -> TokenFilters.edgeNGram(1, 2, false);
            case "elision" -> TokenFilters.elision(TokenFilters.DEFAULT_ELISION_ARTICLES);
            case "german_normalization" -> TokenFilters.germanNormalization();
            case "hindi_normalization", "indic_normalization", "sorani_normalization" -> TokenFilters.passThrough();
            case "phonetic" -> TokenFilters.phonetic(PhoneticEncoders.encoder("metaphone"), true);
            case "keyword_repeat" -> TokenFilters.keywordRepeat();
            case "kstem" -> stemmer(Stemming.MINIMAL_ENGLISH);
            case "length" -> TokenFilters.length(0, Tokenizers.MAX_TOKEN_LENGTH_LIMIT);
            case "limit" -> TokenFilters.limit(1);
            case "lowercase" -> TokenFilters.lowercase();
            case "nGram_v2" -> TokenFilters.nGram(1, 2);
            case "persian_normalization" -> TokenFilters.persianNormalization();
            case "porter_stem" -> TokenFilters.porterStem();
            case "reverse" -> TokenFilters.reverse();
            case "scandinavian_normalization" -> TokenFilters.scandinavianNormalization();
            case "scandinavian_folding" -> TokenFilters.scandinavianFolding();
            case "shingle" -> new ShingleFilter(2, 2, true, false, " ", "_");
            case "snowball", "stemmer" -> stemmer(Stemming.PORTER);
            case "stopwords" -> TokenFilters.stop(TokenFilters.ENGLISH_STOP_WORDS);
            case "trim" -> TokenFilters.trim();
            case "truncate" -> TokenFilters.truncate(Tokenizers.MAX_TOKEN_LENGTH_LIMIT);
            case "unique" -> TokenFilters.unique(false);
            case "uppercase" -> TokenFilters.uppercase();
            case "word_delimiter" ->
                new WordDelimiterFilter(true, true, false, false, false, true, false, true, true, Set.of());
            default -> null;
        };
    }

    /** The built-in character filter {@code name}, or null. */
    static CharFilter charFilter(String name) {
        return "html_strip".equals(name) ? CharFilters.htmlStrip() : null;
    }
}
