package io.floci.az.services.search.engine.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchJson;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles the analyzers and normalizers an index defines ({@code analyzers}, {@code normalizers}) from the
 * tokenizers, token filters and character filters it names, built-in or defined in the index's own
 * {@code tokenizers}, {@code tokenFilters} and {@code charFilters}.
 *
 * <p>Supported now: the {@code StandardAnalyzer}, {@code StopAnalyzer}, {@code PatternAnalyzer} and
 * {@code CustomAnalyzer} definitions; the tokenizers {@code standard_v2}, {@code classic}, {@code keyword_v2},
 * {@code letter}, {@code lowercase}, {@code whitespace}, {@code pattern} and {@code uax_url_email} (as standard); the
 * token filters {@code lowercase}, {@code uppercase}, {@code asciifolding}, {@code elision}, {@code stopwords} and
 * {@code trim}; the {@code html_strip} character filter; and the index-defined {@code StandardTokenizer(V2)},
 * {@code KeywordTokenizer(V2)}, {@code PatternTokenizer}, {@code StopwordsTokenFilter},
 * {@code AsciiFoldingTokenFilter} and {@code ElisionTokenFilter}. <b>Stand-in:</b> any other component is
 * approximated (the standard tokenizer, or the filter skipped) until the analysis work completes this class.</p>
 */
public final class AnalysisComponents {

    private static final Logger LOG = Logger.getLogger(AnalysisComponents.class);
    private static final String PREFIX = "#microsoft.azure.search.";
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    private final JsonNode index;

    private AnalysisComponents(JsonNode index) {
        this.index = index;
    }

    /** Compiles the analyzer {@code definition} of the index whose definition JSON is {@code index}. */
    public static Analyzer compileAnalyzer(JsonNode definition, JsonNode index) {
        return new AnalysisComponents(index).analyzer(definition);
    }

