package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.analysis.Analyzer;
import io.floci.az.services.search.engine.analysis.AnalyzerRegistry;
import io.floci.az.services.search.engine.analysis.IndexAnalysis;
import io.floci.az.services.search.engine.analysis.Token;
import io.floci.az.services.search.engine.vector.UnstoredVectors;
import io.floci.az.services.search.model.IndexingResult;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * One search index in memory: its definition, its documents and the inverted index of each searchable field. Writes
 * (indexing batches, definition changes) take the write lock; queries run inside {@link #read(Function)}, and the
 * accessors documented as such may only be called there.
 */
public final class LiveIndex {

    /** The position gap between the values of a multi-valued field, so phrases do not match across two values. */
    static final int POSITION_GAP = 100;

    private final String name;
    private final AnalyzerRegistry registry;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Integer> ordinalsByKey = new HashMap<>();
    private final List<IndexedDocument> documents = new ArrayList<>();
    private final Map<String, InvertedField> invertedFields = new HashMap<>();
    private volatile SearchIndexDefinition definition;
    private volatile IndexAnalysis analysis;
    private volatile String etag;
    private volatile int liveDocuments;
    private volatile long storageSize;
    private volatile long vectorIndexSize;

    public LiveIndex(SearchIndexDefinition definition, String etag, AnalyzerRegistry registry) {
        this.name = definition.name();
        this.registry = registry;
        this.definition = definition;
        this.analysis = IndexAnalysis.of(definition, registry);
        this.etag = etag;
    }

    public String name() {
        return name;
    }

    public SearchIndexDefinition definition() {
        return definition;
    }

    public IndexAnalysis analysis() {
        return analysis;
    }

    public String etag() {
        return etag;
    }

    /** The number of documents in the index. */
    public int documentCount() {
        return liveDocuments;
    }

    /** The approximate storage the documents take, in bytes. */
    public long storageSize() {
        return storageSize;
    }

    /** The approximate size of the vector values, in bytes. */
    public long vectorIndexSize() {
        return vectorIndexSize;
    }

    /** Runs {@code reader} under the read lock: a consistent view for a whole query. */
    public <T> T read(Function<LiveIndex, T> reader) {
        lock.readLock().lock();
        try {
            return reader.apply(this);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** One past the highest document ordinal; ordinals of deleted documents are empty. Read lock only. */
    public int ordinalLimit() {
        return documents.size();
    }

    /** The document at {@code ordinal}, or null when it was deleted. Read lock only. */
    public IndexedDocument document(int ordinal) {
        return documents.get(ordinal);
    }

    /** The document with {@code key}, or null. Read lock only. */
    public IndexedDocument document(String key) {
        Integer ordinal = ordinalsByKey.get(key);
        return ordinal == null ? null : documents.get(ordinal);
    }

    /** The inverted index of the searchable field at {@code path}, or null when no document has a term in it. */
    public InvertedField invertedField(String path) {
        return invertedFields.get(path);
    }

    /**
     * Replaces the definition (an update the caller validated). The inverted indexes are rebuilt only when the
     * analysis components changed: the other updates the service allows (new fields, {@code retrievable},
     * {@code searchAnalyzer}, synonym maps, profiles) take effect at query time, and documents simply have no value
     * for a field added after them.
     */
    public void redefine(SearchIndexDefinition newDefinition, String newEtag) {
        lock.writeLock().lock();
        try {
            boolean reanalyze = !sameAnalysis(definition, newDefinition);
            definition = newDefinition;
            analysis = IndexAnalysis.of(newDefinition, registry);
            etag = newEtag;
            if (!reanalyze) {
                return;
            }
            invertedFields.clear();
            vectorIndexSize = 0;
            for (int ordinal = 0; ordinal < documents.size(); ordinal++) {
                IndexedDocument document = documents.get(ordinal);
                if (document != null) {
                    IndexedDocument rebuilt = build(ordinal, document.key(), document.source());
                    documents.set(ordinal, rebuilt);
                    addToIndex(rebuilt);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean sameAnalysis(SearchIndexDefinition before, SearchIndexDefinition after) {
        for (String section : new String[] {"analyzers", "tokenizers", "tokenFilters", "charFilters",
            "normalizers"}) {
            if (!before.json().path(section).equals(after.json().path(section))) {
                return false;
            }
        }
        return true;
    }

    /** Adds a persisted document while the index is rebuilt at startup. */
    public void load(String key, ObjectNode source) {
        lock.writeLock().lock();
        try {
            Integer ordinal = ordinalsByKey.get(key);
            if (ordinal == null) {
                insert(key, source);
            } else {
                replace(ordinal, key, source);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Applies an indexing batch in order and hands the resulting changes to {@code sink} before the write lock is
     * released, so the persisted order matches the applied one.
     *
     * @param actions      the batch
     * @param maxDocuments the most documents the index may hold; a new document beyond it fails with 429
     * @param sink         receives the documents to store and the keys to forget
     */
    public List<IndexingResult> apply(List<IndexAction> actions, int maxDocuments, DocumentSink sink) {
        lock.writeLock().lock();
        try {
            List<IndexingResult> results = new ArrayList<>(actions.size());
            Map<String, ObjectNode> upserts = new LinkedHashMap<>();
            Set<String> deletes = new LinkedHashSet<>();
            for (IndexAction action : actions) {
                results.add(applyOne(action, maxDocuments, upserts, deletes));
            }
            if (!upserts.isEmpty() || !deletes.isEmpty()) {
                sink.persist(upserts, deletes);
            }
            return results;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private IndexingResult applyOne(IndexAction action, int maxDocuments, Map<String, ObjectNode> upserts,
                                    Set<String> deletes) {
        String key = action.key();
        if (action.keyError() != null) {
            return IndexingResult.failed(key, 400, action.keyError());
        }
        Integer ordinal = ordinalsByKey.get(key);
        return switch (action.type()) {
            case DELETE -> {
                if (ordinal != null) {
                    remove(ordinal);
                    upserts.remove(key);
                    deletes.add(key);
                }
                yield IndexingResult.succeeded(key, 200);
            }
            case UPLOAD -> upload(key, action.document(), ordinal, maxDocuments, upserts, deletes);
            case MERGE -> ordinal == null
                ? IndexingResult.failed(key, 404, "Document not found.")
                : mergeInto(ordinal, key, action.document(), upserts, deletes);
            case MERGE_OR_UPLOAD -> ordinal == null
                ? upload(key, action.document(), null, maxDocuments, upserts, deletes)
                : mergeInto(ordinal, key, action.document(), upserts, deletes);
        };
    }

    private IndexingResult upload(String key, ObjectNode document, Integer ordinal, int maxDocuments,
                                  Map<String, ObjectNode> upserts, Set<String> deletes) {
        if (ordinal != null) {
            replace(ordinal, key, document);
            record(key, document, upserts, deletes);
            return IndexingResult.succeeded(key, 200);
        }
        if (liveDocuments >= maxDocuments) {
            return IndexingResult.failed(key, 429, "The index '" + name + "' already holds " + liveDocuments
                + " documents, the most this emulator allows per index (floci-az.services.search."
                + "max-documents-per-index).");
        }
        insert(key, document);
        record(key, document, upserts, deletes);
        return IndexingResult.succeeded(key, 201);
    }

    private IndexingResult mergeInto(int ordinal, String key, ObjectNode partial, Map<String, ObjectNode> upserts,
                                     Set<String> deletes) {
        ObjectNode merged = UnstoredVectors.dropOmitted(DocumentCoercer.merge(documents.get(ordinal).source(), partial),
            partial, definition);
        replace(ordinal, key, merged);
        record(key, merged, upserts, deletes);
        return IndexingResult.succeeded(key, 200);
    }

    private static void record(String key, ObjectNode document, Map<String, ObjectNode> upserts,
                               Set<String> deletes) {
        deletes.remove(key);
        upserts.put(key, document);
    }

    private void insert(String key, ObjectNode source) {
        int ordinal = documents.size();
        IndexedDocument document = build(ordinal, key, source);
        documents.add(document);
        ordinalsByKey.put(key, ordinal);
        liveDocuments++;
        storageSize += document.size();
        addToIndex(document);
    }

    private void replace(int ordinal, String key, ObjectNode source) {
        IndexedDocument previous = documents.get(ordinal);
        removeFromIndex(previous);
        storageSize -= previous.size();
        IndexedDocument document = build(ordinal, key, source);
        documents.set(ordinal, document);
        storageSize += document.size();
        addToIndex(document);
    }

    private void remove(int ordinal) {
        IndexedDocument previous = documents.get(ordinal);
        removeFromIndex(previous);
        documents.set(ordinal, null);
        ordinalsByKey.remove(previous.key());
        liveDocuments--;
        storageSize -= previous.size();
    }

    private IndexedDocument build(int ordinal, String key, ObjectNode source) {
        return new IndexedDocument(ordinal, key, source,
            DocumentValues.extract(source, definition.fields(), analysis), SearchJson.writeBytes(source).length);
    }

    private void addToIndex(IndexedDocument document) {
        for (SearchFieldDefinition field : definition.fullTextSearchableFields()) {
            List<Token> tokens = tokens(document.source(), field);
            if (!tokens.isEmpty()) {
                invertedFields.computeIfAbsent(field.path(), path -> new InvertedField()).add(document.ordinal(),
                    tokens);
            }
        }
        vectorIndexSize += vectorBytes(document);
    }

    private void removeFromIndex(IndexedDocument document) {
        for (SearchFieldDefinition field : definition.fullTextSearchableFields()) {
            InvertedField inverted = invertedFields.get(field.path());
            if (inverted != null) {
                inverted.remove(document.ordinal(), tokens(document.source(), field));
            }
        }
        vectorIndexSize -= vectorBytes(document);
    }

    private long vectorBytes(IndexedDocument document) {
        long bytes = 0;
        for (SearchFieldDefinition field : definition.fields()) {
            if (field.isVector() && document.values().get(field.name()) instanceof float[] vector) {
                int width = switch (field.type().element()) {
                    case SINGLE -> 4;
                    case HALF, INT16 -> 2;
                    default -> 1;
                };
                bytes += (long) vector.length * width;
            }
        }
        return bytes;
    }

    /** The tokens of every value of {@code field} in {@code source}, positions continuing across values. */
    private List<Token> tokens(ObjectNode source, SearchFieldDefinition field) {
        Analyzer analyzer = analysis.indexAnalyzer(field);
        List<Token> tokens = new ArrayList<>();
        int base = 0;
        for (String value : DocumentValues.textValues(source, field)) {
            int last = -1;
            for (Token token : analyzer.analyze(value)) {
                tokens.add(new Token(token.term(), base + token.position(), token.startOffset(), token.endOffset()));
                last = Math.max(last, token.position());
            }
            base += last + 1 + POSITION_GAP;
        }
        return tokens;
    }

    /** Receives the changes of an indexing batch while the index's write lock is held. */
    @FunctionalInterface
    public interface DocumentSink {
        void persist(Map<String, ObjectNode> upserts, Set<String> deletes);
    }
}
