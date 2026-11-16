package io.floci.az.services.search.engine.analysis;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The predefined stop word lists: the {@code stopwordsList} values of the {@code StopwordsTokenFilter} and the lists
 * the language analyzers remove. Lists ship only where a permissively licensed source exists, and each resource file
 * names its source and license in its header:
 * <ul>
 *   <li>{@code english}: {@link TokenFilters#ENGLISH_STOP_WORDS}, the list the {@code stop} analyzer and the
 *       {@code stopwords} filter use by default;</li>
 *   <li>the Snowball project's lists (3-clause BSD, https://snowballstem.org/license.html), under
 *       {@code azure/search/stopwords/snowball/};</li>
 *   <li>the lists Microsoft publishes for its {@code *.microsoft} analyzers in "Stopwords reference (Microsoft
 *       analyzers)" (CC BY 4.0), under {@code azure/search/stopwords/microsoft/}.</li>
 * </ul>
 * Which list stands in for which predefined list or analyzer is recorded in {@link AnalysisApproximations}. Every list
 * is read once, when the class initializes, and lowercased.
 */
public final class StopWords {

    private static final Logger LOG = Logger.getLogger(StopWords.class);
    private static final String ROOT = "azure/search/stopwords/";
    private static final List<String> SNOWBALL_LANGUAGES = List.of(
        "danish", "dutch", "english", "finnish", "french", "german", "hungarian", "indonesian", "irish", "italian",
        "norwegian", "portuguese", "russian", "spanish", "swedish");

    /** The documented {@code StopwordsList} values. */
    public static final Set<String> PREDEFINED_LIST_NAMES = Set.of(
        "arabic", "armenian", "basque", "brazilian", "bulgarian", "catalan", "czech", "danish", "dutch", "english",
        "finnish", "french", "galician", "german", "greek", "hindi", "hungarian", "indonesian", "irish", "italian",
        "latvian", "norwegian", "persian", "portuguese", "romanian", "russian", "sorani", "spanish", "swedish", "thai",
        "turkish");

    /** Each {@code StopwordsList} value with the resource that stands in for it; absent values have no list. */
    private static final Map<String, String> PREDEFINED = Map.ofEntries(
        Map.entry("arabic", "microsoft/ar.microsoft"), Map.entry("brazilian", "snowball/portuguese"),
        Map.entry("bulgarian", "microsoft/bg.microsoft"), Map.entry("catalan", "microsoft/ca.microsoft"),
        Map.entry("czech", "microsoft/cs.microsoft"), Map.entry("danish", "snowball/danish"),
        Map.entry("dutch", "snowball/dutch"), Map.entry("finnish", "snowball/finnish"),
        Map.entry("french", "snowball/french"), Map.entry("german", "snowball/german"),
        Map.entry("greek", "microsoft/el.microsoft"), Map.entry("hindi", "microsoft/hi.microsoft"),
        Map.entry("hungarian", "snowball/hungarian"), Map.entry("indonesian", "snowball/indonesian"),
        Map.entry("irish", "snowball/irish"), Map.entry("italian", "snowball/italian"),
        Map.entry("latvian", "microsoft/lv.microsoft"), Map.entry("norwegian", "snowball/norwegian"),
        Map.entry("portuguese", "snowball/portuguese"), Map.entry("romanian", "microsoft/ro.microsoft"),
        Map.entry("russian", "snowball/russian"), Map.entry("spanish", "snowball/spanish"),
        Map.entry("swedish", "snowball/swedish"), Map.entry("thai", "microsoft/th.microsoft"),
        Map.entry("turkish", "microsoft/tr.microsoft"));

    private static final Map<String, Set<String>> LISTS = loadAll();

    private StopWords() {
    }

    /** The predefined list {@code name} ({@code english}, {@code french}, ...); empty when none ships for it. */
    public static Set<String> predefined(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if ("english".equals(key)) {
            return TokenFilters.ENGLISH_STOP_WORDS;
        }
        String resource = PREDEFINED.get(key);
        return resource == null ? Set.of() : LISTS.getOrDefault(resource, Set.of());
    }

    /** The list the Snowball project publishes for {@code language} ({@code french}); empty when there is none. */
    static Set<String> snowball(String language) {
        return LISTS.getOrDefault("snowball/" + language, Set.of());
    }

    /** The list Microsoft publishes for the analyzer {@code analyzer} ({@code fr.microsoft}), if it publishes one. */
    static Optional<Set<String>> microsoft(String analyzer) {
        return Optional.ofNullable(LISTS.get("microsoft/" + analyzer));
    }

    private static Map<String, Set<String>> loadAll() {
        Map<String, Set<String>> lists = new HashMap<>();
        for (String language : SNOWBALL_LANGUAGES) {
            load("snowball/" + language, lists);
        }
        for (String analyzer : BuiltInAnalyzers.LANGUAGE_ANALYZER_NAMES) {
            if (analyzer.endsWith(".microsoft")) {
                load("microsoft/" + analyzer, lists);
            }
        }
        return Map.copyOf(lists);
    }

    private static void load(String path, Map<String, Set<String>> lists) {
        String resource = ROOT + path + ".txt";
        Set<String> words = new LinkedHashSet<>();
        try (InputStream stream = StopWords.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String word = line.strip();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            LOG.errorv("The stop word list {0} could not be read; it is treated as empty: {1}", resource,
                e.getMessage());
            return;
        }
        lists.put(path, Set.copyOf(words));
    }
}
