package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of the enrichment tree: a value (a source field, a skill output, an array element) plus the annotations
 * skills added beneath it. As the annotation language documents, a primitive value can carry annotations
 * ({@code /document/content/language}), and annotations are addressed individually: they are not part of the value a
 * path to the node selects. Object properties and array elements become child nodes on first use, so that skills can
 * annotate them ({@code /document/pages/0/vector}).
 */
final class EnrichmentNode {

    private final String path;
    private final JsonNode value;
    private final Map<String, EnrichmentNode> annotations = new LinkedHashMap<>();
    private final Map<String, EnrichmentNode> properties = new HashMap<>();
    private List<EnrichmentNode> elements;

    EnrichmentNode(String path, JsonNode value) {
        this.path = path;
        this.value = value;
    }

    /** The concrete path of this node, such as {@code /document/pages/3}. */
    String path() {
        return path;
    }

    /** The node's own value, without its annotations; null only for a node created without one. */
    JsonNode value() {
        return value;
    }

    /** Whether the node has no value: a JSON null. */
    boolean isNull() {
        return value == null || value.isNull() || value.isMissingNode();
    }

    /** Adds or replaces the annotation {@code name}, as a skill output named by its {@code targetName} does. */
    void annotate(String name, JsonNode annotation) {
        annotations.put(name, new EnrichmentNode(path + "/" + EnrichmentPath.Segment.name(name).text(), annotation));
    }

    /** The element nodes of an array value; null when the value is not an array. */
    List<EnrichmentNode> elements() {
        if (!(value instanceof ArrayNode array)) {
            return null;
        }
        if (elements == null) {
            List<EnrichmentNode> created = new ArrayList<>(array.size());
            for (int index = 0; index < array.size(); index++) {
                created.add(new EnrichmentNode(path + "/" + index, array.get(index)));
            }
            elements = created;
        }
        return elements;
    }

    /**
     * The child {@code name}: an annotation first, then an object property, then an array element when {@code name} is
     * an index. A child that does not exist comes back with the documented reason.
     */
    Lookup child(String name) {
        EnrichmentNode annotated = annotations.get(name);
        if (annotated != null) {
            return new Lookup(annotated, null);
        }
        if (value instanceof ObjectNode object && object.has(name)) {
            EnrichmentNode property = properties.computeIfAbsent(name,
                key -> new EnrichmentNode(path + "/" + EnrichmentPath.Segment.name(key).text(), object.get(key)));
            return new Lookup(property, null);
        }
        if (EnrichmentPath.Segment.name(name).isIndex()) {
            List<EnrichmentNode> items = elements();
            if (items == null) {
                return new Lookup(null, "Unable to select " + name + " in non-array " + path + ".");
            }
            int index = parseIndex(name);
            if (index < 0 || index >= items.size()) {
                return new Lookup(null, "Unable to select " + name + " in array " + path + " of length " + items.size()
                    + ".");
            }
            return new Lookup(items.get(index), null);
        }
        return new Lookup(null, "Missing value " + path + "/" + EnrichmentPath.Segment.name(name).text() + ".");
    }

    private static int parseIndex(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The result of looking up a child.
     *
     * @param node    the child, or null when there is none
     * @param problem why there is none, in the indexer's documented wording
     */
    record Lookup(EnrichmentNode node, String problem) {
    }
}
