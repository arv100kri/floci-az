package io.floci.az.services.search.engine.semantic;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.model.SearchFieldDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * The text the stand-in reads from one document, assembled as Learn's "How the system collects and summarizes
 * inputs" describes it: the title field up to 128 tokens, the keywords fields up to 128 tokens together, then the
 * content fields in priority order up to the rest of the 2,048-token input; text past a limit is left out. The
 * stand-in counts words as tokens.
 *
 * @param title          the title text, empty when there is none
 * @param contentValues  the content fields' values in priority order, each cut to the budget, empty ones left out
 * @param keywords       the keywords fields' values, comma-separated
 * @param titleUse       how the title field was used, or null when the configuration has none
 * @param contentUses    how each content field was used
 * @param keywordsUses   how each keywords field was used
 */
record SemanticInput(String title, List<String> contentValues, String keywords, FieldUse titleUse,
                     List<FieldUse> contentUses, List<FieldUse> keywordsUses) {

    /** The tokens the title field contributes at most. */
    static final int TITLE_TOKENS = 128;

    /** The tokens the keywords fields contribute at most, together. */
    static final int KEYWORDS_TOKENS = 128;

    /** The tokens of one document's whole input. */
    static final int TOTAL_TOKENS = 2048;

    SemanticInput {
        contentValues = List.copyOf(contentValues);
        contentUses = List.copyOf(contentUses);
        keywordsUses = List.copyOf(keywordsUses);
    }

    /**
     * How a prioritized field fed the stand-in, as {@code @search.documentDebugInfo.semantic} reports it.
     *
     * @param name  the field path
     * @param state {@code used} (all of it), {@code partial} (cut at a token limit) or {@code unused}
     */
    record FieldUse(String name, String state) {
    }

    /** The input of {@code source} under {@code config}. */
    static SemanticInput of(ObjectNode source, SemanticConfig config) {
        int remaining = TOTAL_TOKENS;
        String title = "";
        FieldUse titleUse = null;
        if (config.titleField() != null) {
            Cut cut = Cut.of(String.join(" ", DocumentValues.textValues(source, config.titleField())), TITLE_TOKENS);
            title = cut.text();
            remaining -= cut.words();
            titleUse = new FieldUse(config.titleField().path(), cut.state());
        }

        List<String> keywordTexts = new ArrayList<>();
        List<FieldUse> keywordsUses = new ArrayList<>();
        int keywordBudget = Math.min(KEYWORDS_TOKENS, remaining);
        for (SearchFieldDefinition field : config.keywordsFields()) {
            Cut cut = Cut.of(String.join(", ", DocumentValues.textValues(source, field)), keywordBudget);
            if (!cut.text().isBlank()) {
                keywordTexts.add(cut.text());
            }
            keywordBudget -= cut.words();
            remaining -= cut.words();
            keywordsUses.add(new FieldUse(field.path(), cut.state()));
        }

        List<String> contentValues = new ArrayList<>();
        List<FieldUse> contentUses = new ArrayList<>();
        for (SearchFieldDefinition field : config.contentFields()) {
            int before = remaining;
            boolean cutShort = false;
            boolean any = false;
            for (String value : DocumentValues.textValues(source, field)) {
                Cut cut = Cut.of(value, remaining);
                if (!cut.text().isBlank()) {
                    contentValues.add(cut.text());
                }
                remaining -= cut.words();
                cutShort |= cut.truncated();
                any |= cut.hadWords();
            }
            String state = !any || before == remaining ? Cut.UNUSED : cutShort ? Cut.PARTIAL : Cut.USED;
            contentUses.add(new FieldUse(field.path(), state));
        }
        return new SemanticInput(title, contentValues, String.join(", ", keywordTexts), titleUse, contentUses,
            keywordsUses);
    }

    /** The content values joined, as the reranker input reports them. */
    String content() {
        return String.join(" ", contentValues);
    }

    /**
     * A text cut to a number of words.
     *
     * @param text      what was kept
     * @param words     the words kept
     * @param truncated whether words were left out
     */
    private record Cut(String text, int words, boolean truncated) {

        static final String USED = "used";
        static final String PARTIAL = "partial";
        static final String UNUSED = "unused";

        static Cut of(String text, int budget) {
            int limit = Math.max(budget, 0);
            List<SemanticText.Word> words = SemanticText.words(text, limit + 1);
            if (words.size() <= limit) {
                return new Cut(text, words.size(), false);
            }
            String kept = limit == 0 ? "" : text.substring(0, words.get(limit - 1).end());
            return new Cut(kept, limit, true);
        }

        /** Whether the text had words at all. */
        boolean hadWords() {
            return words > 0 || truncated;
        }

        String state() {
            if (words == 0) {
                return UNUSED;
            }
            return truncated ? PARTIAL : USED;
        }
    }
}
