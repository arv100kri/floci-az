package io.floci.az.services.search.engine.analysis;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A synonym map as the engine uses it: its name, its ETag and its parsed rules, plus the rules compiled for each
 * analyzer that has expanded a query through it (synonyms are analyzed like the query terms of the field they expand,
 * per "Add synonyms"). Immutable apart from that cache; a changed synonym map is a new instance.
 */
public final class SynonymMap {

    private final String name;
    private final String etag;
    private final SolrSynonymRules rules;
    private final Map<Analyzer, SolrSynonymRules.Compiled> compiled = Collections.synchronizedMap(new WeakHashMap<>());

    public SynonymMap(String name, String etag, SolrSynonymRules rules) {
        this.name = name;
        this.etag = etag;
        this.rules = rules;
    }

    public String name() {
        return name;
    }

    public String etag() {
        return etag;
    }

    public SolrSynonymRules rules() {
        return rules;
    }

    /** The rules with every term analyzed by {@code analyzer}, compiled once per analyzer. */
    public SolrSynonymRules.Compiled compiledFor(Analyzer analyzer) {
        return compiled.computeIfAbsent(analyzer, key -> rules.compile(
            text -> key.analyze(text).stream().map(Token::term).toList(), true));
    }
}
