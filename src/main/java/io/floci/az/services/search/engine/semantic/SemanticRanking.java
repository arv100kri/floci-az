package io.floci.az.services.search.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.semantic.SemanticInput.FieldUse;
import io.floci.az.services.search.engine.semantic.SemanticPassages.Passage;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>STAND-IN for Azure's semantic ranker</b>, whose language models are proprietary: a small deterministic lexical
 * judge that keeps the documented shapes (a {@code @search.rerankerScore} from 0 to 4, extractive captions with
 * highlights, extractive answers with a confidence score, the debug and query rewrite annotations) and floci-az's own
 * relevance model.
 *
 * <p>The relevance of a document is the share of the query's terms (stop words left out, plural endings folded) its
 * prioritized fields hold: the title weighs 3, the content 2 and the keywords 1, normalised over the roles the
 * configuration defines. The content's share is the mean of the share the whole content holds and the share its best
 * sentence holds, so terms that occur together count more. The reranker score is that relevance times 4. A caption is
 * the best-matching passage of the content (else the title, else the keywords); an answer is the same passage, scored
 * by the share of the question's terms it holds, and only questions get answers. The stand-in generates no query
 * rewrites: a request for them reports {@code originalQueryOnly}, as Azure does when rewrites are unavailable.</p>
 */
public final class SemanticRanking {

    /** The highest {@code @search.rerankerScore}. */
    public static final double MAX_RERANKER_SCORE = 4.0;

    private static final double TITLE_WEIGHT = 3;
    private static final double CONTENT_WEIGHT = 2;
    private static final double KEYWORDS_WEIGHT = 1;

    private final SemanticOptions options;
    private final SemanticConfig config;
    private final List<String> query;
    private final String preTag;
    private final String postTag;

    /**
     * A ranking of documents for one query.
     *
     * @param options the request's semantic options
     * @param config  the semantic configuration
     * @param preTag  the tag before each highlight
     * @param postTag the tag after each highlight
     */
    public SemanticRanking(SemanticOptions options, SemanticConfig config, String preTag, String postTag) {
        this.options = options;
        this.config = config;
        this.query = SemanticText.queryTerms(options.rankingText());
        this.preTag = preTag;
        this.postTag = postTag;
    }

    /**
     * Whether the query gives the stand-in something to rank by. A query of {@code *}, or with no words, is not
     * semantically ranked, as Learn documents for an empty search.
     */
    public boolean ranks() {
        return !query.isEmpty();
    }

    /** Judges one document. */
    public Judgement judge(String key, ObjectNode source) {
        SemanticInput input = SemanticInput.of(source, config);
        double weighted = 0;
        double weights = 0;
        if (config.titleField() != null) {
            weighted += TITLE_WEIGHT * SemanticText.share(query, SemanticText.terms(input.title()));
            weights += TITLE_WEIGHT;
        }
        if (!config.contentFields().isEmpty()) {
            double whole = SemanticText.share(query, SemanticText.terms(input.content()));
            weighted += CONTENT_WEIGHT * (whole + bestSentenceShare(input)) / 2;
            weights += CONTENT_WEIGHT;
        }
        if (!config.keywordsFields().isEmpty()) {
            weighted += KEYWORDS_WEIGHT * SemanticText.share(query, SemanticText.terms(input.keywords()));
            weights += KEYWORDS_WEIGHT;
        }
        double score = weights == 0 ? 0 : MAX_RERANKER_SCORE * weighted / weights;
        return new Judgement(key, score, input);
    }

    private double bestSentenceShare(SemanticInput input) {
        double best = 0;
        for (String value : input.contentValues()) {
            for (SemanticText.Sentence sentence : SemanticText.sentences(value)) {
                String text = value.substring(sentence.start(), sentence.end());
                best = Math.max(best, SemanticText.share(query, SemanticText.terms(text)));
            }
        }
        return best;
    }

    /** The {@code @search.captions} of a judged document, or null when captions were not requested. */
    public List<ObjectNode> captions(Judgement judgement) {
        SemanticOptions.Captions captions = options.captions();
        if (!captions.extractive()) {
            return null;
        }
        Passage passage = passage(judgement.input(), captions.maxCharLength());
        if (passage == null) {
            return List.of();
        }
        ObjectNode caption = SearchJson.object();
        caption.put("text", passage.text());
        if (captions.highlight()) {
            caption.put("highlights", SemanticPassages.highlight(passage.text(), query, preTag, postTag));
        } else {
            caption.putNull("highlights");
        }
        return List.of(caption);
    }

