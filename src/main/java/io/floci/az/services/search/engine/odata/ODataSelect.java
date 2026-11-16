package io.floci.az.services.search.engine.odata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@code $select} list bound to an index, and the projection of stored documents through it. {@code *} or an empty
 * list selects every retrievable field; otherwise each entry is a field path ({@code HotelName},
 * {@code Address/City}) of a retrievable field. Results keep the index's field order; a selected field the document
 * lacks is {@code null} ({@code []} for a collection), and non-retrievable subfields of a selected complex field are
 * left out.
 */
public final class ODataSelect {

    private final SearchIndexDefinition definition;
    private final Set<String> selectedPaths;
    private final boolean all;

    private ODataSelect(SearchIndexDefinition definition, Set<String> selectedPaths, boolean all) {
        this.definition = definition;
        this.selectedPaths = selectedPaths;
        this.all = all;
    }

    /** Every retrievable field. */
    public static ODataSelect all(SearchIndexDefinition definition) {
        return new ODataSelect(definition, Set.of(), true);
    }

    /** Binds {@code entries} (already split at commas); a 400 names an unknown or non-retrievable field. */
    public static ODataSelect parse(List<String> entries, SearchIndexDefinition definition, String parameter) {
        List<String> paths = new ArrayList<>();
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        if (paths.isEmpty() || paths.contains("*")) {
            return all(definition);
        }
        Set<String> selected = new HashSet<>();
        for (String path : paths) {
            SearchFieldDefinition field = definition.field(path).orElseThrow(() -> SearchException.badRequest(
                "Invalid expression: Could not find a property named '" + path + "' on type 'search.document'."
                    + "\r\nParameter name: " + parameter));
            if (!hasRetrievableLeaf(field)) {
                throw SearchException.badRequest("Invalid expression: The field '" + path + "' is not retrievable. "
                    + "Only retrievable fields can be selected.\r\nParameter name: " + parameter);
            }
            selected.add(path);
        }
        return new ODataSelect(definition, selected, false);
    }

    private static boolean hasRetrievableLeaf(SearchFieldDefinition field) {
        if (!field.isComplex()) {
            return field.retrievable();
        }
        return field.fields().stream().anyMatch(ODataSelect::hasRetrievableLeaf);
    }

    /** The projection of {@code source}, a stored document. */
    public ObjectNode project(ObjectNode source) {
        ObjectNode result = SearchJson.object();
        projectLevel(source, definition.fields(), result, all);
        return result;
    }

    private void projectLevel(JsonNode source, List<SearchFieldDefinition> fields, ObjectNode target,
                              boolean parentSelected) {
        for (SearchFieldDefinition field : fields) {
            boolean selected = parentSelected || selectedPaths.contains(field.path());
            if (!selected && !hasSelectedDescendant(field)) {
                continue;
            }
            JsonNode value = source == null ? null : source.get(field.name());
            if (field.isComplex()) {
                target.set(field.name(), projectComplex(value, field, selected));
            } else if (selected && field.retrievable()) {
                target.set(field.name(), value == null ? missing(field) : value.deepCopy());
            }
        }
    }

    private JsonNode projectComplex(JsonNode value, SearchFieldDefinition field, boolean selected) {
        if (value == null || value.isNull()) {
            return field.type().collection() ? SearchJson.NODES.arrayNode() : NullNode.getInstance();
        }
        if (value.isArray()) {
            ArrayNode elements = SearchJson.NODES.arrayNode();
            for (JsonNode element : value) {
                ObjectNode projected = SearchJson.object();
                projectLevel(element, field.fields(), projected, selected);
                elements.add(projected);
            }
            return elements;
        }
        ObjectNode projected = SearchJson.object();
        projectLevel(value, field.fields(), projected, selected);
        return projected;
    }

    private boolean hasSelectedDescendant(SearchFieldDefinition field) {
        for (SearchFieldDefinition child : field.fields()) {
            if (selectedPaths.contains(child.path()) || hasSelectedDescendant(child)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode missing(SearchFieldDefinition field) {
        return field.type().collection() ? SearchJson.NODES.arrayNode() : NullNode.getInstance();
    }
}
