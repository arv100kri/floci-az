package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.ServiceIndexes;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.IndexDefinitionParser;
import io.floci.az.services.search.model.IndexUpdateValidator;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.VectorizerSecrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

/**
 * Index management: Create Index (POST), Create or Update Index (PUT, with {@code allowIndexDowntime}), Get, List
 * (with {@code $select}) and Delete, with {@code @odata.etag} concurrency through {@code If-Match} and
 * {@code If-None-Match}, and {@code Prefer} choosing between the definition and an empty 204.
 */
@ApplicationScoped
public class IndexesController {

    private final SearchEngine engine;

    @Inject
    public IndexesController(SearchEngine engine) {
        this.engine = engine;
    }

    public Response list(SearchCall call) {
        List<String> select = SearchRequestParser.csv(call.query("$select"));
        boolean all = select.isEmpty() || select.contains("*");
        ArrayNode value = SearchJson.NODES.arrayNode();
        List<LiveIndex> indexes = engine.service(call.serviceName()).list();
        int skip = nonNegative(call, "$skip", 0);
        int top = nonNegative(call, "$top", Integer.MAX_VALUE);
        List<LiveIndex> page = indexes.subList(Math.min(skip, indexes.size()),
            (int) Math.min(indexes.size(), (long) Math.min(skip, indexes.size()) + top));
        for (LiveIndex index : page) {
            ObjectNode definition = VectorizerSecrets.redact(representation(index));
            if (all) {
                value.add(definition);
            } else {
                ObjectNode projected = SearchJson.object();
                for (String property : select) {
                    if (definition.has(property)) {
                        projected.set(property, definition.get(property));
                    }
                }
                value.add(projected);
            }
        }
        ObjectNode body = SearchJson.object();
        if ("true".equalsIgnoreCase(call.query("$count"))) {
            body.put("@odata.count", indexes.size());
        }
        body.set("value", value);
        String fragment = all ? "indexes" : "indexes(" + String.join(",", select) + ")";
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, fragment, body));
    }

    public Response get(SearchCall call) {
        LiveIndex index = require(call);
        return SearchResponses.jsonBuilder(call, 200, VectorizerSecrets.redact(entity(call, index)))
            .header("ETag", index.etag())
            .build();
    }

    public Response create(SearchCall call) {
        SearchIndexDefinition definition = IndexDefinitionParser.parse(call.json(), null, engine.analyzers());
        LiveIndex index = engine.service(call.serviceName()).create(definition);
        return created(call, index);
    }

    public Response createOrUpdate(SearchCall call) {
        String name = call.path("index");
        ServiceIndexes indexes = engine.service(call.serviceName());
        synchronized (indexes) {
            Optional<LiveIndex> existing = indexes.find(name);
            checkPreconditions(call, existing.map(LiveIndex::etag).orElse(null));
            JsonNode body = call.json();
            if (existing.isEmpty()) {
                SearchIndexDefinition definition = IndexDefinitionParser.parse(body, name, engine.analyzers());
                return created(call, indexes.create(definition));
            }
            LiveIndex index = existing.get();
            SearchIndexDefinition updated = IndexDefinitionParser.parse(VectorizerSecrets.restore(
                keepEncryptionKey(body, index), index.definition()), name, engine.analyzers());
            IndexUpdateValidator.validate(index.definition(), updated,
                "true".equalsIgnoreCase(call.query("allowIndexDowntime")));
            indexes.update(index, updated);
            if (!Boolean.TRUE.equals(call.preferRepresentation())) {
                return Response.noContent().header("ETag", index.etag()).build();
            }
            return SearchResponses.jsonBuilder(call, 200, entity(call, index))
                .header("ETag", index.etag())
                .build();
        }
    }

    public Response delete(SearchCall call) {
        String name = call.path("index");
        ServiceIndexes indexes = engine.service(call.serviceName());
        synchronized (indexes) {
            Optional<LiveIndex> existing = indexes.find(name);
            checkPreconditions(call, existing.map(LiveIndex::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(call, name);
            }
            indexes.delete(name);
            return Response.noContent().build();
        }
    }

    private Response created(SearchCall call, LiveIndex index) {
        Response.ResponseBuilder builder;
        if (Boolean.FALSE.equals(call.preferRepresentation())) {
            builder = Response.noContent();
        } else {
            builder = SearchResponses.jsonBuilder(call, 201, entity(call, index));
        }
        return builder.header("ETag", index.etag())
            .header("Location", SearchResponses.indexUrl(call, index.name()) + "?api-version=" + call.apiVersion())
            .build();
    }

    private static ObjectNode entity(SearchCall call, LiveIndex index) {
        return SearchResponses.withContext(call, "indexes/$entity", representation(index));
    }

    /** The definition as GET returns it: {@code @odata.etag} first, then the normalised definition. */
    static ObjectNode representation(LiveIndex index) {
        ObjectNode body = SearchJson.object();
        body.put("@odata.etag", index.etag());
        body.setAll(index.definition().json().deepCopy());
        return body;
    }

    /** The service ignores an attempt to set {@code encryptionKey} to null; the existing key stays. */
    private static JsonNode keepEncryptionKey(JsonNode body, LiveIndex existing) {
        JsonNode previous = existing.definition().json().get("encryptionKey");
        if (body instanceof ObjectNode object && previous != null && !previous.isNull()) {
            JsonNode requested = object.get("encryptionKey");
            if (requested == null || requested.isNull()) {
                ObjectNode copy = object.deepCopy();
                copy.set("encryptionKey", previous.deepCopy());
                return copy;
            }
        }
        return body;
    }

    /** Applies {@code If-Match} and {@code If-None-Match} to a resource whose ETag is {@code etag} (null: absent). */
    static void checkPreconditions(SearchCall call, String etag) {
        String ifMatch = call.header("If-Match");
        if (ifMatch != null && !ifMatch.isBlank() && (etag == null || !Etags.matches(ifMatch, etag))) {
            throw preconditionFailed();
        }
        String ifNoneMatch = call.header("If-None-Match");
        if (ifNoneMatch != null && !ifNoneMatch.isBlank() && etag != null && Etags.matches(ifNoneMatch, etag)) {
            throw preconditionFailed();
        }
    }

    private static SearchException preconditionFailed() {
        return SearchException.preconditionFailed("The precondition given in one of the request headers evaluated to "
            + "false. No change was made to the resource from this request.");
    }

    private LiveIndex require(SearchCall call) {
        String name = call.path("index");
        return engine.service(call.serviceName()).find(name).orElseThrow(() -> notFound(call, name));
    }

    static SearchException notFound(SearchCall call, String name) {
        return SearchException.notFound("No index with the name '" + name + "' was found in the service '"
            + call.service().name() + "'.");
    }

    private static int nonNegative(SearchCall call, String parameter, int defaultValue) {
        String value = call.query(parameter);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalidParameter(parameter, value);
        }
        if (parsed < 0) {
            throw invalidParameter(parameter, value);
        }
        return parsed;
    }

    private static SearchException invalidParameter(String parameter, String value) {
        return SearchException.badRequest("Invalid value '" + value + "' for parameter " + parameter + ".");
    }
}
