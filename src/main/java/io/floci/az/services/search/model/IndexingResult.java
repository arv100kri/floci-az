package io.floci.az.services.search.model;

/**
 * The outcome of one action of an indexing batch, as {@code IndexingResult} on the wire.
 *
 * @param key          the document key
 * @param status       whether the action succeeded
 * @param errorMessage why it failed, or null
 * @param statusCode   200 (modified or deleted), 201 (created), 400, 404 (merge of a missing document) or 429
 */
public record IndexingResult(String key, boolean status, String errorMessage, int statusCode) {

    public static IndexingResult succeeded(String key, int statusCode) {
        return new IndexingResult(key, true, null, statusCode);
    }

    public static IndexingResult failed(String key, int statusCode, String errorMessage) {
        return new IndexingResult(key, false, errorMessage, statusCode);
    }
}
