package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A field mapping function ({@code FieldMappingFunction}).
 *
 * @param name       the function name as the definition spells it
 * @param parameters its parameters, or null
 */
public record MappingFunction(String name, ObjectNode parameters) {
}
