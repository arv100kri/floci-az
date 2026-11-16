package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.engine.index.IndexAction;
import io.floci.az.services.search.model.IndexActionType;
import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the body of Index Documents ({@code {"value": [actions]}}) into engine actions, per Learn's "Add, update or
 * delete documents" and the naming rules: between 1 and 1000 actions; {@code @search.action} defaults to
 * {@code upload}; every action needs its key, which may only contain letters, digits, {@code -}, {@code _} and
 * {@code =}, is at most 1024 characters and does not start with {@code _}. A delete names only its key; any other
 * field it carries is ignored. A document whose fields do not fit the schema fails the whole request with a 400; a
 * malformed key fails just its action.
 */
final class IndexBatchParser {

    static final int MAX_ACTIONS = 1000;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9=\\-][A-Za-z0-9_=\\-]{0,1023}");

    private IndexBatchParser() {
    }

    static List<IndexAction> parse(JsonNode body, SearchIndexDefinition definition) {
        JsonNode value = body == null ? null : body.get("value");
        if (!(value instanceof ArrayNode actions) || actions.isEmpty()) {
            throw SearchException.badRequest("The request is invalid. Details: actions : No indexing actions found in "
                + "the request. Please include between 1 and " + MAX_ACTIONS + " indexing actions in your request.");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new SearchException(SearchErrorCode.REQUEST_TOO_LARGE, "The request is invalid. Details: actions : "
                + "The batch contains " + actions.size() + " indexing actions. Please include between 1 and "
                + MAX_ACTIONS + " indexing actions in your request.");
        }
        String keyField = definition.keyField().name();
        List<IndexAction> parsed = new ArrayList<>(actions.size());
        for (JsonNode actionNode : actions) {
            if (!(actionNode instanceof ObjectNode action)) {
                throw SearchException.badRequest("The request is invalid. Details: actions : each indexing action "
                    + "must be a JSON object.");
            }
            String actionName = SearchJson.text(action, DocumentCoercer.ACTION_PROPERTY);
            IndexActionType type = actionName == null ? IndexActionType.UPLOAD : IndexActionType.parse(actionName);
            JsonNode keyNode = action.get(keyField);
            if (keyNode == null || keyNode.isNull() || keyNode.asText().isEmpty()) {
                throw SearchException.badRequest("The request is invalid. Details: actions : Document key cannot be "
                    + "missing or empty. Every action must set the key field '" + keyField + "'.");
            }
            ObjectNode document;
            if (type == IndexActionType.DELETE) {
                document = SearchJson.object();
                document.put(keyField, keyNode.asText());
            } else {
                document = DocumentCoercer.coerce(action, definition);
            }
            String key = document.get(keyField).asText();
            parsed.add(new IndexAction(type, key, document, KEY.matcher(key).matches() ? null : invalidKey(key)));
        }
        return parsed;
    }

    private static String invalidKey(String key) {
        if (key.length() > 1024) {
            return "Invalid document key: '" + key.substring(0, 32) + "...'. Keys can be at most 1024 characters "
                + "long.";
        }
        return "Invalid document key: '" + key + "'. Keys can only contain letters, digits, underscore (_), dash (-), "
            + "or equal sign (=), and cannot start with an underscore.";
    }
}
