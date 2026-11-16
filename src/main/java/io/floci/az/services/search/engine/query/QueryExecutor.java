package io.floci.az.services.search.engine.query;

import io.floci.az.services.search.engine.analysis.Analyzer;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.index.InvertedField;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Boost;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.FieldScoped;
import io.floci.az.services.search.engine.query.QueryNode.Fuzzy;
import io.floci.az.services.search.engine.query.QueryNode.MatchAll;
import io.floci.az.services.search.engine.query.QueryNode.MatchNone;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Prefix;
import io.floci.az.services.search.engine.query.QueryNode.Regex;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.engine.query.QueryNode.Wildcard;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Runs a {@link QueryNode} tree over one index, inside the index's read lock. Leaf text is analyzed per target field
 * with the field's search analyzer; a word matches when it matches in any target field, and its score is the sum of
 * its BM25 scores in the fields it matches, each multiplied by the field's weight (1 unless a scoring profile sets
 * text weights). Prefix, wildcard and regex queries are unscored: each match scores 1. Fuzzy queries are scored over
 * their expansions ({@link FuzzyExpansion}); regular expressions use Lucene's syntax ({@link LuceneRegExp}).
 *
 * <p>A node that analyzes to nothing in every field (a word the analyzer drops, such as a stop word) is absent: it
 * neither matches nor excludes anything, as Lucene drops such clauses.</p>
 */
public final class QueryExecutor {

    private final LiveIndex index;
    private final List<SearchFieldDefinition> fields;
    private final Bm25 bm25;
    private final Map<String, Double> fieldWeights;

    /**
     * @param index        the index, whose read lock the caller holds
     * @param fields       the fields text search covers (the request's {@code searchFields}, or every searchable one)
     * @param fieldWeights per-field score multipliers by field path; fields not named weigh 1
     */
    public QueryExecutor(LiveIndex index, List<SearchFieldDefinition> fields, Map<String, Double> fieldWeights) {
        this.index = index;
        this.fields = List.copyOf(fields);
        this.bm25 = Bm25.of(index.definition().bm25K1(), index.definition().bm25B());
        this.fieldWeights = Map.copyOf(fieldWeights);
    }

    /** The matches of {@code node} with their scores, or null when the node is absent. */
    public ScoredDocs execute(QueryNode node) {
        return switch (node) {
            case MatchAll matchAll -> matchAll();
            case MatchNone matchNone -> new ScoredDocs();
            case Term term -> term(term);
            case Phrase phrase -> phrase(phrase);
            case Prefix prefix -> multiTerm(prefix.prefix(), (indexed, normalized) -> indexed.startsWith(normalized));
            case Wildcard wildcard -> wildcard(wildcard);
            case Regex regex -> regex(regex);
            case Fuzzy fuzzy -> fuzzy(fuzzy);
            case Bool bool -> bool(bool);
            case FieldScoped scoped -> new QueryExecutor(index, List.of(searchableField(scoped.fieldPath())),
                fieldWeights).execute(scoped.query());
            case Boost boost -> {
                ScoredDocs inner = execute(boost.query());
                yield inner == null ? null : inner.scaled(boost.boost());
            }
        };
    }

    private SearchFieldDefinition searchableField(String path) {
        SearchFieldDefinition field = index.definition().field(path).orElseThrow(() -> SearchException.badRequest(
            "Invalid search request: the field '" + path + "' does not exist in the index."));
        if (!field.isFullTextSearchable()) {
            throw SearchException.badRequest("Invalid search request: the field '" + path + "' is not searchable.");
        }
        return field;
    }

