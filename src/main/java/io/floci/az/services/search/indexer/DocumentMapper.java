package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.indexer.MappingFunctions.MappingException;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turns a cracked source document into the fields of a search document, as Learn's "Field mappings and
 * transformations" describes:
 * <ul>
 *   <li>explicit field mappings first: the source is a name or a JSON Pointer, a missing source is skipped without
 *       an error, a mapping function transforms the value, and names match ignoring case;</li>
 *   <li>then the implicit mappings: every source field whose name matches an index field ignoring case, unless an
 *       explicit mapping names that source field (by name, not by a path into it) or that target field;</li>
 *   <li>the key: from a mapping as above, else the blob indexers' default, the Base64-encoded
 *       {@code metadata_storage_path} (or {@code AzureSearch_DocumentKey} for one-to-many parsing modes). A standard
 *       {@code metadata_storage_*} property that reaches the key by name is encoded too ("the indexer automatically
 *       encodes the key metadata property"), and {@code base64EncodeKeys} encodes any key not already encoded.</li>
 * </ul>
 * Values are then converted toward their field types: a string that looks like a date (an ISO 8601 date, date and
 * time, or date and time with an offset) becomes an {@code Edm.DateTimeOffset}, read as UTC without an offset;
 * everything else goes through the push API's conversions.
 */
final class DocumentMapper {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9=\\-][A-Za-z0-9_=\\-]{0,1023}");
    private static final String KEY_ANCHOR = "#error-could-not-parse-document";
    private static final String MAPPING_ANCHOR = "#error-could-not-parse-document";
    private static final String INVALID_DATA_ANCHOR =
        "#error-could-not-index-document-because-some-of-the-documents-data-was-not-valid";

    private final SearchIndexDefinition index;
    private final List<FieldMapping> mappings;
    private final boolean blobSource;
    private final boolean base64EncodeKeys;
    private final Map<String, SearchFieldDefinition> fields = new HashMap<>();
    private final Set<String> explicitSources = new HashSet<>();
    private final Set<String> explicitTargets = new HashSet<>();

    /**
     * A mapped document.
     *
     * @param key        the document key
     * @param fields     the index fields, by their exact names, before conversion
     * @param enrichment the enrichment tree's {@code /document}: the source fields, the mapping targets and the key
     */
    record Mapped(String key, ObjectNode fields, ObjectNode enrichment) {
    }

    DocumentMapper(SearchIndexDefinition index, List<FieldMapping> mappings, boolean blobSource,
                   boolean base64EncodeKeys) {
        this.index = index;
        this.mappings = List.copyOf(mappings);
        this.blobSource = blobSource;
        this.base64EncodeKeys = base64EncodeKeys;
        for (SearchFieldDefinition field : index.fields()) {
            fields.put(IndexerJson.lower(field.name()), field);
        }
        for (FieldMapping mapping : mappings) {
            String source = mapping.sourceFieldName();
            boolean wholeField = !source.startsWith("/") || source.indexOf('/', 1) < 0;
            if (wholeField) {
                explicitSources.add(IndexerJson.lower(mapping.sourceRoot()));
            }
            explicitTargets.add(IndexerJson.lower(mapping.effectiveTarget()));
        }
    }

    /** The index field a field mapping writes, or null when the index has no such top-level, non-complex field. */
    SearchFieldDefinition targetOf(FieldMapping mapping) {
        SearchFieldDefinition field = fields.get(IndexerJson.lower(mapping.effectiveTarget()));
        return field == null || field.isComplex() ? null : field;
    }

    /** Maps one source document. */
    Mapped map(ObjectNode source) throws ItemFailure {
        ObjectNode mapped = SearchJson.object();
        ObjectNode enrichment = source.deepCopy();
        SearchFieldDefinition keyField = index.keyField();
        boolean keyEncoded = false;
        for (FieldMapping mapping : mappings) {
            JsonNode value = mapping.sourceFieldName().startsWith("/")
                ? IndexerJson.pointer(source, mapping.sourceFieldName())
                : IndexerJson.property(source, mapping.sourceFieldName());
            SearchFieldDefinition field = targetOf(mapping);
            if (value == null || field == null) {
                continue;
            }
            if (mapping.function() != null && !value.isNull()) {
                try {
                    value = MappingFunctions.apply(mapping.function(), value);
                } catch (MappingException e) {
                    throw new ItemFailure(IndexerIssue.error(keyHint(source), 400, "Could not apply mapping function '"
                        + mapping.function().name() + "' to field '" + mapping.sourceFieldName() + "'.",
                        "FieldMapping." + field.name(), e.getMessage(), MAPPING_ANCHOR));
                }
                keyEncoded |= field == keyField && MappingFunctions.producesKeySafeValue(mapping.function());
            }
            mapped.set(field.name(), value);
            enrichment.set(mapping.effectiveTarget(), value);
        }
        Iterator<Map.Entry<String, JsonNode>> sourceFields = source.fields();
        while (sourceFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = sourceFields.next();
            String name = IndexerJson.lower(entry.getKey());
            SearchFieldDefinition field = fields.get(name);
            if (field == null || explicitSources.contains(name) || explicitTargets.contains(name)
                    || mapped.has(field.name())) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (field == keyField && blobSource && name.startsWith("metadata_storage_") && value.isValueNode()) {
                value = SearchJson.NODES.textNode(MappingFunctions.base64Encode(value.asText(), true));
                keyEncoded = true;
            }
            mapped.set(field.name(), value);
        }
        JsonNode keyValue = mapped.get(keyField.name());
        if (IndexerJson.isNull(keyValue) && blobSource
                && !explicitTargets.contains(IndexerJson.lower(keyField.name()))) {
            String raw = SearchJson.text(source, BlobCracker.DOCUMENT_KEY);
            if (raw == null) {
                raw = SearchJson.text(source, "metadata_storage_path");
            }
            if (raw != null) {
                keyValue = SearchJson.NODES.textNode(MappingFunctions.base64Encode(raw, true));
                keyEncoded = true;
            }
        }
        if (IndexerJson.isNull(keyValue) || keyValue.isContainerNode() || keyValue.asText().isEmpty()) {
            throw new ItemFailure(IndexerIssue.error("", 400, "Document key cannot be missing or empty.", null, null,
                KEY_ANCHOR));
        }
        String key = keyValue.asText();
        if (base64EncodeKeys && !keyEncoded) {
            key = MappingFunctions.base64Encode(key, true);
        }
        validateKey(key);
        mapped.put(keyField.name(), key);
        enrichment.put(keyField.name(), key);
        return new Mapped(key, mapped, enrichment);
    }

    private static void validateKey(String key) throws ItemFailure {
        if (key.length() > 1024) {
            throw new ItemFailure(IndexerIssue.error(key.substring(0, 32) + "...", 400, "Document key cannot be "
                + "longer than 1024 characters.", null, null, KEY_ANCHOR));
        }
        String error = keyError(key);
        if (error != null) {
            throw new ItemFailure(IndexerIssue.error(key, 400, error, null, "Map the key from a field with a "
                + "base64Encode mapping function to encode characters keys cannot hold.", KEY_ANCHOR));
        }
    }

    /** Why {@code key} is not a valid document key, or null when it is. */
    static String keyError(String key) {
        if (key.length() > 1024) {
            return "Document key cannot be longer than 1024 characters.";
        }
        if (!KEY.matcher(key).matches()) {
            return "Invalid document key: '" + key + "'. Keys can only contain letters, digits, underscore (_), dash "
                + "(-), or equal sign (=).";
        }
        return null;
    }

    private static String keyHint(ObjectNode source) {
        String path = SearchJson.text(source, "metadata_storage_path");
        return path != null ? path : SearchJson.text(source, "Key") != null ? SearchJson.text(source, "Key")
            : SearchJson.text(source, "id");
    }

    /**
     * The search document for mapped fields: values converted toward their field types, nulls left out (an indexer
     * never clears a field with a null), then checked as the push API checks a document.
     */
    ObjectNode toSearchDocument(String key, ObjectNode mappedFields, String indexName) throws ItemFailure {
        return toSearchDocument(index, key, mappedFields, indexName);
    }

    /** {@link #toSearchDocument(String, ObjectNode, String)} for any index, such as an index projection's. */
    static ObjectNode toSearchDocument(SearchIndexDefinition definition, String key, ObjectNode mappedFields,
                                       String indexName) throws ItemFailure {
        ObjectNode document = SearchJson.object();
        Iterator<Map.Entry<String, JsonNode>> entries = mappedFields.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (IndexerJson.isNull(entry.getValue())) {
                continue;
            }
            SearchFieldDefinition field = definition.field(entry.getKey()).orElse(null);
            if (field == null) {
                continue;
            }
            document.set(field.name(), convert(entry.getValue(), field));
        }
        try {
            return DocumentCoercer.coerce(document, definition);
        } catch (SearchException e) {
            throw new ItemFailure(IndexerIssue.error(key, 400, "Could not index document because some of the "
                + "document's data was not valid.", "Projection.SearchIndex.MergeOrUpload." + indexName,
                e.getMessage(), INVALID_DATA_ANCHOR));
        }
    }

    private static JsonNode convert(JsonNode value, SearchFieldDefinition field) {
        if (!field.type().collection()) {
            return convertScalar(value, field.type().element());
        }
        if (!(value instanceof ArrayNode array)) {
            return value;
        }
        ArrayNode converted = SearchJson.NODES.arrayNode();
        for (JsonNode element : array) {
            converted.add(convertScalar(element, field.type().element()));
        }
        return converted;
    }

    private static JsonNode convertScalar(JsonNode value, EdmType type) {
        if (type == EdmType.DATE_TIME_OFFSET && value.isTextual()) {
            Instant instant = parseDate(value.textValue());
            return instant == null ? value : SearchJson.NODES.textNode(DocumentCoercer.formatDateTime(instant));
        }
        return value;
    }

    /** A string that looks like a date: with an offset, a local date and time (UTC), or a date (midnight UTC). */
    static Instant parseDate(String text) {
        String trimmed = text.trim();
        Instant parsed = DocumentCoercer.parseDateTime(trimmed);
        if (parsed != null) {
            return parsed;
        }
        String isoSeparated = trimmed.length() > 10 && trimmed.charAt(10) == ' '
            ? trimmed.substring(0, 10) + "T" + trimmed.substring(11) : trimmed;
        parsed = tryParse(() -> OffsetDateTime.parse(isoSeparated).toInstant());
        if (parsed == null) {
            parsed = tryParse(() -> LocalDateTime.parse(isoSeparated).toInstant(ZoneOffset.UTC));
        }
        if (parsed == null) {
            parsed = tryParse(() -> LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        return parsed;
    }

    private static Instant tryParse(Supplier<Instant> parser) {
        try {
            return parser.get();
        } catch (DateTimeParseException ignored) {
            // Not this form of date; the caller tries the next one.
            return null;
        }
    }
}
