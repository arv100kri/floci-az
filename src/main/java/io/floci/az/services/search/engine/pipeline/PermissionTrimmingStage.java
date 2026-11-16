package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.security.DocumentAccess;
import io.floci.az.services.search.security.DocumentPermissions;

import java.util.BitSet;

/**
 * Trims a query to the documents its reader may see when the index enforces document-level permission filters
 * ({@code permissionFilterOption: enabled}). Azure appends a security filter "to any filters that might come in with
 * the query", so the visible documents are folded into {@link SearchExecution#filterMatches()} right after
 * {@link FilterStage}: retrieval, fusion, counts, facets and every later stage then see only those documents, the
 * way they see only the ones {@code $filter} admits. Who the reader is comes from {@link DocumentAccess#current()};
 * {@link DocumentPermissions} decides what it may see.
 */
public final class PermissionTrimmingStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        BitSet visible = DocumentPermissions.visibleDocuments(execution.index(), DocumentAccess.current());
        if (visible == null) {
            return;
        }
        BitSet matches = execution.filterMatches();
        if (matches == null) {
            execution.filterMatches(visible);
        } else {
            matches.and(visible);
        }
    }
}
