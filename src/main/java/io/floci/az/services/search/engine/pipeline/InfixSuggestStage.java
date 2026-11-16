package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.analysis.Analyzer;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.InvertedField;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.odata.ODataOrderBy;
import io.floci.az.services.search.engine.odata.ODataSelect;
import io.floci.az.services.search.engine.query.Bm25;
import io.floci.az.services.search.engine.query.EditDistance;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggestions and autocompletion over the index's suggester ({@code analyzingInfixMatching}), per Learn's "Configure
 * a suggester" and the Suggest and Autocomplete references. Source field values are split into words by the field's
 * analyzer and the words are compared in their original spelling, lowercased: an infix match means every word of the
 * query matches a whole word of one value and the last word, unless the query ends with a separator, a word's
 * beginning. With {@code fuzzy}, a word also matches a whole word one edit away.
 * <ul>
 *   <li>Suggest returns matching documents, best match first (BM25 of the matched words) unless {@code orderby}
 *       sorts them, each with {@code @search.text} (the matching value, its matched parts wrapped in the highlight
 *       tags when both are given) and the {@code select}ed fields (the key alone by default);</li>
 *   <li>Autocomplete completes the last word of the query: {@code oneTerm} with the matching words,
 *       {@code twoTerms} with the matching words and the word after them, {@code oneTermWithContext} with matching
 *       words that follow the query's previous word. Completions are lowercase, most frequent first (ties in
 *       alphabetical order); {@code queryPlusText} is the query with its completed part replaced.</li>
 * </ul>
 * Both honour {@code $filter}, {@code searchFields} and {@code $top}, and report {@code @search.coverage} of 100 when
 * {@code minimumCoverage} is set.
 */
public final class InfixSuggestStage implements SuggestStage {

