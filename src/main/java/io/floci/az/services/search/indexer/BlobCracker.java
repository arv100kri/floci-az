package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.blob.BlobReader;
import io.floci.az.services.blob.BlobReader.BlobContent;
import io.floci.az.services.blob.BlobReader.BlobEntry;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.indexer.SourceItem.CrackedItem;
import io.floci.az.services.search.model.SearchJson;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Document cracking for the blob and ADLS Gen2 indexers ("Index data from Azure Blob Storage", "Index JSON blobs and
 * files", "Index CSV blobs and files", "Index Markdown blobs and files", "Index plain text"). Every document carries
 * the standard {@code metadata_storage_*} properties and the blob's user metadata (a hyphen in a metadata name
 * becomes an underscore); {@code dataToExtract} and {@code AzureSearch_SkipContent} decide whether content is
 * extracted, and {@code parsingMode} how:
 * <ul>
 *   <li>{@code default}: plain text, Markdown, CSV and JSON as text, HTML and XML stripped to text, with the
 *       content-specific {@code metadata_content_type}, {@code metadata_content_encoding} and HTML title, description
 *       and keywords;</li>
 *   <li>{@code text}: the content as text in the configured {@code encoding} (UTF-8 by default);</li>
 *   <li>{@code json}: the blob's JSON object, its properties as source fields;</li>
 *   <li>{@code jsonArray} (with {@code documentRoot}), {@code jsonLines}, {@code delimitedText} and {@code markdown}
 *       ({@code oneToMany} by default): one document per element, line, row or section, each with the generated
 *       {@code AzureSearch_DocumentKey} ({@code {metadata_storage_path};{n}}).</li>
 * </ul>
 * PDF, Office, OpenDocument, e-mail, archive, image and media files are not extracted: floci-az has no parser for them.
 * They are reported as unsupported content ({@code failOnUnsupportedContentType}: an error when true, otherwise a
 * warning, with the metadata still indexed), as is binary content of an unknown type. Content that does not parse in
 * the configured mode is unprocessable ({@code failOnUnprocessableDocument}: an error when true, otherwise a warning
 * and the blob is skipped). A blob over the tier's extraction size fails, unless
 * {@code indexStorageMetadataOnlyForOversizedDocuments} indexes its metadata alone; text past the tier's character
 * limit is truncated with a warning.
 */
final class BlobCracker {

    static final String DOCUMENT_KEY = "AzureSearch_DocumentKey";
    static final String SKIP = "AzureSearch_Skip";
    static final String SKIP_CONTENT = "AzureSearch_SkipContent";

    private static final String EXTRACT_ANCHOR = "#error-could-not-extract-content-or-metadata-from-your-document";
    private static final String PARSE_ANCHOR = "#error-could-not-parse-document";
    private static final String TRUNCATED_ANCHOR = "#warning-truncated-extracted-text-to-x-characters";

    /** The kinds of content the cracker tells apart. */
    enum Format {
        TEXT("text/plain"),
        MARKDOWN("text/markdown"),
        CSV("text/csv"),
        JSON("application/json"),
        HTML("text/html"),
        XML("application/xml"),
        UNSUPPORTED(null),
        UNKNOWN(null);

        private final String contentType;

        Format(String contentType) {
            this.contentType = contentType;
        }
    }

    /** A format and the content type it reports. */
    private record Detected(Format format, String contentType) {
    }

