package io.floci.az.services.search.security;

import io.floci.az.core.auth.RbacOperation;

/**
 * Every operation of the Azure AI Search data plane (the {@code Search} TypeSpec of azure-rest-api-specs), implemented
 * or not, with what authorization needs to know about it: the resource it acts on, whether it reads, writes, deletes
 * or acts, and whether a query key may call it (only reads of the documents collection).
 *
 * <p>{@link #permission()} is the RBAC permission the operation checks, one of the {@code Microsoft.Search} operations
 * Learn lists ("Azure permissions for AI + machine learning"): the resource kind and the access by default
 * ({@code Microsoft.Search/searchServices/indexes/write}), and where the documented list words it differently, the
 * permission given explicitly. Analyzing text is {@code indexes/read} ("test the lexical analysis components of an
 * index"); running, resetting and resyncing an indexer is {@code indexers/write} ("manage its execution"); resetting
 * skills is {@code skillsets/write}; changing the files of a knowledge source is {@code knowledgeSources/write}; and
 * retrieving from a knowledge base reads documents, which Search Index Data Reader grants. Document operations are
 * data actions, the rest actions. An Index Documents batch checks {@code documents/write} only: the batch is read after
 * authorization, and every built-in role that grants {@code documents/write} also grants {@code documents/delete}, so
 * with floci-az's built-in roles the outcome is the one a per-action check would give.</p>
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
    ANALYZE_TEXT(SearchResourceKind.INDEX, Access.ACTION, SearchResourceKind.INDEX, Access.READ),

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
    RUN_INDEXER(SearchResourceKind.INDEXER, Access.ACTION, SearchResourceKind.INDEXER, Access.WRITE),
    RESET_INDEXER(SearchResourceKind.INDEXER, Access.ACTION, SearchResourceKind.INDEXER, Access.WRITE),
    RESET_INDEXER_DOCUMENTS(SearchResourceKind.INDEXER, Access.ACTION, SearchResourceKind.INDEXER, Access.WRITE),
    RESYNC_INDEXER(SearchResourceKind.INDEXER, Access.ACTION, SearchResourceKind.INDEXER, Access.WRITE),

    // Skillsets
    LIST_SKILLSETS(SearchResourceKind.SKILLSET, Access.READ),
    CREATE_SKILLSET(SearchResourceKind.SKILLSET, Access.WRITE),
    CREATE_OR_UPDATE_SKILLSET(SearchResourceKind.SKILLSET, Access.WRITE),
    GET_SKILLSET(SearchResourceKind.SKILLSET, Access.READ),
    DELETE_SKILLSET(SearchResourceKind.SKILLSET, Access.DELETE),
    RESET_SKILLS(SearchResourceKind.SKILLSET, Access.ACTION, SearchResourceKind.SKILLSET, Access.WRITE),

    // Knowledge bases
    LIST_KNOWLEDGE_BASES(SearchResourceKind.KNOWLEDGE_BASE, Access.READ),
    CREATE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.WRITE),
    CREATE_OR_UPDATE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.WRITE),
    GET_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.READ),
    DELETE_KNOWLEDGE_BASE(SearchResourceKind.KNOWLEDGE_BASE, Access.DELETE),
    RETRIEVE_KNOWLEDGE(SearchResourceKind.KNOWLEDGE_BASE, Access.ACTION, SearchResourceKind.DOCUMENT, Access.READ),

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
    DELETE_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.DELETE, SearchResourceKind.KNOWLEDGE_SOURCE,
        Access.WRITE),
    INGEST_KNOWLEDGE_SOURCE_FILE(SearchResourceKind.KNOWLEDGE_SOURCE, Access.ACTION, SearchResourceKind.KNOWLEDGE_SOURCE,
        Access.WRITE),
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
    private final SearchResourceKind permissionResource;
    private final Access permissionAccess;

    SearchOperation(SearchResourceKind resource, Access access) {
        this(resource, access, false);
    }

    SearchOperation(SearchResourceKind resource, Access access, boolean queryKeyAllowed) {
        this(resource, access, queryKeyAllowed, resource, access);
    }

    /** An operation whose documented permission is not its resource and access, such as {@code ANALYZE_TEXT}. */
    SearchOperation(SearchResourceKind resource, Access access, SearchResourceKind permissionResource,
                    Access permissionAccess) {
        this(resource, access, false, permissionResource, permissionAccess);
    }

    SearchOperation(SearchResourceKind resource, Access access, boolean queryKeyAllowed,
                    SearchResourceKind permissionResource, Access permissionAccess) {
        this.resource = resource;
        this.access = access;
        this.queryKeyAllowed = queryKeyAllowed;
        this.permissionResource = permissionResource;
        this.permissionAccess = permissionAccess;
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

    /**
     * Whether the operation reads an index's documents (search, lookup, count, suggest, autocomplete, graph queries):
     * the reads document-level permission filters trim.
     */
    public boolean isDocumentRead() {
        return resource == SearchResourceKind.DOCUMENT && access == Access.READ;
    }

    /** Whether a query key may call it: only reads of an index's documents collection. */
    public boolean queryKeyAllowed() {
        return queryKeyAllowed;
    }

    /** Whether RBAC checks the operation as a data action (document operations) rather than an action. */
    public boolean isDataAction() {
        return permissionResource == SearchResourceKind.DOCUMENT;
    }

    /** The RBAC permission the operation needs, such as {@code Microsoft.Search/searchServices/indexes/read}. */
    public String permission() {
        if (permissionResource == SearchResourceKind.SERVICE) {
            return PERMISSION_PREFIX + permissionAccess.verb();
        }
        return PERMISSION_PREFIX + permissionResource.permissionSegment() + "/" + permissionAccess.verb();
    }

    /** {@link #permission()} as the RBAC evaluator asks about it: a data action or an action. */
    public RbacOperation rbacOperation() {
        return isDataAction() ? RbacOperation.dataAction(permission()) : RbacOperation.action(permission());
    }
}
