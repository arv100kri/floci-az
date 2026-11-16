package io.floci.az.services.entra;

import io.floci.az.core.storage.InMemoryStorage;

/** Test support: a directory over an in-memory backend, for plain JUnit tests outside this package. */
public final class InMemoryGraphDirectory {

    private InMemoryGraphDirectory() {
    }

    /** An empty directory whose default tenant is {@code defaultTenantId}; nothing is seeded. */
    public static GraphDirectoryStore create(String defaultTenantId) {
        return new GraphDirectoryStore(new InMemoryStorage<>(), defaultTenantId);
    }
}
