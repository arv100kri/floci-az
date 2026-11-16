package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.time.Duration;
import java.util.Locale;

/**
 * The execution limits Get Indexer Status reports ({@code SearchIndexerLimits}), by pricing tier as Learn's service
 * limits list them: the largest blob the indexer extracts (the S1 limit is 128 MB, the S2 limit 256 MB), the characters
 * extracted from one document (32,000 on Free, 64,000 on Basic, 4 million on Standard, 8 million on S2, 16 million
 * on S3) and the longest run (two hours in the multitenant environment, 24 in the private one). floci-az enforces the
 * two size limits; it does not stop a run that takes longer than the maximum running time.
 *
 * @param maxRunTime                            the longest run
 * @param maxDocumentExtractionSize             the largest document, in bytes, the indexer extracts content from
 * @param maxDocumentContentCharactersToExtract the most characters extracted from one document
 */
public record IndexerLimits(Duration maxRunTime, long maxDocumentExtractionSize,
                            long maxDocumentContentCharactersToExtract) {

    private static final long MB = 1024L * 1024L;

    /** The limits of a service of {@code sku} for an indexer in {@code executionEnvironment}. */
    public static IndexerLimits of(String sku, String executionEnvironment) {
        String tier = sku == null ? "standard" : sku.toLowerCase(Locale.ROOT);
        Duration runTime = "private".equalsIgnoreCase(executionEnvironment) ? Duration.ofHours(24)
            : Duration.ofHours(2);
        return switch (tier) {
            case "free" -> new IndexerLimits(Duration.ofMinutes(3), 16 * MB, 32_000);
            case "basic" -> new IndexerLimits(runTime, 16 * MB, 64_000);
            case "standard2" -> new IndexerLimits(runTime, 256 * MB, 8_000_000);
            case "standard3" -> new IndexerLimits(runTime, 256 * MB, 16_000_000);
            case "storage_optimized_l1", "storage_optimized_l2" -> new IndexerLimits(runTime, 256 * MB, 4_000_000);
            default -> new IndexerLimits(runTime, 128 * MB, 4_000_000);
        };
    }

    ObjectNode toJson() {
        ObjectNode json = SearchJson.object();
        json.put("maxRunTime", maxRunTime.toString());
        json.put("maxDocumentExtractionSize", maxDocumentExtractionSize);
        json.put("maxDocumentContentCharactersToExtract", maxDocumentContentCharactersToExtract);
        return json;
    }
}
