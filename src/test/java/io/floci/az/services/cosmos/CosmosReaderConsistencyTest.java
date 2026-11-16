package io.floci.az.services.cosmos;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CosmosReaderConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void queryWaitsForADocumentOperationInFlight() throws Exception {
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("cosmos")).thenReturn(store);
        when(store.get(anyString())).thenReturn(Optional.of(stored("{\"id\":\"items\"}")));
        when(store.scan(any())).thenReturn(new ArrayList<>(List.of(stored("{\"id\":\"doc1\",\"_rid\":\"r1\"}"))));
        CosmosHandler handler = new CosmosHandler(factory, mock(EmulatorConfig.class));
        CosmosReader reader = new CosmosReader(handler);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<List<Object>>> query;
            // Point writes, transactional batches and deletes all run under the handler's monitor.
            synchronized (handler) {
                query = executor.submit(() -> reader.query("acct", "db", "items", "SELECT c.id FROM c", null));
                assertThrows(TimeoutException.class, () -> query.get(200, TimeUnit.MILLISECONDS));
            }

            assertEquals(List.of(Map.of("id", "doc1")), query.get(5, TimeUnit.SECONDS).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    private static StoredObject stored(String json) {
        return new StoredObject("key", json.getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(), "etag");
    }
}
