package io.floci.az.services.search.engine.semantic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The stand-in's text handling, independent of the index's analyzers (Azure's semantic models do not use them):
 * words with their offsets, matched in a normalised form (lowercase, ASCII-folded, possessive and plural endings
 * removed), English stop words, sentence boundaries, and highlighted copies of a passage.
 *
 * <p>Plural endings follow the published "S" stemmer (Harman, 1991): {@code -ies} becomes {@code -y}, {@code -es}
 * becomes {@code -e}, and a final {@code -s} is dropped, except after the endings the rule names.</p>
 */
final class SemanticText {

    /** Words that carry no topic: Lucene's English stop words plus pronouns, auxiliaries and question words. */
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it", "no", "not",
        "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they", "this", "to", "was",
        "will", "with", "am", "been", "being", "were", "do", "does", "did", "doing", "have", "has", "had", "having",
        "can", "could", "should", "would", "may", "might", "must", "shall", "i", "me", "my", "we", "our", "us", "you",
        "your", "he", "him", "his", "she", "her", "its", "them", "what", "which", "who", "whom", "whose", "when",
        "where", "why", "how", "from", "about", "so", "than", "too", "very", "any", "some", "all");

    /** Words that open a question, so a query starting with one is treated as a question. */
    private static final Set<String> QUESTION_OPENERS = Set.of(
        "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "is", "are", "was", "were", "am", "do",
        "does", "did", "can", "could", "should", "would", "will", "shall", "may", "might", "must", "has", "have",
        "had");

    private SemanticText() {
    }

    /**
     * A word of a text.
     *
     * @param term     the normalised form words are matched by
     * @param stopWord whether the word is a stop word
     * @param start    the offset of the word's first character
     * @param end      the offset just past the word's last character
     */
    record Word(String term, boolean stopWord, int start, int end) {
    }

    /**
     * A sentence of a text.
     *
     * @param start the offset of the sentence's first character
     * @param end   the offset just past its last character, its closing punctuation included
     */
    record Sentence(int start, int end) {
    }

    /** The words of {@code text}: runs of letters and digits, with apostrophes inside a word kept. */
    static List<Word> words(String text) {
        return words(text, Integer.MAX_VALUE);
    }

    /** The first {@code limit} words of {@code text}. */
    static List<Word> words(String text, int limit) {
        List<Word> words = new ArrayList<>();
        int length = text.length();
        int index = 0;
        while (index < length && words.size() < limit) {
            int codePoint = text.codePointAt(index);
            if (!Character.isLetterOrDigit(codePoint)) {
                index += Character.charCount(codePoint);
                continue;
            }
            int start = index;
            while (index < length) {
                int current = text.codePointAt(index);
                if (Character.isLetterOrDigit(current)) {
                    index += Character.charCount(current);
                } else if (isApostrophe(current) && index + 1 < length
                        && Character.isLetterOrDigit(text.codePointAt(index + 1))) {
                    index++;
                } else {
                    break;
                }
            }
            String plain = plainForm(text.substring(start, index));
            if (!plain.isEmpty()) {
                words.add(new Word(stem(plain), STOP_WORDS.contains(plain), start, index));
            }
        }
        return words;
    }

    /**
     * The distinct terms a query asks for, in order: its words without stop words, or every word when the query holds
     * nothing but stop words.
     */
    static List<String> queryTerms(String query) {
        if (query == null) {
            return List.of();
        }
        Set<String> all = new LinkedHashSet<>();
        Set<String> content = new LinkedHashSet<>();
        for (Word word : words(query)) {
            all.add(word.term());
            if (!word.stopWord()) {
                content.add(word.term());
            }
        }
        return List.copyOf(content.isEmpty() ? all : content);
    }

    /** The distinct normalised terms of {@code text}. */
    static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (Word word : words(text)) {
            terms.add(word.term());
        }
        return terms;
    }

    /** The share of {@code query} terms that {@code terms} holds, from 0 to 1; 0 for an empty query. */
    static double share(List<String> query, Set<String> terms) {
        if (query.isEmpty()) {
            return 0;
        }
        int found = 0;
        for (String term : query) {
            if (terms.contains(term)) {
                found++;
            }
        }
        return (double) found / query.size();
    }

    /** Whether {@code query} reads as a question: it ends with a question mark or opens with a question word. */
    static boolean isQuestion(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String trimmed = query.strip();
        if (trimmed.endsWith("?") || trimmed.endsWith("\uFF1F")) {
            return true;
        }
        List<Word> words = words(trimmed);
        if (words.isEmpty()) {
            return false;
        }
        Word first = words.get(0);
        return QUESTION_OPENERS.contains(trimmed.substring(first.start(), first.end()).toLowerCase(Locale.ROOT));
    }

    /**
     * The sentences of {@code text}: a sentence ends at a full stop, question mark or exclamation mark followed by
     * whitespace or the end of the text. Line breaks do not end a sentence; surrounding whitespace is left out.
     */
    static List<Sentence> sentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        int length = text.length();
        int start = 0;
        for (int index = 0; index < length; index++) {
            boolean atEnd = index + 1 == length || Character.isWhitespace(text.charAt(index + 1));
            if (isTerminal(text.charAt(index)) && atEnd) {
                addSentence(text, start, index + 1, sentences);
                start = index + 1;
            }
        }
        addSentence(text, start, length, sentences);
        return sentences;
    }

    /** {@code text} with every run of whitespace, line breaks included, replaced by one space, and trimmed. */
    static String collapseWhitespace(String text) {
        StringBuilder collapsed = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character)) {
                pendingSpace = !collapsed.isEmpty();
            } else {
                if (pendingSpace) {
                    collapsed.append(' ');
                    pendingSpace = false;
                }
                collapsed.append(character);
            }
        }
        return collapsed.toString();
    }

    /**
     * {@code text} with each run of adjacent words that {@code terms} holds wrapped in {@code preTag} and
     * {@code postTag}; the empty string when no word matches.
     */
    static String highlight(String text, Set<String> terms, String preTag, String postTag) {
        List<Word> words = words(text);
        StringBuilder highlighted = new StringBuilder(text.length() + 32);
        int copied = 0;
        boolean any = false;
        int index = 0;
        while (index < words.size()) {
            if (!terms.contains(words.get(index).term())) {
                index++;
                continue;
            }
            int last = index;
            while (last + 1 < words.size() && terms.contains(words.get(last + 1).term())) {
                last++;
            }
            int start = words.get(index).start();
            int end = words.get(last).end();
            highlighted.append(text, copied, start).append(preTag).append(text, start, end).append(postTag);
            copied = end;
            any = true;
            index = last + 1;
        }
        if (!any) {
            return "";
        }
        return highlighted.append(text, copied, text.length()).toString();
    }

    /** {@code text} cut after its last whole word that ends within {@code maxChars} characters. */
    static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = 0;
        for (Word word : words(text)) {
            if (word.end() > maxChars) {
                break;
            }
            cut = word.end();
        }
        return text.substring(0, cut == 0 ? maxChars : cut).strip();
    }

    /** {@code text} cut after its {@code maxWords}-th word; the whole text when it has no more words than that. */
    static String firstWords(String text, int maxWords) {
        if (maxWords <= 0) {
            return "";
        }
        List<Word> words = words(text, maxWords + 1);
        if (words.size() <= maxWords) {
            return text;
        }
        return text.substring(0, words.get(maxWords - 1).end());
    }

    /** The "S" stemmer: removes an English plural ending from a word longer than three characters. */
    static String stem(String word) {
        if (word.length() <= 3) {
            return word;
        }
        if (word.endsWith("ies") && !word.endsWith("eies") && !word.endsWith("aies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("es") && !word.endsWith("aes") && !word.endsWith("ees") && !word.endsWith("oes")) {
            return word.substring(0, word.length() - 1);
        }
        if (word.endsWith("s") && !word.endsWith("us") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static boolean isTerminal(char character) {
        return character == '.' || character == '!' || character == '?' || character == '\u3002'
            || character == '\uFF01' || character == '\uFF1F';
    }

    private static void addSentence(String text, int start, int end, List<Sentence> sentences) {
        int from = start;
        int to = end;
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        if (from < to) {
            sentences.add(new Sentence(from, to));
        }
    }

    private static boolean isApostrophe(int codePoint) {
        return codePoint == '\'' || codePoint == '\u2019';
    }

    /** A word lowercased and folded to ASCII, without a possessive {@code 's} or apostrophes. */
    private static String plainForm(String word) {
        String lower = foldToAscii(word.toLowerCase(Locale.ROOT));
        if (lower.endsWith("'s") || lower.endsWith("\u2019s")) {
            lower = lower.substring(0, lower.length() - 2);
        }
        return lower.replace("'", "").replace("\u2019", "");
    }

    private static String foldToAscii(String text) {
        boolean ascii = true;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return text;
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        for (int index = 0; index < decomposed.length(); index++) {
            char character = decomposed.charAt(index);
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                folded.append(character);
            }
        }
        return folded.toString();
    }
}
