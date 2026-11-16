package io.floci.az.services.search.engine.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Picks the passage of a document that best matches the query, for captions and answers: the sentence that holds the
 * largest share of the query's terms (the first one on a tie, in the configuration's priority order), followed by the
 * next sentences of the same value while the passage stays within its character limit. When no sentence matches, the
 * passage starts at the first sentence. Whitespace runs, line breaks included, become single spaces.
 */
final class SemanticPassages {

    /** The longest passage, in characters, when the request sets no {@code maxcharlength}. */
    static final int DEFAULT_MAX_CHARS = 300;

    private SemanticPassages() {
    }

    /**
     * A passage of a document.
     *
     * @param text     the passage
     * @param coverage the share of the query's terms it holds, from 0 to 1
     */
    record Passage(String text, double coverage) {
    }

    /** The best passage of {@code values} for {@code query}, at most {@code maxChars} long; null when all are empty. */
    static Passage best(List<String> values, List<String> query, int maxChars) {
        List<List<String>> sentencesByValue = new ArrayList<>();
        int bestValue = -1;
        int bestSentence = -1;
        double bestShare = -1;
        for (String value : values) {
            List<String> sentences = new ArrayList<>();
            for (SemanticText.Sentence sentence : SemanticText.sentences(value)) {
                String text = SemanticText.collapseWhitespace(value.substring(sentence.start(), sentence.end()));
                if (text.isEmpty()) {
                    continue;
                }
                double share = SemanticText.share(query, SemanticText.terms(text));
                if (share > bestShare) {
                    bestShare = share;
                    bestValue = sentencesByValue.size();
                    bestSentence = sentences.size();
                }
                sentences.add(text);
            }
            sentencesByValue.add(sentences);
        }
        if (bestValue < 0) {
            return null;
        }
        if (bestShare <= 0) {
            bestValue = firstNonEmpty(sentencesByValue);
            bestSentence = 0;
        }
        String text = assemble(sentencesByValue.get(bestValue), bestSentence, maxChars);
        return new Passage(text, SemanticText.share(query, SemanticText.terms(text)));
    }

    private static int firstNonEmpty(List<List<String>> sentencesByValue) {
        for (int index = 0; index < sentencesByValue.size(); index++) {
            if (!sentencesByValue.get(index).isEmpty()) {
                return index;
            }
        }
        return 0;
    }

    private static String assemble(List<String> sentences, int first, int maxChars) {
        String start = sentences.get(first);
        if (start.length() >= maxChars) {
            return SemanticText.truncate(start, maxChars);
        }
        StringBuilder passage = new StringBuilder(start);
        for (int index = first + 1; index < sentences.size(); index++) {
            String next = sentences.get(index);
            if (passage.length() + 1 + next.length() > maxChars) {
                break;
            }
            passage.append(' ').append(next);
        }
        return passage.toString();
    }

    /** The highlighted copy of {@code passage}: its words that {@code query} holds wrapped in the tags. */
    static String highlight(String passage, List<String> query, String preTag, String postTag) {
        return SemanticText.highlight(passage, Set.copyOf(query), preTag, postTag);
    }
}
