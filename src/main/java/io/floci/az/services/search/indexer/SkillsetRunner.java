package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Runs a skillset over one document an indexer cracked: the seam between the indexer, which reads sources and writes
 * index documents, and the AI enrichment pipeline.
 */
public interface SkillsetRunner {

    /**
     * The node of the {@code /document} the indexer passes, {@code /document/AzureSearch_DocumentKey}, that holds the
     * document's key: index projections write it to their {@code parentKeyFieldName} and build projected keys from it.
     */
    String DOCUMENT_KEY_NODE = "AzureSearch_DocumentKey";

    /**
     * Enriches one document.
     *
     * @param serviceName         the search service the indexer belongs to
     * @param skillset            the skillset definition as stored
     * @param document            the enrichment tree's {@code /document} node, built from the cracked source document
     *                            after field mappings, with the document key in {@link #DOCUMENT_KEY_NODE};
     *                            implementations may add to it
     * @param outputFieldMappings the indexer's {@code outputFieldMappings}, possibly empty
     * @return what the indexer writes: fields for the source document's index entry, projected documents, and the
     *     warnings and errors to report
     */
    EnrichmentResult enrich(String serviceName, ObjectNode skillset, ObjectNode document,
                            ArrayNode outputFieldMappings);

    /**
     * The outcome of enriching one document.
     *
     * @param indexFields        fields the output field mappings produced, merged into the source document's entry
     * @param projections        documents the skillset's index projections produced
     * @param skipParentDocument whether the projections replace the source document's own entry
     * @param warnings           messages reported as indexer warnings
     * @param errors             messages reported as indexer errors; a document with errors is not indexed
     */
    record EnrichmentResult(ObjectNode indexFields, List<ProjectedDocument> projections, boolean skipParentDocument,
                            List<String> warnings, List<String> errors) {

        public EnrichmentResult {
            projections = List.copyOf(projections);
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }
    }

    /**
     * One document an index projection produced.
     *
     * @param targetIndexName the index it is written to
     * @param document        the document, including its key
     */
    record ProjectedDocument(String targetIndexName, ObjectNode document) {
    }
}
