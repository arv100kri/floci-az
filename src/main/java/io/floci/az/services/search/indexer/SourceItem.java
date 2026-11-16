package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One item a data source yields (a blob, a table entity, a Cosmos DB document), before it is cracked into search
 * documents.
 *
 * @param identity             what identifies the item in its source (a blob name, {@code PartitionKey|RowKey}, an id)
 * @param version              its version (an ETag), so a rewrite at the same high-water mark counts as a change
 * @param trackingValue        its change tracking value (epoch nanoseconds of a timestamp, a {@code _ts}), or null
 * @param datasourceDocumentId the id Reset Documents names it by ({@code datasourceDocumentIds})
 * @param errorKey             the key errors about the item report before it has a document key
 * @param excluded             whether the indexer only tracks the item and does not index it (an excluded file
 *                             extension, {@code AzureSearch_Skip})
 * @param cracker              opens the item
 */
record SourceItem(String identity, String version, Long trackingValue, String datasourceDocumentId, String errorKey,
                  boolean excluded, Cracker cracker) {

    /** The identity and version, as the change tracking boundary keeps them. */
    String trackingIdentity() {
        return identity + "|" + (version == null ? "" : version);
    }

    /** Opens an item. */
    @FunctionalInterface
    interface Cracker {
        CrackedItem crack();
    }

    /**
     * What opening an item gave.
     *
     * @param documents the source documents, each the fields an indexer maps (empty when the item failed or was
     *                  skipped)
     * @param oneToMany whether the item was parsed into many documents ({@code jsonArray}, {@code jsonLines},
     *                  {@code delimitedText}, markdown {@code oneToMany}), which deletion detection does not apply to
     * @param warnings  warnings about the item
     * @param error     why the item failed, or null
     */
    record CrackedItem(List<ObjectNode> documents, boolean oneToMany, List<IndexerIssue> warnings, IndexerIssue error) {

        CrackedItem {
            documents = List.copyOf(documents);
            warnings = List.copyOf(warnings);
        }

        static CrackedItem of(List<ObjectNode> documents, boolean oneToMany, List<IndexerIssue> warnings) {
            return new CrackedItem(documents, oneToMany, warnings, null);
        }

        static CrackedItem failed(IndexerIssue error, List<IndexerIssue> warnings) {
            return new CrackedItem(List.of(), false, warnings, error);
        }
    }
}
