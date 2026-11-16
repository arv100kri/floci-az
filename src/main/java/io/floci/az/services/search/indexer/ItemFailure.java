package io.floci.az.services.search.indexer;

/** An item or document that fails while the run goes on: it counts against {@code maxFailedItems}. */
final class ItemFailure extends Exception {

    private final transient IndexerIssue issue;

    ItemFailure(IndexerIssue issue) {
        super(issue.message());
        this.issue = issue;
    }

    IndexerIssue issue() {
        return issue;
    }
}
