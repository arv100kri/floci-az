package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Where index definitions and documents are kept between runs. The engine holds everything in memory and rebuilds its
 * inverted indexes from what this returns; the data plane implements it over {@code StorageFactory.create("search")}.
 */
public interface IndexPersistence {

    /** Persistence that keeps nothing, for engine tests. */
    IndexPersistence NONE = new IndexPersistence() {
        @Override
        public void saveIndex(String service, String index, ObjectNode definition, String etag) {
            // Nothing is persisted.
        }

        @Override
        public void deleteIndex(String service, String index) {
            // Nothing is persisted.
        }

        @Override
        public void saveDocuments(String service, String index, Map<String, ObjectNode> upserts,
                                  Set<String> deletes) {
            // Nothing is persisted.
        }

        @Override
        public List<StoredIndex> loadIndexes(String service) {
            return List.of();
        }

        @Override
        public void loadDocuments(String service, String index, BiConsumer<String, ObjectNode> consumer) {
            // Nothing is persisted.
        }

        @Override
        public void clear() {
            // Nothing is persisted.
        }
    };

    void saveIndex(String service, String index, ObjectNode definition, String etag);

    /** Forgets the index and every document in it. */
    void deleteIndex(String service, String index);

    /** Stores {@code upserts} (key to document) and forgets {@code deletes}, as one mutation. */
    void saveDocuments(String service, String index, Map<String, ObjectNode> upserts, Set<String> deletes);

    /** The indexes of {@code service}. */
    List<StoredIndex> loadIndexes(String service);

    /** Hands every document of the index to {@code consumer}, key first. */
    void loadDocuments(String service, String index, BiConsumer<String, ObjectNode> consumer);

    /** Forgets every index of every service. */
    void clear();

    /**
     * A persisted index definition.
     *
     * @param name       the index name
     * @param definition the definition JSON, as GET returns it without the ETag
     * @param etag       the ETag
     */
    record StoredIndex(String name, ObjectNode definition, String etag) {
    }
}
