package io.floci.az.services.search.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.PermissionFilterValidator;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Document-level permission filters at query time, as "Query-time ACL and RBAC enforcement" and "Indexing ACLs using
 * the push REST API" describe them, for an index whose {@code permissionFilterOption} is {@code enabled}.
 *
 * <ul>
 *   <li>A document is visible when one of its permission filter fields grants the reader: a {@code userIds} value
 *       that is the end user's object id, a {@code groupIds} value that is one of its groups, or {@code all} in
 *       either, which "grants public access regardless of any other ACL field values". {@code none} and an empty or
 *       missing value grant nothing through that field. "Each filter is applied independently and a document is
 *       considered authorized if any filter succeeds."</li>
 *   <li>Values compare as a filter on the field compares them: exactly, or through the field's normalizer.</li>
 *   <li>A read with no end user ({@code x-ms-query-source-authorization} omitted) sees only the documents granted to
 *       {@code all}: "If the user token is omitted, ACL-protected content isn't returned", whatever the caller's
 *       key or role. An elevated read sees every document.</li>
 *   <li>{@code rbacScope} is not evaluated: floci-az's storage has no ADLS Gen2 access-control model to resolve it
 *       against. An {@code rbacScope} field is stored and returned, and adds no clause, so an index whose only
 *       permission filter field is {@code rbacScope} is not trimmed, and a document Azure would show only through its
 *       {@code rbacScope} is hidden when the index also has {@code userIds} or {@code groupIds}.</li>
 * </ul>
 *
 * <p>Search runs this through {@code PermissionTrimmingStage}, which folds the visible documents into the filter
 * matches; lookup, count, suggest and autocomplete call the hooks here. Every method reads
 * {@link DocumentAccess#current()} unless it takes an access.</p>
 */
public final class DocumentPermissions {

    /** The special value that grants every reader. */
    public static final String ALL = "all";

    /** The special value that grants no reader. */
    public static final String NONE = "none";

    private static final String DELIMITER = "|";

    private DocumentPermissions() {
    }

    /**
     * The {@code userIds} and {@code groupIds} fields of {@code definition} when it enables permission filtering: the
     * fields whose values grant access. Empty when reads of the index are not trimmed.
     */
    public static List<SearchFieldDefinition> grantingFields(SearchIndexDefinition definition) {
        JsonNode option = definition.json().get(PermissionFilterValidator.OPTION);
        if (option == null || !option.isTextual()
                || !PermissionFilterValidator.ENABLED.equalsIgnoreCase(option.textValue())) {
            return List.of();
        }
        List<SearchFieldDefinition> fields = new ArrayList<>();
        for (SearchFieldDefinition field : definition.fields()) {
            String type = PermissionFilterValidator.permissionFilter(field);
            if (PermissionFilterValidator.USER_IDS.equalsIgnoreCase(type)
                    || PermissionFilterValidator.GROUP_IDS.equalsIgnoreCase(type)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Whether a read of {@code index} by {@code access} is trimmed. */
    public static boolean trims(LiveIndex index, DocumentAccess access) {
        return !access.unrestricted() && !grantingFields(index.definition()).isEmpty();
    }

    /**
     * The documents of {@code index} {@code access} may see, or null when its reads are not trimmed. Read lock only.
     */
    public static BitSet visibleDocuments(LiveIndex index, DocumentAccess access) {
        if (!trims(index, access)) {
            return null;
        }
        Grants grants = Grants.of(index, access);
        BitSet visible = new BitSet(index.ordinalLimit());
        for (int ordinal = 0; ordinal < index.ordinalLimit(); ordinal++) {
            IndexedDocument document = index.document(ordinal);
            if (document != null && grants.admit(document)) {
                visible.set(ordinal);
            }
        }
        return visible;
    }

    /** Lookup's hook: the document with {@code key} when the current reader may see it, else null. Read lock only. */
    public static IndexedDocument visibleDocument(LiveIndex index, String key) {
        IndexedDocument document = index.document(key);
        DocumentAccess access = DocumentAccess.current();
        if (document == null || !trims(index, access)) {
            return document;
        }
        return Grants.of(index, access).admit(document) ? document : null;
    }

    /** Count's hook: the number of documents in {@code index} the current reader may see. */
    public static long count(LiveIndex index) {
        DocumentAccess access = DocumentAccess.current();
        if (!trims(index, access)) {
            return index.documentCount();
        }
        return index.read(live -> visibleDocuments(live, access).cardinality());
    }

    /**
     * Suggest's and autocomplete's hook: {@code parameters} with the permission filter ANDed into their
     * {@code filter}, the way Azure appends "security filters ... to any filters that might come in with the query",
     * so the suggestions come from documents the current reader may see. The parameters themselves when the read is
     * not trimmed.
     */
    public static ObjectNode restrict(LiveIndex index, ObjectNode parameters) {
        DocumentAccess access = DocumentAccess.current();
        if (!trims(index, access)) {
            return parameters;
        }
        String security = securityFilter(grantingFields(index.definition()), access);
        JsonNode filter = parameters.get("filter");
        ObjectNode restricted = parameters.deepCopy();
        restricted.put("filter", filter != null && filter.isTextual() && !filter.textValue().isBlank()
            ? "(" + filter.textValue() + ") and (" + security + ")" : security);
        return restricted;
    }

    /** The OData filter that admits exactly the documents {@code access} may see through {@code fields}. */
    static String securityFilter(List<SearchFieldDefinition> fields, DocumentAccess access) {
        Set<String> values = new LinkedHashSet<>();
        values.add(ALL);
        for (String id : access.principalIds()) {
            if (!id.contains(DELIMITER) && !NONE.equalsIgnoreCase(id)) {
                values.add(id);
            }
        }
        String literal = "'" + String.join(DELIMITER, values).replace("'", "''") + "'";
        List<String> clauses = new ArrayList<>();
        for (SearchFieldDefinition field : fields) {
            clauses.add(field.type().collection()
                ? field.path() + "/any(p: search.in(p, " + literal + ", '" + DELIMITER + "'))"
                : "search.in(" + field.path() + ", " + literal + ", '" + DELIMITER + "')");
        }
        return String.join(" or ", clauses);
    }

    /** The granting fields of an index with the values that admit a document through each. */
    private record Grants(List<GrantingField> fields) {

        static Grants of(LiveIndex index, DocumentAccess access) {
            List<GrantingField> fields = new ArrayList<>();
            for (SearchFieldDefinition field : grantingFields(index.definition())) {
                UnaryOperator<String> normalize = index.analysis().normalizer(field)
                    .<UnaryOperator<String>>map(normalizer -> normalizer::normalize)
                    .orElse(UnaryOperator.identity());
                Set<String> accepted = new HashSet<>();
                accepted.add(normalize.apply(ALL));
                for (String id : access.principalIds()) {
                    if (!NONE.equalsIgnoreCase(id)) {
                        accepted.add(normalize.apply(id));
                    }
                }
                fields.add(new GrantingField(field, normalize, accepted));
            }
            return new Grants(fields);
        }

        boolean admit(IndexedDocument document) {
            for (GrantingField granting : fields) {
                for (String value : DocumentValues.textValues(document.source(), granting.field())) {
                    if (granting.accepted().contains(granting.normalize().apply(value))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * One granting field.
     *
     * @param field     the {@code userIds} or {@code groupIds} field
     * @param normalize the field's normalizer, as its filters apply it; the identity when it has none
     * @param accepted  the normalized values that admit a document: {@code all} and the reader's principal ids
     */
    private record GrantingField(SearchFieldDefinition field, UnaryOperator<String> normalize, Set<String> accepted) {
    }
}
