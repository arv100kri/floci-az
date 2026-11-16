package io.floci.az.services.search.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;

class SearchOperationTest {

    /**
     * The data-plane permissions of Learn's {@code Microsoft.Search} operations list ("Azure permissions for AI +
     * machine learning"): the actions on the search service and its objects, and the four data actions.
     */
    private static final Set<String> DOCUMENTED = Set.of(
        "Microsoft.Search/searchServices/read",
        "Microsoft.Search/searchServices/aliases/read", "Microsoft.Search/searchServices/aliases/write",
        "Microsoft.Search/searchServices/aliases/delete",
        "Microsoft.Search/searchServices/dataSources/read", "Microsoft.Search/searchServices/dataSources/write",
        "Microsoft.Search/searchServices/dataSources/delete",
        "Microsoft.Search/searchServices/indexers/read", "Microsoft.Search/searchServices/indexers/write",
        "Microsoft.Search/searchServices/indexers/delete",
        "Microsoft.Search/searchServices/indexes/read", "Microsoft.Search/searchServices/indexes/write",
        "Microsoft.Search/searchServices/indexes/delete",
        "Microsoft.Search/searchServices/knowledgeBases/read", "Microsoft.Search/searchServices/knowledgeBases/write",
        "Microsoft.Search/searchServices/knowledgeBases/delete",
        "Microsoft.Search/searchServices/knowledgeSources/read",
        "Microsoft.Search/searchServices/knowledgeSources/write",
        "Microsoft.Search/searchServices/knowledgeSources/delete",
        "Microsoft.Search/searchServices/skillsets/read", "Microsoft.Search/searchServices/skillsets/write",
        "Microsoft.Search/searchServices/skillsets/delete",
        "Microsoft.Search/searchServices/synonymMaps/read", "Microsoft.Search/searchServices/synonymMaps/write",
        "Microsoft.Search/searchServices/synonymMaps/delete",
        "Microsoft.Search/searchServices/indexes/documents/read",
        "Microsoft.Search/searchServices/indexes/documents/write",
        "Microsoft.Search/searchServices/indexes/documents/delete");

    @Test
    void everyOperationChecksADocumentedPermissionOfTheRightKind() {
        for (SearchOperation operation : SearchOperation.values()) {
            assertThat(operation.name(), operation.permission(), is(in(DOCUMENTED)));
            assertThat(operation.name(), operation.isDataAction(),
                is(operation.permission().contains("/indexes/documents/")));
        }
    }
}
