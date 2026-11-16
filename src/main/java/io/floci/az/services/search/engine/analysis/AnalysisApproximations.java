package io.floci.az.services.search.engine.analysis;

import java.util.List;
import java.util.Map;

/**
 * The one list of every analysis behavior floci-az approximates rather than reproduces, for the documentation.
 * Everything not listed here follows Azure's documentation (and, for {@code en.lucene} and {@code porter_stem},
 * Martin Porter's published algorithm). Stop word lists come only from permissively licensed sources
 * ({@link StopWords}).
 *
 * <table>
 *   <caption>Analysis approximations</caption>
 *   <tr><th>Component</th><th>floci-az behavior</th></tr>
 *   <tr><td>standard tokenizer ({@code standard.lucene}, {@code standard_v2}, every language analyzer)</td>
 *       <td>Unicode word boundaries from the JDK's {@code BreakIterator}, split further at dashes and double quotes and
 *       between ideographs and Hiragana characters (one token each), as UAX #29 does. Rare UAX #29 cases (emoji
 *       sequences, some mixed letter and number forms) can differ.</td></tr>
 *   <tr><td>{@code *.lucene} language analyzers other than {@code en.lucene}</td>
 *       <td>Unicode word boundaries and lowercase, plus the language's stop words: the Snowball list for Danish, Dutch,
 *       Finnish, French, German, Hungarian, Indonesian, Irish, Italian, Norwegian, Portuguese (also for
 *       {@code pt-BR.lucene}), Russian, Spanish and Swedish; Microsoft's published list for the same language for
 *       Arabic, Bulgarian, Catalan, Czech, Greek, Hindi, Latvian, Polish, Romanian, Thai and Turkish; none for
 *       Armenian, Basque, Chinese, Galician, Japanese, Korean and Persian. No stemming. French adds elision. Chinese,
 *       Japanese and Korean use {@code cjk_width} and overlapping character bigrams instead of dictionary or
 *       morphological segmentation; Thai uses the JDK's dictionary-based Thai word boundaries.</td></tr>
 *   <tr><td>{@code *.microsoft} language analyzers other than {@code en.microsoft}</td>
 *       <td>The same as the Lucene approximation, with the stop words Microsoft publishes for the analyzer. No
 *       lemmatization, decompounding or entity recognition.</td></tr>
 *   <tr><td>{@code en.microsoft}</td>
 *       <td>Analyzes like {@code en.lucene} (possessive removal, lowercase, Porter stemming), with the stop words
 *       Microsoft publishes for {@code en.microsoft}; stemming stands in for lemmatization.</td></tr>
 *   <tr><td>{@code classic} tokenizer</td>
 *       <td>An approximation of the classic grammar: e-mail addresses, acronyms, company names, words with
 *       apostrophes, host names and numbers with punctuation stay whole within each whitespace-separated chunk; the
 *       rest splits into letter and digit runs.</td></tr>
 *   <tr><td>{@code uax_url_email} tokenizer</td>
 *       <td>The standard tokenizer, with URLs (with a scheme or starting {@code www.}) and e-mail addresses kept whole
 *       as recognized by regular expressions, not the full UAX #29 URL grammar.</td></tr>
 *   <tr><td>{@code microsoft_language_tokenizer}, {@code microsoft_language_stemming_tokenizer}</td>
 *       <td>Unicode word boundaries whatever the {@code language}; {@code isSearchTokenizer} has no effect. The
 *       stemming tokenizer reduces lowercase English words with the Porter stemmer and leaves every other language
 *       unstemmed.</td></tr>
 *   <tr><td>{@code keyword} tokenizer (deprecated v1)</td>
 *       <td>{@code bufferSize} is accepted and has no effect; the whole input is one token.</td></tr>
 *   <tr><td>{@code stemmer} token filter</td>
 *       <td>{@code english} is the Porter stemmer and {@code minimalEnglish} Harman's S stemmer, as documented;
 *       {@code porter2} and {@code lovins} use the Porter stemmer; {@code lightEnglish} uses the S stemmer;
 *       {@code possessiveEnglish} removes {@code 's}; every other language passes tokens through unchanged.</td></tr>
 *   <tr><td>{@code snowball} token filter</td>
 *       <td>{@code porter} is the Porter stemmer; {@code english} and {@code lovins} use it too; every other language
 *       passes tokens through unchanged.</td></tr>
 *   <tr><td>{@code kstem} token filter</td><td>Harman's S stemmer (plural removal) stands in.</td></tr>
 *   <tr><td>{@code phonetic} token filter</td>
 *       <td>{@code soundex} is American Soundex as the U.S. National Archives documents it and {@code metaphone}
 *       Philips' original Metaphone (codes of at most four characters); {@code refinedSoundex}, {@code cologne},
 *       {@code koelnerPhonetik} and {@code haasePhonetik} use Soundex, and {@code doubleMetaphone},
 *       {@code caverphone1}, {@code caverphone2}, {@code nysiis} and {@code beiderMorse} use Metaphone.</td></tr>
 *   <tr><td>{@code hindi_normalization}, {@code indic_normalization}, {@code sorani_normalization}</td>
 *       <td>Accepted; tokens pass through unchanged.</td></tr>
 *   <tr><td>{@code persian_normalization}</td>
 *       <td>Only Arabic yeh and kaf to their Persian forms and the heh variants to heh.</td></tr>
 *   <tr><td>{@code arabic_normalization}, {@code german_normalization}, {@code scandinavian_normalization}</td>
 *       <td>Written from the published descriptions (Larkey et al.'s Arabic normalization, the German2 Snowball
 *       heuristics, the documented Scandinavian letter equivalences); edge cases can differ.</td></tr>
 *   <tr><td>{@code classic} token filter</td>
 *       <td>Removes {@code 's} and acronym dots from any token, not only the classic tokenizer's apostrophe and
 *       acronym tokens.</td></tr>
 *   <tr><td>{@code cjk_bigram}</td>
 *       <td>Recognizes CJK tokens by their characters' scripts rather than by token types.</td></tr>
 *   <tr><td>{@code stopwords} predefined lists</td>
 *       <td>{@code english} is the 33-word list the {@code stop} analyzer uses; the Snowball list for danish, dutch,
 *       finnish, french, german, hungarian, indonesian, irish, italian, norwegian, portuguese, brazilian (the
 *       Portuguese list), russian, spanish and swedish; Microsoft's published list for arabic, bulgarian, catalan,
 *       czech, greek, hindi, latvian, romanian, thai and turkish; empty for armenian, basque, galician, persian and
 *       sorani.</td></tr>
 *   <tr><td>{@code limit} token filter</td><td>{@code consumeAllTokens} has no effect.</td></tr>
 *   <tr><td>{@code unique} token filter</td>
 *       <td>{@code onlyOnSamePosition} defaults to {@code false}, as the REST specification documents (Learn's table
 *       says {@code true}); {@code false} removes repeats anywhere in the field.</td></tr>
 *   <tr><td>{@code word_delimiter}</td>
 *       <td>Splits on case only from lower to upper case ({@code AzureSearch}); later tokens move up by the positions
 *       the subwords add.</td></tr>
 *   <tr><td>{@code synonym} token filter</td>
 *       <td>Rule terms are split on whitespace (lowercased with {@code ignoreCase}) and matched longest first; a
 *       multi-word replacement occupies consecutive positions.</td></tr>
 *   <tr><td>{@code html_strip} character filter</td>
 *       <td>Tags, comments, {@code script} and {@code style} elements become spaces, so markup between two words
 *       always separates them; numeric entities and common named entities are decoded.</td></tr>
 *   <tr><td>analyzer token length</td>
 *       <td>Tokens longer than 300 characters are indexed; Azure fails the document.</td></tr>
 * </table>
 */
