package io.floci.az.services.search.security;

/**
 * Every operation of the Azure AI Search data plane (the {@code Search} TypeSpec of azure-rest-api-specs), implemented
 * or not, with what authorization needs to know about it: the resource it acts on, whether it reads, writes, deletes
 * or acts, and whether a query key may call it (only reads of the documents collection).
 *
 * <p>{@link #permission()} is the RBAC permission the operation would check, built from the resource kind and the
 * access ({@code Microsoft.Search/searchServices/indexes/documents/read}); document operations are data actions, the
 * rest actions. Refine the mapping here when role enforcement lands, not at call sites.</p>
 */
public enum SearchOperation {

    // Indexes
    LIST_INDEXES(SearchResourceKind.INDEX, Access.READ),
    CREATE_INDEX(SearchResourceKind.INDEX, Access.WRITE),
    CREATE_OR_UPDATE_INDEX(SearchResourceKind.INDEX, Access.WRITE),
    GET_INDEX(SearchResourceKind.INDEX, Access.READ),
    DELETE_INDEX(SearchResourceKind.INDEX, Access.DELETE),
    GET_INDEX_STATISTICS(SearchResourceKind.INDEX, Access.READ),
    LIST_INDEX_STATISTICS(SearchResourceKind.INDEX, Access.READ),
    ANALYZE_TEXT(SearchResourceKind.INDEX, Access.ACTION),

    // Documents
    SEARCH_DOCUMENTS(SearchResourceKind.DOCUMENT, Access.READ, true),
    GET_DOCUMENT(SearchResourceKind.DOCUMENT, Access.READ, true),
    COUNT_DOCUMENTS(SearchResourceKind.DOCUMENT, Access.READ, true),
    SUGGEST(SearchResourceKind.DOCUMENT, Access.READ, true),
    AUTOCOMPLETE(SearchResourceKind.DOCUMENT, Access.READ, true),
    GRAPH_QUERY(SearchResourceKind.DOCUMENT, Access.READ, true),
    INDEX_DOCUMENTS(SearchResourceKind.DOCUMENT, Access.WRITE),

    // Service
    GET_SERVICE_STATISTICS(SearchResourceKind.SERVICE, Access.READ),

    // Synonym maps
    LIST_SYNONYM_MAPS(SearchResourceKind.SYNONYM_MAP, Access.READ),
    CREATE_SYNONYM_MAP(SearchResourceKind.SYNONYM_MAP, Access.WRITE),
    CREATE_OR_UPDATE_SYNONYM_MAP(SearchResourceKind.SYNONYM_MAP, Access.WRITE),
    GET_SYNONYM_MAP(SearchResourceKind.SYNONYM_MAP, Access.READ),
    DELETE_SYNONYM_MAP(SearchResourceKind.SYNONYM_MAP, Access.DELETE),

    // Aliases
    LIST_ALIASES(SearchResourceKind.ALIAS, Access.READ),
    CREATE_ALIAS(SearchResourceKind.ALIAS, Access.WRITE),
    CREATE_OR_UPDATE_ALIAS(SearchResourceKind.ALIAS, Access.WRITE),
    GET_ALIAS(SearchResourceKind.ALIAS, Access.READ),
    DELETE_ALIAS(SearchResourceKind.ALIAS, Access.DELETE),

    // Data sources
    LIST_DATA_SOURCES(SearchResourceKind.DATA_SOURCE, Access.READ),
    CREATE_DATA_SOURCE(SearchResourceKind.DATA_SOURCE, Access.WRITE),
    CREATE_OR_UPDATE_DATA_SOURCE(SearchResourceKind.DATA_SOURCE, Access.WRITE),
    GET_DATA_SOURCE(SearchResourceKind.DATA_SOURCE, Access.READ),
    DELETE_DATA_SOURCE(SearchResourceKind.DATA_SOURCE, Access.DELETE),

