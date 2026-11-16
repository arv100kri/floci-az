package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * A document as the index holds it.
 *
 * @param ordinal the document's slot in the index, stable while the document exists
 * @param key     the document key
 * @param source  the document as stored and retrieved: every field, values normalised to their EDM type; never mutate
 * @param values  typed values for filtering, sorting and faceting, by top-level field name (see {@link DocumentValues})
 * @param size    the stored size in bytes, for index statistics
 */
public record IndexedDocument(int ordinal, String key, ObjectNode source, Map<String, Object> values, long size) {
}
