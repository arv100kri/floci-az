package io.floci.az.services.search.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayList;
import java.util.List;

/**
 * The semantic configuration a query runs with, resolved from the index's {@code semantic} section: the one
 * {@code semanticConfiguration} names, else the index's {@code defaultConfiguration}. Its prioritized fields feed the
 * stand-in; {@code semanticFields}, when the query sets it, keeps only the prioritized fields it names.
 *
 * @param name               the configuration's name
 * @param titleField         the title field, or null
 * @param contentFields      the content fields, in priority order
 * @param keywordsFields     the keywords fields, in priority order
 * @param rankByBoostedScore whether results sort by {@code @search.rerankerBoostedScore} when a scoring profile applies
 *                           ({@code rankingOrder} {@code BoostedRerankerScore}, the default), rather than by
 *                           {@code @search.rerankerScore}
 */
public record SemanticConfig(String name, SearchFieldDefinition titleField, List<SearchFieldDefinition> contentFields,
                             List<SearchFieldDefinition> keywordsFields, boolean rankByBoostedScore) {

    /** The {@code rankingOrder} that sorts by the reranker score alone. */
    static final String RERANKER_SCORE = "RerankerScore";

    public SemanticConfig {
        contentFields = List.copyOf(contentFields);
        keywordsFields = List.copyOf(keywordsFields);
    }

    /**
     * The configuration a semantic query on {@code definition} runs with; a 400 when the query names none and the
     * index has no default, or names one the index does not define, or names an unknown field in
     * {@code semanticFields}.
     */
    public static SemanticConfig resolve(SearchIndexDefinition definition, String requested,
                                         List<String> semanticFields) {
        JsonNode semantic = definition.json().path("semantic");
        String name = requested == null || requested.isBlank() ? SearchJson.text(semantic, "defaultConfiguration")
            : requested;
        if (name == null || name.isBlank()) {
            throw SearchException.badRequest("Invalid search request: semantic queries need a semantic configuration; "
                + "set 'semanticConfiguration', or a 'defaultConfiguration' in the semantic settings of the index '"
                + definition.name() + "'.\r\nParameter name: semanticConfiguration");
        }
        for (JsonNode configuration : semantic.path("configurations")) {
            if (name.equals(SearchJson.text(configuration, "name"))) {
                return of(definition, configuration, semanticFields);
            }
        }
        throw SearchException.badRequest("Invalid search request: the semantic configuration '" + name + "' is not "
            + "defined in the index '" + definition.name() + "'.\r\nParameter name: semanticConfiguration");
    }

    private static SemanticConfig of(SearchIndexDefinition definition, JsonNode configuration,
                                     List<String> semanticFields) {
        for (String path : semanticFields) {
            if (definition.field(path).isEmpty()) {
                throw SearchException.badRequest("Invalid search request: Could not find a property named '" + path
                    + "' on type 'search.document'.\r\nParameter name: semanticFields");
            }
        }
        JsonNode prioritized = configuration.path("prioritizedFields");
        List<SearchFieldDefinition> title = fields(definition, List.of(prioritized.path("titleField")),
            semanticFields);
        List<SearchFieldDefinition> content = fields(definition, list(prioritized.path("prioritizedContentFields")),
            semanticFields);
        List<SearchFieldDefinition> keywords = fields(definition, list(prioritized.path("prioritizedKeywordsFields")),
            semanticFields);
        String rankingOrder = SearchJson.text(configuration, "rankingOrder");
        return new SemanticConfig(SearchJson.text(configuration, "name"), title.isEmpty() ? null : title.get(0),
            content, keywords, !RERANKER_SCORE.equalsIgnoreCase(rankingOrder));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> entries = new ArrayList<>();
        array.forEach(entries::add);
        return entries;
    }

    /** The definitions of the named fields that exist (and that {@code semanticFields} names, when it is set). */
    private static List<SearchFieldDefinition> fields(SearchIndexDefinition definition, List<JsonNode> entries,
                                                      List<String> semanticFields) {
        List<SearchFieldDefinition> fields = new ArrayList<>();
        for (JsonNode entry : entries) {
            String path = SearchJson.text(entry, "fieldName");
            if (path != null && (semanticFields.isEmpty() || semanticFields.contains(path))) {
                definition.field(path).ifPresent(fields::add);
            }
        }
        return fields;
    }
}
