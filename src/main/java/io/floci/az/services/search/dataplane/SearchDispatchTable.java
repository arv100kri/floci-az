package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.model.SearchErrorCode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.security.SearchOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one table mapping every data-plane route to its {@link SearchOperation} and controller method. A feature lands
 * by filling in its controller; this table already routes every operation of the public API, including the ones whose
 * controllers are stubs.
 *
 * <p>Patterns are matched against the normalised {@link SearchPath}, so {@code indexes('x')/docs('k')} and
 * {@code indexes/x/docs/k} are the same route. A pattern segment is literal alternatives ({@code search.index|index},
 * matched ignoring case, never against a quoted segment) or a {@code {variable}}. Routes are tried in order: literal
 * actions come before the {@code {key}} route that would otherwise capture them. A path that matches only under
 * another method is a 405, one that matches nothing a 404.</p>
 */
@ApplicationScoped
public class SearchDispatchTable {

    private final List<Route> routes;

    @Inject
    public SearchDispatchTable(IndexesController indexes, DocumentsController documents,
                               ServiceStatisticsController statistics, AnalyzeController analyze,
                               SuggestController suggest, AutocompleteController autocomplete,
                               SynonymMapsController synonymMaps, AliasesController aliases,
                               DataSourcesController dataSources, IndexersController indexers,
                               SkillsetsController skillsets, KnowledgeController knowledge) {
        List<Route> table = new ArrayList<>();
        // Service
        add(table, "GET", "servicestats", SearchOperation.GET_SERVICE_STATISTICS, statistics::serviceStatistics);
        add(table, "GET", "indexstats", SearchOperation.LIST_INDEX_STATISTICS, statistics::indexStatisticsSummary);
        // Indexes
        add(table, "GET", "indexes", SearchOperation.LIST_INDEXES, indexes::list);
        add(table, "POST", "indexes", SearchOperation.CREATE_INDEX, indexes::create);
        add(table, "GET", "indexes/{index}", SearchOperation.GET_INDEX, indexes::get);
        add(table, "PUT", "indexes/{index}", SearchOperation.CREATE_OR_UPDATE_INDEX, indexes::createOrUpdate);
        add(table, "DELETE", "indexes/{index}", SearchOperation.DELETE_INDEX, indexes::delete);
        add(table, "GET", "indexes/{index}/search.stats|stats", SearchOperation.GET_INDEX_STATISTICS,
            statistics::indexStatistics);
        add(table, "POST", "indexes/{index}/search.analyze|analyze", SearchOperation.ANALYZE_TEXT, analyze::analyze);
        // Documents
        add(table, "GET", "indexes/{index}/docs", SearchOperation.SEARCH_DOCUMENTS, documents::searchGet);
        add(table, "GET", "indexes/{index}/docs/$count", SearchOperation.COUNT_DOCUMENTS, documents::count);
        add(table, "POST", "indexes/{index}/docs/search.post.search|search", SearchOperation.SEARCH_DOCUMENTS,
            documents::searchPost);
        add(table, "POST", "indexes/{index}/docs/search.index|index", SearchOperation.INDEX_DOCUMENTS,
            documents::index);
        add(table, "GET", "indexes/{index}/docs/search.suggest|suggest", SearchOperation.SUGGEST,
            suggest::suggestGet);
        add(table, "POST", "indexes/{index}/docs/search.post.suggest|suggest", SearchOperation.SUGGEST,
            suggest::suggestPost);
        add(table, "GET", "indexes/{index}/docs/search.autocomplete|autocomplete", SearchOperation.AUTOCOMPLETE,
            autocomplete::autocompleteGet);
        add(table, "POST", "indexes/{index}/docs/search.post.autocomplete|autocomplete",
            SearchOperation.AUTOCOMPLETE, autocomplete::autocompletePost);
        add(table, "POST", "indexes/{index}/docs/search.graph", SearchOperation.GRAPH_QUERY, documents::graph);
        add(table, "GET", "indexes/{index}/docs/{key}", SearchOperation.GET_DOCUMENT, documents::lookup);
        // Synonym maps
        add(table, "GET", "synonymmaps", SearchOperation.LIST_SYNONYM_MAPS, synonymMaps::list);
        add(table, "POST", "synonymmaps", SearchOperation.CREATE_SYNONYM_MAP, synonymMaps::create);
        add(table, "GET", "synonymmaps/{name}", SearchOperation.GET_SYNONYM_MAP, synonymMaps::get);
        add(table, "PUT", "synonymmaps/{name}", SearchOperation.CREATE_OR_UPDATE_SYNONYM_MAP,
            synonymMaps::createOrUpdate);
        add(table, "DELETE", "synonymmaps/{name}", SearchOperation.DELETE_SYNONYM_MAP, synonymMaps::delete);
        // Aliases
        add(table, "GET", "aliases", SearchOperation.LIST_ALIASES, aliases::list);
        add(table, "POST", "aliases", SearchOperation.CREATE_ALIAS, aliases::create);
        add(table, "GET", "aliases/{name}", SearchOperation.GET_ALIAS, aliases::get);
        add(table, "PUT", "aliases/{name}", SearchOperation.CREATE_OR_UPDATE_ALIAS, aliases::createOrUpdate);
        add(table, "DELETE", "aliases/{name}", SearchOperation.DELETE_ALIAS, aliases::delete);
        // Data sources
        add(table, "GET", "datasources", SearchOperation.LIST_DATA_SOURCES, dataSources::list);
        add(table, "POST", "datasources", SearchOperation.CREATE_DATA_SOURCE, dataSources::create);
        add(table, "GET", "datasources/{name}", SearchOperation.GET_DATA_SOURCE, dataSources::get);
        add(table, "PUT", "datasources/{name}", SearchOperation.CREATE_OR_UPDATE_DATA_SOURCE,
            dataSources::createOrUpdate);
        add(table, "DELETE", "datasources/{name}", SearchOperation.DELETE_DATA_SOURCE, dataSources::delete);
        // Indexers
        add(table, "GET", "indexers", SearchOperation.LIST_INDEXERS, indexers::list);
        add(table, "POST", "indexers", SearchOperation.CREATE_INDEXER, indexers::create);
        add(table, "GET", "indexers/{name}", SearchOperation.GET_INDEXER, indexers::get);
        add(table, "PUT", "indexers/{name}", SearchOperation.CREATE_OR_UPDATE_INDEXER, indexers::createOrUpdate);
        add(table, "DELETE", "indexers/{name}", SearchOperation.DELETE_INDEXER, indexers::delete);
        add(table, "GET", "indexers/{name}/search.status|status", SearchOperation.GET_INDEXER_STATUS,
            indexers::status);
        add(table, "POST", "indexers/{name}/search.run|run", SearchOperation.RUN_INDEXER, indexers::run);
        add(table, "POST", "indexers/{name}/search.reset|reset", SearchOperation.RESET_INDEXER, indexers::reset);
        add(table, "POST", "indexers/{name}/search.resetdocs|resetdocs", SearchOperation.RESET_INDEXER_DOCUMENTS,
            indexers::resetDocuments);
        add(table, "POST", "indexers/{name}/search.resync|resync", SearchOperation.RESYNC_INDEXER,
            indexers::resync);
        // Skillsets
        add(table, "GET", "skillsets", SearchOperation.LIST_SKILLSETS, skillsets::list);
        add(table, "POST", "skillsets", SearchOperation.CREATE_SKILLSET, skillsets::create);
        add(table, "GET", "skillsets/{name}", SearchOperation.GET_SKILLSET, skillsets::get);
        add(table, "PUT", "skillsets/{name}", SearchOperation.CREATE_OR_UPDATE_SKILLSET, skillsets::createOrUpdate);
        add(table, "DELETE", "skillsets/{name}", SearchOperation.DELETE_SKILLSET, skillsets::delete);
        add(table, "POST", "skillsets/{name}/search.resetskills|resetskills", SearchOperation.RESET_SKILLS,
            skillsets::resetSkills);
        // Knowledge bases
        add(table, "GET", "knowledgebases", SearchOperation.LIST_KNOWLEDGE_BASES, knowledge::notImplemented);
        add(table, "POST", "knowledgebases", SearchOperation.CREATE_KNOWLEDGE_BASE, knowledge::notImplemented);
        add(table, "GET", "knowledgebases/{name}", SearchOperation.GET_KNOWLEDGE_BASE, knowledge::notImplemented);
        add(table, "PUT", "knowledgebases/{name}", SearchOperation.CREATE_OR_UPDATE_KNOWLEDGE_BASE,
            knowledge::notImplemented);
        add(table, "DELETE", "knowledgebases/{name}", SearchOperation.DELETE_KNOWLEDGE_BASE,
            knowledge::notImplemented);
        add(table, "POST", "knowledgebases/{name}/retrieve", SearchOperation.RETRIEVE_KNOWLEDGE,
            knowledge::notImplemented);
        // Knowledge sources
        add(table, "GET", "knowledgesources", SearchOperation.LIST_KNOWLEDGE_SOURCES, knowledge::notImplemented);
        add(table, "POST", "knowledgesources", SearchOperation.CREATE_KNOWLEDGE_SOURCE, knowledge::notImplemented);
        add(table, "GET", "knowledgesources/{name}", SearchOperation.GET_KNOWLEDGE_SOURCE, knowledge::notImplemented);
        add(table, "PUT", "knowledgesources/{name}", SearchOperation.CREATE_OR_UPDATE_KNOWLEDGE_SOURCE,
            knowledge::notImplemented);
        add(table, "DELETE", "knowledgesources/{name}", SearchOperation.DELETE_KNOWLEDGE_SOURCE,
            knowledge::notImplemented);
        add(table, "GET", "knowledgesources/{name}/status", SearchOperation.GET_KNOWLEDGE_SOURCE_STATUS,
            knowledge::notImplemented);
        add(table, "GET", "knowledgesources/{name}/files", SearchOperation.LIST_KNOWLEDGE_SOURCE_FILES,
            knowledge::notImplemented);
        add(table, "POST", "knowledgesources/{name}/files", SearchOperation.UPLOAD_KNOWLEDGE_SOURCE_FILE,
            knowledge::notImplemented);
        add(table, "POST", "knowledgesources/{name}/files:ingest", SearchOperation.INGEST_KNOWLEDGE_SOURCE_FILE,
            knowledge::notImplemented);
        add(table, "GET", "knowledgesources/{name}/files/operations",
            SearchOperation.LIST_KNOWLEDGE_SOURCE_FILE_OPERATIONS, knowledge::notImplemented);
        add(table, "GET", "knowledgesources/{name}/files/operations/{operationId}",
            SearchOperation.GET_KNOWLEDGE_SOURCE_FILE_OPERATION, knowledge::notImplemented);
        add(table, "PUT", "knowledgesources/{name}/files/{fileId}", SearchOperation.UPDATE_KNOWLEDGE_SOURCE_FILE,
            knowledge::notImplemented);
        add(table, "DELETE", "knowledgesources/{name}/files/{fileId}", SearchOperation.DELETE_KNOWLEDGE_SOURCE_FILE,
            knowledge::notImplemented);
        add(table, "POST", "knowledgesources/{name}/files/{fileId}/:ingest",
            SearchOperation.INGEST_KNOWLEDGE_SOURCE_FILE, knowledge::notImplemented);
        this.routes = List.copyOf(table);
    }

