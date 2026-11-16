package io.floci.az.services.search.engine.analysis;

import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.QueryNode;
import io.floci.az.services.search.engine.query.QueryNode.Bool;
import io.floci.az.services.search.engine.query.QueryNode.Boost;
import io.floci.az.services.search.engine.query.QueryNode.Clause;
import io.floci.az.services.search.engine.query.QueryNode.FieldScoped;
import io.floci.az.services.search.engine.query.QueryNode.Occur;
import io.floci.az.services.search.engine.query.QueryNode.Phrase;
import io.floci.az.services.search.engine.query.QueryNode.Term;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Query-time synonym expansion, per Learn's "Add synonyms": for every searchable field whose {@code synonymMaps} names
 * a map, a query word or phrase that matches a rule of the map is rewritten, for that field only, to the OR of its
 * alternatives: all the terms of an equivalency rule, or the right side of an explicit {@code =>} mapping (which drops
 * the original). Rule terms are analyzed with the field's search analyzer, like any query term. Behavior:
 * <ul>
 *   <li>a word (simple syntax splits words on whitespace) expands when its analyzed terms equal a rule term; a
 *       multi-word rule term therefore matches only a quoted phrase, and multi-word alternatives are phrases;</li>
 *   <li>a phrase expands every rule term found in it, longest first, into one phrase per combination of
 *       alternatives (at most 64);</li>
 *   <li>prefix, wildcard, regular expression and fuzzy terms are never expanded;</li>
 *   <li>a searched field whose synonym map does not exist fails the query with a 404.</li>
 * </ul>
 * Fields without synonyms keep the original node, so scores are unchanged when nothing expands.
 */
public final class SynonymExpansion {

    private static final int MAX_PHRASE_ALTERNATIVES = 64;

    private final LiveIndex index;
    private final Map<String, Optional<SynonymMap>> maps = new HashMap<>();

    private SynonymExpansion(LiveIndex index) {
        this.index = index;
    }

    /**
     * {@code query} with the synonyms of the synonym-enabled fields among {@code searchFields} (or the field a
     * fielded query names) expanded. Run under the index's read lock.
     *
     * @throws SearchException a 404 when a searched field names a synonym map that does not exist
     */
    public static QueryNode expand(QueryNode query, LiveIndex index, List<SearchFieldDefinition> searchFields) {
        SynonymExpansion expansion = new SynonymExpansion(index);
        boolean any = false;
        for (SearchFieldDefinition field : searchFields) {
            any |= expansion.mapFor(field).isPresent();
        }
        if (!any && !hasFieldScope(query)) {
            return query;
        }
        return expansion.rewrite(query, searchFields);
    }

    private static boolean hasFieldScope(QueryNode node) {
        return switch (node) {
            case FieldScoped scoped -> true;
            case Bool bool -> bool.clauses().stream().anyMatch(clause -> hasFieldScope(clause.query()));
            case Boost boost -> hasFieldScope(boost.query());
            default -> false;
        };
    }

    private Optional<SynonymMap> mapFor(SearchFieldDefinition field) {
        if (field.synonymMaps().isEmpty()) {
            return Optional.empty();
        }
        return maps.computeIfAbsent(field.path(), path -> {
            String name = field.synonymMaps().get(0);
            SynonymMap map = index.analysis().synonymMap(name).orElseThrow(() -> SearchException.notFound(
                "The synonym map '" + name + "' that the field '" + path + "' uses was not found."));
            return Optional.of(map);
        });
    }

    private QueryNode rewrite(QueryNode node, List<SearchFieldDefinition> fields) {
        return switch (node) {
            case Term term -> expandLeaf(term, fields);
            case Phrase phrase -> expandLeaf(phrase, fields);
            case Bool bool -> {
                List<Clause> clauses = new ArrayList<>(bool.clauses().size());
                for (Clause clause : bool.clauses()) {
                    clauses.add(new Clause(clause.occur(), rewrite(clause.query(), fields)));
                }
                yield new Bool(clauses);
            }
            case Boost boost -> new Boost(rewrite(boost.query(), fields), boost.boost());
            case FieldScoped scoped -> index.definition().field(scoped.fieldPath())
                .filter(SearchFieldDefinition::isFullTextSearchable)
                .map(field -> (QueryNode) new FieldScoped(scoped.fieldPath(), rewrite(scoped.query(), List.of(field))))
                .orElse(scoped);
            default -> node;
        };
    }

