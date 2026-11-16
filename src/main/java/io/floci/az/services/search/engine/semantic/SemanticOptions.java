package io.floci.az.services.search.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The semantic parameters of a search request, validated as Search Documents (REST) and Learn's semantic ranking pages
 * describe them: {@code answers} ({@code extractive}, with {@code count-<n>} up to 10, {@code threshold-<t>} from 0 to
 * 1, default 0.7, and {@code maxcharlength-<n>}), {@code captions} ({@code extractive}, with {@code highlight-<bool>},
 * default true, and {@code maxcharlength-<n>}), {@code queryRewrites} ({@code generative}, with {@code count-<n>} from
 * 1 to 10, which needs {@code queryLanguage}), {@code semanticErrorHandling} ({@code partial} or {@code fail}),
 * {@code semanticMaxWaitInMilliseconds} (at least 700) and {@code debug} ({@code semantic}, {@code queryRewrites} and
 * {@code all} concern semantic ranking). Options follow the type after a {@code |}; SDKs separate further options with
 * {@code |} or {@code ,}, and both are accepted.
 *
 * <p>Answers, captions and query rewrites are only valid on a semantic request: one with {@code queryType=semantic}
 * or a non-empty {@code semanticQuery}.</p>
 *
 * @param requested       whether semantic ranking is requested
 * @param rankingText     the text the semantic ranker compares documents with: {@code semanticQuery}, else
 *                        {@code search}, else the text of the first vectorizable text query; null when there is none
 * @param answers         the answers option
 * @param captions        the captions option
 * @param rewrites        the query rewrites option
 * @param debugSemantic   whether {@code debug} asks for each result's semantic inputs
 * @param debugRewrites   whether {@code debug} asks for the query rewrites
 */