    private static final Map<String, Detected> EXTENSIONS = Map.ofEntries(
        Map.entry(".txt", text(Format.TEXT)), Map.entry(".text", text(Format.TEXT)), Map.entry(".log", text(Format.TEXT)),
        Map.entry(".md", text(Format.MARKDOWN)), Map.entry(".markdown", text(Format.MARKDOWN)),
        Map.entry(".csv", text(Format.CSV)), Map.entry(".tsv", new Detected(Format.CSV, "text/tab-separated-values")),
        Map.entry(".json", text(Format.JSON)), Map.entry(".jsonl", text(Format.JSON)),
        Map.entry(".ndjson", text(Format.JSON)),
        Map.entry(".html", text(Format.HTML)), Map.entry(".htm", text(Format.HTML)),
        Map.entry(".xhtml", new Detected(Format.HTML, "application/xhtml+xml")),
        Map.entry(".xml", text(Format.XML)),
        Map.entry(".kml", new Detected(Format.XML, "application/vnd.google-earth.kml+xml")),
        Map.entry(".pdf", unsupported("application/pdf")),
        Map.entry(".doc", unsupported("application/msword")),
        Map.entry(".docx", unsupported("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
        Map.entry(".docm", unsupported("application/vnd.ms-word.document.macroenabled.12")),
        Map.entry(".xls", unsupported("application/vnd.ms-excel")),
        Map.entry(".xlsx", unsupported("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
        Map.entry(".xlsm", unsupported("application/vnd.ms-excel.sheet.macroenabled.12")),
        Map.entry(".ppt", unsupported("application/vnd.ms-powerpoint")),
        Map.entry(".pptx", unsupported("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
        Map.entry(".pptm", unsupported("application/vnd.ms-powerpoint.presentation.macroenabled.12")),
        Map.entry(".msg", unsupported("application/vnd.ms-outlook")),
        Map.entry(".eml", unsupported("message/rfc822")),
        Map.entry(".epub", unsupported("application/epub+zip")),
        Map.entry(".gz", unsupported("application/gzip")),
        Map.entry(".zip", unsupported("application/zip")),
        Map.entry(".rtf", unsupported("application/rtf")),
        Map.entry(".odt", unsupported("application/vnd.oasis.opendocument.text")),
        Map.entry(".ods", unsupported("application/vnd.oasis.opendocument.spreadsheet")),
        Map.entry(".odp", unsupported("application/vnd.oasis.opendocument.presentation")),
        Map.entry(".png", unsupported("image/png")), Map.entry(".jpg", unsupported("image/jpeg")),
        Map.entry(".jpeg", unsupported("image/jpeg")), Map.entry(".gif", unsupported("image/gif")),
        Map.entry(".bmp", unsupported("image/bmp")), Map.entry(".tif", unsupported("image/tiff")),
        Map.entry(".tiff", unsupported("image/tiff")), Map.entry(".webp", unsupported("image/webp")),
        Map.entry(".mp3", unsupported("audio/mpeg")), Map.entry(".wav", unsupported("audio/wav")),
        Map.entry(".mp4", unsupported("video/mp4")), Map.entry(".avi", unsupported("video/x-msvideo")));

    private final BlobReader blobs;
    private final String account;
    private final String container;
    private final IndexerParameters parameters;
    private final IndexerLimits limits;
    private final String baseUrl;
    private final String issueName;

    BlobCracker(BlobReader blobs, String account, String container, IndexerParameters parameters,
                IndexerLimits limits, String baseUrl, String issueName) {
        this.blobs = blobs;
        this.account = account;
        this.container = container;
        this.parameters = parameters;
        this.limits = limits;
        this.baseUrl = baseUrl;
        this.issueName = issueName;
    }

    private static Detected text(Format format) {
        return new Detected(format, format.contentType);
    }

    private static Detected unsupported(String contentType) {
        return new Detected(Format.UNSUPPORTED, contentType);
    }

    /** The URI {@code metadata_storage_path} reports: the emulator's path-style blob URL. */
    String storagePath(String blobName) {
        StringBuilder path = new StringBuilder(baseUrl).append('/').append(account).append('/').append(container);
        for (String segment : blobName.split("/", -1)) {
            path.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return path.toString();
    }

    /** The extension of a blob name, lowercased with its dot; empty when it has none. */
    static String extension(String blobName) {
        String fileName = blobName.substring(blobName.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Cracks one blob. */
    CrackedItem crack(BlobEntry entry) {
        CrackedItem cracked = crackContent(entry);
        if (!parameters.allowSkillsetToReadFileData() || cracked.documents().isEmpty()) {
            return cracked;
        }
        Optional<BlobContent> content = blobs.read(account, container, entry.name());
        if (content.isEmpty()) {
            return cracked;
        }
        String data = Base64.getEncoder().encodeToString(content.get().content());
        for (ObjectNode document : cracked.documents()) {
            ObjectNode fileData = document.putObject("file_data");
            fileData.put("$type", "file");
            fileData.put("data", data);
        }
        return cracked;
    }

    private CrackedItem crackContent(BlobEntry entry) {
        ObjectNode metadata = metadata(entry);
        String path = metadata.get("metadata_storage_path").textValue();
        if ("storageMetadata".equals(parameters.dataToExtract())) {
            return CrackedItem.of(List.of(metadata), false, List.of());
        }
        Detected detected = detect(entry);
        boolean skipContent = "true".equalsIgnoreCase(entry.metadata().get(SKIP_CONTENT));
        if ("allMetadata".equals(parameters.dataToExtract()) || skipContent) {
            contentMetadata(metadata, detected);
            return CrackedItem.of(List.of(metadata), false, List.of());
        }
        if (entry.contentLength() > limits.maxDocumentExtractionSize()) {
            String message = "Document is '" + entry.contentLength() + "' bytes, which exceeds the maximum size '"
                + limits.maxDocumentExtractionSize() + "' bytes for document extraction for your current service tier.";
            if (parameters.indexStorageMetadataOnlyForOversizedDocuments()) {
                return CrackedItem.of(List.of(metadata), false, List.of(IndexerIssue.warning(path, message
                    + " Only its storage metadata was indexed.", issueName, null, EXTRACT_ANCHOR)));
            }
            return CrackedItem.failed(IndexerIssue.error(path, 400, "Could not extract content or metadata from "
                + "your document.", issueName, message, EXTRACT_ANCHOR), List.of());
        }
        Optional<BlobContent> content = blobs.read(account, container, entry.name());
        if (content.isEmpty()) {
            return CrackedItem.of(List.of(), false, List.of());
        }
        byte[] bytes = content.get().content();
        try {
            return switch (parameters.parsingMode()) {
                case "text" -> single(metadata, decodeText(bytes, parameters.encoding()), detected, path);
                case "json" -> json(metadata, bytes, path);
                case "jsonArray" -> jsonArray(metadata, bytes, path);
                case "jsonLines" -> jsonLines(metadata, bytes, path);
                case "delimitedText" -> delimited(metadata, bytes, path);
                case "markdown" -> markdown(metadata, bytes, path, detected);
                default -> defaultMode(metadata, bytes, detected, path);
            };
        } catch (UnprocessableException e) {
            return unprocessable(metadata, path, e.getMessage());
        }
    }

    // ── metadata ──────────────────────────────────────────────────────────────────────────────────────────────

    private ObjectNode metadata(BlobEntry entry) {
        ObjectNode document = SearchJson.object();
        for (Map.Entry<String, String> userMetadata : entry.metadata().entrySet()) {
            document.put(userMetadata.getKey().replace('-', '_'), userMetadata.getValue());
        }
        String name = entry.name();
        document.put("metadata_storage_name", name.substring(name.lastIndexOf('/') + 1));
        document.put("metadata_storage_path", storagePath(name));
        document.put("metadata_storage_size", entry.contentLength());
        document.put("metadata_storage_last_modified", DocumentCoercer.formatDateTime(entry.lastModified()));
        document.put("metadata_storage_content_type", entry.contentType());
        if (entry.contentMd5() != null) {
            document.put("metadata_storage_content_md5", entry.contentMd5());
        }
        document.put("metadata_storage_file_extension", extension(name));
        return document;
    }

    private static void contentMetadata(ObjectNode document, Detected detected) {
        if (detected.contentType() != null) {
            document.put("metadata_content_type", detected.contentType());
        }
        if (detected.format() != Format.UNSUPPORTED && detected.format() != Format.UNKNOWN) {
            document.put("metadata_content_encoding", "UTF-8");
        }
    }

    private static Detected detect(BlobEntry entry) {
        Detected byExtension = EXTENSIONS.get(extension(entry.name()));
        if (byExtension != null) {
            return byExtension;
        }
        String contentType = entry.contentType() == null ? "" : entry.contentType().toLowerCase(Locale.ROOT);
        String mediaType = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(';')).trim()
            : contentType;
        if (mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml")) {
            return new Detected(Format.HTML, mediaType);
        }
        if (mediaType.equals("text/markdown")) {
            return new Detected(Format.MARKDOWN, mediaType);
        }
        if (mediaType.equals("text/csv")) {
            return new Detected(Format.CSV, mediaType);
        }
        if (mediaType.endsWith("json")) {
            return new Detected(Format.JSON, mediaType);
        }
        if (mediaType.endsWith("xml")) {
            return new Detected(Format.XML, mediaType);
        }
        if (mediaType.startsWith("text/")) {
            return new Detected(Format.TEXT, mediaType);
        }
        for (Detected known : EXTENSIONS.values()) {
            if (known.format() == Format.UNSUPPORTED && known.contentType().equals(mediaType)) {
                return known;
            }
        }
        return new Detected(Format.UNKNOWN, mediaType.isEmpty() ? null : mediaType);
    }

    // ── parsing modes ─────────────────────────────────────────────────────────────────────────────────────────

    private CrackedItem defaultMode(ObjectNode metadata, byte[] bytes, Detected detected, String path)
            throws UnprocessableException {
        Detected effective = detected;
        if (detected.format() == Format.UNKNOWN) {
            if (!looksLikeText(bytes)) {
                return unsupported(metadata, path, detected.contentType() == null ? "application/octet-stream"
                    : detected.contentType());
            }
            effective = new Detected(Format.TEXT, "text/plain");
        }
        if (effective.format() == Format.UNSUPPORTED) {
            return unsupported(metadata, path, effective.contentType());
        }
        String text = decodeText(bytes, "UTF-8");
        contentMetadata(metadata, effective);
        if (effective.format() == Format.HTML || effective.format() == Format.XML) {
            MarkupText.Extracted extracted = MarkupText.extract(text, effective.format() == Format.HTML);
            putIfPresent(metadata, "metadata_title", extracted.title());
            putIfPresent(metadata, "metadata_description", extracted.description());
            putIfPresent(metadata, "metadata_keywords", extracted.keywords());
            text = extracted.text();
        }
        return single(metadata, text, effective, path);
    }

    private CrackedItem single(ObjectNode metadata, String text, Detected detected, String path) {
        List<IndexerIssue> warnings = new ArrayList<>();
        if (!metadata.has("metadata_content_type")) {
            contentMetadata(metadata, detected.format() == Format.UNKNOWN || detected.format() == Format.UNSUPPORTED
                ? new Detected(Format.TEXT, "text/plain") : detected);
        }
        metadata.put("content", truncate(text, path, warnings));
        return CrackedItem.of(List.of(metadata), false, warnings);
    }

    private CrackedItem json(ObjectNode metadata, byte[] bytes, String path) throws UnprocessableException {
        JsonNode parsed = parseJson(decodeText(bytes, "UTF-8"));
        if (!(parsed instanceof ObjectNode object)) {
            throw new UnprocessableException("The blob is not a JSON object; the json parsing mode needs one object "
                + "per blob.");
        }
        return CrackedItem.of(List.of(merge(object, metadata)), false, List.of());
    }

    private CrackedItem jsonArray(ObjectNode metadata, byte[] bytes, String path) throws UnprocessableException {
        JsonNode parsed = parseJson(decodeText(bytes, "UTF-8"));
        String root = parameters.documentRoot();
        JsonNode array = root == null ? parsed : IndexerJson.pointer(parsed, root);
        if (!(array instanceof ArrayNode elements)) {
            throw new UnprocessableException(root == null ? "The blob is not a JSON array."
                : "The documentRoot '" + root + "' does not point to a JSON array in the blob.");
        }
        List<ObjectNode> documents = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!(element instanceof ObjectNode object)) {
                throw new UnprocessableException("The JSON array holds a value that is not a JSON object.");
            }
            documents.add(manyDocument(object, metadata, path, documents.size() + 1));
        }
        return CrackedItem.of(documents, true, List.of());
    }

    private CrackedItem jsonLines(ObjectNode metadata, byte[] bytes, String path) throws UnprocessableException {
        List<ObjectNode> documents = new ArrayList<>();
        int lineNumber = 0;
        for (String line : decodeText(bytes, "UTF-8").split("\r\n|\r|\n")) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode parsed;
            try {
                parsed = parseJson(line);
            } catch (UnprocessableException e) {
                throw new UnprocessableException("Line " + lineNumber + ": " + e.getMessage());
            }
            if (!(parsed instanceof ObjectNode object)) {
                throw new UnprocessableException("Line " + lineNumber + " is not a JSON object.");
            }
            documents.add(manyDocument(object, metadata, path, documents.size() + 1));
        }
        return CrackedItem.of(documents, true, List.of());
    }

    private CrackedItem delimited(ObjectNode metadata, byte[] bytes, String path) throws UnprocessableException {
        List<List<String>> records = DelimitedText.parse(decodeText(bytes, "UTF-8"),
            parameters.delimitedTextDelimiter());
        List<String> headers;
        int first;
        String configured = parameters.delimitedTextHeaders();
        boolean explicitHeaderLine = parameters.configuration().has("firstLineContainsHeaders")
            && parameters.firstLineContainsHeaders();
        if (configured != null && !configured.isBlank()) {
            headers = DelimitedText.headers(configured);
            first = explicitHeaderLine ? 1 : 0;
        } else {
            if (!parameters.firstLineContainsHeaders()) {
                throw new UnprocessableException("The blob has no header line (firstLineContainsHeaders is false) and "
                    + "the indexer names no delimitedTextHeaders.");
            }
            headers = records.isEmpty() ? List.of() : records.getFirst();
            first = 1;
        }
        List<ObjectNode> documents = new ArrayList<>();
        for (int index = first; index < records.size(); index++) {
            List<String> values = records.get(index);
            ObjectNode row = SearchJson.object();
            for (int column = 0; column < headers.size() && column < values.size(); column++) {
                if (!headers.get(column).isEmpty()) {
                    row.put(headers.get(column), values.get(column));
                }
            }
            documents.add(manyDocument(row, metadata, path, documents.size() + 1));
        }
        return CrackedItem.of(documents, true, List.of());
    }

    private CrackedItem markdown(ObjectNode metadata, byte[] bytes, String path, Detected detected) {
        String text = decodeText(bytes, "UTF-8");
        int depth = parameters.markdownHeaderDepth();
        contentMetadata(metadata, detected.format() == Format.MARKDOWN ? detected
            : new Detected(Format.MARKDOWN, Format.MARKDOWN.contentType));
        if ("oneToOne".equals(parameters.markdownParsingSubmode())) {
            return CrackedItem.of(List.of(merge(MarkdownSections.oneToOne(text, depth), metadata)),
                false, List.of());
        }
        List<ObjectNode> documents = new ArrayList<>();
        for (ObjectNode section : MarkdownSections.oneToMany(text, depth)) {
            documents.add(manyDocument(section, metadata, path, documents.size() + 1));
        }
        return CrackedItem.of(documents, true, List.of());
    }

    private ObjectNode manyDocument(ObjectNode fields, ObjectNode metadata, String path, int ordinal) {
        ObjectNode document = merge(fields, metadata);
        document.put(DOCUMENT_KEY, path + ";" + ordinal);
        return document;
    }

    /** The parsed fields with the blob's metadata added where the content does not set them. */
    private static ObjectNode merge(ObjectNode fields, ObjectNode metadata) {
        ObjectNode document = fields.deepCopy();
        metadata.fields().forEachRemaining(entry -> {
            if (!document.has(entry.getKey())) {
                document.set(entry.getKey(), entry.getValue());
            }
        });
        return document;
    }

    // ── problems ──────────────────────────────────────────────────────────────────────────────────────────────

    private CrackedItem unsupported(ObjectNode metadata, String path, String contentType) {
        String message = "Document has unsupported content type '" + contentType + "'.";
        String details = "floci-az does not extract content from PDF, Microsoft Office, OpenDocument, e-mail, archive, "
            + "image or media files, or from binary content of an unknown type.";
        if (parameters.failOnUnsupportedContentType()) {
            return CrackedItem.failed(IndexerIssue.error(path, 400, message, issueName, details, EXTRACT_ANCHOR),
                List.of());
        }
        metadata.put("metadata_content_type", contentType);
        return CrackedItem.of(List.of(metadata), false, List.of(IndexerIssue.warning(path,
            message + " Only its metadata was indexed.", issueName, details, EXTRACT_ANCHOR)));
    }

    private CrackedItem unprocessable(ObjectNode metadata, String path, String reason) {
        String message = "Could not parse document. The parsing mode '" + parameters.parsingMode()
            + "' could not read the blob.";
        if (parameters.failOnUnprocessableDocument()) {
            return CrackedItem.failed(IndexerIssue.error(path, 400, message, issueName, reason, PARSE_ANCHOR),
                List.of());
        }
        return CrackedItem.of(List.of(), false, List.of(IndexerIssue.warning(path, message + " The blob was skipped.",
            issueName, reason, PARSE_ANCHOR)));
    }

    private String truncate(String text, String path, List<IndexerIssue> warnings) {
        long max = limits.maxDocumentContentCharactersToExtract();
        if (text.length() <= max) {
            return text;
        }
        warnings.add(IndexerIssue.warning(path, "Truncated extracted text to " + max + " characters.", issueName, null,
            TRUNCATED_ANCHOR));
        return text.substring(0, (int) max);
    }

    private static void putIfPresent(ObjectNode document, String field, String value) {
        if (value != null && !value.isBlank()) {
            document.put(field, value);
        }
    }

    // ── text ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static JsonNode parseJson(String text) throws UnprocessableException {
        try {
            JsonNode parsed = SearchJson.MAPPER.readTree(text);
            if (parsed == null) {
                throw new UnprocessableException("The blob is empty.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new UnprocessableException("The blob is not valid JSON: " + e.getOriginalMessage());
        }
    }

    /** {@code bytes} as text: a byte order mark wins over {@code charsetName}, and is dropped. */
    static String decodeText(byte[] bytes, String charsetName) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, charset(charsetName));
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Whether content of an unknown type is text: valid UTF-8 (or with a BOM) and free of NUL characters. */
    private static boolean looksLikeText(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
                || (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)) {
            return true;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.indexOf('\u0000') < 0;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Content that the configured parsing mode cannot read. */
    private static final class UnprocessableException extends Exception {

        UnprocessableException(String message) {
            super(message);
        }
    }
}
