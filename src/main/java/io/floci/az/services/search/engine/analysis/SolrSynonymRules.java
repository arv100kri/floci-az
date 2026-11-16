package io.floci.az.services.search.engine.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Synonym rules in the Solr format, which synonym maps ({@code "format": "solr"}) and the {@code synonym} token filter
 * use. One rule per line; blank lines and lines starting with {@code #} are ignored:
 * <ul>
 *   <li>an equivalency rule lists equal terms, comma-separated:
 *       {@code USA, United States, United States of America};</li>
 *   <li>an explicit mapping rewrites the terms left of {@code =>} to the terms right of it:
 *       {@code Washington, Wash., WA => WA}.</li>
 * </ul>
 * A backslash makes the next character literal ({@code WA\, USA} is one term). Immutable.
 */
public final class SolrSynonymRules {

    private final List<Rule> rules;

    private SolrSynonymRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * One rule.
     *
     * @param inputs   the terms the rule matches (every term of an equivalency rule)
     * @param outputs  the terms an explicit mapping rewrites to; empty for an equivalency rule
     * @param explicit whether the rule is an explicit mapping
     */
    public record Rule(List<String> inputs, List<String> outputs, boolean explicit) {

        public Rule {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }

        /** How many terms the rule expands a matched term into, as the synonym limits count them. */
        public int expansions() {
            return explicit ? outputs.size() : inputs.size() - 1;
        }
    }

    /** Parses newline-separated rules. */
    public static SolrSynonymRules parse(String text) {
        return parse(List.of(text.split("\\r?\\n|\\r", -1)));
    }

    /**
     * Parses {@code lines}, one rule each.
     *
     * @throws IllegalArgumentException naming the first malformed rule
     */
    public static SolrSynonymRules parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            rules.add(parseRule(trimmed));
        }
        return new SolrSynonymRules(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    private static Rule parseRule(String line) {
        int arrow = -1;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\\') {
                index++;
            } else if (character == '=' && index + 1 < line.length() && line.charAt(index + 1) == '>') {
                if (arrow >= 0) {
                    throw new IllegalArgumentException("the synonym rule '" + line + "' has more than one '=>'.");
                }
                arrow = index;
                index++;
            }
        }
        if (arrow < 0) {
            return new Rule(terms(line, line), List.of(), false);
        }
        return new Rule(terms(line.substring(0, arrow), line), terms(line.substring(arrow + 2), line), true);
    }

    private static List<String> terms(String side, String line) {
        List<String> terms = new ArrayList<>();
        StringBuilder term = new StringBuilder();
        for (int index = 0; index <= side.length(); index++) {
            char character = index < side.length() ? side.charAt(index) : ',';
            if (character == '\\' && index + 1 < side.length()) {
                term.append(side.charAt(++index));
            } else if (character == ',') {
                String value = term.toString().strip();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("the synonym rule '" + line + "' has an empty term.");
                }
                terms.add(value);
                term.setLength(0);
            } else {
                term.append(character);
            }
        }
        return terms;
    }

    /**
     * The rules compiled for matching: each input's analyzed terms mapped to the alternatives it expands to.
     *
     * @param analyze turns a rule term into the terms it matches (an analyzer's output)
     * @param expand  whether every term of an equivalency rule maps to all of them ({@code true}), or only to the
     *                first ({@code false})
     */
    public Compiled compile(Function<String, List<String>> analyze, boolean expand) {
        Map<List<String>, Map<List<String>, Alternative>> byInput = new HashMap<>();
        int longest = 0;
        for (Rule rule : rules) {
            List<String> targets = rule.explicit() ? rule.outputs()
                : expand ? rule.inputs() : List.of(rule.inputs().get(0));
            List<Alternative> alternatives = new ArrayList<>();
            for (String target : targets) {
                List<String> terms = analyze.apply(target);
                if (!terms.isEmpty()) {
                    alternatives.add(new Alternative(target, List.copyOf(terms)));
                }
            }
            for (String input : rule.inputs()) {
                List<String> key = List.copyOf(analyze.apply(input));
                if (key.isEmpty()) {
                    continue;
                }
                longest = Math.max(longest, key.size());
                Map<List<String>, Alternative> merged = byInput.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                for (Alternative alternative : alternatives) {
                    merged.putIfAbsent(alternative.terms(), alternative);
                }
            }
        }
        Map<List<String>, List<Alternative>> mappings = new HashMap<>();
        byInput.forEach((key, alternatives) -> mappings.put(key, List.copyOf(alternatives.values())));
        return new Compiled(Map.copyOf(mappings), longest);
    }

    /**
     * One term a matched input expands to.
     *
     * @param text  the term as the rule writes it
     * @param terms the terms it analyzes to
     */
    public record Alternative(String text, List<String> terms) {
    }

    /**
     * Compiled rules.
     *
     * @param mappings     each input's terms with the alternatives it expands to, in rule order; an alternative whose
     *                     terms equal the input stands for the input itself
     * @param longestInput the most terms any input has
     */
    public record Compiled(Map<List<String>, List<Alternative>> mappings, int longestInput) {

        /** The longest input that matches {@code terms} from {@code start}, as its length; 0 when none does. */
        public int longestMatch(List<String> terms, int start) {
            for (int length = Math.min(longestInput, terms.size() - start); length > 0; length--) {
                if (mappings.containsKey(terms.subList(start, start + length))) {
                    return length;
                }
            }
            return 0;
        }

        /** The alternatives of the input {@code terms}, or an empty list. */
        public List<Alternative> alternatives(List<String> terms) {
            return mappings.getOrDefault(terms, List.of());
        }
    }
}