    // Indexers
    LIST_INDEXERS(SearchResourceKind.INDEXER, Access.READ),
    CREATE_INDEXER(SearchResourceKind.INDEXER, Access.WRITE),
    CREATE_OR_UPDATE_INDEXER(SearchResourceKind.INDEXER, Access.WRITE),
    GET_INDEXER(SearchResourceKind.INDEXER, Access.READ),
    DELETE_INDEXER(SearchResourceKind.INDEXER, Access.DELETE),
    GET_INDEXER_STATUS(SearchResourceKind.INDEXER, Access.READ),
    RUN_INDEXER(SearchResourceKind.INDEXER, Access.ACTION),
    RESET_INDEXER(SearchResourceKind.INDEXER, Access.ACTION),
    RESET_INDEXER_DOCUMENTS(SearchResourceKind.INDEXER, Access.ACTION),
    RESYNC_INDEXER(SearchResourceKind.INDEXER, Access.ACTION),

    // Skillsets
    LIST_SKILLSETS(SearchResourceKind.SKILLSET, Access.READ),
    CREATE_SKILLSET(SearchResourceKind.SKILLSET, Access.WRITE),
    CREATE_OR_UPDATE_SKILLSET(SearchResourceKind.SKILLSET, Access.WRITE),
    GET_SKILLSET(SearchResourceKind.SKILLSET, Access.READ),
    DELETE_SKILLSET(SearchResourceKind.SKILLSET, Access.DELETE),
    RESET_SKILLS(SearchResourceKind.SKILLSET, Access.ACTION),

    // Knowledge bases
    LIST_KNOWLEDGE_BASES(SearchResourceKind.KNOWLEDGE_BASE, Access.READ),
    CREATE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.WRITE),
    CREATE_OR_UPDATE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.WRITE),
    GET_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.READ),
    DELETE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.DELETE),
    RETRIEVE_KNOWLEDGE(SearchResourceKind.KNOWLEDGE_BASE, Access.ACTION),

    // Knowledge sources
    LIST_KNOWLEDGE_SOURCES(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ),
    CREATE_KNOWLEDGE_SOURCE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.WRITE),
    CREATE_OR_UPDATE_KNOWLEDGE_SOURCE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.WRITE),
    GET_KNOWLEDGE_SOURCE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ),
    DELETE_KNOWLEDGE_SOURCE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.DELETE),
    GET_KNOWLEDGE_SOURCE_STATUS(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ),
    LIST_KNOWLEDGE_SOURCE_FILES(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ),
    UPLOAD_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.WRITE),
    UPDATE_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.WRITE),
    DELETE_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.DELETE),
    INGEST_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.ACTION),
    GET_KNOWLEDGE_SOURCE_FILE_OPERATION(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ),
    LIST_KNOWLEDGE_SOURCE_FILE_OPERATIONS(SearchResourceKind.KNOWLEDGE_SOURCE, Access.READ);

    /** What an operation does to its resource. */
    public enum Access {
        READ("read"),
        WRITE("write"),
        DELETE("delete"),
        ACTION("action");

        private final String verb;

        Access(String verb) {
            this.verb = verb;
        }

        public String verb() {
            return verb;
        }
    }

    private static final String PERMISSION_PREFIX = "Microsoft.Search/searchServices/";

    private final SearchResourceKind resource;
    private final Access access;
    private final boolean queryKeyAllowed;

    SearchOperation(SearchResourceKind resource, Access access) {
        this(resource, access, false);
    }

    SearchOperation(SearchResourceKind resource, Access access, boolean queryKeyAllowed) {
        this.resource = resource;
        this.access = access;
        this.queryKeyAllowed = queryKeyAllowed;
    }

    public SearchResourceKind resource() {
        return resource;
    }

    public Access access() {
        return access;
    }

    /** Whether the operation only reads (the network and identity layers relax some rules for reads). */
    public boolean isRead() {
        return access == Access.READ;
    }

    /** Whether a query key may call it: only reads of an index's documents collection. */
    public boolean queryKeyAllowed() {
        return queryKeyAllowed;
    }

    /** Whether RBAC checks the operation as a data action (document operations) rather than an action. */
    public boolean isDataAction() {
        return resource == SearchResourceKind.DOCUMENT;
    }

    /** The RBAC permission the operation needs, such as {@code Microsoft.Search/searchServices/indexes/read}. */
    public String permission() {
        if (resource == SearchResourceKind.SERVICE) {
            return PERMISSION_PREFIX + access.verb();
        }
        return PERMISSION_PREFIX + resource.permissionSegment() + "/" + access.verb();
    }
}
