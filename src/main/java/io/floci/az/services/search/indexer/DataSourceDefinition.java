package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

/**
 * A validated data source: the normalized definition JSON, connection string included, and its ETag. Reads go
 * through {@link #representation()}, which never returns the connection string.
 *
 * @param name the data source name
 * @param type the data source type
 * @param json the normalized definition as stored, secrets included; never mutate it
 * @param etag the ETag, quoted
 */
public record DataSourceDefinition(String name, DataSourceType type, ObjectNode json, String etag) {

    static final String HIGH_WATER_MARK_POLICY = "#Microsoft.Azure.Search.HighWaterMarkChangeDetectionPolicy";
    static final String SQL_CHANGE_TRACKING_POLICY = "#Microsoft.Azure.Search.SqlIntegratedChangeTrackingPolicy";
    static final String SOFT_DELETE_POLICY = "#Microsoft.Azure.Search.SoftDeleteColumnDeletionDetectionPolicy";
    static final String NATIVE_SOFT_DELETE_POLICY = "#Microsoft.Azure.Search.NativeBlobSoftDeleteDeletionDetectionPolicy";
    static final String NONE_IDENTITY = "#Microsoft.Azure.Search.DataNoneIdentity";
    static final String USER_ASSIGNED_IDENTITY = "#Microsoft.Azure.Search.DataUserAssignedIdentity";
    private static final String ODATA_TYPE = "@odata.type";

    /**
     * A soft-delete column policy.
     *
     * @param column      the column (property, metadata name) that marks a deleted item
     * @param markerValue the value that marks it
     */
    public record SoftDeletePolicy(String column, String markerValue) {
    }

    /** The connection string, or null when none is stored ({@code <redacted>} removed it). */
    public String connectionString() {
        return SearchJson.text(json.path("credentials"), "connectionString");
    }

    /** The container (blob container, table or Cosmos DB container) name. */
    public String containerName() {
        return SearchJson.text(json.path("container"), "name");
    }

    /** The container query (a virtual folder, a table filter, a Cosmos DB query), or null. */
    public String containerQuery() {
        String query = SearchJson.text(json.path("container"), "query");
        return query == null || query.isBlank() ? null : query;
    }

    /** The column a {@code HighWaterMarkChangeDetectionPolicy} names, or null without one. */
    public String highWaterMarkColumn() {
        JsonNode policy = json.get("dataChangeDetectionPolicy");
        if (policy == null || !HIGH_WATER_MARK_POLICY.equals(SearchJson.text(policy, ODATA_TYPE))) {
            return null;
        }
        return SearchJson.text(policy, "highWaterMarkColumnName");
    }

    /** The soft-delete column policy, or null without one. */
    public SoftDeletePolicy softDeletePolicy() {
        JsonNode policy = json.get("dataDeletionDetectionPolicy");
        if (policy == null || !SOFT_DELETE_POLICY.equals(SearchJson.text(policy, ODATA_TYPE))) {
            return null;
        }
        return new SoftDeletePolicy(SearchJson.text(policy, "softDeleteColumnName"),
            SearchJson.text(policy, "softDeleteMarkerValue"));
    }

    /** Whether the data source asks for native blob soft-delete detection. */
    public boolean nativeBlobSoftDelete() {
        JsonNode policy = json.get("dataDeletionDetectionPolicy");
        return policy != null && NATIVE_SOFT_DELETE_POLICY.equals(SearchJson.text(policy, ODATA_TYPE));
    }

    /** The ARM id of the user-assigned identity the data source names, or null for the system-assigned one. */
    public String userAssignedIdentity() {
        JsonNode identity = json.get("identity");
        if (identity == null || !USER_ASSIGNED_IDENTITY.equals(SearchJson.text(identity, ODATA_TYPE))) {
            return null;
        }
        return SearchJson.text(identity, "userAssignedIdentity");
    }

    /**
     * The definition as GET returns it: {@code @odata.etag} first, the connection string as {@code null} (the
     * service never returns credentials), and an encryption key's application secret as {@code null}.
     */
    public ObjectNode representation() {
        ObjectNode body = SearchJson.object();
        body.put("@odata.etag", etag);
        ObjectNode copy = json.deepCopy();
        if (copy.get("credentials") instanceof ObjectNode credentials) {
            credentials.putNull("connectionString");
        }
        redactEncryptionKey(copy);
        body.setAll(copy);
        return body;
    }

    /** Replaces the application secret of {@code definition}'s encryption key, if any, with null. */
    static void redactEncryptionKey(ObjectNode definition) {
        if (definition.get("encryptionKey") instanceof ObjectNode key
                && key.get("accessCredentials") instanceof ObjectNode credentials
                && credentials.has("applicationSecret")) {
            credentials.putNull("applicationSecret");
        }
    }
}
