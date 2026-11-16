package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The vector part of {@code @search.documentDebugInfo}, returned when {@code debug} is {@code vector} or {@code all}
 * ("Unpack a search score into subscores"): {@code vectors.subscores} holds the document's BM25 score for the text
 * query ({@code text.searchScore}) and, for each vector query in request order, the {@code searchScore} and
 * {@code vectorSimilarity} of each field it matched in.
 */
public final class VectorDebugInfo {

    private VectorDebugInfo() {
    }

    /** Whether {@code debug} (modes separated by {@code |}) asks for vector subscores. */
    public static boolean requested(String debug) {
        if (debug == null) {
            return false;
        }
        for (String mode : debug.split("\\|")) {
            String lower = mode.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("vector") || lower.equals("all")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code vectors} object of each document's debug information, by ordinal.
     *
     * @param queries    the vector queries, in request order
     * @param lists      the ranked lists of the vector queries
     * @param textScores the text query's scores by ordinal, or null without a text query
     * @param ordinals   the documents to describe
     */
    public static Map<Integer, ObjectNode> subscores(List<VectorQuery> queries, List<VectorList> lists,
                                                     Map<Integer, Double> textScores, Iterable<Integer> ordinals) {
        Map<String, Map<Integer, VectorMatch>> matchesByList = new HashMap<>();
        for (VectorList list : lists) {
            Map<Integer, VectorMatch> byOrdinal = new HashMap<>();
            list.matches().forEach(match -> byOrdinal.put(match.ordinal(), match));
            matchesByList.put(key(list.query(), list.field().path()), byOrdinal);
        }
        Map<Integer, ObjectNode> result = new HashMap<>();
        for (int ordinal : ordinals) {
            ObjectNode subscores = SearchJson.object();
            if (textScores != null && textScores.containsKey(ordinal)) {
                subscores.putObject("text").put("searchScore", textScores.get(ordinal));
            }
            ArrayNode vectors = subscores.putArray("vectors");
            for (VectorQuery query : queries) {
                ObjectNode fields = vectors.addObject();
                for (VectorList list : lists) {
                    if (list.query() != query) {
                        continue;
                    }
                    VectorMatch match = matchesByList.get(key(query, list.field().path())).get(ordinal);
                    if (match != null) {
                        ObjectNode field = fields.putObject(list.field().path());
                        field.put("searchScore", match.score());
                        field.put("vectorSimilarity", match.similarity());
                    }
                }
            }
            ObjectNode debug = SearchJson.object();
            debug.putObject("subscores").setAll(subscores);
            result.put(ordinal, debug);
        }
        return result;
    }

    private static String key(VectorQuery query, String path) {
        return query.position() + "/" + path;
    }
}
