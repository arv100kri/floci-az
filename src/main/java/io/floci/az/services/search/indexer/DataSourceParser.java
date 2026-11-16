package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchJson;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and normalizes a data source definition ({@code SearchIndexerDataSource}) the way Create and Create or
 * Update Data Source store it:
 * <ul>
 *   <li>{@code name}, {@code type} and {@code container.name} are required; the name follows the documented rule (1 to
 *       128 lowercase letters, digits or dashes, starting and ending with a letter or digit);</li>
 *   <li>every documented type is accepted, including the ones floci-az cannot read (their indexer runs fail);</li>
 *   <li>{@code credentials.connectionString} is required on create. On update {@code <unchanged>}, or no value at
 *       all, keeps the stored string, and {@code <redacted>} removes it;</li>
 *   <li>{@code identity} and {@code encryptionKey} keep their stored values when an update leaves them out or sets
 *       them to null ("the value remains unchanged", "the search service will ignore attempts to set this property
 *       to null");</li>
 *   <li>the change and deletion detection policies and identity must name a documented {@code @odata.type}.</li>
 * </ul>
 * Properties the parser does not know are kept as sent.
 */
public final class DataSourceParser {

    /** The sentinel that keeps the stored connection string. */
    static final String UNCHANGED = "<unchanged>";
    /** The sentinel that removes the stored connection string. */
    static final String REDACTED = "<redacted>";

    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?");
    private static final Set<String> KNOWN = Set.of("name", "description", "type", "subtype", "credentials",
        "container", "dataChangeDetectionPolicy", "dataDeletionDetectionPolicy", "encryptionKey", "identity");

    private DataSourceParser() {
    }

    /** Whether {@code name} follows the naming rule for data sources, indexers and skillsets. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Parses a request body.
     *
     * @param body      the request body
     * @param nameInUrl the name the URL names, or null for a create by POST
     * @param previous  the stored definition being replaced, or null for a create
     * @return the definition to store, without an ETag
     */
    public static DataSourceDefinition parse(JsonNode body, String nameInUrl, DataSourceDefinition previous) {
        if (!(body instanceof ObjectNode input)) {
            throw invalid("the data source definition must be a JSON object.");
        }
        String name = resolveName(input, nameInUrl);
        String typeName = SearchJson.text(input, "type");
        if (typeName == null || typeName.isBlank()) {
            throw invalid("the data source type is required.");
        }
        DataSourceType type = DataSourceType.parse(typeName).orElseThrow(() -> invalid("Data source type '"
            + typeName + "' is not supported. Supported types are azureblob, adlsgen2, azuretable, cosmosdb, "
            + "azuresql, mysql, onelake, sharepoint and azurefile."));

        ObjectNode output = SearchJson.object();
        output.put("name", name);
        output.set("description", nullToNullNode(input.get("description")));
        output.put("type", type.wireName());
        if (input.has("subtype")) {
            output.set("subtype", input.get("subtype"));
        }
        ObjectNode credentials = output.putObject("credentials");
        String connectionString = connectionString(input.get("credentials"), previous);
        if (connectionString == null) {
            credentials.putNull("connectionString");
        } else {
            credentials.put("connectionString", connectionString);
        }
        output.set("container", container(input.get("container")));
        output.set("dataChangeDetectionPolicy", changePolicy(input.get("dataChangeDetectionPolicy")));
        output.set("dataDeletionDetectionPolicy", deletionPolicy(input.get("dataDeletionDetectionPolicy")));
        output.set("encryptionKey", kept(input.get("encryptionKey"), previous, "encryptionKey"));
        output.set("identity", identity(kept(input.get("identity"), previous, "identity")));
        Iterator<Map.Entry<String, JsonNode>> extras = input.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!KNOWN.contains(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                output.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }
        return new DataSourceDefinition(name, type, output, null);
    }

    private static String resolveName(ObjectNode input, String nameInUrl) {
        String name = SearchJson.text(input, "name");
        if (name == null || name.isBlank()) {
            if (nameInUrl == null) {
                throw invalid("the data source name is required.");
            }
            name = nameInUrl;
        } else if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the data source name '" + name + "' in the request body does not match the name '"
                + nameInUrl + "' in the request URL.");
        }
        if (!isValidName(name)) {
            throw invalid("Data source name '" + name + "' is invalid. Data source names must be 1 to 128 "
                + "characters long, contain only lowercase letters, digits or dashes, and start and end with a "
                + "letter or digit.");
        }
        return name;
    }

    private static String connectionString(JsonNode credentials, DataSourceDefinition previous) {
        String stored = previous == null ? null : previous.connectionString();
        if (credentials != null && !credentials.isNull() && !credentials.isObject()) {
            throw invalid("credentials must be a JSON object.");
        }
        JsonNode value = credentials == null ? null : credentials.get("connectionString");
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw invalid("credentials.connectionString must be a string.");
        }
        String text = value == null || value.isNull() ? null : value.textValue();
        if (REDACTED.equals(text)) {
            return null;
        }
        if (text == null || text.isBlank() || UNCHANGED.equals(text)) {
            if (previous == null) {
                throw invalid("credentials.connectionString is required: the data source needs a connection string "
                    + "to its data.");
            }
            return stored;
        }
        return text;
    }

    private static ObjectNode container(JsonNode node) {
        if (!(node instanceof ObjectNode input)) {
            throw invalid("the data source container is required: give its name, and optionally a query.");
        }
        String name = SearchJson.text(input, "name");
        if (name == null || name.isBlank()) {
            throw invalid("container.name is required.");
        }
        JsonNode query = input.get("query");
        if (query != null && !query.isNull() && !query.isTextual()) {
            throw invalid("container.query must be a string.");
        }
        ObjectNode container = input.deepCopy();
        container.put("name", name);
        container.set("query", nullToNullNode(query));
        return container;
    }

    private static JsonNode changePolicy(JsonNode node) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.nullNode();
        }
        ObjectNode policy = requireObject(node, "dataChangeDetectionPolicy");
        String type = SearchJson.text(policy, "@odata.type");
        if (DataSourceDefinition.HIGH_WATER_MARK_POLICY.equals(type)) {
            requireText(policy, "highWaterMarkColumnName", "dataChangeDetectionPolicy");
        } else if (!DataSourceDefinition.SQL_CHANGE_TRACKING_POLICY.equals(type)) {
            throw invalid("dataChangeDetectionPolicy has the unknown @odata.type '" + type + "'. Use "
                + DataSourceDefinition.HIGH_WATER_MARK_POLICY + " or " + DataSourceDefinition.SQL_CHANGE_TRACKING_POLICY
                + ".");
        }
        return policy.deepCopy();
    }

    private static JsonNode deletionPolicy(JsonNode node) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.nullNode();
        }
        ObjectNode policy = requireObject(node, "dataDeletionDetectionPolicy");
        String type = SearchJson.text(policy, "@odata.type");
        if (DataSourceDefinition.SOFT_DELETE_POLICY.equals(type)) {
            requireText(policy, "softDeleteColumnName", "dataDeletionDetectionPolicy");
            JsonNode marker = policy.get("softDeleteMarkerValue");
            if (IndexerJson.isNull(marker) || !marker.isValueNode() || marker.asText().isEmpty()) {
                throw invalid("dataDeletionDetectionPolicy.softDeleteMarkerValue is required.");
            }
        } else if (!DataSourceDefinition.NATIVE_SOFT_DELETE_POLICY.equals(type)) {
            throw invalid("dataDeletionDetectionPolicy has the unknown @odata.type '" + type + "'. Use "
                + DataSourceDefinition.SOFT_DELETE_POLICY + " or " + DataSourceDefinition.NATIVE_SOFT_DELETE_POLICY
                + ".");
        }
        return policy.deepCopy();
    }

    private static JsonNode identity(JsonNode node) {
        if (IndexerJson.isNull(node)) {
            return SearchJson.NODES.nullNode();
        }
        ObjectNode identity = requireObject(node, "identity");
        String type = SearchJson.text(identity, "@odata.type");
        if (DataSourceDefinition.USER_ASSIGNED_IDENTITY.equals(type)) {
            requireText(identity, "userAssignedIdentity", "identity");
        } else if (!DataSourceDefinition.NONE_IDENTITY.equals(type)) {
            throw invalid("identity has the unknown @odata.type '" + type + "'. Use "
                + DataSourceDefinition.USER_ASSIGNED_IDENTITY + " or " + DataSourceDefinition.NONE_IDENTITY + ".");
        }
        return identity.deepCopy();
    }

    /** {@code requested}, or the stored value of {@code property} when the request leaves it out or sets null. */
    private static JsonNode kept(JsonNode requested, DataSourceDefinition previous, String property) {
        if (!IndexerJson.isNull(requested)) {
            if (!requested.isObject()) {
                throw invalid(property + " must be a JSON object.");
            }
            return requested.deepCopy();
        }
        JsonNode stored = previous == null ? null : previous.json().get(property);
        return IndexerJson.isNull(stored) ? SearchJson.NODES.nullNode() : stored.deepCopy();
    }

    private static ObjectNode requireObject(JsonNode node, String property) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(property + " must be a JSON object.");
        }
        return object;
    }

    private static void requireText(ObjectNode node, String field, String owner) {
        String value = SearchJson.text(node, field);
        if (value == null || value.isBlank()) {
            throw invalid(owner + "." + field + " is required.");
        }
    }

    private static JsonNode nullToNullNode(JsonNode node) {
        return node == null ? SearchJson.NODES.nullNode() : node.deepCopy();
    }

    static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: dataSource : " + detail);
    }
}
