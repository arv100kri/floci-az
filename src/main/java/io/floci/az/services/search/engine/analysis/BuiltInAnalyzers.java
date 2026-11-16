package io.floci.az.services.search.engine.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The analyzers and normalizers Azure AI Search provides by name. Add a built-in analyzer here (one entry) and every
 * index can name it.
 *
 * <p>Implemented as Azure documents them: {@code standard.lucene} (standard tokenizer, lowercase, no stop words),
 * {@code standardasciifolding.lucene} (plus ASCII folding), {@code keyword}, {@code pattern} ({@code \W+}, lowercase),
 * {@code simple} (letters, lowercase), {@code stop} (letters, lowercase, English stop words) and
 * {@code whitespace}.</p>
 *
 * <p><b>Stand-ins:</b> the language analyzers ({@code en.lucene}, {@code fr.microsoft}, ...) are accepted so index
 * definitions that name them work, but until the language analyzers land they analyze like {@code standard.lucene}
 * (no stemming, no language stop words). Replace the entries built by {@link #languageStandIn} as real ones land.</p>
 */
final class BuiltInAnalyzers {

    static final String STANDARD = "standard.lucene";

    /** The generic built-in analyzers: every built-in name that is not a language analyzer. */
    static final Set<String> GENERIC_ANALYZER_NAMES = Set.of(
        STANDARD, "standardasciifolding.lucene", "keyword", "pattern", "simple", "stop", "whitespace");

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

    private BuiltInAnalyzers() {
    }

    static Map<String, Analyzer> analyzers() {
        Map<String, Analyzer> analyzers = new LinkedHashMap<>();
        TokenFilter lowercase = TokenFilters.lowercase();
        analyzers.put(STANDARD, new ComposedAnalyzer(STANDARD,
            Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH), List.of(lowercase)));
        analyzers.put("standardasciifolding.lucene", new ComposedAnalyzer("standardasciifolding.lucene",
            Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH), List.of(lowercase, TokenFilters.asciiFolding())));
        analyzers.put("keyword", new ComposedAnalyzer("keyword", Tokenizers.keyword(), List.of()));
        analyzers.put("pattern", new ComposedAnalyzer("pattern", Tokenizers.pattern(Pattern.compile("\\W+")),
            List.of(lowercase)));
        analyzers.put("simple", new ComposedAnalyzer("simple", Tokenizers.letter(), List.of(lowercase)));
        analyzers.put("stop", new ComposedAnalyzer("stop", Tokenizers.letter(),
            List.of(lowercase, TokenFilters.stop(TokenFilters.ENGLISH_STOP_WORDS))));
        analyzers.put("whitespace", new ComposedAnalyzer("whitespace", Tokenizers.whitespace(), List.of()));
        for (String name : LANGUAGE_ANALYZER_NAMES) {
            analyzers.put(name, languageStandIn(name));
        }
        return analyzers;
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

    /** STAND-IN for a language analyzer: the standard analyzer under the language analyzer's name. */
    private static Analyzer languageStandIn(String name) {
        return new ComposedAnalyzer(name, Tokenizers.standard(Tokenizers.DEFAULT_MAX_TOKEN_LENGTH),
            List.of(TokenFilters.lowercase()));
    }

    /** A normalizer that applies {@code filters}' term normalisation in order. */
    static LexicalNormalizer normalizer(String name, TokenFilter... filters) {
        List<TokenFilter> chain = List.of(filters);
        return new LexicalNormalizer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String normalize(String value) {
                String normalized = value;
                for (TokenFilter filter : chain) {
                    normalized = filter.normalizeTerm(normalized);
                }
                return normalized;
            }
        };
    }
}
