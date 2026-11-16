package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code #Microsoft.Skills.Text.SplitSkill}: splits {@code text} into {@code pages} or {@code sentences}, with the
 * documented parameters ({@code maximumPageLength}, default 5000; {@code pageOverlapLength}; {@code maximumPagesToTake};
 * {@code defaultLanguageCode}; {@code unit}). Sentence boundaries come from the JDK's {@link BreakIterator} for the
 * language. A page grows sentence by sentence while it fits, so it may be shorter than the maximum; a sentence longer
 * than a page breaks between words, and a word longer than a page is cut. Each page after the first starts with up to
 * {@code pageOverlapLength} characters from the end of the previous one, from a word start.
 *
 * <p>Outputs: {@code textItems}, plus {@code offsets} and {@code lengths} (each in UTF-8 bytes, UTF-16 code units and
 * code points) and 1-based {@code ordinalPositions}. With {@code unit} {@code azureOpenAITokens}, lengths are
 * {@linkplain #CHARACTERS_PER_TOKEN approximated} instead of running a tokenizer.</p>
 */
final class SplitSkill implements SkillExecutor {

    /**
     * How many characters one Azure OpenAI token stands for: OpenAI's published rule of thumb for English text. The
     * emulator has no tokenizer, so {@code azureOpenAITokens} lengths are multiplied by this.
     */
    static final int CHARACTERS_PER_TOKEN = 4;

    static final int DEFAULT_MAXIMUM_PAGE_LENGTH = 5000;

    /** The languages the documentation and the REST specification list for the skill, lowercased. */
    static final Set<String> LANGUAGES = Set.of("am", "bs", "cs", "da", "de", "en", "es", "et", "fi", "fr", "he",
        "hi", "hr", "hu", "id", "is", "it", "ja", "ko", "lv", "nb", "no", "nl", "pl", "pt", "pt-pt", "pt-br", "ru",
        "sk", "sl", "sr", "sv", "tr", "ur", "zh", "zh-hans");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public List<RequiredInput> requiredInputs() {
        return List.of(new RequiredInput("text", InputType.STRING));
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        ObjectNode json = skill.json();
        boolean sentences = "sentences".equalsIgnoreCase(SkillDefinition.text(json, "textSplitMode"));
        int factor = "azureOpenAITokens".equalsIgnoreCase(SkillDefinition.text(json, "unit")) ? CHARACTERS_PER_TOKEN : 1;
        int maximum = Math.max(1, intParameter(json, "maximumPageLength", DEFAULT_MAXIMUM_PAGE_LENGTH) * factor);
        int overlap = Math.max(0, intParameter(json, "pageOverlapLength", 0) * factor);
        int pagesToTake = Math.max(0, intParameter(json, "maximumPagesToTake", 0));
        String defaultLanguage = SkillDefinition.text(json, "defaultLanguageCode");
        List<ObjectNode> results = new ArrayList<>(records.size());
        for (ObjectNode record : records) {
            String text = record.get("text").textValue();
            JsonNode languageInput = record.get("languageCode");
            String language = languageInput != null && languageInput.isTextual() && !languageInput.textValue().isBlank()
                ? languageInput.textValue() : defaultLanguage == null ? "en" : defaultLanguage;
            List<int[]> spans;
            if (!LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
                messages.warning(skill, "Skill input 'languageCode' has the following language codes '" + language
                    + "', at least one of which is invalid. The text was not split.");
                spans = text.isEmpty() ? List.of() : List.of(new int[] {0, text.length()});
            } else {
                Locale locale = Locale.forLanguageTag(language);
                spans = sentences ? sentences(text, locale) : pages(text, locale, maximum, overlap, pagesToTake);
            }
            results.add(outputs(text, spans));
        }
        return results;
    }

    private static int intParameter(ObjectNode json, String name, int defaultValue) {
        JsonNode value = json.get(name);
        return value != null && value.canConvertToInt() && value.isNumber() ? value.intValue() : defaultValue;
    }

    /** The sentences of {@code text}, without surrounding whitespace, as {@code [start, end)} spans. */
    static List<int[]> sentences(String text, Locale locale) {
        List<int[]> spans = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(locale);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            int from = start;
            int to = end;
            while (from < to && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }
            if (to > from) {
                spans.add(new int[] {from, to});
            }
        }
        return spans;
    }

    /**
     * The pages of {@code text} as {@code [start, end)} spans: contiguous, except that each page after the first
     * starts up to {@code overlap} characters into the previous one.
     */
    static List<int[]> pages(String text, Locale locale, int maximum, int overlap, int pagesToTake) {
        List<int[]> spans = new ArrayList<>();
        if (text.isEmpty()) {
            return spans;
        }
        int[] sentenceBounds = boundaries(BreakIterator.getSentenceInstance(locale), text);
        int[] wordBounds = boundaries(BreakIterator.getWordInstance(locale), text);
        int effectiveOverlap = Math.min(overlap, maximum / 2);
        int start = 0;
        int pageStart = 0;
        while (start < text.length()) {
            int limit = Math.min(text.length(), pageStart + maximum);
            int end = lastBoundary(sentenceBounds, start, limit);
            if (end < 0) {
                end = lastBoundary(wordBounds, start, limit);
            }
            if (end < 0) {
                end = safeCut(text, limit, start);
            }
            spans.add(new int[] {pageStart, end});
            if (end >= text.length() || (pagesToTake > 0 && spans.size() >= pagesToTake)) {
                break;
            }
            int nextPageStart = end;
            if (effectiveOverlap > 0) {
                int candidate = Math.max(pageStart + 1, end - effectiveOverlap);
                int aligned = firstWordStart(text, wordBounds, candidate, end);
                nextPageStart = aligned >= 0 ? aligned : safeCut(text, candidate, pageStart);
            }
            start = end;
            pageStart = nextPageStart;
        }
        return spans;
    }

    private static int[] boundaries(BreakIterator iterator, String text) {
        iterator.setText(text);
        List<Integer> bounds = new ArrayList<>();
        for (int bound = iterator.first(); bound != BreakIterator.DONE; bound = iterator.next()) {
            bounds.add(bound);
        }
        int[] result = new int[bounds.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = bounds.get(index);
        }
        return result;
    }

    /** The last boundary in {@code (after, limit]}, or -1. */
    private static int lastBoundary(int[] bounds, int after, int limit) {
        int found = -1;
        for (int bound : bounds) {
            if (bound > limit) {
                break;
            }
            if (bound > after) {
                found = bound;
            }
        }
        return found;
    }

    /** The first boundary in {@code [from, before)} where a word starts, or -1. */
    private static int firstWordStart(String text, int[] bounds, int from, int before) {
        for (int bound : bounds) {
            if (bound >= before) {
                break;
            }
            if (bound >= from && !Character.isWhitespace(text.charAt(bound))) {
                return bound;
            }
        }
        return -1;
    }

    /** {@code position}, moved back so that it does not split a surrogate pair, and kept after {@code floor}. */
    private static int safeCut(String text, int position, int floor) {
        int cut = position;
        if (cut > floor + 1 && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))
                && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return Math.max(cut, floor + 1);
    }

    private static ObjectNode outputs(String text, List<int[]> spans) {
        ArrayNode items = NODES.arrayNode();
        ArrayNode offsets = NODES.arrayNode();
        ArrayNode lengths = NODES.arrayNode();
        ArrayNode ordinals = NODES.arrayNode();
        int ordinal = 1;
        for (int[] span : spans) {
            items.add(text.substring(span[0], span[1]));
            offsets.add(measure(text, 0, span[0]));
            lengths.add(measure(text, span[0], span[1]));
            ordinals.add(ordinal++);
        }
        ObjectNode result = NODES.objectNode();
        result.set("textItems", items);
        result.set("offsets", offsets);
        result.set("lengths", lengths);
        result.set("ordinalPositions", ordinals);
        return result;
    }

    /** The length of {@code text[from, to)} in the three documented encodings. */
    static ObjectNode measure(String text, int from, int to) {
        long utf8 = 0;
        int codePoints = 0;
        int index = from;
        while (index < to) {
            int codePoint = text.codePointAt(index);
            utf8 += codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            codePoints++;
            index += Character.charCount(codePoint);
        }
        ObjectNode measured = NODES.objectNode();
        measured.put("utf8", utf8);
        measured.put("utf16", to - from);
        measured.put("codePoint", codePoints);
        return measured;
    }
}
