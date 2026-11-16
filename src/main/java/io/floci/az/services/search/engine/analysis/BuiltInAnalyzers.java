package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The analyzers and normalizers Azure AI Search provides by name. Add a built-in analyzer here (one entry) and every
 * index can name it.
 *
 * <p>Implemented as Azure documents them: {@code standard.lucene} (also named {@code standard}: standard tokenizer,
 * lowercase, no stop words), {@code standardasciifolding.lucene} (plus ASCII folding; also accepted as
 * {@code asciifolding}, the name "Analyzers for text processing" lists), {@code keyword}, {@code pattern}
 * ({@code \W+}, lowercase), {@code simple} (letters, lowercase), {@code stop} (letters, lowercase, English stop words),
 * {@code whitespace}, and {@code en.lucene} (possessive removal, lowercase, English stop words, the Porter
 * stemmer).</p>
 *
 * <p>Every other language analyzer is approximated: Unicode word boundaries and lowercase, plus the language's stop
 * words where a permissively licensed list exists ({@link StopWords}), French elision for French, character bigrams
 * for Chinese, Japanese and Korean, and the JDK's dictionary-based word boundaries for Thai. {@code en.microsoft}
 * analyzes like {@code en.lucene} with the stop words Microsoft publishes for it. {@link AnalysisApproximations} lists
 * every approximation.</p>
 */
final class BuiltInAnalyzers {

    static final String STANDARD = "standard.lucene";

    /** The generic built-in analyzers: every built-in name that is not a language analyzer. */
    static final Set<String> GENERIC_ANALYZER_NAMES = Set.of(
        STANDARD, "standard", "standardasciifolding.lucene", "asciifolding", "keyword", "pattern", "simple", "stop",
        "whitespace");

    /** The language analyzers of {@code LexicalAnalyzerName}, Lucene and Microsoft. */
    static final List<String> LANGUAGE_ANALYZER_NAMES = List.of(
        "ar.microsoft", "ar.lucene", "hy.lucene", "bn.microsoft", "eu.lucene", "bg.microsoft", "bg.lucene",
        "ca.microsoft", "ca.lucene", "zh-Hans.microsoft", "zh-Hans.lucene", "zh-Hant.microsoft", "zh-Hant.lucene",
        "hr.microsoft", "cs.microsoft", "cs.lucene", "da.microsoft", "da.lucene", "nl.microsoft", "nl.lucene",
        "en.microsoft", "en.lucene", "et.microsoft", "fi.microsoft", "fi.lucene", "fr.microsoft", "fr.lucene",
        "gl.lucene", "de.microsoft", "de.lucene", "el.microsoft", "el.lucene", "gu.microsoft", "he.microsoft",
        "hi.microsoft", "hi.lucene", "hu.microsoft", "hu.lucene", "is.microsoft", "id.microsoft", "id.lucene",
        "ga.lucene", "it.microsoft", "it.lucene", "ja.microsoft", "ja.lucene", "kn.microsoft", "ko.microsoft",
        "ko.lucene", "lv.microsoft", "lv.lucene", "lt.microsoft", "ml.microsoft", "ms.microsoft", "mr.microsoft",
        "nb.microsoft", "no.lucene", "fa.lucene", "pl.microsoft", "pl.lucene", "pt-BR.microsoft", "pt-BR.lucene",
        "pt-PT.microsoft", "pt-PT.lucene", "pa.microsoft", "ro.microsoft", "ro.lucene", "ru.microsoft", "ru.lucene",
        "sr-cyrillic.microsoft", "sr-latin.microsoft", "sk.microsoft", "sl.microsoft", "es.microsoft", "es.lucene",
        "sv.microsoft", "sv.lucene", "ta.microsoft", "te.microsoft", "th.microsoft", "th.lucene", "tr.microsoft",
        "tr.lucene", "uk.microsoft", "ur.microsoft", "vi.microsoft");

    /**
     * The stop word list each Lucene language analyzer removes: a Snowball list ({@code snowball/...}) or the list
     * Microsoft publishes for the same language's Microsoft analyzer ({@code microsoft/...}). Absent: none.
     */
    static final Map<String, String> LUCENE_STOP_WORDS = Map.ofEntries(
        Map.entry("ar.lucene", "microsoft/ar.microsoft"), Map.entry("bg.lucene", "microsoft/bg.microsoft"),
        Map.entry("ca.lucene", "microsoft/ca.microsoft"), Map.entry("cs.lucene", "microsoft/cs.microsoft"),
        Map.entry("da.lucene", "snowball/danish"), Map.entry("nl.lucene", "snowball/dutch"),
        Map.entry("fi.lucene", "snowball/finnish"), Map.entry("fr.lucene", "snowball/french"),
        Map.entry("de.lucene", "snowball/german"), Map.entry("el.lucene", "microsoft/el.microsoft"),
        Map.entry("hi.lucene", "microsoft/hi.microsoft"), Map.entry("hu.lucene", "snowball/hungarian"),
        Map.entry("id.lucene", "snowball/indonesian"), Map.entry("ga.lucene", "snowball/irish"),
        Map.entry("it.lucene", "snowball/italian"), Map.entry("lv.lucene", "microsoft/lv.microsoft"),
        Map.entry("no.lucene", "snowball/norwegian"), Map.entry("pl.lucene", "microsoft/pl.microsoft"),
        Map.entry("pt-BR.lucene", "snowball/portuguese"), Map.entry("pt-PT.lucene", "snowball/portuguese"),
        Map.entry("ro.lucene", "microsoft/ro.microsoft"), Map.entry("ru.lucene", "snowball/russian"),
        Map.entry("es.lucene", "snowball/spanish"), Map.entry("sv.lucene", "snowball/swedish"),
        Map.entry("th.lucene", "microsoft/th.microsoft"), Map.entry("tr.lucene", "microsoft/tr.microsoft"));