    private static void add(List<Route> table, String method, String pattern, SearchOperation operation,
                            SearchEndpoint endpoint) {
        table.add(new Route(method, RoutePattern.parse(pattern), operation, endpoint));
    }

    /** Every route, in match order. */
    public List<Route> routes() {
        return routes;
    }

    /** The route for {@code method} and {@code path}; a 404 or 405 when there is none. */
    public Match match(String method, SearchPath path) {
        boolean otherMethod = false;
        for (Route route : routes) {
            Map<String, String> parameters = route.pattern().match(path);
            if (parameters == null) {
                continue;
            }
            if (route.method().equalsIgnoreCase(method)) {
                return new Match(route.operation(), route.endpoint(), parameters);
            }
            otherMethod = true;
        }
        if (otherMethod) {
            throw new SearchException(SearchErrorCode.METHOD_NOT_ALLOWED, "The requested resource does not support "
                + "http method '" + method.toUpperCase(Locale.ROOT) + "'.");
        }
        throw SearchException.notFound("The requested resource does not exist on this search service.");
    }

    /**
     * A matched route.
     *
     * @param operation  the operation, for authorization
     * @param endpoint   the controller method
     * @param parameters the path variables
     */
    public record Match(SearchOperation operation, SearchEndpoint endpoint, Map<String, String> parameters) {
    }