public record SemanticOptions(boolean requested, String rankingText, Answers answers, Captions captions,
                              Rewrites rewrites, boolean debugSemantic, boolean debugRewrites) {

    /** The fewest milliseconds {@code semanticMaxWaitInMilliseconds} accepts. */
    static final int MIN_MAX_WAIT_MILLIS = 700;

    /** The most answers, and the most query rewrites, a request may ask for. */
    static final int MAX_COUNT = 10;

    /** The answer confidence threshold when the request names none. */
    static final double DEFAULT_ANSWER_THRESHOLD = 0.7;

    /** The query rewrites {@code generative} asks for without a count. */
    static final int DEFAULT_REWRITE_COUNT = 10;

    /** Whether {@code request} asks for semantic ranking: {@code queryType=semantic} or a non-empty semanticQuery. */
    public static boolean isRequested(SearchRequest request) {
        return request.effectiveQueryType() == QueryType.SEMANTIC
            || (request.semanticQuery() != null && !request.semanticQuery().isBlank());
    }

    /**
     * The 400 a semantic request gets from a service without semantic ranking: {@code semantic-ranker: disabled}, or
     * a service whose {@code semanticSearch} plan is {@code disabled}.
     */
    public static SearchException notEnabled(SearchRequest request) {
        String parameter = request.effectiveQueryType() == QueryType.SEMANTIC ? "queryType" : "semanticQuery";
        return SearchException.badRequest("Semantic search is not enabled for this service.\r\nParameter name: "
            + parameter);
    }

    /** Parses and validates the semantic parameters of {@code request}; a 400 for an invalid one. */
    public static SemanticOptions parse(SearchRequest request) {
        boolean requested = isRequested(request);
        Answers answers = Answers.parse(request.answers());
        Captions captions = Captions.parse(request.captions());
        Rewrites rewrites = Rewrites.parse(request.queryRewrites());
        validateErrorHandling(request.semanticErrorHandling());
        validateMaxWait(request.semanticMaxWaitInMilliseconds());
        if (!requested) {
            requireSemantic(answers.extractive(), "answers");
            requireSemantic(captions.extractive(), "captions");
            requireSemantic(rewrites.generative(), "queryRewrites");
        } else if (rewrites.generative() && (request.queryLanguage() == null || request.queryLanguage().isBlank()
                || "none".equalsIgnoreCase(request.queryLanguage().trim()))) {
            throw SearchException.badRequest("Invalid search request: query rewrites need the language of the query; "
                + "set 'queryLanguage' when 'queryRewrites' is 'generative'.\r\nParameter name: queryLanguage");
        }
        List<String> debug = debugModes(request.debug());
        boolean all = debug.contains("all");
        return new SemanticOptions(requested, requested ? rankingText(request) : null, answers, captions, rewrites,
            all || debug.contains("semantic"), all || debug.contains("queryrewrites"));
    }

    private static String rankingText(SearchRequest request) {
        if (request.semanticQuery() != null && !request.semanticQuery().isBlank()) {
            return request.semanticQuery();
        }
        if (!request.matchesAllText()) {
            return request.search();
        }
        for (ObjectNode vectorQuery : request.vectorQueries()) {
            JsonNode text = vectorQuery.get("text");
            if ("text".equalsIgnoreCase(vectorQuery.path("kind").asText()) && text != null && text.isTextual()
                    && !text.textValue().isBlank()) {
                return text.textValue();
            }
        }
        return null;
    }

    private static void requireSemantic(boolean set, String parameter) {
        if (set) {
            throw SearchException.badRequest("Invalid search request: '" + parameter + "' is only valid for semantic "
                + "queries; set 'queryType' to 'semantic' or provide a 'semanticQuery'.\r\nParameter name: "
                + parameter);
        }
    }

    private static void validateErrorHandling(String value) {
        if (value == null) {
            return;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (!lower.equals("partial") && !lower.equals("fail")) {
            throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'semanticErrorHandling'. "
                + "Valid values are 'partial' and 'fail'.");
        }
    }

    private static void validateMaxWait(Integer value) {
        if (value != null && value < MIN_MAX_WAIT_MILLIS) {
            throw SearchException.badRequest("Invalid value " + value + " for parameter "
                + "'semanticMaxWaitInMilliseconds'. It must be at least " + MIN_MAX_WAIT_MILLIS + ".");
        }
    }

    /** The debug modes, lowercased: {@code semantic|queryRewrites} names two. */
    private static List<String> debugModes(String value) {
        List<String> modes = new ArrayList<>();
        if (value == null) {
            return modes;
        }
        for (String mode : value.split("[|,]")) {
            String trimmed = mode.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                modes.add(trimmed);
            }
        }
        return modes;
    }

    /** {@code value} split into its type (first) and its {@code name-value} options; a 400 when it names no type. */
    private static List<String> parts(String parameter, String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("[|,]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            throw invalidOption(parameter, value, "It must name a type.");
        }
        return parts;
    }

    private static SearchException invalidOption(String parameter, String value, String expected) {
        return SearchException.badRequest("Invalid value '" + value + "' for parameter '" + parameter + "'. "
            + expected);
    }

    private static String optionName(String option) {
        int dash = option.indexOf('-');
        return (dash < 0 ? option : option.substring(0, dash)).toLowerCase(Locale.ROOT);
    }

    private static String optionValue(String option) {
        int dash = option.indexOf('-');
        return dash < 0 ? "" : option.substring(dash + 1).trim();
    }

    private static int intOption(String parameter, String value, String option, int min, int max) {
        try {
            int parsed = Integer.parseInt(optionValue(option));
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // not a number: reported below with the valid range
        }
        throw invalidOption(parameter, value, "The option '" + optionName(option) + "' must be an integer from " + min
            + " to " + max + ".");
    }

    /**
     * The {@code answers} option.
     *
     * @param extractive    whether answers are requested
     * @param count         the most answers to return
     * @param threshold     the confidence an answer needs
     * @param maxCharLength the longest answer, in characters; null for the stand-in's default
     */
    public record Answers(boolean extractive, int count, double threshold, Integer maxCharLength) {

        static final Answers NONE = new Answers(false, 1, DEFAULT_ANSWER_THRESHOLD, null);

        static Answers parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            List<String> parts = parts("answers", value);
            String type = parts.get(0).toLowerCase(Locale.ROOT);
            if (type.equals("none") && parts.size() == 1) {
                return NONE;
            }
            if (!type.equals("extractive")) {
                throw invalidOption("answers", value, "Valid values are 'none' and 'extractive', optionally followed "
                    + "by '|count-<n>', '|threshold-<t>' or '|maxcharlength-<n>'.");
            }
            int count = 1;
            double threshold = DEFAULT_ANSWER_THRESHOLD;
            Integer maxCharLength = null;
            for (String option : parts.subList(1, parts.size())) {
                switch (optionName(option)) {
                    case "count" -> count = intOption("answers", value, option, 1, MAX_COUNT);
                    case "threshold" -> threshold = threshold(value, option);
                    case "maxcharlength" -> maxCharLength = intOption("answers", value, option, 1, Integer.MAX_VALUE);
                    default -> throw invalidOption("answers", value, "Unknown option '" + option + "'; the options "
                        + "are 'count', 'threshold' and 'maxcharlength'.");
                }
            }
            return new Answers(true, count, threshold, maxCharLength);
        }

        private static double threshold(String value, String option) {
            try {
                double parsed = Double.parseDouble(optionValue(option));
                if (parsed >= 0 && parsed <= 1) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // not a number: reported below with the valid range
            }
            throw invalidOption("answers", value, "The option 'threshold' must be a number from 0 to 1.");
        }
    }

    /**
     * The {@code captions} option.
     *
     * @param extractive    whether captions are requested
     * @param highlight     whether captions carry highlights
     * @param maxCharLength the longest caption, in characters; null for the stand-in's default
     */
    public record Captions(boolean extractive, boolean highlight, Integer maxCharLength) {

        static final Captions NONE = new Captions(false, false, null);

        static Captions parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            List<String> parts = parts("captions", value);
            String type = parts.get(0).toLowerCase(Locale.ROOT);
            if (type.equals("none") && parts.size() == 1) {
                return NONE;
            }
            if (!type.equals("extractive")) {
                throw invalidOption("captions", value, "Valid values are 'none' and 'extractive', optionally followed "
                    + "by '|highlight-<true|false>' or '|maxcharlength-<n>'.");
            }
            boolean highlight = true;
            Integer maxCharLength = null;
            for (String option : parts.subList(1, parts.size())) {
                switch (optionName(option)) {
                    case "highlight" -> highlight = bool(value, option);
                    case "maxcharlength" -> maxCharLength = intOption("captions", value, option, 1, Integer.MAX_VALUE);
                    default -> throw invalidOption("captions", value, "Unknown option '" + option + "'; the options "
                        + "are 'highlight' and 'maxcharlength'.");
                }
            }
            return new Captions(true, highlight, maxCharLength);
        }

        private static boolean bool(String value, String option) {
            String flag = optionValue(option).toLowerCase(Locale.ROOT);
            if (!flag.equals("true") && !flag.equals("false")) {
                throw invalidOption("captions", value, "The option 'highlight' must be true or false.");
            }
            return flag.equals("true");
        }
    }

    /**
     * The {@code queryRewrites} option.
     *
     * @param generative whether query rewrites are requested
     * @param count      the most rewrites asked for
     */
    public record Rewrites(boolean generative, int count) {

        static final Rewrites NONE = new Rewrites(false, 0);

        static Rewrites parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            List<String> parts = parts("queryRewrites", value);
            String type = parts.get(0).toLowerCase(Locale.ROOT);
            if (type.equals("none") && parts.size() == 1) {
                return NONE;
            }
            if (!type.equals("generative")) {
                throw invalidOption("queryRewrites", value, "Valid values are 'none' and 'generative', optionally "
                    + "followed by '|count-<n>'.");
            }
            int count = DEFAULT_REWRITE_COUNT;
            for (String option : parts.subList(1, parts.size())) {
                if (!optionName(option).equals("count")) {
                    throw invalidOption("queryRewrites", value, "Unknown option '" + option + "'; the option is "
                        + "'count'.");
                }
                count = intOption("queryRewrites", value, option, 1, MAX_COUNT);
            }
            return new Rewrites(true, count);
        }
    }
}