    @Override
    public ObjectNode suggest(LiveIndex index, ObjectNode parameters) {
        SearchIndexDefinition definition = index.definition();
        SuggestParameters request = SuggestParameters.parse(parameters, definition, true);
        ODataOrderBy orderBy = ODataOrderBy.parse(request.orderBy(), definition);
        ODataSelect select = ODataSelect.parse(request.select() == null
            ? List.of(definition.keyField().path()) : request.select(), definition, "$select");
        BitSet admitted = admitted(index, request.filter());
        Bm25 bm25 = Bm25.of(definition.bm25K1(), definition.bm25B());
        Map<String, Words> queries = new HashMap<>();
        List<Suggestion> suggestions = new ArrayList<>();
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document != null && (admitted == null || admitted.get(ordinal))) {
                Suggestion suggestion = suggestion(index, document, request, bm25, queries);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
        }
        Comparator<Suggestion> byScore = Comparator.comparingDouble(Suggestion::score).reversed();
        Comparator<Suggestion> order = orderBy.isEmpty() ? byScore
            : orderBy.<Suggestion>comparator(suggestion -> index.document(suggestion.ordinal()), Suggestion::score)
                .thenComparing(byScore);
        suggestions.sort(order.thenComparingInt(Suggestion::ordinal));
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (Suggestion suggestion : suggestions.subList(0, Math.min(request.top(), suggestions.size()))) {
            ObjectNode item = value.addObject();
            item.put("@search.text", suggestion.render(request.preTag(), request.postTag()));
            item.setAll(select.project(index.document(suggestion.ordinal()).source()));
        }
        return body(request, value);
    }

    @Override
    public ObjectNode autocomplete(LiveIndex index, ObjectNode parameters) {
        SuggestParameters request = SuggestParameters.parse(parameters, index.definition(), false);
        BitSet admitted = admitted(index, request.filter());
        Map<String, Words> queries = new HashMap<>();
        Map<String, Set<Integer>> completions = new LinkedHashMap<>();
        Words prefixWords = null;
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document == null || (admitted != null && !admitted.get(ordinal))) {
                continue;
            }
            for (SearchFieldDefinition field : request.fields()) {
                Words query = query(index, field, request.search(), queries);
                if (query.isEmpty()) {
                    continue;
                }
                prefixWords = prefixWords == null ? query : prefixWords;
                Analyzer analyzer = index.analysis().indexAnalyzer(field);
                for (String text : DocumentValues.textValues(document.source(), field)) {
                    complete(Words.of(text, analyzer), query, request, ordinal, completions);
                }
            }
        }
        List<Map.Entry<String, Set<Integer>>> ranked = new ArrayList<>(completions.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<String, Set<Integer>> entry) -> entry.getValue().size())
            .reversed().thenComparing(Map.Entry::getKey));
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (Map.Entry<String, Set<Integer>> completion : ranked.subList(0, Math.min(request.top(), ranked.size()))) {
            String text = completion.getKey();
            String highlighted = request.preTag() == null ? text : request.preTag() + text + request.postTag();
            ObjectNode item = value.addObject();
            item.put("text", text);
            item.put("queryPlusText", queryPrefix(request, prefixWords) + highlighted);
        }
        return body(request, value);
    }

    /** Adds the completions {@code value} offers for the last word of {@code query}. */
    private static void complete(Words value, Words query, SuggestParameters request, int ordinal,
                                 Map<String, Set<Integer>> completions) {
        String last = query.words().get(query.size() - 1);
        String context = query.size() >= 2 ? query.words().get(query.size() - 2) : null;
        for (int index = 0; index < value.size(); index++) {
            String word = value.words().get(index);
            if (!word.startsWith(last) && !(request.fuzzy() && EditDistance.within(word, last, 1))) {
                continue;
            }
            String completion = switch (request.mode()) {
                case ONE_TERM -> word;
                case TWO_TERMS -> index + 1 < value.size() ? word + " " + value.words().get(index + 1) : null;
                case ONE_TERM_WITH_CONTEXT -> {
                    if (context == null) {
                        yield word;
                    }
                    yield index > 0 && value.words().get(index - 1).equals(context)
                        ? context + " " + word : null;
                }
            };
            if (completion != null) {
                completions.computeIfAbsent(completion, key -> new LinkedHashSet<>()).add(ordinal);
            }
        }
    }

    /** The query text before the part a completion replaces: the last word, or the last two with context. */
    private static String queryPrefix(SuggestParameters request, Words query) {
        if (query == null) {
            return "";
        }
        int replaced = request.mode() == SuggestParameters.Mode.ONE_TERM_WITH_CONTEXT && query.size() >= 2
            ? query.size() - 2 : query.size() - 1;
        return request.search().substring(0, query.tokens().get(replaced).startOffset());
    }

    private static Suggestion suggestion(LiveIndex index, IndexedDocument document, SuggestParameters request,
                                         Bm25 bm25, Map<String, Words> queries) {
        for (SearchFieldDefinition field : request.fields()) {
            Words query = query(index, field, request.search(), queries);
            if (query.isEmpty()) {
                continue;
            }
            Analyzer analyzer = index.analysis().indexAnalyzer(field);
            for (String text : DocumentValues.textValues(document.source(), field)) {
                Words value = Words.of(text, analyzer);
                List<int[]> spans = new ArrayList<>();
                double score = match(query, value, request.fuzzy(), spans, index.invertedField(field.path()),
                    document.ordinal(), bm25);
                if (score >= 0) {
                    return new Suggestion(document.ordinal(), text, spans, score);
                }
            }
        }
        return null;
    }

    /**
     * Matches every query word to words of the value, collecting the spans to highlight; the score is the sum of the
     * matched words' BM25 scores, or -1 when a query word matches nothing.
     */
    private static double match(Words query, Words value, boolean fuzzy, List<int[]> spans, InvertedField inverted,
                                int ordinal, Bm25 bm25) {
        double score = 0;
        for (int queryIndex = 0; queryIndex < query.size(); queryIndex++) {
            String word = query.words().get(queryIndex);
            boolean prefix = queryIndex == query.size() - 1 && query.endsInWord();
            double best = -1;
            for (int index = 0; index < value.size(); index++) {
                String candidate = value.words().get(index);
                Token token = value.tokens().get(index);
                if (prefix && candidate.startsWith(word)) {
                    spans.add(new int[] {token.startOffset(),
                        Math.min(token.endOffset(), token.startOffset() + word.length())});
                } else if (candidate.equals(word) || (fuzzy && EditDistance.within(candidate, word, 1))) {
                    spans.add(new int[] {token.startOffset(), token.endOffset()});
                } else {
                    continue;
                }
                best = Math.max(best, termScore(inverted, token.term(), ordinal, bm25));
            }
            if (best < 0) {
                return -1;
            }
            score += best;
        }
        return score;
    }

    private static double termScore(InvertedField inverted, String term, int ordinal, Bm25 bm25) {
        if (inverted == null) {
            return 0;
        }
        Map<Integer, int[]> postings = inverted.postings(term);
        int[] positions = postings.get(ordinal);
        if (positions == null) {
            return 0;
        }
        return bm25.score(bm25.idf(inverted.documentCount(), postings.size()), positions.length,
            inverted.length(ordinal), inverted.averageLength());
    }

    private static Words query(LiveIndex index, SearchFieldDefinition field, String search,
                               Map<String, Words> queries) {
        Analyzer analyzer = index.analysis().searchAnalyzer(field);
        return queries.computeIfAbsent(analyzer.name(), name -> Words.of(search, analyzer));
    }

    /** The documents {@code filter} admits, or null for all. */
    private static BitSet admitted(LiveIndex index, String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        // The filter needs no engine setting; the defaults only satisfy the execution's constructor.
        SearchExecution execution = new SearchExecution(SearchRequest.builder().filter(filter).build(), index,
            EngineSettings.DEFAULTS);
        new FilterStage().apply(execution);
        return execution.filterMatches();
    }

    private static ObjectNode body(SuggestParameters request, ArrayNode value) {
        ObjectNode body = SearchJson.object();
        if (request.minimumCoverage() != null) {
            body.put("@search.coverage", 100.0);
        }
        body.set("value", value);
        return body;
    }

    /**
     * The words of a text: its tokens, each with its original spelling lowercased by the analyzer's normalising
     * filters.
     *
     * @param tokens     the analyzer's tokens
     * @param words      the original spelling of each token, normalised
     * @param endsInWord whether the text ends inside its last word (no separator after it)
     */
    private record Words(List<Token> tokens, List<String> words, boolean endsInWord) {

        static Words of(String text, Analyzer analyzer) {
            List<Token> tokens = analyzer.analyze(text);
            List<String> words = new ArrayList<>(tokens.size());
            for (Token token : tokens) {
                words.add(analyzer.normalizeTerm(text.substring(token.startOffset(), token.endOffset())));
            }
            boolean endsInWord = !tokens.isEmpty() && tokens.get(tokens.size() - 1).endOffset() == text.length();
            return new Words(tokens, words, endsInWord);
        }

        int size() {
            return words.size();
        }

        boolean isEmpty() {
            return words.isEmpty();
        }
    }

    /**
     * A matching document.
     *
     * @param ordinal the document
     * @param text    the matching value
     * @param spans   the matched parts of the value
     * @param score   the match score
     */
    private record Suggestion(int ordinal, String text, List<int[]> spans, double score) {

        String render(String preTag, String postTag) {
            if (preTag == null || spans.isEmpty()) {
                return text;
            }
            List<int[]> sorted = new ArrayList<>(spans);
            sorted.sort(Comparator.comparingInt(span -> span[0]));
            StringBuilder rendered = new StringBuilder();
            int cursor = 0;
            for (int[] span : sorted) {
                if (span[0] < cursor) {
                    continue;
                }
                rendered.append(text, cursor, span[0]).append(preTag).append(text, span[0], span[1]).append(postTag);
                cursor = span[1];
            }
            return rendered.append(text.substring(cursor)).toString();
        }
    }
}