    private static final Set<String> CJK_LANGUAGES = Set.of("zh-Hans", "zh-Hant", "ja", "ko");

    private BuiltInAnalyzers() {
    }

    static Map<String, Analyzer> analyzers() {
        Map<String, Analyzer> analyzers = new LinkedHashMap<>();
        TokenFilter lowercase = TokenFilters.lowercase();
        Tokenizer standard = Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH);
        analyzers.put(STANDARD, new ComposedAnalyzer(STANDARD, standard, List.of(lowercase)));
        analyzers.put("standard", new ComposedAnalyzer("standard", standard, List.of(lowercase)));
        analyzers.put("standardasciifolding.lucene", new ComposedAnalyzer("standardasciifolding.lucene",
            standard, List.of(lowercase, TokenFilters.asciiFolding())));
        analyzers.put("asciifolding", new ComposedAnalyzer("asciifolding", standard,
            List.of(lowercase, TokenFilters.asciiFolding())));
        analyzers.put("keyword", new ComposedAnalyzer("keyword", Tokenizers.keyword(), List.of()));
        analyzers.put("pattern", new ComposedAnalyzer("pattern", Tokenizers.pattern(Pattern.compile("\\W+")),
            List.of(lowercase)));
        analyzers.put("simple", new ComposedAnalyzer("simple", Tokenizers.letter(), List.of(lowercase)));
        analyzers.put("stop", new ComposedAnalyzer("stop", Tokenizers.letter(),
            List.of(lowercase, TokenFilters.stop(TokenFilters.ENGLISH_STOP_WORDS))));
        analyzers.put("whitespace", new ComposedAnalyzer("whitespace", Tokenizers.whitespace(), List.of()));
        for (String name : LANGUAGE_ANALYZER_NAMES) {
            analyzers.put(name, languageAnalyzer(name, standard));
        }
        return analyzers;
    }

    private static Analyzer languageAnalyzer(String name, Tokenizer standard) {
        String language = name.substring(0, name.indexOf('.'));
        boolean microsoft = name.endsWith(".microsoft");
        List<TokenFilter> filters = new ArrayList<>();
        if ("en".equals(language)) {
            filters.add(TokenFilters.possessiveEnglish());
        }
        if (CJK_LANGUAGES.contains(language)) {
            filters.add(TokenFilters.cjkWidth());
        }
        if ("fr".equals(language)) {
            filters.add(TokenFilters.elision(TokenFilters.DEFAULT_ELISION_ARTICLES));
        }
        filters.add(TokenFilters.lowercase());
        Set<String> stopWords = stopWords(name, microsoft);
        if (!stopWords.isEmpty()) {
            filters.add(TokenFilters.stop(stopWords));
        }
        if ("en".equals(language)) {
            filters.add(TokenFilters.porterStem());
        }
        if (CJK_LANGUAGES.contains(language)) {
            filters.add(new CjkBigramFilter(Set.of(), false));
        }
        Tokenizer tokenizer = "th".equals(language) ? Tokenizers.thai(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH) : standard;
        return new ComposedAnalyzer(name, tokenizer, filters);
    }

    private static Set<String> stopWords(String name, boolean microsoft) {
        if ("en.lucene".equals(name)) {
            return TokenFilters.ENGLISH_STOP_WORDS;
        }
        if (microsoft) {
            return StopWords.microsoft(name).orElse(Set.of());
        }
        String list = LUCENE_STOP_WORDS.get(name);
        if (list == null) {
            return Set.of();
        }
        return list.startsWith("snowball/") ? StopWords.snowball(list.substring("snowball/".length()))
            : StopWords.microsoft(list.substring("microsoft/".length())).orElse(Set.of());
    }

    static Map<String, LexicalNormalizer> normalizers() {
        Map<String, LexicalNormalizer> normalizers = new LinkedHashMap<>();
        TokenFilter lowercase = TokenFilters.lowercase();
        TokenFilter folding = TokenFilters.asciiFolding();
        normalizers.put("standard", normalizer("standard", lowercase, folding));
        normalizers.put("lowercase", normalizer("lowercase", lowercase));
        normalizers.put("uppercase", normalizer("uppercase", TokenFilters.uppercase()));
        normalizers.put("asciifolding", normalizer("asciifolding", folding));
        normalizers.put("elision", normalizer("elision", TokenFilters.elision(TokenFilters.DEFAULT_ELISION_ARTICLES)));
        return normalizers;
    }

    /** A normalizer that applies {@code filters}' term normalisation in order. */
    static LexicalNormalizer normalizer(String name, TokenFilter... filters) {
        return normalizer(name, List.of(), List.of(filters));
    }

    /**
     * A normalizer that rewrites a whole value with {@code charFilters}, then normalizes it as one term with each of
     * {@code tokenFilters}.
     */
    static LexicalNormalizer normalizer(String name, List<CharFilter> charFilters, List<TokenFilter> tokenFilters) {
        List<CharFilter> characters = List.copyOf(charFilters);
        List<TokenFilter> tokens = List.copyOf(tokenFilters);
        return new LexicalNormalizer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String normalize(String value) {
                String normalized = value;
                for (CharFilter charFilter : characters) {
                    normalized = charFilter.apply(normalized).text();
                }
                for (TokenFilter filter : tokens) {
                    normalized = filter.normalizeTerm(normalized);
                }
                return normalized;
            }
        };
    }
}
