package io.floci.az.services.search.indexer;

/**
 * A run that cannot go on: its data source, index or skillset is missing or unreadable, or the indexer cannot be
 * served. The message becomes the execution's {@code errorMessage}.
 */
final class IndexerRunException extends Exception {

    private final boolean persistent;

    IndexerRunException(String message) {
        this(message, false);
    }

    /**
     * @param message    the top-level error
     * @param persistent whether the failure needs human intervention, making the run a {@code persistentFailure} and
     *                   the indexer status {@code error}
     */
    IndexerRunException(String message, boolean persistent) {
        super(message);
        this.persistent = persistent;
    }

    boolean persistent() {
        return persistent;
    }
}
