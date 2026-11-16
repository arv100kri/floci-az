package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Responses of the definition resources that follow the indexes' conventions (synonym maps, aliases):
 * {@code @odata.etag} first in each definition, {@code ETag} and {@code Location} headers, {@code Prefer} choosing
 * between the definition and an empty 204, and {@code $select} on lists.
 */
final class DefinitionResponses {

    private DefinitionResponses() {
    }

    /** The definition as GET returns it: {@code @odata.etag} first. */
    static ObjectNode representation(ObjectNode definition, String etag) {
        ObjectNode body = SearchJson.object();
        body.put("@odata.etag", etag);
        body.setAll(definition.deepCopy());
        return body;
    }

    /** A 200 with the definition. */
    static Response ok(SearchCall call, String collection, ObjectNode definition, String etag) {
        return SearchResponses.jsonBuilder(call, 200, entity(call, collection, definition, etag))
            .header("ETag", etag)
            .build();
    }

    /** A 201 with the definition and its {@code Location}, or a 204 when the caller prefers a minimal response. */
    static Response created(SearchCall call, String collection, String name, ObjectNode definition, String etag) {
        Response.ResponseBuilder builder = Boolean.FALSE.equals(call.preferRepresentation()) ? Response.noContent()
            : SearchResponses.jsonBuilder(call, 201, entity(call, collection, definition, etag));
        return builder.header("ETag", etag)
            .header("Location", call.serviceUrl() + "/" + collection + "('" + name + "')?api-version="
                + call.apiVersion())
            .build();
    }

    /** A 204 after an update, or a 200 with the definition when the caller asked for it. */
    static Response updated(SearchCall call, String collection, ObjectNode definition, String etag) {
        if (!Boolean.TRUE.equals(call.preferRepresentation())) {
            return Response.noContent().header("ETag", etag).build();
        }
        return ok(call, collection, definition, etag);
    }

    /** A list of definitions, each projected to {@code $select} when the caller sent one. */
    static Response list(SearchCall call, String collection, List<ObjectNode> definitions) {
        List<String> select = SearchRequestParser.csv(call.query("$select"));
        boolean all = select.isEmpty() || select.contains("*");
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (ObjectNode definition : definitions) {
            if (all) {
                value.add(definition);
                continue;
            }
            ObjectNode projected = SearchJson.object();
            for (String property : select) {
                if (definition.has(property)) {
                    projected.set(property, definition.get(property));
                }
            }
            value.add(projected);
        }
        ObjectNode body = SearchJson.object();
        body.set("value", value);
        String fragment = all ? collection : collection + "(" + String.join(",", select) + ")";
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, fragment, body));
    }

    private static ObjectNode entity(SearchCall call, String collection, ObjectNode definition, String etag) {
        return SearchResponses.withContext(call, collection + "/$entity", representation(definition, etag));
    }
}