    /**
     * One route.
     *
     * @param method    the HTTP method
     * @param pattern   the path pattern
     * @param operation the operation
     * @param endpoint  the controller method
     */
    public record Route(String method, RoutePattern pattern, SearchOperation operation, SearchEndpoint endpoint) {
    }

    /** A path pattern: literal alternatives or variables, segment by segment. */
    public record RoutePattern(String text, List<PatternSegment> segments) {

        static RoutePattern parse(String text) {
            List<PatternSegment> segments = new ArrayList<>();
            for (String segment : text.split("/")) {
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    segments.add(new PatternSegment(segment.substring(1, segment.length() - 1), Set.of()));
                } else {
                    segments.add(new PatternSegment(null, Set.of(segment.toLowerCase(Locale.ROOT).split("\\|"))));
                }
            }
            return new RoutePattern(text, List.copyOf(segments));
        }

        /** The path variables when {@code path} matches, else null. */
        Map<String, String> match(SearchPath path) {
            if (path.segments().size() != segments.size()) {
                return null;
            }
            Map<String, String> parameters = new HashMap<>();
            for (int index = 0; index < segments.size(); index++) {
                PatternSegment expected = segments.get(index);
                SearchPath.Segment actual = path.segments().get(index);
                if (expected.variable() != null) {
                    parameters.put(expected.variable(), actual.value());
                } else if (actual.quoted() || !expected.literals().contains(actual.value().toLowerCase(Locale.ROOT))) {
                    return null;
                }
            }
            return parameters;
        }
    }

    /**
     * One pattern segment.
     *
     * @param variable the variable name, or null for a literal segment
     * @param literals the accepted literals, lowercased
     */
    public record PatternSegment(String variable, Set<String> literals) {
    }
}
