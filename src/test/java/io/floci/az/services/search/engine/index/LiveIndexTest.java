package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.Etags;
import io.floci.az.services.search.engine.query.QueryExecutor;
import io.floci.az.services.search.engine.query.ScoredDocs;
import io.floci.az.services.search.engine.query.SimpleQueryParser;
import io.floci.az.services.search.model.IndexActionType;
import io.floci.az.services.search.model.IndexingResult;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

class LiveIndexTest {

    private static final String DEFINITION = """
        {"name": "fruit", "fields": [
          {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
          {"name": "text", "type": "Edm.String"},
          {"name": "code", "type": "Edm.String", "analyzer": "codes"}
        ],
        "analyzers": [{"@odata.type": "#Microsoft.Azure.Search.CustomAnalyzer", "name": "codes",
          "tokenizer": "whitespace"%s}]}
        """;

    private static final LiveIndex.DocumentSink NO_SINK = (upserts, deletes) -> { };

    private static ObjectNode json(String text) {
        return (ObjectNode) SearchJson.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> search(LiveIndex index, String query) {
        return index.read(live -> {
            ScoredDocs docs = new QueryExecutor(live, live.definition().fullTextSearchableFields(), Map.of())
                .execute(new SimpleQueryParser().parse(query, SearchMode.ANY));
            return docs == null ? List.<String>of()
                : docs.ordinals().stream().sorted().map(ordinal -> live.document(ordinal).key()).toList();
        });
    }

    private static IndexingResult apply(LiveIndex index, IndexActionType type, String document) {
        ObjectNode parsed = json(document);
        return index.apply(List.of(new IndexAction(type, parsed.get("id").asText(), parsed, null)), 100,
            NO_SINK).get(0);
    }

    @Test
    void mergesAndDeletesKeepThePostingsCurrent() {
        LiveIndex index = EngineFixtures.index(DEFINITION.formatted(""), "{\"id\":\"1\",\"text\":\"red apple\"}");
        assertThat(apply(index, IndexActionType.MERGE, "{\"id\":\"1\",\"text\":\"green pear\"}").statusCode(), is(200));
        assertThat(search(index, "red"), empty());
        assertThat(search(index, "pear"), contains("1"));
        assertThat(apply(index, IndexActionType.DELETE, "{\"id\":\"1\"}").statusCode(), is(200));
        assertThat(search(index, "pear"), empty());
        assertThat(index.documentCount(), is(0));
        assertThat(index.storageSize(), is(0L));
    }

    @Test
    void theSinkReceivesTheFinalStateOfEachDocument() {
        LiveIndex index = EngineFixtures.index(DEFINITION.formatted(""));
        AtomicReference<Map<String, ObjectNode>> persisted = new AtomicReference<>();
        AtomicReference<Set<String>> removed = new AtomicReference<>();
        index.apply(List.of(
            new IndexAction(IndexActionType.UPLOAD, "1", json("{\"id\":\"1\",\"text\":\"a\"}"), null),
            new IndexAction(IndexActionType.MERGE, "1", json("{\"id\":\"1\",\"code\":\"X\"}"), null),
            new IndexAction(IndexActionType.UPLOAD, "2", json("{\"id\":\"2\"}"), null),
            new IndexAction(IndexActionType.DELETE, "2", json("{\"id\":\"2\"}"), null)),
            100, (upserts, deletes) -> {
                persisted.set(Map.copyOf(upserts));
                removed.set(Set.copyOf(deletes));
            });
        assertThat(persisted.get().keySet(), contains("1"));
        assertThat(persisted.get().get("1").get("text").asText(), is("a"));
        assertThat(persisted.get().get("1").get("code").asText(), is("X"));
        assertThat(removed.get(), contains("2"));
    }

    @Test
    void addingAFieldNeedsNoReanalysis() {
        LiveIndex index = EngineFixtures.index(DEFINITION.formatted(""), "{\"id\":\"1\",\"text\":\"red apple\"}");
        String extended = DEFINITION.formatted("").replace("{\"name\": \"code\"",
            "{\"name\": \"notes\", \"type\": \"Edm.String\"}, {\"name\": \"code\"");
        index.redefine(EngineFixtures.definition(extended), Etags.next());
        apply(index, IndexActionType.UPLOAD, "{\"id\":\"2\",\"notes\":\"apple pie\"}");
        assertThat(search(index, "apple"), contains("1", "2"));
    }

    @Test
    void changedAnalysisComponentsReanalyzeTheDocuments() {
        LiveIndex index = EngineFixtures.index(DEFINITION.formatted(""), "{\"id\":\"1\",\"code\":\"ABC-1\"}");
        assertThat(search(index, "abc-1"), empty());
        index.redefine(EngineFixtures.definition(DEFINITION.formatted(", \"tokenFilters\": [\"lowercase\"]")),
            Etags.next());
        assertThat(search(index, "abc-1"), contains("1"));
    }
}
