package io.floci.az.services.blob;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.auth.StorageSasAuthorization;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.blob.BlobReader.BlobEntry;
import io.floci.az.services.blob.BlobReader.BlobListing;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlobReaderConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void listingWaitsForAWriteThatHoldsTheLeaseMonitor() throws Exception {
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("blob")).thenReturn(store);
        when(store.get(anyString())).thenReturn(Optional.of(
                new StoredObject("", new byte[0], Map.of(), Instant.now(), "\"0x1\"")));
        when(store.scan(any())).thenReturn(new ArrayList<>(List.of(
                new StoredObject("a.txt", new byte[] {1}, Map.of("Name", "a.txt"), Instant.now(), "\"0x2\""))));
        BlobLeaseService leaseService = new BlobLeaseService();
        BlobServiceHandler handler = new BlobServiceHandler(factory, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(UserDelegationKeyService.class), mock(StorageSasAuthorization.class), leaseService);
        BlobReader reader = new BlobReader(handler);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<BlobListing>> listing;
            // Every Blob and Data Lake write runs inside leaseService.exclusively(), which holds this monitor.
            synchronized (leaseService) {
                listing = executor.submit(() -> reader.list("acct", "docs", ""));
                assertThrows(TimeoutException.class, () -> listing.get(200, TimeUnit.MILLISECONDS));
            }

            List<BlobEntry> entries = listing.get(5, TimeUnit.SECONDS).orElseThrow().entries();
            assertEquals(List.of("a.txt"), entries.stream().map(BlobEntry::name).toList());
        } finally {
            executor.shutdownNow();
        }
    }
}
