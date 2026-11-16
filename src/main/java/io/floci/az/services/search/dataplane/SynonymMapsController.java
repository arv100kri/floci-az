package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.dataplane.NamedDefinitionStore.StoredDefinition;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.analysis.SolrSynonymRules;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Synonym maps: Create (POST), Create or Update (PUT), Get, List (with {@code $select}) and Delete Synonym Map, with
 * {@code @odata.etag} concurrency through {@code If-Match} and {@code If-None-Match}. A map has a name (1 to 128
 * lowercase letters, digits or dashes, starting and ending with a letter or digit), the format {@code solr} and its
 * rules ({@link SolrSynonymRules}), at most 20,000 rules of at most 20 expansions each ("Service limits", synonym
 * limits). Queries use the current version of a map as soon as it is saved; the engine reads maps through
 * {@link SynonymMapStore}. Service quotas on the number of maps are not enforced.
 */
@ApplicationScoped
public class SynonymMapsController implements SearchResourceCounter {

    static final String COLLECTION = "synonymmaps";
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?");
    private static final int MAX_RULES = 20_000;
    private static final int MAX_EXPANSIONS = 20;

    private final SynonymMapStore store;

    @Inject
    public SynonymMapsController(SynonymMapStore store) {
        this.store = store;
    }

    public Response list(SearchCall call) {
        List<ObjectNode> definitions = new ArrayList<>();
        for (StoredDefinition stored : store.definitions(call.serviceName())) {
            definitions.add(DefinitionResponses.representation(stored.definition(), stored.etag()));
        }
        return DefinitionResponses.list(call, COLLECTION, definitions);
    }

    public Response create(SearchCall call) {
        ObjectNode definition = parse(call.jsonObject("The synonym map"), null, null);
        String name = SearchJson.text(definition, "name");
        synchronized (store) {
            if (store.definition(call.serviceName(), name).isPresent()) {
                throw SearchException.alreadyExists("Cannot create synonym map '" + name + "' because it already "
                    + "exists.");
            }
            String etag = Etags.next();
            store.save(call.serviceName(), name, definition, etag);
            return DefinitionResponses.created(call, COLLECTION, name, definition, etag);
        }
    }

    public Response createOrUpdate(SearchCall call) {
        String name = call.path("name");
        synchronized (store) {
            Optional<StoredDefinition> existing = store.definition(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredDefinition::etag).orElse(null));
            ObjectNode definition = parse(call.jsonObject("The synonym map"), name,
                existing.map(StoredDefinition::definition).orElse(null));
            String etag = Etags.next();
            store.save(call.serviceName(), name, definition, etag);
            return existing.isEmpty() ? DefinitionResponses.created(call, COLLECTION, name, definition, etag)
                : DefinitionResponses.updated(call, COLLECTION, definition, etag);
        }
    }

    public Response get(SearchCall call) {
        StoredDefinition stored = require(call);
        return DefinitionResponses.ok(call, COLLECTION, stored.definition(), stored.etag());
    }

    public Response delete(SearchCall call) {
        String name = call.path("name");
        synchronized (store) {
            Optional<StoredDefinition> existing = store.definition(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredDefinition::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(name);
            }
            store.delete(call.serviceName(), name);
            return Response.noContent().build();
        }
    }

    @Override
    public String counterName() {
        return "synonymMaps";
    }

    @Override
    public long usage(String service) {
        return store.count(service);
    }

    private StoredDefinition require(SearchCall call) {
        String name = call.path("name");
        return store.definition(call.serviceName(), name).orElseThrow(() -> notFound(name));
    }

    private static SearchException notFound(String name) {
        return SearchException.notFound("No synonym map with the name '" + name + "' was found in the service.");
    }

    /**
     * Validates a synonym map from a request body and normalises it to what GET returns.
     *
     * @param nameInUrl the name the URL names, or null for a create by POST
     * @param existing  the stored definition an update replaces, or null
     */
    private static ObjectNode parse(ObjectNode body, String nameInUrl, ObjectNode existing) {
        JsonNode nameNode = body.get("name");
        String name = nameNode == null || nameNode.isNull() ? nameInUrl : nameNode.isTextual() ? nameNode.textValue()
            : null;
        if (name == null) {
            throw invalid("the synonym map name is required.");
        }
        if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the synonym map name '" + name + "' in the request body does not match the name '"
                + nameInUrl + "' in the request URL.");
        }
        if (!NAME.matcher(name).matches()) {
            throw invalid("Synonym map name '" + name + "' is invalid. Synonym map names must be 1 to 128 characters "
                + "long, contain only lowercase letters, digits or dashes, and start and end with a letter or digit.");
        }
        JsonNode format = body.get("format");
        if (format == null || !format.isTextual() || !"solr".equals(format.textValue())) {
            throw invalid("the synonym map format must be 'solr', the only format the service supports.");
        }
        JsonNode synonyms = body.get("synonyms");
        if (synonyms == null || !synonyms.isTextual()) {
            throw invalid("'synonyms' is required: the rules, one per line, as a string.");
        }
        validateRules(synonyms.textValue());
        ObjectNode definition = SearchJson.object();
        definition.put("name", name);
        definition.put("format", "solr");
        definition.put("synonyms", synonyms.textValue());
        JsonNode encryptionKey = body.get("encryptionKey");
        if (encryptionKey != null && !encryptionKey.isNull() && !encryptionKey.isObject()) {
            throw invalid("'encryptionKey' must be an object.");
        }
        if ((encryptionKey == null || encryptionKey.isNull()) && existing != null) {
            encryptionKey = existing.get("encryptionKey");
        }
        definition.set("encryptionKey", encryptionKey == null ? SearchJson.NODES.nullNode() : encryptionKey.deepCopy());
        return definition;
    }

    private static void validateRules(String synonyms) {
        SolrSynonymRules rules;
        try {
            rules = SolrSynonymRules.parse(synonyms);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        if (rules.rules().size() > MAX_RULES) {
            throw invalid("the synonym map has " + rules.rules().size() + " rules; the maximum is " + MAX_RULES
                + ".");
        }
        for (SolrSynonymRules.Rule rule : rules.rules()) {
            if (rule.expansions() > MAX_EXPANSIONS) {
                throw invalid("the synonym rule for '" + rule.inputs().get(0) + "' has " + rule.expansions()
                    + " expansions; a rule can have at most " + MAX_EXPANSIONS + ".");
            }
        }
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: synonymMap : " + detail);
    }
}
