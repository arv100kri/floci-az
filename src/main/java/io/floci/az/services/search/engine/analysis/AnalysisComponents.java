package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the analysis components an index defines ({@code analyzers}, {@code normalizers}, {@code tokenizers},
 * {@code tokenFilters} and {@code charFilters}) into runnable ones, resolving the names they reference against the
 * index's own components first and the built-in ones ({@link BuiltInComponents}) second. Every documented component
 * type is accepted and its parameters are read with their documented defaults and limits; an unknown type, a bad
 * parameter or an unresolvable name is an {@link IllegalArgumentException} naming it. Components floci-az cannot run
 * faithfully are approximated as {@link AnalysisApproximations} records.
 */
public final class AnalysisComponents {

    /** The prefix of every analysis component's {@code @odata.type}. */
    static final String TYPE_PREFIX = "#microsoft.azure.search.";

    /** The {@code @odata.type} names (without prefix, lowercased) each index section accepts. */
    static final Map<String, Set<String>> TYPES = Map.of(
        "analyzers", Set.of("customanalyzer", "patternanalyzer", "standardanalyzer", "stopanalyzer"),
        "tokenizers", Set.of("classictokenizer", "edgengramtokenizer", "keywordtokenizer", "keywordtokenizerv2",
            "microsoftlanguagetokenizer", "microsoftlanguagestemmingtokenizer", "ngramtokenizer",
            "pathhierarchytokenizerv2", "patterntokenizer", "standardtokenizer", "standardtokenizerv2",
            "uaxurlemailtokenizer"),
        "tokenFilters", Set.of("asciifoldingtokenfilter", "cjkbigramtokenfilter", "commongramtokenfilter",
            "dictionarydecompoundertokenfilter", "edgengramtokenfilter", "edgengramtokenfilterv2", "elisiontokenfilter",
            "keeptokenfilter", "keywordmarkertokenfilter", "lengthtokenfilter", "limittokenfilter", "ngramtokenfilter",
            "ngramtokenfilterv2", "patterncapturetokenfilter", "patternreplacetokenfilter", "phonetictokenfilter",
            "shingletokenfilter", "snowballtokenfilter", "stemmertokenfilter", "stemmeroverridetokenfilter",
            "stopwordstokenfilter", "synonymtokenfilter", "truncatetokenfilter", "uniquetokenfilter",
            "worddelimitertokenfilter", "indicnormalizationtokenfilter", "soraninormalizationtokenfilter"),
        "charFilters", Set.of("mappingcharfilter", "patternreplacecharfilter"),
        "normalizers", Set.of("customnormalizer"));

    /** The token filter types a custom normalizer may use: the ones that rewrite a value without splitting it. */
    static final Set<String> NORMALIZER_TOKEN_FILTER_TYPES = Set.of(
        "asciifoldingtokenfilter", "elisiontokenfilter", "indicnormalizationtokenfilter",
        "soraninormalizationtokenfilter");

    /** The {@code language} values of the Microsoft tokenizers. */
    static final Set<String> MICROSOFT_TOKENIZER_LANGUAGES = Set.of(
        "bangla", "bulgarian", "catalan", "chineseSimplified", "chineseTraditional", "croatian", "czech", "danish",
        "dutch", "english", "french", "german", "greek", "gujarati", "hindi", "icelandic", "indonesian", "italian",
        "japanese", "kannada", "korean", "malay", "malayalam", "marathi", "norwegianBokmaal", "polish", "portuguese",
        "portugueseBrazilian", "punjabi", "romanian", "russian", "serbianCyrillic", "serbianLatin", "slovenian",
        "spanish", "swedish", "tamil", "telugu", "thai", "ukrainian", "urdu", "vietnamese");

    /** The {@code language} values of the Microsoft stemming tokenizer. */
    static final Set<String> MICROSOFT_STEMMING_LANGUAGES = Set.of(
        "arabic", "bangla", "bulgarian", "catalan", "croatian", "czech", "danish", "dutch", "english", "estonian",
        "finnish", "french", "german", "greek", "gujarati", "hebrew", "hindi", "hungarian", "icelandic", "indonesian",
        "italian", "kannada", "latvian", "lithuanian", "malay", "malayalam", "marathi", "norwegianBokmaal", "polish",
        "portuguese", "portugueseBrazilian", "punjabi", "romanian", "russian", "serbianCyrillic", "serbianLatin",
        "slovak", "slovenian", "spanish", "swedish", "tamil", "telugu", "turkish", "ukrainian", "urdu");

