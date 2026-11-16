package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the document-level permission settings of an index definition, as the REST API (api-versions
 * 2025-05-01-preview and later previews, and 2026-10-01) and "Indexing ACLs using the push REST API" define them:
 *
 * <ul>
 *   <li>{@code permissionFilterOption} is {@code enabled}, {@code disabled} or null;</li>
 *   <li>a field's {@code permissionFilter} is {@code userIds}, {@code groupIds} or {@code rbacScope}, on a filterable
 *       {@code Edm.String} or {@code Collection(Edm.String)} field ("Permission metadata must be in filterable string
 *       fields");</li>
 *   <li>"Only one field of each permissionFilter type ... can exist in an index".</li>
 * </ul>
 *
 * <p>Two rules are floci-az's own: a permission filter field must be a top-level field, which is where the
 * documentation and its samples put them; and the OneLake filter types of 2026-11-01-preview, which need a OneLake
 * security source floci-az does not model, are answered as not implemented. The values are kept as sent, so the
 * definition round-trips.</p>
 */
public final class PermissionFilterValidator {

    /** The index property that turns permission filtering on or off. */
    public static final String OPTION = "permissionFilterOption";

    /** The field attribute that makes a field a permission filter field. */
    public static final String ATTRIBUTE = "permissionFilter";

    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";
    public static final String USER_IDS = "userIds";
    public static final String GROUP_IDS = "groupIds";
    public static final String RBAC_SCOPE = "rbacScope";

    private static final List<String> TYPES = List.of(USER_IDS, GROUP_IDS, RBAC_SCOPE);
    private static final List<String> ONE_LAKE_TYPES = List.of("oneLakeParentFolders", "oneLakeLakehouseId");

    private PermissionFilterValidator() {
    }

    /**
     * Validates the permission settings of a normalised index definition.
     *
     * @param index  the definition as GET will return it
     * @param fields its top-level fields
     */
    public static void validate(ObjectNode index, List<SearchFieldDefinition> fields) {
        JsonNode option = index.get(OPTION);
        if (option != null && !option.isNull() && !(option.isTextual()
                && (ENABLED.equalsIgnoreCase(option.textValue()) || DISABLED.equalsIgnoreCase(option.textValue())))) {
            throw invalid("'" + OPTION + "' must be '" + ENABLED + "' or '" + DISABLED + "'.");
        }
        validateFields(fields, new HashMap<>());
    }

    /** The {@code permissionFilter} of {@code field} as the definition spells it, or null when it has none. */
    public static String permissionFilter(SearchFieldDefinition field) {
        JsonNode value = field.json().get(ATTRIBUTE);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static void validateFields(List<SearchFieldDefinition> fields, Map<String, String> fieldsByType) {
        for (SearchFieldDefinition field : fields) {
            validateField(field, fieldsByType);
            validateFields(field.fields(), fieldsByType);
        }
    }

    private static void validateField(SearchFieldDefinition field, Map<String, String> fieldsByType) {
        JsonNode value = field.json().get(ATTRIBUTE);
        if (value == null || value.isNull()) {
            return;
        }
        String path = field.path();
        if (!value.isTextual()) {
            throw invalid("the attribute '" + ATTRIBUTE + "' of the field '" + path + "' must be a string.");
        }
        String type = canonicalType(value.textValue(), path);
        if (path.contains("/")) {
            throw invalid("the field '" + path + "' sets '" + ATTRIBUTE + "'; only top-level fields can be "
                + "permission filter fields.");
        }
        if (field.isComplex() || field.type().element() != EdmType.STRING) {
            throw invalid("the permission filter field '" + path + "' has type " + field.type().edmName()
                + "; permission filter fields must be of type Edm.String or Collection(Edm.String).");
        }
        if (!field.filterable()) {
            throw invalid("the permission filter field '" + path + "' must be filterable.");
        }
        String previous = fieldsByType.put(type, path);
        if (previous != null) {
            throw invalid("the fields '" + previous + "' and '" + path + "' both set '" + ATTRIBUTE + "' to '" + type
                + "'; an index can have only one field of each permission filter type.");
        }
    }

    private static String canonicalType(String value, String path) {
        for (String type : TYPES) {
            if (type.equalsIgnoreCase(value)) {
                return type;
            }
        }
        for (String type : ONE_LAKE_TYPES) {
            if (type.equalsIgnoreCase(value)) {
                throw SearchException.notImplemented("The OneLake permission filter '" + type + "' (field '" + path
                    + "')");
            }
        }
        throw invalid("the field '" + path + "' has the " + ATTRIBUTE + " '" + value + "'; the supported values are "
            + "'" + USER_IDS + "', '" + GROUP_IDS + "' and '" + RBAC_SCOPE + "'.");
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
