package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A validated index definition: the JSON GET returns (documented defaults applied, every section kept, the ETag left
 * out) and a typed view of its fields. Immutable; the JSON is never mutated after construction, so callers copy it
 * before editing.
 */
public final class SearchIndexDefinition {

    private final String name;
    private final ObjectNode json;
    private final List<SearchFieldDefinition> fields;
    private final SearchFieldDefinition keyField;
    private final Map<String, SearchFieldDefinition> fieldsByPath;
    private final Double bm25K1;
    private final Double bm25B;

    public SearchIndexDefinition(String name, ObjectNode json, List<SearchFieldDefinition> fields,
                                 SearchFieldDefinition keyField, Double bm25K1, Double bm25B) {
        this.name = name;
        this.json = json;
        this.fields = List.copyOf(fields);
        this.keyField = keyField;
        this.bm25K1 = bm25K1;
        this.bm25B = bm25B;
        Map<String, SearchFieldDefinition> byPath = new LinkedHashMap<>();
        collect(this.fields, byPath);
        this.fieldsByPath = Collections.unmodifiableMap(byPath);
    }

    private static void collect(List<SearchFieldDefinition> level, Map<String, SearchFieldDefinition> byPath) {
        for (SearchFieldDefinition field : level) {
            byPath.put(field.path(), field);
            collect(field.fields(), byPath);
        }
    }

    public String name() {
        return name;
    }

    /** The definition as GET returns it, without {@code @odata.etag}; never mutate it. */
    public ObjectNode json() {
        return json;
    }

    /** The top-level fields, in definition order. */
    public List<SearchFieldDefinition> fields() {
        return fields;
    }

    public SearchFieldDefinition keyField() {
        return keyField;
    }

    /** The field at {@code path} ({@code Rating}, {@code Address/City}). */
    public Optional<SearchFieldDefinition> field(String path) {
        return Optional.ofNullable(fieldsByPath.get(path));
    }

    /** Every field at every depth, parents before their subfields. */
    public Collection<SearchFieldDefinition> allFields() {
        return fieldsByPath.values();
    }

    /** The fields full-text search covers when a query names none: searchable text fields at any depth. */
    public List<SearchFieldDefinition> fullTextSearchableFields() {
        List<SearchFieldDefinition> searchable = new ArrayList<>();
        for (SearchFieldDefinition field : fieldsByPath.values()) {
            if (field.isFullTextSearchable()) {
                searchable.add(field);
            }
        }
        return searchable;
    }

    /** The BM25 {@code k1} the index sets, or null for Azure's default (1.2). */
    public Double bm25K1() {
        return bm25K1;
    }

    /** The BM25 {@code b} the index sets, or null for Azure's default (0.75). */
    public Double bm25B() {
        return bm25B;
    }
}