    private static final Set<String> CJK_SCRIPTS = Set.of("han", "hiragana", "katakana", "hangul");
    private static final Set<String> EDGE_SIDES = Set.of("front", "back");
    private static final Set<String> PHONETIC_ENCODERS = Set.of(
        "metaphone", "doubleMetaphone", "soundex", "refinedSoundex", "caverphone1", "caverphone2", "cologne",
        "nysiis", "koelnerPhonetik", "haasePhonetik", "beiderMorse");
    private static final int MAX = Tokenizers.MAX_TOKEN_LENGTH_LIMIT;

    private final JsonNode index;

    private AnalysisComponents(JsonNode index) {
        this.index = index;
    }

    /** The components of the index whose definition JSON is {@code index} (null for none). */
    public static AnalysisComponents of(JsonNode index) {
        return new AnalysisComponents(index);
    }

    /** Compiles the analyzer {@code definition} of the index whose definition JSON is {@code index}. */
    public static Analyzer compileAnalyzer(JsonNode definition, JsonNode index) {
        return new AnalysisComponents(index).analyzer(definition);
    }

    /** Compiles the normalizer {@code definition} of the index whose definition JSON is {@code index}. */
    public static LexicalNormalizer compileNormalizer(JsonNode definition, JsonNode index) {
        return new AnalysisComponents(index).normalizer(definition);
    }