    private ScoredDocs matchAll() {
        ScoredDocs all = new ScoredDocs();
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            if (index.document(ordinal) != null) {
                all.put(ordinal, 1.0);
            }
        }
        return all;
    }

    private ScoredDocs term(Term term) {
        ScoredDocs result = null;
        for (SearchFieldDefinition field : fields) {
            List<Token> tokens = analyzer(field).analyze(term.text());
            if (tokens.isEmpty()) {
                continue;
            }
            ScoredDocs fieldResult = tokens.size() == 1
                ? termInField(field, tokens.get(0).term())
                : tokensInField(field, tokens, term.multiTokenOccur());
            result = union(result, fieldResult);
        }
        return result;
    }

    /** Combines the tokens of a word with {@code occur}; tokens on one position (a word and its synonyms) are ORed. */
    private ScoredDocs tokensInField(SearchFieldDefinition field, List<Token> tokens, Occur occur) {
        ScoredDocs combined = null;
        int start = 0;
        while (start < tokens.size()) {
            int end = start;
            ScoredDocs single = null;
            while (end < tokens.size() && tokens.get(end).position() == tokens.get(start).position()) {
                single = union(single, termInField(field, tokens.get(end).term()));
                end++;
            }
            if (combined == null) {
                combined = single;
            } else if (occur == Occur.MUST) {
                combined = intersect(combined, single);
            } else {
                combined = union(combined, single);
            }
            start = end;
        }
        return combined;
    }

    private ScoredDocs termInField(SearchFieldDefinition field, String term) {
        ScoredDocs result = new ScoredDocs();
        InvertedField inverted = index.invertedField(field.path());
        if (inverted == null) {
            return result;
        }
        Map<Integer, int[]> postings = inverted.postings(term);
        if (postings.isEmpty()) {
            return result;
        }
        double idf = bm25.idf(inverted.documentCount(), postings.size());
        double weight = weight(field);
        double averageLength = inverted.averageLength();
        postings.forEach((ordinal, positions) -> result.put(ordinal,
            weight * bm25.score(idf, positions.length, inverted.length(ordinal), averageLength)));
        return result;
    }

    private ScoredDocs phrase(Phrase phrase) {
        ScoredDocs result = null;
        for (SearchFieldDefinition field : fields) {
            List<Token> tokens = analyzer(field).analyze(phrase.text());
            if (tokens.isEmpty()) {
                continue;
            }
            ScoredDocs fieldResult = tokens.size() == 1
                ? termInField(field, tokens.get(0).term())
                : phraseInField(field, tokens, phrase.slop());
            result = union(result, fieldResult);
        }
        return result;
    }

    private ScoredDocs phraseInField(SearchFieldDefinition field, List<Token> tokens, int slop) {
        ScoredDocs result = new ScoredDocs();
        InvertedField inverted = index.invertedField(field.path());
        if (inverted == null) {
            return result;
        }
        List<Map<Integer, int[]>> postings = new ArrayList<>(tokens.size());
        double idf = 0;
        for (Token token : tokens) {
            Map<Integer, int[]> termPostings = inverted.postings(token.term());
            if (termPostings.isEmpty()) {
                return result;
            }
            postings.add(termPostings);
            idf += bm25.idf(inverted.documentCount(), termPostings.size());
        }
        int base = tokens.get(0).position();
        int[] offsets = tokens.stream().mapToInt(token -> token.position() - base).toArray();
        double weight = weight(field);
        double averageLength = inverted.averageLength();
        for (Map.Entry<Integer, int[]> candidate : postings.get(0).entrySet()) {
            int ordinal = candidate.getKey();
            int[][] positions = new int[tokens.size()][];
            boolean all = true;
            for (int term = 0; term < tokens.size(); term++) {
                positions[term] = postings.get(term).get(ordinal);
                if (positions[term] == null) {
                    all = false;
                    break;
                }
            }
            if (!all) {
                continue;
            }
            int frequency = PhraseMatcher.count(positions, offsets, slop);
            if (frequency > 0) {
                result.put(ordinal, weight * bm25.score(idf, frequency, inverted.length(ordinal), averageLength));
            }
        }
        return result;
    }

    private ScoredDocs wildcard(Wildcard wildcard) {
        return multiTermPattern(wildcard.pattern(), LuceneRegExp::wildcard);
    }

    private ScoredDocs regex(Regex regex) {
        return multiTermPattern(regex.pattern(), LuceneRegExp::compile);
    }

    private ScoredDocs multiTermPattern(String text, Function<String, LuceneRegExp> compiler) {
        ScoredDocs result = null;
        for (SearchFieldDefinition field : fields) {
            LuceneRegExp pattern = compiler.apply(analyzer(field).normalizeTerm(text));
            ScoredDocs fieldResult = expand(field, (indexed, normalized) -> pattern.matches(indexed), text);
            result = constantUnion(result, fieldResult);
        }
        return result;
    }

    private ScoredDocs fuzzy(Fuzzy fuzzy) {
        ScoredDocs result = null;
        for (SearchFieldDefinition field : fields) {
            String normalized = analyzer(field).normalizeTerm(fuzzy.term());
            result = union(result, FuzzyExpansion.score(index.invertedField(field.path()), normalized,
                fuzzy.maxEdits(), bm25, weight(field)));
        }
        return result;
    }

    private ScoredDocs multiTerm(String text, BiPredicate<String, String> matches) {
        ScoredDocs result = null;
        for (SearchFieldDefinition field : fields) {
            result = constantUnion(result, expand(field, matches, analyzer(field).normalizeTerm(text)));
        }
        return result;
    }

    /** Every document with a term of {@code field} that {@code matches} the normalised query term; each scores 1. */
    private ScoredDocs expand(SearchFieldDefinition field, BiPredicate<String, String> matches, String normalized) {
        ScoredDocs result = new ScoredDocs();
        InvertedField inverted = index.invertedField(field.path());
        if (inverted == null) {
            return result;
        }
        for (Map.Entry<String, Map<Integer, int[]>> entry : inverted.dictionary().entrySet()) {
            if (matches.test(entry.getKey(), normalized)) {
                entry.getValue().keySet().forEach(ordinal -> result.put(ordinal, 1.0));
            }
        }
        return result;
    }

    private ScoredDocs bool(Bool bool) {
        List<ScoredDocs> required = new ArrayList<>();
        List<ScoredDocs> filters = new ArrayList<>();
        List<ScoredDocs> optional = new ArrayList<>();
        List<ScoredDocs> prohibited = new ArrayList<>();
        for (Clause clause : bool.clauses()) {
            ScoredDocs docs = execute(clause.query());
            if (docs == null) {
                continue;
            }
            switch (clause.occur()) {
                case MUST -> required.add(docs);
                case FILTER -> filters.add(docs);
                case SHOULD -> optional.add(docs);
                case MUST_NOT -> prohibited.add(docs);
            }
        }
        if (required.isEmpty() && filters.isEmpty() && optional.isEmpty()) {
            return prohibited.isEmpty() ? null : new ScoredDocs();
        }
        ScoredDocs result;
        if (required.isEmpty() && filters.isEmpty()) {
            result = null;
            for (ScoredDocs docs : optional) {
                result = union(result, docs);
            }
        } else {
            result = null;
            for (ScoredDocs docs : required) {
                result = result == null ? copy(docs) : intersect(result, docs);
            }
            for (ScoredDocs docs : filters) {
                result = result == null ? zeroScores(docs) : intersect(result, zeroScores(docs));
            }
            for (ScoredDocs docs : optional) {
                for (Map.Entry<Integer, Double> entry : docs.asMap().entrySet()) {
                    if (result.contains(entry.getKey())) {
                        result.add(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        for (ScoredDocs docs : prohibited) {
            for (int ordinal : docs.ordinals()) {
                result.remove(ordinal);
            }
        }
        return result;
    }

    private Analyzer analyzer(SearchFieldDefinition field) {
        return index.analysis().searchAnalyzer(field);
    }

    private double weight(SearchFieldDefinition field) {
        return fieldWeights.getOrDefault(field.path(), 1.0);
    }

    private static ScoredDocs union(ScoredDocs left, ScoredDocs right) {
        if (left == null) {
            return right == null ? null : copy(right);
        }
        if (right != null) {
            right.asMap().forEach(left::add);
        }
        return left;
    }

    private static ScoredDocs constantUnion(ScoredDocs left, ScoredDocs right) {
        if (left == null) {
            return right;
        }
        right.ordinals().forEach(ordinal -> left.put(ordinal, 1.0));
        return left;
    }

    private static ScoredDocs intersect(ScoredDocs left, ScoredDocs right) {
        ScoredDocs result = new ScoredDocs();
        ScoredDocs smaller = left.size() <= right.size() ? left : right;
        ScoredDocs larger = smaller == left ? right : left;
        for (Map.Entry<Integer, Double> entry : smaller.asMap().entrySet()) {
            if (larger.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue() + larger.score(entry.getKey()));
            }
        }
        return result;
    }

    private static ScoredDocs copy(ScoredDocs docs) {
        return docs.scaled(1.0);
    }

    private static ScoredDocs zeroScores(ScoredDocs docs) {
        return docs.scaled(0.0);
    }

    /** Whether {@code a} is within {@code maxEdits} insertions, deletions, substitutions or transpositions of b. */
    static boolean withinEdits(String a, String b, int maxEdits) {
        return EditDistance.within(a, b, maxEdits);
    }
}