    /** Compiles the normalizer {@code definition} of the index whose definition JSON is {@code index}. */
    public static LexicalNormalizer compileNormalizer(JsonNode definition, JsonNode index) {
        AnalysisComponents components = new AnalysisComponents(index);
        String name = SearchJson.text(definition, "name");
        List<CharFilter> charFilters = components.charFilters(definition.get("charFilters"));
        List<TokenFilter> tokenFilters = components.tokenFilters(definition.get("tokenFilters"));
        return new LexicalNormalizer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String normalize(String value) {
                String normalized = value;
                for (CharFilter charFilter : charFilters) {
                    normalized = charFilter.apply(normalized).text();
                }
                for (TokenFilter tokenFilter : tokenFilters) {
                    normalized = tokenFilter.normalizeTerm(normalized);
                }
                return normalized;
            }
        };
    }

    private Analyzer analyzer(JsonNode definition) {
        String name = SearchJson.text(definition, "name");
        String type = type(definition);
        return switch (type) {
            case "standardanalyzer" -> new ComposedAnalyzer(name,
                Tokenizers.standard(intValue(definition, "maxTokenLength", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH)),
                List.of(TokenFilters.lowercase(), TokenFilters.stop(words(definition.get("stopwords"), Set.of()))));
            case "stopanalyzer" -> new ComposedAnalyzer(name, Tokenizers.letter(), List.of(TokenFilters.lowercase(),
                TokenFilters.stop(words(definition.get("stopwords"), TokenFilters.ENGLISH_STOP_WORDS))));
            case "patternanalyzer" -> patternAnalyzer(name, definition);
            case "customanalyzer" -> new ComposedAnalyzer(name, charFilters(definition.get("charFilters")),
                tokenizer(SearchJson.text(definition, "tokenizer")), tokenFilters(definition.get("tokenFilters")));
            default -> {
                LOG.debugv("Analyzer {0} has the unsupported type {1}; it analyzes like standard.lucene", name, type);
                yield new ComposedAnalyzer(name, Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH),
                    List.of(TokenFilters.lowercase()));
            }
        };
    }

    private static Analyzer patternAnalyzer(String name, JsonNode definition) {
        JsonNode lowercaseNode = definition.get("lowercase");
        boolean lowercase = lowercaseNode == null || lowercaseNode.isNull() || lowercaseNode.booleanValue();
        String pattern = SearchJson.text(definition, "pattern");
        List<TokenFilter> filters = new ArrayList<>();
        if (lowercase) {
            filters.add(TokenFilters.lowercase());
        }
        filters.add(TokenFilters.stop(words(definition.get("stopwords"), Set.of())));
        return new ComposedAnalyzer(name, Tokenizers.pattern(Pattern.compile(pattern == null ? "\\W+" : pattern,
            regexFlags(SearchJson.text(definition, "flags")))), filters);
    }

    private Tokenizer tokenizer(String name) {
        JsonNode defined = named("tokenizers", name);
        if (defined != null) {
            String type = type(defined);
            return switch (type) {
                case "standardtokenizer", "standardtokenizerv2", "classictokenizer", "uaxurlemailtokenizer" ->
                    Tokenizers.standard(intValue(defined, "maxTokenLength", Tokenizers.DEFAULT_MAX_TOKEN_LENGTH));
                case "keywordtokenizer", "keywordtokenizerv2" -> Tokenizers.keyword();
                case "patterntokenizer" -> {
                    String pattern = SearchJson.text(defined, "pattern");
                    yield Tokenizers.pattern(Pattern.compile(pattern == null ? "\\W+" : pattern,
                        regexFlags(SearchJson.text(defined, "flags"))));
                }
                default -> {
                    LOG.debugv("Tokenizer {0} has the unsupported type {1}; the standard tokenizer stands in",
                        name, type);
                    yield Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
                }
            };
        }
        return switch (name == null ? "" : name) {
            case "keyword_v2", "keyword" -> Tokenizers.keyword();
            case "letter" -> Tokenizers.letter();
            case "whitespace" -> Tokenizers.whitespace();
            case "pattern" -> Tokenizers.pattern(Pattern.compile("\\W+"));
            case "lowercase" -> {
                Tokenizer letters = Tokenizers.letter();
                TokenFilter lower = TokenFilters.lowercase();
                yield text -> lower.filter(letters.tokenize(text));
            }
            default -> Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
        };
    }

    private List<TokenFilter> tokenFilters(JsonNode names) {
        List<TokenFilter> filters = new ArrayList<>();
        if (names == null || !names.isArray()) {
            return filters;
        }
        for (JsonNode nameNode : names) {
            TokenFilter filter = tokenFilter(nameNode.asText());
            if (filter != null) {
                filters.add(filter);
            }
        }
        return filters;
    }

    private TokenFilter tokenFilter(String name) {
        JsonNode defined = named("tokenFilters", name);
        if (defined != null) {
            String type = type(defined);
            return switch (type) {
                case "stopwordstokenfilter" -> TokenFilters.stop(words(defined.get("stopwords"),
                    TokenFilters.ENGLISH_STOP_WORDS));
                case "asciifoldingtokenfilter" -> TokenFilters.asciiFolding();
                case "elisiontokenfilter" -> TokenFilters.elision(words(defined.get("articles"),
                    TokenFilters.DEFAULT_ELISION_ARTICLES));
                default -> {
                    LOG.debugv("Token filter {0} has the unsupported type {1}; it is skipped", name, type);
                    yield null;
                }
            };
        }
        return switch (name) {
            case "lowercase" -> TokenFilters.lowercase();
            case "uppercase" -> TokenFilters.uppercase();
            case "asciifolding" -> TokenFilters.asciiFolding();
            case "elision" -> TokenFilters.elision(TokenFilters.DEFAULT_ELISION_ARTICLES);
            case "stopwords" -> TokenFilters.stop(TokenFilters.ENGLISH_STOP_WORDS);
            case "trim" -> TokenFilters.normalizing(String::strip);
            default -> {
                LOG.debugv("The built-in token filter {0} is not emulated yet; it is skipped", name);
                yield null;
            }
        };
    }

    private List<CharFilter> charFilters(JsonNode names) {
        List<CharFilter> filters = new ArrayList<>();
        if (names == null || !names.isArray()) {
            return filters;
        }
        for (JsonNode nameNode : names) {
            String name = nameNode.asText();
            if ("html_strip".equals(name)) {
                filters.add(input -> new CharFilter.Result(blankOutTags(input), IntUnaryOperator.identity()));
            } else {
                LOG.debugv("Character filter {0} is not emulated yet; it is skipped", name);
            }
        }
        return filters;
    }

    /** Replaces each HTML tag with spaces of the same length, so offsets into the text stay valid. */
    private static String blankOutTags(String input) {
        StringBuilder text = new StringBuilder(input);
        Matcher matcher = HTML_TAG.matcher(input);
        while (matcher.find()) {
            for (int index = matcher.start(); index < matcher.end(); index++) {
                text.setCharAt(index, ' ');
            }
        }
        return text.toString();
    }

    private JsonNode named(String section, String name) {
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

    private static String type(JsonNode definition) {
        String type = SearchJson.text(definition, "@odata.type");
        if (type == null) {
            return "";
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith(PREFIX) ? lower.substring(PREFIX.length()) : lower;
    }

    private static int intValue(JsonNode definition, String property, int defaultValue) {
        JsonNode value = definition.get(property);
        return value != null && value.isInt() ? value.intValue() : defaultValue;
    }

    private static Set<String> words(JsonNode list, Set<String> defaultWords) {
        if (list == null || !list.isArray()) {
            return defaultWords;
        }
        Set<String> words = new HashSet<>();
        list.forEach(word -> words.add(word.asText()));
        return words;
    }

    private static int regexFlags(String flags) {
        if (flags == null || flags.isBlank()) {
            return 0;
        }
        int bits = 0;
        for (String flag : flags.split("\\|")) {
            bits |= switch (flag.trim().toUpperCase(Locale.ROOT)) {
                case "CANON_EQ" -> Pattern.CANON_EQ;
                case "CASE_INSENSITIVE" -> Pattern.CASE_INSENSITIVE;
                case "COMMENTS" -> Pattern.COMMENTS;
                case "DOTALL" -> Pattern.DOTALL;
                case "LITERAL" -> Pattern.LITERAL;
                case "MULTILINE" -> Pattern.MULTILINE;
                case "UNICODE_CASE" -> Pattern.UNICODE_CASE;
                case "UNIX_LINES" -> Pattern.UNIX_LINES;
                default -> 0;
            };
        }
        return bits;
    }
}
