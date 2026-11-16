package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The changes an update may make to an existing index, per "Update an index schema": adding fields (and subfields),
 * changing {@code retrievable}, {@code searchAnalyzer} and {@code synonymMaps}, and editing scoring profiles, semantic
 * configurations, CORS options and the description. Changing or deleting an existing field needs a rebuild and is
 * refused; analysis components and the BM25 parameters change only with {@code allowIndexDowntime=true}; a new
 * suggester may only name fields the same update adds.
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

    private static final String[] DOWNTIME_SECTIONS = {
        "analyzers", "tokenizers", "tokenFilters", "charFilters", "normalizers", "similarity"
    };

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
        if (!allowIndexDowntime) {
            for (String section : DOWNTIME_SECTIONS) {
                if (!Objects.equals(existing.json().get(section), updated.json().get(section))) {
                    throw SearchException.badRequest(DOWNTIME_MESSAGE);
                }
            }
        }
        validateSuggesters(existing, updated);
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
}
