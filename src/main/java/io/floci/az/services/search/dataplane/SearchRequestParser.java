package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.QueryType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchMode;
import io.floci.az.services.search.model.SearchRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a Search Documents request from a POST body ({@code SearchRequest}) or a GET query string
 * ({@code SearchOptions}, with their {@code $}-prefixed names), into the same {@link SearchRequest}. Values of the
 * wrong type are a 400; comma-separated lists may also be sent as JSON arrays. Body properties the record does not
 * name are kept in {@link SearchRequest#additionalProperties()}.
 */
public final class SearchRequestParser {

    private SearchRequestParser() {
    }

    /** Parses a POST body. */
    public static SearchRequest fromBody(ObjectNode body) {
        SearchRequest.Builder builder = SearchRequest.builder();
        Iterator<Map.Entry<String, JsonNode>> properties = body.fields();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            String name = property.getKey();
            JsonNode value = property.getValue();
            if (value.isNull()) {
                continue;
            }
            switch (name) {
                case "search" -> builder.search(text(name, value));
                case "searchMode" -> builder.searchMode(SearchMode.parse(text(name, value)));
                case "queryType" -> builder.queryType(QueryType.parse(text(name, value)));
                case "searchFields" -> builder.searchFields(list(name, value));
                case "filter" -> builder.filter(text(name, value));
                case "orderby" -> builder.orderBy(joined(name, value));
                case "select" -> builder.select(list(name, value));
                case "top" -> builder.top(integer(name, value));
                case "skip" -> builder.skip(integer(name, value));
                case "count" -> builder.count(bool(name, value));
                case "facets" -> builder.facets(array(name, value));
                case "highlight" -> builder.highlightFields(list(name, value));
                case "highlightPreTag" -> builder.highlightPreTag(text(name, value));
                case "highlightPostTag" -> builder.highlightPostTag(text(name, value));
                case "minimumCoverage" -> builder.minimumCoverage(number(name, value));
                case "scoringProfile" -> builder.scoringProfile(text(name, value));
                case "scoringParameters" -> builder.scoringParameters(array(name, value));
                case "scoringStatistics" -> builder.scoringStatistics(scoringStatistics(text(name, value)));
                case "sessionId" -> builder.sessionId(sessionId(text(name, value)));
                case "vectorQueries" -> builder.vectorQueries(objects(name, value));
                case "vectorFilterMode" -> builder.vectorFilterMode(text(name, value));
                case "hybridSearch" -> builder.hybridSearch(object(name, value));
                case "semanticConfiguration" -> builder.semanticConfiguration(text(name, value));
                case "semanticQuery" -> builder.semanticQuery(text(name, value));
                case "semanticErrorHandling" -> builder.semanticErrorHandling(text(name, value));
                case "semanticMaxWaitInMilliseconds" -> builder.semanticMaxWaitInMilliseconds(integer(name, value));
                case "semanticFields" -> builder.semanticFields(list(name, value));
                case "answers" -> builder.answers(text(name, value));
                case "captions" -> builder.captions(text(name, value));
                case "queryRewrites" -> builder.queryRewrites(text(name, value));
                case "debug" -> builder.debug(text(name, value));
                case "queryLanguage" -> builder.queryLanguage(text(name, value));
                case "speller" -> builder.speller(text(name, value));
                case "moreLikeThis" -> builder.moreLikeThis(text(name, value));
                case "agenticOptions" -> builder.agenticOptions(object(name, value));
                default -> builder.additionalProperty(name, value);
            }
        }
        return builder.build();
    }

    /** Parses a GET query string. */
    public static SearchRequest fromQuery(SearchCall call) {
        SearchRequest.Builder builder = SearchRequest.builder();
        builder.search(call.query("search"));
        String searchMode = call.query("searchMode");
        if (searchMode != null) {
            builder.searchMode(SearchMode.parse(searchMode));
        }
        String queryType = call.query("queryType");
        if (queryType != null) {
            builder.queryType(QueryType.parse(queryType));
        }
        builder.searchFields(csv(String.join(",", call.queryValues("searchFields"))));
        builder.filter(call.query("$filter"));
        List<String> orderBy = call.queryValues("$orderby");
        builder.orderBy(orderBy.isEmpty() ? null : String.join(",", orderBy));
        builder.select(csv(String.join(",", call.queryValues("$select"))));
        builder.top(integer("$top", call.query("$top")));
        builder.skip(integer("$skip", call.query("$skip")));
        builder.count(bool("$count", call.query("$count")));
        builder.facets(call.queryValues("facet"));
        builder.highlightFields(csv(String.join(",", call.queryValues("highlight"))));
        builder.highlightPreTag(call.query("highlightPreTag"));
        builder.highlightPostTag(call.query("highlightPostTag"));
        String coverage = call.query("minimumCoverage");
        builder.minimumCoverage(coverage == null ? null : parseDouble("minimumCoverage", coverage));
        builder.scoringProfile(call.query("scoringProfile"));
        builder.scoringParameters(call.queryValues("scoringParameter"));
        String statistics = call.query("scoringStatistics");
        builder.scoringStatistics(statistics == null ? null : scoringStatistics(statistics));
        String sessionId = call.query("sessionId");
        builder.sessionId(sessionId == null ? null : sessionId(sessionId));
        builder.semanticConfiguration(call.query("semanticConfiguration"));
        builder.semanticErrorHandling(call.query("semanticErrorHandling"));
        builder.semanticMaxWaitInMilliseconds(integer("semanticMaxWaitInMilliseconds",
            call.query("semanticMaxWaitInMilliseconds")));
        builder.answers(call.query("answers"));
        builder.captions(call.query("captions"));
        builder.semanticQuery(call.query("semanticQuery"));
        builder.queryRewrites(call.query("queryRewrites"));
        builder.debug(call.query("debug"));
        builder.queryLanguage(call.query("queryLanguage"));
        builder.speller(call.query("speller"));
        builder.semanticFields(csv(String.join(",", call.queryValues("semanticFields"))));
        builder.moreLikeThis(call.query("moreLikeThis"));
        return builder.build();
    }

    /** Splits a comma-separated list, trimming entries and dropping empty ones. */
    public static List<String> csv(String value) {
        List<String> entries = new ArrayList<>();
        if (value == null) {
            return entries;
        }
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static String scoringStatistics(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.equals("local") && !lower.equals("global")) {
            throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'scoringStatistics'. Valid "
                + "values are 'local' and 'global'.");
        }
        return lower;
    }

    private static String sessionId(String value) {
        if (value.startsWith("_")) {
            throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'sessionId'. A session id "
                + "cannot start with an underscore.");
        }
        return value;
    }

    private static String text(String name, JsonNode value) {
        if (!value.isTextual()) {
            throw invalidType(name, "a string");
        }
        return value.textValue();
    }

    private static List<String> list(String name, JsonNode value) {
        if (value.isTextual()) {
            return csv(value.textValue());
        }
        return array(name, value);
    }

    private static String joined(String name, JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        return String.join(",", array(name, value));
    }

    private static List<String> array(String name, JsonNode value) {
        if (!value.isArray()) {
            throw invalidType(name, "an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalidType(name, "an array of strings");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private static List<ObjectNode> objects(String name, JsonNode value) {
        if (!value.isArray()) {
            throw invalidType(name, "an array of objects");
        }
        List<ObjectNode> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!(element instanceof ObjectNode object)) {
                throw invalidType(name, "an array of objects");
            }
            values.add(object);
        }
        return values;
    }

    private static ObjectNode object(String name, JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw invalidType(name, "an object");
        }
        return object;
    }

    private static Integer integer(String name, JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidType(name, "an integer");
        }
        return value.intValue();
    }

    private static Integer integer(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalidType(name, "an integer");
        }
    }

    private static Double number(String name, JsonNode value) {
        if (!value.isNumber()) {
            throw invalidType(name, "a number");
        }
        return value.doubleValue();
    }

    private static Double parseDouble(String name, String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw invalidType(name, "a number");
        }
    }

    private static Boolean bool(String name, JsonNode value) {
        if (!value.isBoolean()) {
            throw invalidType(name, "true or false");
        }
        return value.booleanValue();
    }

    private static Boolean bool(String name, String value) {
        if (value == null) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (!lower.equals("true") && !lower.equals("false")) {
            throw invalidType(name, "true or false");
        }
        return Boolean.parseBoolean(lower);
    }

    private static SearchException invalidType(String name, String expected) {
        return SearchException.badRequest("The request is invalid. Details: the parameter '" + name + "' must be "
            + expected + ".");
    }
}
