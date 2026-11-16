package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * An indexer's {@code parameters} ({@code IndexingParameters}) with the documented defaults applied: the batch size
 * is the data source's default when unset (10 for blobs, 1000 otherwise), {@code maxFailedItems} defaults to 0 and
 * -1 means no limit, and the {@code configuration} settings default as the REST reference lists them.
 *
 * @param batchSize              the batch size, or null for the data source's default
 * @param maxFailedItems         the failures a run tolerates, -1 for no limit, or null for the default 0
 * @param maxFailedItemsPerBatch the failures a batch tolerates, -1 for no limit, or null when unset
 * @param base64EncodeKeys       whether document keys are Base64-encoded
 * @param configuration          the {@code configuration} settings; empty when unset
 */
public record IndexerParameters(Integer batchSize, Integer maxFailedItems, Integer maxFailedItemsPerBatch,
                                boolean base64EncodeKeys, ObjectNode configuration) {

    /** The blob indexers' documented default batch size. */
    static final int BLOB_BATCH_SIZE = 10;
    /** The batch size of the other indexers. */
    static final int DEFAULT_BATCH_SIZE = 1000;

    /** Reads the normalized {@code parameters} JSON; null or absent gives the defaults. */
    public static IndexerParameters from(JsonNode parameters) {
        if (!(parameters instanceof ObjectNode object)) {
            return new IndexerParameters(null, null, null, false, SearchJson.object());
        }
        ObjectNode configuration = object.get("configuration") instanceof ObjectNode config ? config
            : SearchJson.object();
        return new IndexerParameters(integer(object.get("batchSize")), integer(object.get("maxFailedItems")),
            integer(object.get("maxFailedItemsPerBatch")), bool(object.get("base64EncodeKeys"), false),
            configuration);
    }

    /** The batch size for a data source of {@code type}. */
    public int effectiveBatchSize(DataSourceType type) {
        if (batchSize != null && batchSize > 0) {
            return batchSize;
        }
        return type.isBlob() ? BLOB_BATCH_SIZE : DEFAULT_BATCH_SIZE;
    }

    /** The failed items a run tolerates; negative for no limit. */
    public int failureLimit() {
        return maxFailedItems == null ? 0 : maxFailedItems;
    }

    /**
     * The failed items a batch tolerates; negative for no limit. Unset, it follows {@code maxFailedItems}, so a run
     * that tolerates some failures does not stop on a batch holding one of them.
     */
    public int batchFailureLimit() {
        return maxFailedItemsPerBatch == null ? failureLimit() : maxFailedItemsPerBatch;
    }

    public String parsingMode() {
        return text("parsingMode", "default");
    }

    public String dataToExtract() {
        return text("dataToExtract", "contentAndMetadata");
    }

    /** The extensions {@code indexedFileNameExtensions} lists, lowercased with their dot; empty for every blob. */
    public Set<String> indexedFileNameExtensions() {
        return extensions("indexedFileNameExtensions");
    }

    /** The extensions {@code excludedFileNameExtensions} lists, lowercased with their dot. */
    public Set<String> excludedFileNameExtensions() {
        return extensions("excludedFileNameExtensions");
    }

    public boolean failOnUnsupportedContentType() {
        return bool(configuration.get("failOnUnsupportedContentType"), false);
    }

    public boolean failOnUnprocessableDocument() {
        return bool(configuration.get("failOnUnprocessableDocument"), false);
    }

    public boolean indexStorageMetadataOnlyForOversizedDocuments() {
        return bool(configuration.get("indexStorageMetadataOnlyForOversizedDocuments"), false);
    }

    public boolean firstLineContainsHeaders() {
        return bool(configuration.get("firstLineContainsHeaders"), true);
    }

    public boolean allowSkillsetToReadFileData() {
        return bool(configuration.get("allowSkillsetToReadFileData"), false);
    }

    /** The {@code delimitedTextHeaders} list, or null. */
    public String delimitedTextHeaders() {
        return text("delimitedTextHeaders", null);
    }

    public char delimitedTextDelimiter() {
        String delimiter = text("delimitedTextDelimiter", ",");
        return delimiter.isEmpty() ? ',' : delimiter.charAt(0);
    }

    /** The {@code documentRoot} JSON Pointer, or null. */
    public String documentRoot() {
        String root = text("documentRoot", null);
        return root == null || root.isBlank() ? null : root;
    }

    public String markdownParsingSubmode() {
        return text("markdownParsingSubmode", "oneToMany");
    }

    /** The deepest header level markdown parsing considers, 1 to 6. */
    public int markdownHeaderDepth() {
        String depth = text("markdownHeaderDepth", "h6").toLowerCase(Locale.ROOT);
        if (depth.length() == 2 && depth.charAt(0) == 'h' && depth.charAt(1) >= '1' && depth.charAt(1) <= '6') {
            return depth.charAt(1) - '0';
        }
        return 6;
    }

    /** The text encoding of {@code parsingMode: text}; UTF-8 by default. */
    public String encoding() {
        return text("encoding", "UTF-8");
    }

    public String executionEnvironment() {
        return text("executionEnvironment", "standard");
    }

    /**
     * Whether the indexer runs in the private execution environment, the only one that can use a shared private link.
     */
    public boolean privateExecution() {
        return "private".equalsIgnoreCase(executionEnvironment());
    }

    private String text(String name, String fallback) {
        JsonNode value = configuration.get(name);
        if (IndexerJson.isNull(value) || value.isContainerNode()) {
            return fallback;
        }
        return value.asText();
    }

    private Set<String> extensions(String name) {
        String list = text(name, null);
        Set<String> extensions = new LinkedHashSet<>();
        if (list == null) {
            return extensions;
        }
        for (String part : list.split(",")) {
            String extension = part.trim().toLowerCase(Locale.ROOT);
            if (!extension.isEmpty()) {
                extensions.add(extension.startsWith(".") ? extension : "." + extension);
            }
        }
        return extensions;
    }

    private static Integer integer(JsonNode node) {
        if (IndexerJson.isNull(node)) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean bool(JsonNode node, boolean fallback) {
        if (IndexerJson.isNull(node)) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText().trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        return "false".equalsIgnoreCase(text) ? false : fallback;
    }
}
