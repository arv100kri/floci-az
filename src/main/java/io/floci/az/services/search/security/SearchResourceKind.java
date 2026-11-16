package io.floci.az.services.search.security;

/** The kinds of resource the search data plane serves, which RBAC scopes and network rules reason about. */
public enum SearchResourceKind {
    SERVICE("searchServices"),
    INDEX("indexes"),
    DOCUMENT("indexes/documents"),
    SYNONYM_MAP("synonymMaps"),
    ALIAS("aliases"),
    DATA_SOURCE("dataSources"),
    INDEXER("indexers"),
    SKILLSET("skillsets"),
    KNOWLEDGE_BASE("knowledgeBases"),
    KNOWLEDGE_SOURCE("knowledgeSources");

    private final String permissionSegment;

    SearchResourceKind(String permissionSegment) {
        this.permissionSegment = permissionSegment;
    }

    /** The resource-type segment of the RBAC permission ({@code indexes}, {@code indexes/documents}, ...). */
    public String permissionSegment() {
        return permissionSegment;
    }
}
