package io.floci.az.services.table;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.table.TableReader.TableEntity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableReaderConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void queryWaitsForAWriteInFlight() throws Exception {
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("table")).thenReturn(store);
        StoredObject table = new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");
        when(store.get(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).startsWith("__ns__:")
                ? Optional.of(table) : Optional.empty());
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            writing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(store).put(anyString(), any());
        TableServiceHandler handler = new TableServiceHandler(factory, mock(EmulatorConfig.class));
        TableReader reader = new TableReader(handler);
        AzureRequest insert = new AzureRequest("POST", "acct", "table", "people",
                new SyntheticHttpHeaders(Map.of()),
                new ByteArrayInputStream("{\"PartitionKey\":\"p\",\"RowKey\":\"r\"}".getBytes(StandardCharsets.UTF_8)),
                Map.of(), null, false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Response> inserted = executor.submit(() -> handler.handle(insert));
            assertTrue(writing.await(5, TimeUnit.SECONDS), "the insert holds the write lock while it writes");
            Future<Optional<List<TableEntity>>> query =
                    executor.submit(() -> reader.query("acct", "people", null, null));
            assertThrows(TimeoutException.class, () -> query.get(200, TimeUnit.MILLISECONDS));

            release.countDown();
            assertEquals(201, inserted.get(5, TimeUnit.SECONDS).getStatus());
            assertTrue(query.get(5, TimeUnit.SECONDS).isPresent());
        } finally {
            executor.shutdownNow();
        }
    }
}
