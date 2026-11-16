package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The changes an update may make to an existing index, per "Update an index schema": adding fields (and subfields),
 * changing {@code retrievable}, {@code searchAnalyzer} and {@code synonymMaps}, and editing scoring profiles, semantic
 * configurations, CORS options and the description. Changing or deleting an existing field needs a rebuild and is
 * refused; so is changing or deleting an existing analyzer, tokenizer, token filter or char filter ("You can't delete
 * or change an existing analyzer configuration ... unless you rebuild the entire index"), while a new one is added only
 * with {@code allowIndexDowntime=true} ("Add custom analyzers": "New ones can be added to an existing index only if the
 * allowIndexDowntime flag is set to true"). Normalizers ("Adding a custom normalizer to an existing index requires the
 * allowIndexDowntime flag") and the BM25 parameters ("If the index is live, append the allowIndexDowntime=true URI
 * parameter") change only with {@code allowIndexDowntime=true}. A new suggester may only name fields the same update
 * adds.
 */
public final class IndexUpdateValidator {

    /** Azure's documented refusal of an update that needs the index taken offline. */
    public static final String DOWNTIME_MESSAGE = "Index update not allowed because it would cause downtime. In order "
        + "to add new analyzers, tokenizers, token filters, or character filters to an existing index, set the "
        + "'allowIndexDowntime' query parameter to 'true' in the index update request. Note that this operation will "
        + "put your index offline for at least a few seconds, causing your indexing and query requests to fail. "
        + "Performance and write availability of the index can be impaired for several minutes after the index is "
        + "updated, or longer for very large indexes.";

    private static final String[] IMMUTABLE_ATTRIBUTES = {
        "type", "key", "searchable", "filterable", "sortable", "facetable", "stored", "analyzer", "indexAnalyzer",
        "normalizer", "dimensions", "vectorEncoding", "vectorSearchProfile"
    };

    /** The analysis component sections, each with what one of its components is called in a refusal. */
    private static final List<ComponentSection> ANALYSIS_COMPONENTS = List.of(
        new ComponentSection("analyzers", "analyzer"),
        new ComponentSection("tokenizers", "tokenizer"),
        new ComponentSection("tokenFilters", "token filter"),
        new ComponentSection("charFilters", "char filter"));

    /** The sections any change to which takes the index offline. */
    private static final String[] DOWNTIME_SECTIONS = {"normalizers", "similarity"};

    private IndexUpdateValidator() {
    }

    public static void validate(SearchIndexDefinition existing, SearchIndexDefinition updated,
                                boolean allowIndexDowntime) {
        for (SearchFieldDefinition field : existing.allFields()) {
            SearchFieldDefinition replacement = updated.field(field.path()).orElseThrow(() -> invalid(
                "Existing field(s) cannot be deleted. The field '" + field.path() + "' is missing from the update."));
            for (String attribute : IMMUTABLE_ATTRIBUTES) {
                if (!Objects.equals(field.json().get(attribute), replacement.json().get(attribute))) {
                    throw invalid("Existing field(s) cannot be modified. The '" + attribute + "' property of the "
                        + "field '" + field.path() + "' cannot be changed; rebuild the index to change it.");
                }
            }
        }
        boolean componentsAdded = validateAnalysisComponents(existing, updated);
        if (!allowIndexDowntime) {
            boolean downtime = componentsAdded;
            for (String section : DOWNTIME_SECTIONS) {
                downtime |= !Objects.equals(existing.json().get(section), updated.json().get(section));
            }
            if (downtime) {
                throw SearchException.badRequest(DOWNTIME_MESSAGE);
            }
        }
        validateSuggesters(existing, updated);
        VectorSearchValidator.validateUpdate(existing, updated);
    }

    /**
     * Refuses an update that changes or deletes an existing analyzer, tokenizer, token filter or char filter, matched
     * by name; returns whether it adds one. A member set to null counts as absent.
     */
    private static boolean validateAnalysisComponents(SearchIndexDefinition existing, SearchIndexDefinition updated) {
        boolean added = false;
        for (ComponentSection section : ANALYSIS_COMPONENTS) {
            Map<String, JsonNode> before = byName(existing.json().get(section.section()));
            Map<String, JsonNode> after = byName(updated.json().get(section.section()));
            for (Map.Entry<String, JsonNode> component : before.entrySet()) {
                JsonNode replacement = after.get(component.getKey());
                if (replacement == null) {
                    throw invalid("the existing " + section.kind() + " '" + component.getKey() + "' cannot be "
                        + "deleted; an analysis component can only be removed by rebuilding the index.");
                }
                if (!withoutNulls(replacement).equals(withoutNulls(component.getValue()))) {
                    throw invalid("the existing " + section.kind() + " '" + component.getKey() + "' cannot be "
                        + "modified; an analysis component can only be changed by rebuilding the index.");
                }
            }
            added |= !before.keySet().containsAll(after.keySet());
        }
        return added;
    }

    /** The components of a section by name. */
    private static Map<String, JsonNode> byName(JsonNode section) {
        Map<String, JsonNode> components = new HashMap<>();
        if (section != null) {
            for (JsonNode component : section) {
                String name = SearchJson.text(component, "name");
                if (name != null) {
                    components.put(name, component);
                }
            }
        }
        return components;
    }

    /** {@code node} with the null members of its objects removed, at every depth. */
    private static JsonNode withoutNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = SearchJson.object();
            object.fields().forEachRemaining(member -> {
                if (!member.getValue().isNull()) {
                    copy.set(member.getKey(), withoutNulls(member.getValue()));
                }
            });
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = SearchJson.NODES.arrayNode();
            array.forEach(element -> copy.add(withoutNulls(element)));
            return copy;
        }
        return node;
    }

    private static void validateSuggesters(SearchIndexDefinition existing, SearchIndexDefinition updated) {
        Map<String, JsonNode> before = new HashMap<>();
        for (JsonNode suggester : existing.json().get("suggesters")) {
            before.put(SearchJson.text(suggester, "name"), suggester);
        }
        Set<String> existingPaths = new HashSet<>();
        existing.allFields().forEach(field -> existingPaths.add(field.path()));
        for (JsonNode suggester : updated.json().get("suggesters")) {
            String name = SearchJson.text(suggester, "name");
            JsonNode previous = before.get(name);
            if (previous != null) {
                if (!previous.equals(suggester)) {
                    throw invalid("the existing suggester '" + name + "' cannot be modified.");
                }
                continue;
            }
            for (JsonNode source : suggester.get("sourceFields")) {
                if (existingPaths.contains(source.textValue())) {
                    throw invalid("the new suggester '" + name + "' names the existing field '" + source.textValue()
                        + "'; a suggester added to an existing index can only use fields the same update adds.");
                }
            }
        }
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }

    /**
     * An analysis component section of an index definition.
     *
     * @param section the section, such as {@code tokenFilters}
     * @param kind    what one of its components is called in a refusal, such as {@code token filter}
     */
    private record ComponentSection(String section, String kind) {
    }
}
