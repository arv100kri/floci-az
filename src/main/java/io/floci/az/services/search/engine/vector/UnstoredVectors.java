package io.floci.az.services.search.engine.vector;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;

/**
 * What a partial update does to a vector field with {@code stored: false}, per "Eliminate optional vector instances
 * from storage": the service keeps no retrievable copy to merge from, so a {@code merge} or {@code mergeOrUpload} that
 * omits the field loses its vector, without an error or warning.
 */
public final class UnstoredVectors {

    private UnstoredVectors() {
    }

    /** {@code merged} without the unstored vector fields that {@code provided}, the partial document, omits. */
    public static ObjectNode dropOmitted(ObjectNode merged, ObjectNode provided, SearchIndexDefinition definition) {
        for (SearchFieldDefinition field : definition.fields()) {
            if (field.isVector() && !field.stored() && !provided.has(field.name())) {
                merged.remove(field.name());
            }
        }
        return merged;
    }
}
