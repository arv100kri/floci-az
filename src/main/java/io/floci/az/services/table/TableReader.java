package io.floci.az.services.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Read-only, in-process access to the entities of floci-az's Table service, for emulated services that pull rows
 * from it, such as the Azure AI Search table indexer. Nothing here goes over HTTP, checks a credential or changes
 * state: the caller resolves the account it reads (see {@link io.floci.az.core.storage.StorageConnectionStrings})
 * and owns any authorization decision. Table and property names match exactly, as floci-az stores them.
 *
 * <p><b>Consistency.</b> {@link #query} takes its snapshot under the Table service's write lock, which every
 * insert, update, merge, delete, Delete Table and {@code $batch} holds for its whole check-and-write, so an entity
 * group transaction in flight is seen entirely or not at all, never half applied or half rolled back.
 *
 * <p><b>Change detection.</b> Each entity's {@code Timestamp} is set by every write, as Azure sets it, so it is the
 * high-water mark the table indexer tracks; {@link #query}'s {@code timestampAtOrAfter} applies the indexer's
 * {@code Timestamp >= HighWaterMark} filter on the parsed instant.
 */
@ApplicationScoped
public class TableReader {

    private static final Logger LOG = Logger.getLogger(TableReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> ENTITY_TYPE = new TypeReference<>() {};

    private static final String PARTITION_KEY = "PartitionKey";
    private static final String ROW_KEY = "RowKey";
    private static final String TIMESTAMP = "Timestamp";
    private static final String TYPE_ANNOTATION = "@odata.type";

    private final TableServiceHandler tableService;

    @Inject
    public TableReader(TableServiceHandler tableService) {
        this.tableService = tableService;
    }

    /** The Entity Data Model types a Table Storage property can hold. */
    public enum EdmType {
        BINARY("Edm.Binary"),
        BOOLEAN("Edm.Boolean"),
        DATE_TIME("Edm.DateTime"),
        DOUBLE("Edm.Double"),
        GUID("Edm.Guid"),
        INT32("Edm.Int32"),
        INT64("Edm.Int64"),
        STRING("Edm.String");

        private final String edmName;

        EdmType(String edmName) {
            this.edmName = edmName;
        }

        /** The type's OData name, as a {@code Property@odata.type} annotation carries it. */
        public String edmName() {
            return edmName;
        }

        static EdmType fromEdmName(String edmName) {
            for (EdmType type : values()) {
                if (type.edmName.equals(edmName)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * One property of an entity with its type. The type is the {@code @odata.type} annotation stored with the
     * value, or for an unannotated value the one Table Storage infers from its JSON form: a string is
     * {@code Edm.String}, a boolean {@code Edm.Boolean}, a number without a decimal point {@code Edm.Int32}, one
     * with a decimal point {@code Edm.Double}. A value its annotation cannot describe (floci-az stores what clients
     * send without checking it) is reported as an {@code Edm.String} of its text.
     *
     * @param value the value as Java: {@link String} for {@code STRING} and {@code GUID}, {@link Boolean},
     *              {@link Integer} for {@code INT32}, {@link Long} for {@code INT64}, {@link Double} (NaN and the
     *              infinities included), {@link Instant} for {@code DATE_TIME}, {@code byte[]} for {@code BINARY};
     *              never {@code null}, since Table Storage does not keep null properties
     */
    public record TableProperty(String name, EdmType type, Object value) {
    }

    /**
     * One entity.
     *
     * @param timestamp  when the entity was last written
     * @param etag       the entity's ETag, as the {@code ETag} header and {@code odata.etag} carry it
     * @param properties every property in order: {@code PartitionKey}, {@code RowKey} and {@code Timestamp} first,
     *                   then the entity's own properties as stored
     */
    public record TableEntity(String partitionKey, String rowKey, Instant timestamp, String etag,
                              Map<String, TableProperty> properties) {
    }

    public boolean tableExists(String accountName, String tableName) {
        return tableService.tableExists(accountName, tableName);
    }

    /** The names of the account's tables, in name order. */
    public List<String> listTables(String accountName) {
        return tableService.tableNames(accountName);
    }

    /**
     * Queries a table's entities in {@code PartitionKey}, {@code RowKey} order, as Query Entities returns them.
     *
     * @param filter             an OData {@code $filter} expression evaluated as the Query Entities {@code $filter}
     *                           is, or {@code null} for every entity. Unlike the REST {@code $filter}, which compares
     *                           {@code Timestamp} as text, {@code Timestamp} compares as the {@code Edm.DateTime} it is
     * @param timestampAtOrAfter when not {@code null}, only entities whose {@code Timestamp} is at or after it: the
     *                           table indexer's high-water-mark filter
     * @return empty when the table does not exist
     */
    public Optional<List<TableEntity>> query(String accountName, String tableName, String filter,
                                             Instant timestampAtOrAfter) {
        Optional<List<StoredObject>> stored = tableService.storedEntities(accountName, tableName);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Predicate<Map<String, Object>> matches = ODataFilter.compile(filter);
        List<TableEntity> entities = new ArrayList<>();
        for (StoredObject object : stored.get()) {
            Map<String, Object> raw = parse(object);
            if (raw == null || !matches.test(filterView(raw))) {
                continue;
            }
            TableEntity entity = entity(raw, object);
            if (timestampAtOrAfter == null || !entity.timestamp().isBefore(timestampAtOrAfter)) {
                entities.add(entity);
            }
        }
        entities.sort(Comparator.comparing(TableEntity::partitionKey).thenComparing(TableEntity::rowKey));
        return Optional.of(List.copyOf(entities));
    }

    private static Map<String, Object> parse(StoredObject object) {
        try {
            return MAPPER.readValue(object.data(), ENTITY_TYPE);
        } catch (IOException e) {
            LOG.warnv("Skipping table entity {0}: its stored JSON does not parse: {1}", object.key(), e.getMessage());
            return null;
        }
    }

    /** The entity as the filter sees it: Timestamp carries the Edm.DateTime type Table Storage declares for it. */
    private static Map<String, Object> filterView(Map<String, Object> raw) {
        if (raw.containsKey(TIMESTAMP + TYPE_ANNOTATION)) {
            return raw;
        }
        Map<String, Object> view = new LinkedHashMap<>(raw);
        view.put(TIMESTAMP + TYPE_ANNOTATION, EdmType.DATE_TIME.edmName());
        return view;
    }

    private static TableEntity entity(Map<String, Object> raw, StoredObject object) {
        String partitionKey = keyText(raw.get(PARTITION_KEY));
        String rowKey = keyText(raw.get(ROW_KEY));
        Instant timestamp = timestamp(raw.get(TIMESTAMP), object);
        Map<String, TableProperty> properties = new LinkedHashMap<>();
        properties.put(PARTITION_KEY, new TableProperty(PARTITION_KEY, EdmType.STRING, partitionKey));
        properties.put(ROW_KEY, new TableProperty(ROW_KEY, EdmType.STRING, rowKey));
        properties.put(TIMESTAMP, new TableProperty(TIMESTAMP, EdmType.DATE_TIME, timestamp));
        raw.forEach((name, value) -> {
            if (value != null && !isAnnotation(name) && !properties.containsKey(name)) {
                properties.put(name, property(name, value, raw.get(name + TYPE_ANNOTATION)));
            }
        });
        return new TableEntity(partitionKey, rowKey, timestamp, object.etag(),
                Collections.unmodifiableMap(properties));
    }

    private static String keyText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Instant timestamp(Object stored, StoredObject object) {
        if (stored instanceof String text) {
            try {
                return dateTime(text);
            } catch (DateTimeParseException e) {
                LOG.debugv("Table entity {0} has an unreadable Timestamp {1}; using its write time",
                        object.key(), text);
            }
        }
        return object.lastModified();
    }

    /** OData control information ({@code odata.etag}, {@code Name@odata.type}) rather than a property. */
    private static boolean isAnnotation(String name) {
        return name.startsWith("odata.") || name.contains("@");
    }

    private static TableProperty property(String name, Object value, Object annotation) {
        if (annotation == null) {
            return inferred(name, value);
        }
        EdmType declared = EdmType.fromEdmName(annotation.toString());
        Object converted = declared == null ? null : convert(declared, value);
        if (converted == null) {
            LOG.debugv("Table property {0} holds a value its type {1} cannot describe; reading it as Edm.String",
                    name, annotation);
            return new TableProperty(name, EdmType.STRING, text(value));
        }
        return new TableProperty(name, declared, converted);
    }

    private static TableProperty inferred(String name, Object value) {
        return switch (value) {
            case String text -> new TableProperty(name, EdmType.STRING, text);
            case Boolean flag -> new TableProperty(name, EdmType.BOOLEAN, flag);
            case Integer number -> new TableProperty(name, EdmType.INT32, number);
            case Long number -> new TableProperty(name, EdmType.INT64, number);
            case Number number -> new TableProperty(name, EdmType.DOUBLE, number.doubleValue());
            default -> new TableProperty(name, EdmType.STRING, text(value));
        };
    }

    /** The value as {@code type} describes it, or {@code null} when it cannot. */
    private static Object convert(EdmType type, Object value) {
        try {
            return switch (type) {
                case STRING, GUID -> text(value);
                case BOOLEAN -> bool(value);
                case INT32 -> value instanceof Integer number ? number : Integer.valueOf(value.toString());
                case INT64 -> value instanceof Integer || value instanceof Long
                        ? Long.valueOf(((Number) value).longValue()) : Long.valueOf(value.toString());
                case DOUBLE -> value instanceof Number number
                        ? Double.valueOf(number.doubleValue()) : Double.valueOf(value.toString());
                case DATE_TIME -> dateTime(value.toString());
                case BINARY -> Base64.getDecoder().decode(value.toString());
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = value.toString();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.valueOf(text);
        }
        return null;
    }

    /** An Edm.DateTime, which Table Storage keeps in UTC: a value without an offset is read as UTC. */
    private static Instant dateTime(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
    }

    private static String text(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
