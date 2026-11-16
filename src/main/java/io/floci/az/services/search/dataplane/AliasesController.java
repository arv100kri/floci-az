package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.search.dataplane.NamedDefinitionStore.StoredDefinition;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.model.IndexDefinitionParser;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Index aliases ("Create an index alias"): Create (POST), Create or Update (PUT), Get, List (with {@code $select}) and
 * Delete Alias, with {@code @odata.etag} concurrency. An alias maps its name (the index naming rule) to exactly one
 * existing index; every document operation and Get and Create or Update Index accept the alias in place of the index
 * name ({@link #resolveIndex}), while Delete Index and Analyze Text do not. An index an alias maps to cannot be deleted
 * (400) until the alias is deleted or repointed. Aliases are persisted under {@code dp/alias/{service}/{name}}; an
 * update takes effect on the next request. Service quotas on the number of aliases are not enforced.
 */
@ApplicationScoped
public class AliasesController implements SearchResourceCounter {

    static final String COLLECTION = "aliases";

    private final NamedDefinitionStore store;
    private final SearchEngine engine;

    @Inject
    public AliasesController(StorageFactory storageFactory, SearchEngine engine) {
        this.store = new NamedDefinitionStore(storageFactory.create("search"), "alias");
        this.engine = engine;
    }

    public Response list(SearchCall call) {
        List<ObjectNode> definitions = new ArrayList<>();
        for (StoredDefinition stored : store.list(call.serviceName())) {
            definitions.add(DefinitionResponses.representation(stored.definition(), stored.etag()));
        }
        return DefinitionResponses.list(call, COLLECTION, definitions);
    }

    public Response create(SearchCall call) {
        ObjectNode definition = parse(call, call.jsonObject("The alias"), null);
        String name = SearchJson.text(definition, "name");
        synchronized (store) {
            if (store.find(call.serviceName(), name).isPresent()) {
                throw SearchException.alreadyExists("Cannot create alias '" + name + "' because it already exists.");
            }
            String etag = Etags.next();
            store.put(call.serviceName(), name, definition, etag);
            return DefinitionResponses.created(call, COLLECTION, name, definition, etag);
        }
    }

    public Response createOrUpdate(SearchCall call) {
        String name = call.path("name");
        synchronized (store) {
            Optional<StoredDefinition> existing = store.find(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredDefinition::etag).orElse(null));
            ObjectNode definition = parse(call, call.jsonObject("The alias"), name);
            String etag = Etags.next();
            store.put(call.serviceName(), name, definition, etag);
            return existing.isEmpty() ? DefinitionResponses.created(call, COLLECTION, name, definition, etag)
                : DefinitionResponses.updated(call, COLLECTION, definition, etag);
        }
    }

    public Response get(SearchCall call) {
        String name = call.path("name");
        StoredDefinition stored = store.find(call.serviceName(), name).orElseThrow(() -> notFound(name));
        return DefinitionResponses.ok(call, COLLECTION, stored.definition(), stored.etag());
    }

    public Response delete(SearchCall call) {
        String name = call.path("name");
        synchronized (store) {
            Optional<StoredDefinition> existing = store.find(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredDefinition::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(name);
            }
            store.delete(call.serviceName(), name);
            return Response.noContent().build();
        }
    }

    /**
     * The index the alias {@code name} of {@code service} points to. Document operations call it when no index has
     * the name they were given.
     */
    public Optional<String> resolveIndex(String service, String name) {
        if (name == null) {
            return Optional.empty();
        }
        return store.find(service, name).map(stored -> stored.definition().path("indexes").path(0).asText(null));
    }

    /**
     * The index an index-definition request naming {@code name} addresses: {@code name} itself when an index has that
     * name or no alias does, else the index the alias {@code name} points to.
     */
    public String indexNameFor(String service, String name) {
        if (name == null || engine.service(service).find(name).isPresent()) {
            return name;
        }
        return resolveIndex(service, name).orElse(name);
    }

    /** Refuses, with a 400, to delete the index {@code index} of {@code service} while an alias maps to it. */
    public void checkIndexDeletable(String service, String index) {
        List<String> aliases = new ArrayList<>();
        for (StoredDefinition stored : store.list(service)) {
            for (JsonNode target : stored.definition().path("indexes")) {
                if (index.equals(target.asText())) {
                    aliases.add(stored.name());
                }
            }
        }
        if (!aliases.isEmpty()) {
            throw SearchException.badRequest("Index '" + index + "' cannot be deleted because it is mapped to the "
                + "alias(es) '" + String.join("', '", aliases) + "'. Delete the alias(es) or map them to a different "
                + "index before deleting this index.");
        }
    }

    @Override
    public String counterName() {
        return "aliasesCount";
    }

    @Override
    public long usage(String service) {
        return store.count(service);
    }

    private static SearchException notFound(String name) {
        return SearchException.notFound("No alias with the name '" + name + "' was found in the service.");
    }

    /** Validates an alias from a request body and normalises it to what GET returns. */
    private ObjectNode parse(SearchCall call, ObjectNode body, String nameInUrl) {
        JsonNode nameNode = body.get("name");
        String name = nameNode == null || nameNode.isNull() ? nameInUrl : nameNode.isTextual() ? nameNode.textValue()
            : null;
        if (name == null) {
            throw invalid("the alias name is required.");
        }
        if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the alias name '" + name + "' in the request body does not match the name '" + nameInUrl
                + "' in the request URL.");
        }
        if (!IndexDefinitionParser.isValidIndexName(name)) {
            throw invalid("Alias name '" + name + "' is invalid. Alias names must be 2 to 128 characters long, contain "
                + "only lowercase letters, digits, dashes or underscores, start with a letter or digit, and not "
                + "contain consecutive dashes or underscores.");
        }
        JsonNode indexes = body.get("indexes");
        if (!(indexes instanceof ArrayNode targets) || targets.size() != 1 || !targets.get(0).isTextual()) {
            throw invalid("'indexes' must name exactly one index; an alias maps to a single index.");
        }
        String index = targets.get(0).textValue();
        if (engine.service(call.serviceName()).find(index).isEmpty()) {
            throw invalid("the index '" + index + "' that the alias maps to does not exist.");
        }
        ObjectNode definition = SearchJson.object();
        definition.put("name", name);
        definition.putArray("indexes").add(index);
        return definition;
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: alias : " + detail);
    }
}