    private QueryNode expandLeaf(QueryNode leaf, List<SearchFieldDefinition> fields) {
        List<QueryNode> perField = new ArrayList<>(fields.size());
        boolean expanded = false;
        for (SearchFieldDefinition field : fields) {
            Optional<QueryNode> alternatives = mapFor(field).flatMap(map -> alternatives(leaf, field, map));
            expanded |= alternatives.isPresent();
            perField.add(new FieldScoped(field.path(), alternatives.orElse(leaf)));
        }
        if (!expanded) {
            return leaf;
        }
        return perField.size() == 1 ? perField.get(0) : or(perField);
    }

    private Optional<QueryNode> alternatives(QueryNode leaf, SearchFieldDefinition field, SynonymMap map) {
        Analyzer analyzer = index.analysis().searchAnalyzer(field);
        SolrSynonymRules.Compiled rules = map.compiledFor(analyzer);
        if (rules.mappings().isEmpty()) {
            return Optional.empty();
        }
        return switch (leaf) {
            case Term term -> termAlternatives(term, analyzer, rules);
            case Phrase phrase -> phraseAlternatives(phrase, analyzer, rules);
            default -> Optional.empty();
        };
    }

    private static Optional<QueryNode> termAlternatives(Term term, Analyzer analyzer,
                                                        SolrSynonymRules.Compiled rules) {
        List<String> terms = analyzer.analyze(term.text()).stream().map(Token::term).toList();
        List<SolrSynonymRules.Alternative> alternatives = rules.alternatives(terms);
        if (terms.isEmpty() || alternatives.isEmpty()) {
            return Optional.empty();
        }
        List<QueryNode> options = new ArrayList<>(alternatives.size());
        for (SolrSynonymRules.Alternative alternative : alternatives) {
            if (alternative.terms().equals(terms)) {
                options.add(term);
            } else if (alternative.terms().size() == 1) {
                options.add(new Term(alternative.text(), term.multiTokenOccur()));
            } else {
                options.add(new Phrase(alternative.text(), 0));
            }
        }
        return Optional.of(options.size() == 1 ? options.get(0) : or(options));
    }

    private static Optional<QueryNode> phraseAlternatives(Phrase phrase, Analyzer analyzer,
                                                          SolrSynonymRules.Compiled rules) {
        List<Token> tokens = analyzer.analyze(phrase.text());
        List<String> terms = tokens.stream().map(Token::term).toList();
        List<String> texts = new ArrayList<>(List.of(""));
        int copied = 0;
        boolean matched = false;
        for (int index = 0; index < terms.size(); ) {
            int length = rules.longestMatch(terms, index);
            if (length == 0) {
                index++;
                continue;
            }
            matched = true;
            int start = tokens.get(index).startOffset();
            int end = tokens.get(index + length - 1).endOffset();
            String before = phrase.text().substring(copied, start);
            String original = phrase.text().substring(start, end);
            List<String> next = new ArrayList<>();
            for (String text : texts) {
                for (SolrSynonymRules.Alternative alternative : rules.alternatives(terms.subList(index,
                        index + length))) {
                    if (next.size() < MAX_PHRASE_ALTERNATIVES) {
                        boolean itself = alternative.terms().equals(terms.subList(index, index + length));
                        next.add(text + before + (itself ? original : alternative.text()));
                    }
                }
            }
            texts = next;
            copied = end;
            index += length;
        }
        if (!matched) {
            return Optional.empty();
        }
        String rest = phrase.text().substring(copied);
        List<QueryNode> options = new ArrayList<>(texts.size());
        for (String text : texts) {
            options.add(new Phrase(text + rest, phrase.slop()));
        }
        return Optional.of(options.size() == 1 ? options.get(0) : or(options));
    }

    private static QueryNode or(List<QueryNode> nodes) {
        List<Clause> clauses = new ArrayList<>(nodes.size());
        for (QueryNode node : nodes) {
            clauses.add(new Clause(Occur.SHOULD, node));
        }
        return new Bool(clauses);
    }
}