    /** The {@code @odata.type} of {@code definition} without its prefix, lowercased; empty when it has none. */
    static String type(JsonNode definition) {
        String type = SearchJson.text(definition, "@odata.type");
        if (type == null) {
            return "";
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith(TYPE_PREFIX) ? lower.substring(TYPE_PREFIX.length()) : lower;
    }

    /** The entry of the index section {@code section} named {@code name}, or null. */
    JsonNode defined(String section, String name) {
        JsonNode entries = index == null ? null : index.get(section);
        if (entries == null || !entries.isArray() || name == null) {
            return null;
        }
        for (JsonNode entry : entries) {
            if (name.equals(SearchJson.text(entry, "name"))) {
                return entry;
            }
        }
        return null;
    }

    /** Compiles an analyzer the index defines. */
    public Analyzer analyzer(JsonNode definition) {
        ComponentDefinition component = new ComponentDefinition(definition, "analyzer");
        String name = component.name();
        return switch (type(definition)) {
            case "customanalyzer" -> {
                String tokenizer = component.requiredString("tokenizer");
                yield new ComposedAnalyzer(name, charFilters(component.strings("charFilters")), tokenizer(tokenizer),
                    tokenFilters(component.strings("tokenFilters")));
            }
            case "standardanalyzer" -> new ComposedAnalyzer(name,
                Tokenizers.standard(component.integer("maxTokenLength", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH, 1, MAX)),
                List.of(TokenFilters.lowercase(), TokenFilters.stop(new HashSet<>(component.strings("stopwords")))));
            case "stopanalyzer" -> new ComposedAnalyzer(name, Tokenizers.letter(), List.of(TokenFilters.lowercase(),
                TokenFilters.stop(component.has("stopwords") ? new HashSet<>(component.strings("stopwords"))
                    : TokenFilters.ENGLISH_STOP_WORDS)));
            case "patternanalyzer" -> {
                boolean lowercase = component.bool("lowercase", true);
                Pattern pattern = component.pattern("pattern", "\\W+", component.regexFlags());
                List<TokenFilter> filters = new ArrayList<>();
                if (lowercase) {
                    filters.add(TokenFilters.lowercase());
                }
                filters.add(TokenFilters.stop(new HashSet<>(component.strings("stopwords"))));
                yield new ComposedAnalyzer(name, Tokenizers.pattern(pattern, -1), filters);
            }
            default -> throw unknownType(component, "analyzers");
        };
    }

    /** Compiles a normalizer the index defines: its character filters, then its token filters, over the whole value. */
    public LexicalNormalizer normalizer(JsonNode definition) {
        ComponentDefinition component = new ComponentDefinition(definition, "normalizer");
        if (!"customnormalizer".equals(type(definition))) {
            throw unknownType(component, "normalizers");
        }
        List<CharFilter> charFilters = new ArrayList<>();
        for (String name : component.strings("charFilters")) {
            JsonNode defined = defined("charFilters", name);
            if (defined == null) {
                throw component.invalid("the character filter '" + name + "' is not a mapping or pattern_replace "
                    + "character filter the index defines; normalizers support only those");
            }
            charFilters.add(charFilter(name));
        }
        List<TokenFilter> tokenFilters = new ArrayList<>();
        for (String name : component.strings("tokenFilters")) {
            JsonNode defined = defined("tokenFilters", name);
            boolean supported = defined != null ? NORMALIZER_TOKEN_FILTER_TYPES.contains(type(defined))
                : BuiltInComponents.NORMALIZER_TOKEN_FILTER_NAMES.contains(name);
            if (!supported) {
                throw component.invalid("the token filter '" + name + "' cannot be used in a normalizer; the "
                    + "supported token filters are " + String.join(", ",
                    BuiltInComponents.NORMALIZER_TOKEN_FILTER_NAMES.stream().sorted().toList()));
            }
            tokenFilters.add(tokenFilter(name));
        }
        return BuiltInAnalyzers.normalizer(component.name(), charFilters, tokenFilters);
    }

    /** The tokenizer {@code name}: one the index defines, else a built-in one. */
    public Tokenizer tokenizer(String name) {
        JsonNode defined = defined("tokenizers", name);
        if (defined == null) {
            Tokenizer builtIn = name == null ? null : BuiltInComponents.tokenizer(name);
            if (builtIn == null) {
                throw new IllegalArgumentException("the tokenizer '" + name + "' is neither built in nor defined by "
                    + "the index.");
            }
            return builtIn;
        }
        ComponentDefinition component = new ComponentDefinition(defined, "tokenizer");
        int defaultLength = Tokenizers.DEFAULT_MAX_TOKEN_LENGTH;
        return switch (type(defined)) {
            case "classictokenizer" -> Tokenizers.classic(component.integer("maxTokenLength", defaultLength, 1, MAX));
            case "standardtokenizer" -> Tokenizers.standard(
                component.integer("maxTokenLength", defaultLength, 1, Integer.MAX_VALUE));
            case "standardtokenizerv2" -> Tokenizers.standard(component.integer("maxTokenLength", defaultLength, 1,
                MAX));
            case "uaxurlemailtokenizer" -> Tokenizers.uaxUrlEmail(component.integer("maxTokenLength", defaultLength,
                1, MAX));
            case "keywordtokenizer" -> {
                component.integer("bufferSize", 256, 1, Integer.MAX_VALUE);
                yield Tokenizers.keyword();
            }
            case "keywordtokenizerv2" -> Tokenizers.keyword(component.integer("maxTokenLength",
                Tokenizers.DEFAULT_KEYWORD_MAX_TOKEN_LENGTH, 1, MAX));
            case "microsoftlanguagetokenizer" -> microsoft(component, MICROSOFT_TOKENIZER_LANGUAGES, false);
            case "microsoftlanguagestemmingtokenizer" -> microsoft(component, MICROSOFT_STEMMING_LANGUAGES, true);
            case "ngramtokenizer", "edgengramtokenizer" -> {
                int[] grams = grams(component, MAX);
                Set<String> tokenChars = new LinkedHashSet<>();
                for (String kind : component.strings("tokenChars")) {
                    if (!CharacterClasses.TOKEN_CHARACTER_KINDS.contains(kind.toLowerCase(Locale.ROOT))) {
                        throw component.invalid("'tokenChars' has the unsupported value '" + kind + "'");
                    }
                    tokenChars.add(kind.toLowerCase(Locale.ROOT));
                }
                yield "ngramtokenizer".equals(type(defined)) ? Tokenizers.nGram(grams[0], grams[1], tokenChars)
                    : Tokenizers.edgeNGram(grams[0], grams[1], tokenChars);
            }
            case "pathhierarchytokenizerv2" -> {
                String delimiter = component.string("delimiter", "/");
                String replacement = component.string("replacement", delimiter);
                if (delimiter.length() != 1 || replacement.length() != 1) {
                    throw component.invalid("'delimiter' and 'replacement' must be single characters");
                }
                yield Tokenizers.pathHierarchy(delimiter.charAt(0), replacement.charAt(0),
                    component.integer("maxTokenLength", MAX, 1, MAX), component.bool("reverse", false),
                    component.integer("skip", 0, 0, Integer.MAX_VALUE));
            }
            case "patterntokenizer" -> Tokenizers.pattern(component.pattern("pattern", "\\W+", component.regexFlags()),
                component.integer("group", -1, -1, Integer.MAX_VALUE));
            default -> throw unknownType(component, "tokenizers");
        };
    }

    private static Tokenizer microsoft(ComponentDefinition component, Set<String> languages, boolean stemming) {
        int maxTokenLength = component.integer("maxTokenLength", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH, 1, MAX);
        component.bool("isSearchTokenizer", false);
        String language = component.choice("language", "english", languages);
        return Tokenizers.microsoftLanguage(language, maxTokenLength, stemming);
    }

    /** {@code minGram} and {@code maxGram}, with {@code minGram <= maxGram}. */
    private static int[] grams(ComponentDefinition component, int max) {
        int minGram = component.integer("minGram", 1, 1, max);
        int maxGram = component.integer("maxGram", 2, 1, max);
        if (minGram > maxGram) {
            throw component.invalid("'minGram' (" + minGram + ") must not be greater than 'maxGram' (" + maxGram + ")");
        }
        return new int[] {minGram, maxGram};
    }

    /** The token filters {@code names} name, in order. */
    public List<TokenFilter> tokenFilters(List<String> names) {
        List<TokenFilter> filters = new ArrayList<>(names.size());
        for (String name : names) {
            filters.add(tokenFilter(name));
        }
        return filters;
    }

    /** The character filters {@code names} name, in order. */
    public List<CharFilter> charFilters(List<String> names) {
        List<CharFilter> filters = new ArrayList<>(names.size());
        for (String name : names) {
            filters.add(charFilter(name));
        }
        return filters;
    }

    /** The token filter {@code name}: one the index defines, else a built-in one. */
    public TokenFilter tokenFilter(String name) {
        JsonNode defined = defined("tokenFilters", name);
        if (defined == null) {
            TokenFilter builtIn = name == null ? null : BuiltInComponents.tokenFilter(name);
            if (builtIn == null) {
                throw new IllegalArgumentException("the token filter '" + name + "' is neither built in nor defined "
                    + "by the index.");
            }
            return builtIn;
        }
        ComponentDefinition component = new ComponentDefinition(defined, "token filter");
        return switch (type(defined)) {
            case "asciifoldingtokenfilter" -> TokenFilters.asciiFolding(component.bool("preserveOriginal", false));
            case "cjkbigramtokenfilter" -> {
                Set<String> ignored = new HashSet<>();
                for (String script : component.strings("ignoreScripts")) {
                    if (!CJK_SCRIPTS.contains(script.toLowerCase(Locale.ROOT))) {
                        throw component.invalid("'ignoreScripts' has the unsupported value '" + script + "'");
                    }
                    ignored.add(script.toLowerCase(Locale.ROOT));
                }
                yield new CjkBigramFilter(ignored, component.bool("outputUnigrams", false));
            }
            case "commongramtokenfilter" -> new CommonGramsFilter(
                new HashSet<>(component.requiredStrings("commonWords")), component.bool("ignoreCase", false),
                component.bool("queryMode", false));
            case "dictionarydecompoundertokenfilter" -> TokenFilters.dictionaryDecompounder(
                new HashSet<>(component.requiredStrings("wordList")), component.integer("minWordSize", 5, 0, MAX),
                component.integer("minSubwordSize", 2, 1, MAX), component.integer("maxSubwordSize", 15, 1, MAX),
                component.bool("onlyLongestMatch", false));
            case "edgengramtokenfilter", "edgengramtokenfilterv2" -> {
                int[] grams = grams(component, "edgengramtokenfilter".equals(type(defined)) ? Integer.MAX_VALUE : MAX);
                boolean back = "back".equals(component.choice("side", "front", EDGE_SIDES));
                yield TokenFilters.edgeNGram(grams[0], grams[1], back);
            }
            case "ngramtokenfilter", "ngramtokenfilterv2" -> {
                int[] grams = grams(component, "ngramtokenfilter".equals(type(defined)) ? Integer.MAX_VALUE : MAX);
                yield TokenFilters.nGram(grams[0], grams[1]);
            }
            case "elisiontokenfilter" -> TokenFilters.elision(component.has("articles")
                ? TokenFilters.lowercaseAll(new HashSet<>(component.strings("articles")))
                : TokenFilters.DEFAULT_ELISION_ARTICLES);
            case "keeptokenfilter" -> TokenFilters.keep(new HashSet<>(component.requiredStrings("keepWords")),
                component.bool("keepWordsCase", false));
            case "keywordmarkertokenfilter" -> TokenFilters.keywordMarker(
                new HashSet<>(component.requiredStrings("keywords")), component.bool("ignoreCase", false));
            case "lengthtokenfilter" -> {
                int min = component.integer("min", 0, 0, MAX);
                int max = component.integer("max", MAX, 0, MAX);
                if (min > max) {
                    throw component.invalid("'min' (" + min + ") must not be greater than 'max' (" + max + ")");
                }
                yield TokenFilters.length(min, max);
            }
            case "limittokenfilter" -> {
                component.bool("consumeAllTokens", false);
                yield TokenFilters.limit(component.integer("maxTokenCount", 1, 0, Integer.MAX_VALUE));
            }
            case "patterncapturetokenfilter" -> {
                List<Pattern> patterns = new ArrayList<>();
                for (String pattern : component.requiredStrings("patterns")) {
                    patterns.add(component.compile("patterns", pattern, 0));
                }
                yield TokenFilters.patternCapture(patterns, component.bool("preserveOriginal", true));
            }
            case "patternreplacetokenfilter" -> {
                Pattern pattern = component.pattern("pattern", null, 0);
                yield TokenFilters.patternReplace(pattern, replacement(component, pattern));
            }
            case "phonetictokenfilter" -> TokenFilters.phonetic(PhoneticEncoders.encoder(
                component.choice("encoder", "metaphone", PHONETIC_ENCODERS)), component.bool("replace", true));
            case "shingletokenfilter" -> {
                int maxSize = component.integer("maxShingleSize", 2, 2, Integer.MAX_VALUE);
                int minSize = component.integer("minShingleSize", 2, 2, Integer.MAX_VALUE);
                if (minSize > maxSize) {
                    throw component.invalid("'minShingleSize' (" + minSize + ") must not be greater than "
                        + "'maxShingleSize' (" + maxSize + ")");
                }
                yield new ShingleFilter(minSize, maxSize, component.bool("outputUnigrams", true),
                    component.bool("outputUnigramsIfNoShingles", false), component.string("tokenSeparator", " "),
                    component.string("filterToken", "_"));
            }
            case "snowballtokenfilter" -> BuiltInComponents.stemmer(BuiltInComponents.snowball(
                component.requiredChoice("language", BuiltInComponents.SNOWBALL_LANGUAGES)));
            case "stemmertokenfilter" -> BuiltInComponents.stemmer(BuiltInComponents.STEMMER_LANGUAGES.get(
                component.requiredChoice("language", BuiltInComponents.STEMMER_LANGUAGES.keySet())));
            case "stemmeroverridetokenfilter" -> TokenFilters.stemmerOverride(stemmerOverrides(component));
            case "stopwordstokenfilter" -> stopwords(component);
            case "synonymtokenfilter" -> {
                SolrSynonymRules rules;
                try {
                    rules = SolrSynonymRules.parse(component.requiredStrings("synonyms"));
                } catch (IllegalArgumentException e) {
                    throw component.invalid(e.getMessage());
                }
                yield new SynonymTokenFilter(rules, component.bool("ignoreCase", false), component.bool("expand",
                    true));
            }
            case "truncatetokenfilter" -> TokenFilters.truncate(component.integer("length", MAX, 0, MAX));
            case "uniquetokenfilter" -> TokenFilters.unique(component.bool("onlyOnSamePosition", false));
            case "worddelimitertokenfilter" -> new WordDelimiterFilter(component.bool("generateWordParts", true),
                component.bool("generateNumberParts", true), component.bool("catenateWords", false),
                component.bool("catenateNumbers", false), component.bool("catenateAll", false),
                component.bool("splitOnCaseChange", true), component.bool("preserveOriginal", false),
                component.bool("splitOnNumerics", true), component.bool("stemEnglishPossessive", true),
                new HashSet<>(component.strings("protectedWords")));
            case "indicnormalizationtokenfilter", "soraninormalizationtokenfilter" -> TokenFilters.passThrough();
            default -> throw unknownType(component, "tokenFilters");
        };
    }

    private static Map<String, String> stemmerOverrides(ComponentDefinition component) {
        Map<String, String> overrides = new HashMap<>();
        for (String rule : component.requiredStrings("rules")) {
            int arrow = rule.indexOf("=>");
            String stem = arrow < 0 ? "" : rule.substring(arrow + 2).strip();
            if (arrow < 0 || stem.isEmpty()) {
                throw component.invalid("the rule '" + rule + "' is not of the form 'word => stem'");
            }
            for (String word : rule.substring(0, arrow).split(",")) {
                if (word.isBlank()) {
                    throw component.invalid("the rule '" + rule + "' has an empty word");
                }
                overrides.put(word.strip(), stem);
            }
        }
        return overrides;
    }

    private static TokenFilter stopwords(ComponentDefinition component) {
        if (component.has("stopwords") && component.has("stopwordsList")) {
            throw component.invalid("'stopwords' and 'stopwordsList' cannot both be set");
        }
        Set<String> words;
        if (component.has("stopwords")) {
            words = new HashSet<>(component.strings("stopwords"));
        } else {
            words = StopWords.predefined(component.choice("stopwordsList", "english", StopWords.PREDEFINED_LIST_NAMES));
        }
        return TokenFilters.stop(words, component.bool("ignoreCase", false), component.bool("removeTrailing", true));
    }

    /** The character filter {@code name}: one the index defines, else a built-in one. */
    public CharFilter charFilter(String name) {
        JsonNode defined = defined("charFilters", name);
        if (defined == null) {
            CharFilter builtIn = name == null ? null : BuiltInComponents.charFilter(name);
            if (builtIn == null) {
                throw new IllegalArgumentException("the character filter '" + name + "' is neither built in nor "
                    + "defined by the index.");
            }
            return builtIn;
        }
        ComponentDefinition component = new ComponentDefinition(defined, "character filter");
        return switch (type(defined)) {
            case "mappingcharfilter" -> {
                List<String> mappings = component.requiredStrings("mappings");
                try {
                    yield CharFilters.mapping(mappings);
                } catch (IllegalArgumentException e) {
                    throw component.invalid(e.getMessage());
                }
            }
            case "patternreplacecharfilter" -> {
                Pattern pattern = component.pattern("pattern", null, 0);
                yield CharFilters.patternReplace(pattern, replacement(component, pattern));
            }
            default -> throw unknownType(component, "charFilters");
        };
    }

    private static String replacement(ComponentDefinition component, Pattern pattern) {
        String replacement = component.requiredString("replacement");
        try {
            Replacements.validate(replacement, pattern);
        } catch (IllegalArgumentException e) {
            throw component.invalid(e.getMessage());
        }
        return replacement;
    }

    private static IllegalArgumentException unknownType(ComponentDefinition component, String section) {
        String type = SearchJson.text(component.node(), "@odata.type");
        return component.invalid("'@odata.type' '" + type + "' is not a type the index section '" + section
            + "' supports");
    }
}
