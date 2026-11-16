package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/**
 * One field of an index definition, with every attribute resolved to its effective value (documented defaults
 * applied). Complex fields carry {@code false} for the attributes the API leaves {@code null} on them; their JSON keeps
 * the nulls.
 *
 * @param name                the field name
 * @param path                the field path, {@code Address/City} for a subfield
 * @param type                the data type
 * @param key                 whether this is the document key
 * @param retrievable         whether the field is returned in results
 * @param stored              whether a vector field's values are stored
 * @param searchable          whether the field is full-text searchable (or vector searchable)
 * @param filterable          whether {@code $filter} may reference the field
 * @param sortable            whether {@code $orderby} may reference the field
 * @param facetable           whether facets may reference the field
 * @param analyzer            the analyzer for indexing and searching, or null
 * @param searchAnalyzer      the search-time analyzer, or null
 * @param indexAnalyzer       the indexing-time analyzer, or null
 * @param normalizer          the normalizer for filtering, sorting and faceting, or null
 * @param dimensions          a vector field's dimensions, or null
 * @param vectorSearchProfile a vector field's profile, or null
 * @param vectorEncoding      a vector field's encoding ({@code packedBit}), or null
 * @param synonymMaps         the synonym maps the field's query terms expand through
 * @param fields              a complex field's subfields; empty otherwise
 * @param multiValued         whether the field or one of its ancestors is a collection
 * @param json                the field as GET returns it; never mutate it
 */
public record SearchFieldDefinition(String name, String path, FieldType type, boolean key, boolean retrievable,
                                    boolean stored, boolean searchable, boolean filterable, boolean sortable,
                                    boolean facetable, String analyzer, String searchAnalyzer, String indexAnalyzer,
                                    String normalizer, Integer dimensions, String vectorSearchProfile,
                                    String vectorEncoding, List<String> synonymMaps,
                                    List<SearchFieldDefinition> fields, boolean multiValued, ObjectNode json) {

    /** The analyzer an unset {@code analyzer} means: Azure's default, the standard Lucene analyzer. */
    public static final String DEFAULT_ANALYZER = "standard.lucene";

    public SearchFieldDefinition {
        synonymMaps = List.copyOf(synonymMaps);
        fields = List.copyOf(fields);
    }

    public boolean isComplex() {
        return type.isComplex();
    }

    public boolean isVector() {
        return type.isVector();
    }

    /** Whether the field takes part in full-text search: searchable text, not a vector. */
    public boolean isFullTextSearchable() {
        return searchable && type.isText();
    }

    /** The subfield named {@code childName}. */
    public Optional<SearchFieldDefinition> child(String childName) {
        return fields.stream().filter(field -> field.name().equals(childName)).findFirst();
    }

    /** The analyzer that turns field values into indexed terms. */
    public String effectiveIndexAnalyzer() {
        if (indexAnalyzer != null) {
            return indexAnalyzer;
        }
        return analyzer != null ? analyzer : DEFAULT_ANALYZER;
    }

    /** The analyzer that turns query text into terms for this field. */
    public String effectiveSearchAnalyzer() {
        if (searchAnalyzer != null) {
            return searchAnalyzer;
        }
        return analyzer != null ? analyzer : DEFAULT_ANALYZER;
    }
}
