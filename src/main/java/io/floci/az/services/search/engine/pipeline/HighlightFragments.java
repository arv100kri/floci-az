package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.analysis.Token;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds hit-highlight fragments from the offsets of matched tokens. A value of at most {@value #WHOLE_VALUE_LENGTH}
 * characters is one fragment; a longer one yields the sentences holding matches, a sentence longer than twice
 * {@value #WINDOW} characters being cut to a window around its first match at word boundaries. Each matched token is
 * wrapped in the tags on its own, so adjacent matches read {@code <em>Divine</em> <em>Secrets</em>}. The best fragments
 * come first: most distinct matched terms, then most matches, then document order.
 */
final class HighlightFragments {

    static final int WHOLE_VALUE_LENGTH = 200;
    static final int WINDOW = 200;

    private HighlightFragments() {
    }

    /**
     * A candidate fragment.
     *
     * @param value         the field value it is cut from
     * @param start         its first character in the value
     * @param end           one past its last character
     * @param spans         the matched token spans inside it, as {@code [start, end)} offsets into the value, sorted
     * @param distinctTerms the number of distinct matched terms in it
     * @param order         its position in the document, for ties
     */
    record Fragment(String value, int start, int end, List<int[]> spans, int distinctTerms, long order) {

        String render(String preTag, String postTag) {
            StringBuilder text = new StringBuilder();
            int cursor = start;
            for (int[] span : spans) {
                text.append(value, cursor, span[0]).append(preTag).append(value, span[0], span[1]).append(postTag);
                cursor = span[1];
            }
            return text.append(value, cursor, end).toString();
        }
    }

    /** The candidate fragments of one value, whose matched tokens are set in {@code matched}. */
    static List<Fragment> of(String value, List<Token> tokens, BitSet matched, int valueIndex) {
        List<Token> hits = new ArrayList<>();
        for (int index = matched.nextSetBit(0); index >= 0; index = matched.nextSetBit(index + 1)) {
            hits.add(tokens.get(index));
        }
        hits.sort(Comparator.comparingInt(Token::startOffset));
        List<int[]> spans = new ArrayList<>();
        for (Token hit : hits) {
            int[] last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (last != null && hit.startOffset() < last[1]) {
                last[1] = Math.max(last[1], hit.endOffset());
            } else {
                spans.add(new int[] {hit.startOffset(), hit.endOffset()});
            }
        }
        if (value.length() <= WHOLE_VALUE_LENGTH) {
            return List.of(fragment(value, 0, value.length(), spans, hits, valueIndex));
        }
        List<Fragment> fragments = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(value);
        int sentenceStart = sentences.first();
        for (int sentenceEnd = sentences.next(); sentenceEnd != BreakIterator.DONE;
             sentenceStart = sentenceEnd, sentenceEnd = sentences.next()) {
            int[] range = trim(value, sentenceStart, sentenceEnd);
            int firstMatch = firstSpanWithin(spans, range[0], range[1]);
            if (firstMatch < 0) {
                continue;
            }
            if (range[1] - range[0] > 2 * WINDOW) {
                range = window(value, range, spans.get(firstMatch));
            }
            fragments.add(fragment(value, range[0], range[1], spans, hits, valueIndex));
        }
        return fragments;
    }

    /** The best {@code max} fragments, rendered with the tags. */
    static List<String> best(List<Fragment> candidates, int max, String preTag, String postTag) {
        List<Fragment> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((Fragment fragment) -> fragment.distinctTerms()).reversed()
            .thenComparing(Comparator.comparingInt((Fragment fragment) -> fragment.spans().size()).reversed())
            .thenComparingLong(Fragment::order));
        List<String> rendered = new ArrayList<>();
        for (int index = 0; index < sorted.size() && index < max; index++) {
            rendered.add(sorted.get(index).render(preTag, postTag));
        }
        return rendered;
    }

    private static Fragment fragment(String value, int start, int end, List<int[]> spans, List<Token> hits,
                                     int valueIndex) {
        List<int[]> inside = new ArrayList<>();
        for (int[] span : spans) {
            if (span[0] >= start && span[1] <= end) {
                inside.add(span);
            }
        }
        Set<String> distinct = new HashSet<>();
        for (Token hit : hits) {
            if (hit.startOffset() >= start && hit.endOffset() <= end) {
                distinct.add(hit.term());
            }
        }
        return new Fragment(value, start, end, inside, distinct.size(), ((long) valueIndex << 32) | start);
    }

    private static int firstSpanWithin(List<int[]> spans, int start, int end) {
        for (int index = 0; index < spans.size(); index++) {
            if (spans.get(index)[0] >= start && spans.get(index)[1] <= end) {
                return index;
            }
        }
        return -1;
    }

    private static int[] trim(String value, int start, int end) {
        int from = start;
        int to = end;
        while (from < to && Character.isWhitespace(value.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(value.charAt(to - 1))) {
            to--;
        }
        return new int[] {from, to};
    }

    /** A window of about {@value #WINDOW} characters around {@code span}, at word boundaries inside {@code range}. */
    private static int[] window(String value, int[] range, int[] span) {
        int start = Math.max(range[0], span[0] - WINDOW / 2);
        while (start > range[0] && start < span[0] && !Character.isWhitespace(value.charAt(start - 1))) {
            start++;
        }
        int end = Math.min(range[1], Math.max(start + WINDOW, span[1]));
        while (end < range[1] && end > span[1] && !Character.isWhitespace(value.charAt(end))) {
            end--;
        }
        return trim(value, start, end);
    }
}
