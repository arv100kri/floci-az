package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

/**
 * An item-level error ({@code SearchIndexerError}) or warning ({@code SearchIndexerWarning}) of an indexer run.
 *
 * @param key               the key of the item, or null when there is none
 * @param message           what happened
 * @param statusCode        an error's status code (400 malformed input, 404 not found, 409 conflict, 422 index
 *                          unavailable, 503 busy); null for a warning
 * @param name              where it happened ({@code DocumentExtraction.azureblob.myDataSource}), or null
 * @param details           further details, or null
 * @param documentationLink a troubleshooting link, or null
 */
public record IndexerIssue(String key, String message, Integer statusCode, String name, String details,
                           String documentationLink) {

    /** The troubleshooting guide for indexer errors and warnings. */
    static final String GUIDE = "https://learn.microsoft.com/azure/search/cognitive-search-common-errors-warnings";

    static IndexerIssue error(String key, int statusCode, String message, String name, String details,
                              String anchor) {
        return new IndexerIssue(key, message, statusCode, name, details, anchor == null ? null : GUIDE + anchor);
    }

    static IndexerIssue warning(String key, String message, String name, String details, String anchor) {
        return new IndexerIssue(key, message, null, name, details, anchor == null ? null : GUIDE + anchor);
    }

    ObjectNode toErrorJson() {
        ObjectNode json = SearchJson.object();
        putText(json, "key", key);
        json.put("errorMessage", message);
        json.put("statusCode", statusCode == null ? 400 : statusCode);
        putText(json, "name", name);
        putText(json, "details", details);
        putText(json, "documentationLink", documentationLink);
        return json;
    }

    ObjectNode toWarningJson() {
        ObjectNode json = SearchJson.object();
        putText(json, "key", key);
        json.put("message", message);
        putText(json, "name", name);
        putText(json, "details", details);
        putText(json, "documentationLink", documentationLink);
        return json;
    }

    static IndexerIssue fromErrorJson(JsonNode json) {
        return new IndexerIssue(SearchJson.text(json, "key"), SearchJson.text(json, "errorMessage"),
            json.path("statusCode").asInt(400), SearchJson.text(json, "name"), SearchJson.text(json, "details"),
            SearchJson.text(json, "documentationLink"));
    }

    static IndexerIssue fromWarningJson(JsonNode json) {
        return new IndexerIssue(SearchJson.text(json, "key"), SearchJson.text(json, "message"), null,
            SearchJson.text(json, "name"), SearchJson.text(json, "details"),
            SearchJson.text(json, "documentationLink"));
    }

    private static void putText(ObjectNode json, String field, String value) {
        if (value == null) {
            json.putNull(field);
        } else {
            json.put(field, value);
        }
    }
}