public final class AnalysisApproximations {

    /** Each approximated component with a one-line summary, in the order of the table above. */
    public static final List<Map.Entry<String, String>> SUMMARY = List.of(
        Map.entry("standard tokenizer", "JDK word boundaries adjusted toward UAX #29"),
        Map.entry("*.lucene (except en.lucene)", "word boundaries, lowercase, licensed stop words; no stemming"),
        Map.entry("*.microsoft (except en.microsoft)", "word boundaries, lowercase, Microsoft's published stop words"),
        Map.entry("en.microsoft", "en.lucene with Microsoft's published English stop words"),
        Map.entry("classic tokenizer", "regular-expression approximation of the classic grammar"),
        Map.entry("uax_url_email tokenizer", "standard tokenizer plus URL and e-mail regular expressions"),
        Map.entry("microsoft_language_tokenizer", "word boundaries whatever the language"),
        Map.entry("microsoft_language_stemming_tokenizer", "word boundaries; Porter for English only"),
        Map.entry("keyword tokenizer (v1)", "bufferSize has no effect"),
        Map.entry("stemmer token filter", "English variants via Porter or the S stemmer; other languages unchanged"),
        Map.entry("snowball token filter", "Porter for porter, english and lovins; other languages unchanged"),
        Map.entry("kstem token filter", "S stemmer stands in"),
        Map.entry("phonetic token filter", "Soundex or Metaphone stand in for the other encoders"),
        Map.entry("hindi_normalization, indic_normalization, sorani_normalization", "tokens unchanged"),
        Map.entry("persian_normalization", "yeh, kaf and heh variants only"),
        Map.entry("arabic, german and scandinavian normalization", "from published descriptions"),
        Map.entry("classic token filter", "applies to every token"),
        Map.entry("cjk_bigram token filter", "scripts detected from characters"),
        Map.entry("stopwords predefined lists", "Snowball or Microsoft lists; some languages empty"),
        Map.entry("limit token filter", "consumeAllTokens has no effect"),
        Map.entry("unique token filter", "onlyOnSamePosition defaults to false"),
        Map.entry("word_delimiter token filter", "lower-to-upper case splits only"),
        Map.entry("synonym token filter", "rule terms split on whitespace"),
        Map.entry("html_strip character filter", "markup becomes spaces; common entities decoded"),
        Map.entry("token length", "tokens over 300 characters are indexed"));

    private AnalysisApproximations() {
    }
}
