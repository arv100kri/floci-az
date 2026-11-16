package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.ParityLevel;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.skills.SkillsetDefinitions;
import io.floci.az.services.search.skills.SkillsetDefinitions.ParsedSkillset;
import io.floci.az.services.search.skills.SkillsetStore;
import io.floci.az.services.search.skills.SkillsetStore.StoredSkillset;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Skillset management: Create Skillset (POST), Create or Update Skillset (PUT), Get, List (with {@code $select}),
 * Delete and Reset Skills, with {@code @odata.etag} concurrency through {@code If-Match} and {@code If-None-Match}, and
 * {@code Prefer} choosing between the definition and an empty 204, as for indexes. Definitions are validated and
 * normalised by {@link SkillsetDefinitions} and kept in {@link SkillsetStore}; GET and LIST redact the Custom Web API
 * skills' secrets. Custom skill URIs must use https, as Azure requires; outside {@code parity: strict} plain http is
 * also accepted for local endpoints (loopback and private addresses, single-label host names), which the emulator's
 * users run their skills on.
 */
@ApplicationScoped
public class SkillsetsController implements SearchResourceCounter {

    private final SkillsetStore store;
    private final SearchEngine engine;
    private final EmulatorConfig config;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public SkillsetsController(SkillsetStore store, SearchEngine engine, EmulatorConfig config) {
        this.store = store;
        this.engine = engine;
        this.config = config;
    }

    public Response list(SearchCall call) {
        List<String> select = SearchRequestParser.csv(call.query("$select"));
        boolean all = select.isEmpty() || select.contains("*");
        ArrayNode value = SearchJson.NODES.arrayNode();
        for (StoredSkillset skillset : store.list(call.serviceName())) {
            ObjectNode definition = representation(skillset);
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
        body.set("value", value);
        String fragment = all ? "skillsets" : "skillsets(" + String.join(",", select) + ")";
        return SearchResponses.json(call, 200, SearchResponses.withContext(call, fragment, body));
    }

    public Response get(SearchCall call) {
        String name = call.path("name");
        StoredSkillset skillset = store.find(call.serviceName(), name).orElseThrow(() -> notFound(call, name));
        return SearchResponses.jsonBuilder(call, 200, entity(call, representation(skillset)))
            .header("ETag", skillset.etag())
            .build();
    }

    public Response create(SearchCall call) {
        synchronized (lock(call)) {
            ParsedSkillset parsed = parse(call, null, null);
            if (store.find(call.serviceName(), parsed.name()).isPresent()) {
                throw SearchException.alreadyExists("Cannot create skillset '" + parsed.name() + "' because it "
                    + "already exists.");
            }
            return created(call, save(call, parsed), parsed);
        }
    }

    public Response createOrUpdate(SearchCall call) {
        String name = call.path("name");
        synchronized (lock(call)) {
            Optional<StoredSkillset> existing = store.find(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredSkillset::etag).orElse(null));
            ParsedSkillset parsed = parse(call, name, existing.map(StoredSkillset::definition).orElse(null));
            String etag = save(call, parsed);
            if (existing.isEmpty()) {
                return created(call, etag, parsed);
            }
            if (!Boolean.TRUE.equals(call.preferRepresentation())) {
                return Response.noContent().header("ETag", etag).build();
            }
            return SearchResponses.jsonBuilder(call, 200, entity(call, withEtag(etag, parsed.response())))
                .header("ETag", etag)
                .build();
        }
    }

    public Response delete(SearchCall call) {
        String name = call.path("name");
        synchronized (lock(call)) {
            Optional<StoredSkillset> existing = store.find(call.serviceName(), name);
            IndexesController.checkPreconditions(call, existing.map(StoredSkillset::etag).orElse(null));
            if (existing.isEmpty()) {
                throw notFound(call, name);
            }
            store.delete(call.serviceName(), name);
            return Response.noContent().build();
        }
    }

    /**
     * Reset Skills: marks skills for reprocessing on the indexer's next run. The emulator keeps no enrichment cache, so
     * every run already reprocesses every skill; the request is validated and acknowledged.
     */
    public Response resetSkills(SearchCall call) {
        String name = call.path("name");
        store.find(call.serviceName(), name).orElseThrow(() -> notFound(call, name));
        JsonNode body = call.json();
        if (body != null && !body.isNull()) {
            if (!body.isObject()) {
                throw SearchException.badRequest("The request is invalid. Details: the request body must be a JSON "
                    + "object with 'skillNames'.");
            }
            JsonNode names = body.get("skillNames");
            if (names != null && !names.isNull()) {
                if (!names.isArray()) {
                    throw SearchException.badRequest("The request is invalid. Details: 'skillNames' must be an "
                        + "array of skill names.");
                }
                for (JsonNode skillName : names) {
                    if (!skillName.isTextual()) {
                        throw SearchException.badRequest("The request is invalid. Details: 'skillNames' must be an "
                            + "array of skill names.");
                    }
                }
            }
        }
        return Response.noContent().build();
    }

    private ParsedSkillset parse(SearchCall call, String nameInUrl, ObjectNode previous) {
        String service = call.serviceName();
        return SkillsetDefinitions.parse(call.json(), nameInUrl, previous,
            index -> engine.service(service).find(index).map(LiveIndex::definition),
            config.parity().includes(ParityLevel.STRICT));
    }

    private String save(SearchCall call, ParsedSkillset parsed) {
        String etag = Etags.next();
        store.save(call.serviceName(), parsed.name(), parsed.stored(), etag);
        return etag;
    }

    private static Response created(SearchCall call, String etag, ParsedSkillset parsed) {
        Response.ResponseBuilder builder;
        if (Boolean.FALSE.equals(call.preferRepresentation())) {
            builder = Response.noContent();
        } else {
            builder = SearchResponses.jsonBuilder(call, 201, entity(call, withEtag(etag, parsed.response())));
        }
        return builder.header("ETag", etag)
            .header("Location", call.serviceUrl() + "/skillsets('" + parsed.name() + "')?api-version="
                + call.apiVersion())
            .build();
    }

    private static ObjectNode entity(SearchCall call, ObjectNode body) {
        return SearchResponses.withContext(call, "skillsets/$entity", body);
    }

    /** The definition as GET and LIST answer it: {@code @odata.etag} first, secrets redacted. */
    private static ObjectNode representation(StoredSkillset skillset) {
        return withEtag(skillset.etag(), SkillsetDefinitions.redacted(skillset.definition()));
    }

    private static ObjectNode withEtag(String etag, ObjectNode definition) {
        ObjectNode body = SearchJson.object();
        body.put("@odata.etag", etag);
        body.setAll(definition);
        return body;
    }

    private Object lock(SearchCall call) {
        return locks.computeIfAbsent(call.serviceName().toLowerCase(Locale.ROOT), service -> new Object());
    }

    private static SearchException notFound(SearchCall call, String name) {
        return SearchException.notFound("No skillset with the name '" + name + "' was found in a service named '"
            + call.service().name() + "'.");
    }

    @Override
    public String counterName() {
        return "skillsetCount";
    }

    @Override
    public long usage(String service) {
        return store.count(service);
    }
}
