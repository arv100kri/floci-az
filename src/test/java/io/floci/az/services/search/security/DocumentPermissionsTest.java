package io.floci.az.services.search.security;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.engine.pipeline.SearchPipeline;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

class DocumentPermissionsTest {

    private static final String ALICE = "aaaaaaaa-0000-0000-0000-00000000a11c";
    private static final String BOB = "bbbbbbbb-0000-0000-0000-000000000b0b";
    private static final String READERS = "cccccccc-0000-0000-0000-0000000000c1";

    /** {@code users} is a collection, {@code team} a single string with a normalizer, as filters treat them. */
    private static final String DEFINITION = """
        {"name": "acl", "permissionFilterOption": "enabled", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "rank", "type": "Edm.Int32"},
          {"name": "users", "type": "Collection(Edm.String)", "permissionFilter": "userIds"},
          {"name": "team", "type": "Edm.String", "permissionFilter": "groupIds", "normalizer": "lowercase"},
          {"name": "scope", "type": "Edm.String", "permissionFilter": "rbacScope"}
        ]}
        """;

    private static final SearchPipeline PIPELINE = SearchPipeline.standard();

    private static LiveIndex index() {
        return EngineFixtures.index(DEFINITION,
            "{\"id\":\"1\",\"rank\":1,\"users\":[\"" + ALICE + "\"]}",
            "{\"id\":\"2\",\"rank\":2,\"users\":[\"all\"],\"team\":\"none\"}",
            "{\"id\":\"3\",\"rank\":3,\"users\":[\"none\"],\"team\":\"" + READERS.toUpperCase() + "\"}",
            "{\"id\":\"4\",\"rank\":4,\"users\":[],\"team\":\"all\"}",
            "{\"id\":\"5\",\"rank\":5,\"users\":[\"" + BOB + "\",\"" + ALICE + "\"]}",
            "{\"id\":\"6\",\"rank\":6,\"scope\":\"/subscriptions/s/resourceGroups/r\"}",
            "{\"id\":\"7\",\"rank\":7}");
    }

    private static Set<String> keys(LiveIndex index, BitSet ordinals) {
        Set<String> keys = new TreeSet<>();
        index.read(live -> {
            ordinals.stream().forEach(ordinal -> keys.add(live.document(ordinal).key()));
            return null;
        });
        return keys;
    }

    private static Set<String> search(LiveIndex index, DocumentAccess access, String filter) {
        SearchOutcome outcome = access.bindDuring(() -> PIPELINE.run(index,
            SearchRequest.builder().filter(filter).top(100).build(), EngineSettings.DEFAULTS));
        Set<String> keys = new TreeSet<>();
        outcome.results().forEach(result -> keys.add(result.document().get("id").asText()));
        return keys;
    }

    @Test
    void readersSeeTheDocumentsTheirIdsTheirGroupsOrAllGrant() {
        LiveIndex index = index();
        assertThat(search(index, DocumentAccess.ANONYMOUS, null), contains("2", "4"));
        assertThat(search(index, DocumentAccess.of(Set.of(ALICE)), null), contains("1", "2", "4", "5"));
        assertThat(search(index, DocumentAccess.of(Set.of(BOB, READERS)), null), contains("2", "3", "4", "5"));
        assertThat(search(index, DocumentAccess.UNRESTRICTED, null), contains("1", "2", "3", "4", "5", "6", "7"));
        assertThat(search(index, DocumentAccess.of(Set.of(ALICE)), "rank gt 2"), contains("4", "5"));
    }

    @Test
    void theSuggestFilterAdmitsExactlyTheVisibleDocuments() {
        LiveIndex index = index();
        for (DocumentAccess access : List.of(DocumentAccess.ANONYMOUS, DocumentAccess.of(Set.of(ALICE)),
                DocumentAccess.of(Set.of(BOB, READERS)), DocumentAccess.of(Set.of("it's", "none")))) {
            Set<String> visible = keys(index, index.read(live -> DocumentPermissions.visibleDocuments(live, access)));
            ObjectNode restricted = access.bindDuring(() -> index.read(live ->
                DocumentPermissions.restrict(live, SearchJson.object())));
            assertThat(access.toString(), search(index, DocumentAccess.UNRESTRICTED,
                restricted.get("filter").asText()), is(visible));
        }
    }

    @Test
    void theSuggestFilterKeepsTheCallersFilter() {
        LiveIndex index = index();
        ObjectNode parameters = SearchJson.object();
        parameters.put("filter", "rank lt 5");
        ObjectNode restricted = DocumentAccess.of(Set.of(ALICE)).bindDuring(() -> index.read(live ->
            DocumentPermissions.restrict(live, parameters)));
        assertThat(search(index, DocumentAccess.UNRESTRICTED, restricted.get("filter").asText()),
            contains("1", "2", "4"));
        assertThat(parameters.get("filter").asText(), is("rank lt 5"));
    }

    @Test
    void anElevatedReadOrAnIndexWithoutPermissionFiltersIsNotTrimmed() {
        LiveIndex index = index();
        ObjectNode parameters = SearchJson.object();
        assertThat(DocumentAccess.UNRESTRICTED.bindDuring(() -> index.read(live ->
            DocumentPermissions.restrict(live, parameters))), sameInstance(parameters));
        LiveIndex open = EngineFixtures.index(DEFINITION.replace("\"enabled\"", "\"disabled\""),
            "{\"id\":\"1\",\"users\":[\"" + BOB + "\"]}");
        assertThat(search(open, DocumentAccess.ANONYMOUS, null), contains("1"));
        assertThat(DocumentPermissions.count(open), is(1L));
    }

    @Test
    void countAndLookupFollowTheBoundReader() {
        LiveIndex index = index();
        assertThat(DocumentPermissions.count(index), is(2L));
        assertThat(DocumentAccess.of(Set.of(ALICE)).bindDuring(() -> DocumentPermissions.count(index)), is(4L));
        assertThat(index.read(live -> DocumentPermissions.visibleDocument(live, "1") == null), is(true));
        assertThat(DocumentAccess.of(Set.of(ALICE)).bindDuring(() -> index.read(live ->
            DocumentPermissions.visibleDocument(live, "1").key())), is("1"));
    }
}
