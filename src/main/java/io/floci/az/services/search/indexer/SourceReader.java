package io.floci.az.services.search.indexer;

import java.util.List;

/** Where a run reads its items from: a blob container, a table, a Cosmos DB container. */
interface SourceReader {

    /**
     * The items a run considers, in processing order.
     *
     * @param tracking   the change tracking state the run starts from
     * @param everything whether to read every item regardless of the high-water mark (Reset Documents, Resync)
     * @throws IndexerRunException when the source cannot be read
     */
    List<SourceItem> items(ChangeTracking tracking, boolean everything) throws IndexerRunException;

    /** Whether the items' change tracking values are timestamps (blob last-modified, table Timestamp). */
    boolean timestampTracking();
}
