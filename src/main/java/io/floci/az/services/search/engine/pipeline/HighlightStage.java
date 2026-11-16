package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.query.QueryNode;
import io.floci.az.services.search.engine.query.QueryParsers;
import io.floci.az.services.search.engine.query.QueryTermMatcher;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hit highlighting, per Learn's "Shape search results" and the Search Documents reference: {@code highlight} lists
 * searchable string fields, each optionally followed by {@code -n} for at most n fragments (5 by default); matched
 * terms are wrapped in {@code highlightPreTag} and {@code highlightPostTag} ({@code <em>} and {@code </em>} by default,
 * set together or not at all). Each result on the page gets {@code @search.highlights} for the fields where the query
 * matched something (see {@link QueryTermMatcher} and {@link HighlightFragments}); a query matching everything
 * highlights nothing.
 */
public final class HighlightStage implements SearchStage {

    static final String DEFAULT_PRE_TAG = "<em>";
    static final String DEFAULT_POST_TAG = "</em>";
    static final int DEFAULT_FRAGMENTS = 5;

    private static final Pattern FRAGMENT_COUNT = Pattern.compile("(.+)-(\\d+)");

    @Override
    public void apply(SearchExecution execution) {
        SearchRequest request = execution.request();
        if (request.highlightFields().isEmpty()) {
            return;
        }
        LiveIndex index = execution.index();
        List<Target> targets = targets(request.highlightFields(), index.definition());
        String preTag = request.highlightPreTag();
        String postTag = request.highlightPostTag();
        if ((preTag == null) != (postTag == null)) {
            throw SearchException.badRequest("Invalid search request: highlightPreTag and highlightPostTag must be "
                + "set together.");
        }
        if (request.matchesAllText() || execution.page().isEmpty()) {
            return;
        }
        QueryNode query = QueryParsers.forType(request.effectiveQueryType())
            .parse(request.search(), request.effectiveSearchMode());
        QueryTermMatcher matcher = new QueryTermMatcher(query, index.analysis(), execution.searchFields());
        for (SearchHit hit : execution.page()) {
            Map<String, List<String>> highlights = highlight(index.document(hit.ordinal()), targets, matcher,
                index.analysis(), preTag == null ? DEFAULT_PRE_TAG : preTag,
                postTag == null ? DEFAULT_POST_TAG : postTag);
            if (!highlights.isEmpty()) {
                hit.highlights(highlights);
            }
        }
    }

    private static Map<String, List<String>> highlight(IndexedDocument document, List<Target> targets,
                                                       QueryTermMatcher matcher, IndexAnalysis analysis,
                                                       String preTag, String postTag) {
        Map<String, List<String>> highlights = new LinkedHashMap<>();
        for (Target target : targets) {
            SearchFieldDefinition field = target.field();
            if (target.fragments() == 0 || !matcher.highlights(field)) {
                continue;
            }
            List<HighlightFragments.Fragment> candidates = new ArrayList<>();
            int valueIndex = 0;
            for (String value : DocumentValues.textValues(document.source(), field)) {
                List<Token> tokens = analysis.indexAnalyzer(field).analyze(value);
                BitSet matched = matcher.matches(field, tokens);
                if (!matched.isEmpty()) {
                    candidates.addAll(HighlightFragments.of(value, tokens, matched, valueIndex));
                }
                valueIndex++;
            }
            List<String> fragments = HighlightFragments.best(candidates, target.fragments(), preTag, postTag);
            if (!fragments.isEmpty()) {
                highlights.put(target.name(), fragments);
            }
        }
        return highlights;
    }

    /** The fields of {@code highlight}, each searchable, with its fragment limit. */
    private static List<Target> targets(List<String> entries, SearchIndexDefinition definition) {
        List<Target> targets = new ArrayList<>();
        for (String entry : entries) {
            String name = entry.trim();
            int fragments = DEFAULT_FRAGMENTS;
            Matcher count = FRAGMENT_COUNT.matcher(name);
            if (count.matches()) {
                name = count.group(1).trim();
                fragments = parseCount(count.group(2));
            }
            String path = name;
            SearchFieldDefinition field = definition.field(path).orElseThrow(() -> SearchException.badRequest(
                "Invalid search request: Could not find a property named '" + path + "' on type 'search.document'."
                    + "\r\nParameter name: highlight"));
            if (!field.isFullTextSearchable()) {
                throw SearchException.badRequest("Invalid search request: The field '" + path + "' in highlight is "
                    + "not searchable. Only searchable string fields can be used for hit highlighting.\r\nParameter "
                    + "name: highlight");
            }
            targets.add(new Target(path, field, fragments));
        }
        return targets;
    }

    private static int parseCount(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw SearchException.badRequest("Invalid search request: the highlight fragment count '" + digits
                + "' is too large.\r\nParameter name: highlight");
        }
    }

    /**
     * A field to highlight.
     *
     * @param name      the field path as requested, the key in {@code @search.highlights}
     * @param field     the field
     * @param fragments the most fragments returned
     */
    private record Target(String name, SearchFieldDefinition field, int fragments) {
    }
}