    /**
     * The {@code @search.answers}, from judged documents in their reranked order: one candidate passage per document,
     * kept when its score reaches the threshold, best first, at most the requested count. Empty when the query does not
     * read as a question; null when answers were not requested.
     */
    public ArrayNode answers(List<Judgement> reranked) {
        SemanticOptions.Answers answers = options.answers();
        if (!answers.extractive()) {
            return null;
        }
        ArrayNode array = SearchJson.NODES.arrayNode();
        if (!ranks() || !SemanticText.isQuestion(options.rankingText())) {
            return array;
        }
        List<Answer> candidates = new ArrayList<>();
        for (Judgement judgement : reranked) {
            Passage passage = passage(judgement.input(), answers.maxCharLength());
            if (passage != null && passage.coverage() >= answers.threshold()) {
                candidates.add(new Answer(judgement.key(), passage));
            }
        }
        candidates.sort(Comparator.comparingDouble((Answer answer) -> answer.passage().coverage()).reversed());
        for (Answer answer : candidates.subList(0, Math.min(answers.count(), candidates.size()))) {
            ObjectNode item = array.addObject();
            item.put("key", answer.key());
            item.put("text", answer.passage().text());
            item.put("highlights", SemanticPassages.highlight(answer.passage().text(), query, preTag, postTag));
            item.put("score", answer.passage().coverage());
        }
        return array;
    }

    /** The {@code semantic} entry of a judged document's {@code @search.documentDebugInfo}, or null when not asked. */
    public ObjectNode documentDebug(Judgement judgement) {
        if (!options.debugSemantic()) {
            return null;
        }
        SemanticInput input = judgement.input();
        ObjectNode semantic = SearchJson.object();
        if (input.titleUse() != null) {
            semantic.set("titleField", fieldUse(input.titleUse()));
        }
        ArrayNode content = semantic.putArray("contentFields");
        input.contentUses().forEach(use -> content.add(fieldUse(use)));
        ArrayNode keywords = semantic.putArray("keywordFields");
        input.keywordsUses().forEach(use -> keywords.add(fieldUse(use)));
        ObjectNode rerankerInput = semantic.putObject("rerankerInput");
        rerankerInput.put("title", input.title());
        rerankerInput.put("content", input.content());
        rerankerInput.put("keywords", input.keywords());
        return semantic;
    }

    /**
     * The {@code @search.debug} of the response, or null when {@code debug} does not ask for query rewrites. The
     * stand-in generates none, so the lists are empty, in the shape Learn's query rewrite page shows.
     */
    public JsonNode responseDebug() {
        if (!options.debugRewrites()) {
            return null;
        }
        ObjectNode debug = SearchJson.object();
        debug.putNull("semantic");
        ObjectNode rewrites = debug.putObject("queryRewrites");
        ObjectNode text = rewrites.putObject("text");
        if (!options.rewrites().generative()) {
            text.put("inputQuery", "");
        }
        text.putArray("rewrites");
        rewrites.putArray("vectors");
        return debug;
    }

    /**
     * The {@code @search.semanticQueryRewritesResultType} of the response: {@code originalQueryOnly} when rewrites were
     * requested (the stand-in generates none, so only the original query ran), else null.
     */
    public String queryRewritesResultType() {
        return options.rewrites().generative() ? "originalQueryOnly" : null;
    }

    private Passage passage(SemanticInput input, Integer maxCharLength) {
        int maxChars = maxCharLength == null ? SemanticPassages.DEFAULT_MAX_CHARS : maxCharLength;
        if (!input.contentValues().isEmpty()) {
            return SemanticPassages.best(input.contentValues(), query, maxChars);
        }
        if (!input.title().isBlank()) {
            return SemanticPassages.best(List.of(input.title()), query, maxChars);
        }
        return input.keywords().isBlank() ? null : SemanticPassages.best(List.of(input.keywords()), query, maxChars);
    }

    private static ObjectNode fieldUse(FieldUse use) {
        ObjectNode field = SearchJson.object();
        field.put("name", use.name());
        field.put("state", use.state());
        return field;
    }

    /**
     * The stand-in's verdict on one document.
     *
     * @param key           the document key
     * @param rerankerScore the {@code @search.rerankerScore}, from 0 to 4
     * @param input         the text the stand-in read
     */
    public record Judgement(String key, double rerankerScore, SemanticInput input) {
    }

    private record Answer(String key, Passage passage) {
    }
}
