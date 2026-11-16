package io.floci.az.services.search.dataplane;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.services.search.engine.EngineFixtures;
import io.floci.az.services.search.engine.EngineSettings;
import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.ServiceIndexes;
import io.floci.az.services.search.engine.index.IndexAction;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.engine.pipeline.SearchOutcome;
import io.floci.az.services.search.model.IndexActionType;
import io.floci.az.services.search.model.SearchJson;
import io.floci.az.services.search.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

class StorageIndexPersistenceTest {

    private static final String DEFINITION = """
        {"name": "hotels", "fields": [
          {"name": "id", "type": "Edm.String", "key": true},
          {"name": "name", "type": "Edm.String"}
        ]}
        """;

    private static IndexAction upload(String key, String name) {
        ObjectNode document = (ObjectNode) SearchJson.parse(("{\"id\":\"" + key + "\",\"name\":\"" + name + "\"}")
            .getBytes(StandardCharsets.UTF_8));
        return new IndexAction(IndexActionType.UPLOAD, key, document, null);
    }

    private static IndexAction delete(String key) {
        ObjectNode document = SearchJson.object().put("id", key);
        return new IndexAction(IndexActionType.DELETE, key, document, null);
    }

    @Test
    void indexesAndDocumentsSurviveARestart() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        SearchEngine first = new SearchEngine(new StorageIndexPersistence(store), EngineSettings.DEFAULTS);
        LiveIndex created = first.service("Svc").create(EngineFixtures.definition(DEFINITION));
        first.index("Svc", created, List.of(upload("1", "Quiet Beach Hotel"), upload("2", "City Inn"),
            upload("3", "Mountain Lodge")));
        first.index("Svc", created, List.of(delete("3")));

        SearchEngine restarted = new SearchEngine(new StorageIndexPersistence(store), EngineSettings.DEFAULTS);
        ServiceIndexes service = restarted.service("svc");
        LiveIndex reloaded = service.find("hotels").orElseThrow();
        assertThat(reloaded.documentCount(), is(2));
        assertThat(reloaded.etag(), is(created.etag()));
        SearchOutcome outcome = restarted.search(reloaded, SearchRequest.builder().search("beach").build());
        assertThat(outcome.results(), hasSize(1));
        assertThat(outcome.results().get(0).document().get("id").asText(), is("1"));
    }

    @Test
    void deletingAnIndexForgetsItsDocumentsAndResetLeavesOtherKeys() {
        InMemoryStorage<String, StoredObject> store = new InMemoryStorage<>();
        SearchEngine engine = new SearchEngine(new StorageIndexPersistence(store), EngineSettings.DEFAULTS);
        LiveIndex created = engine.service("svc").create(EngineFixtures.definition(DEFINITION));
        engine.index("svc", created, List.of(upload("1", "Inn")));
        store.put("other/key", new StoredObject("other/key", new byte[0], Map.of(), null, null));
        engine.service("svc").delete("hotels");
        assertThat(store.keys().stream().filter(key -> key.startsWith("dp/")).toList(), empty());
        engine.service("svc").create(EngineFixtures.definition(DEFINITION));
        engine.clear();
        assertThat(store.keys().stream().filter(key -> key.startsWith("dp/")).toList(), empty());
        assertThat(store.keys().contains("other/key"), is(true));
    }
}
