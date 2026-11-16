package io.floci.az.services.search.engine.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.IndexActionType;

/**
 * One action of an indexing batch, its document already converted to the index schema.
 *
 * @param type     the action
 * @param key      the document key
 * @param document the converted fields the action provides (only the key for a delete)
 * @param keyError why the key is invalid, failing just this action with a 400; null for a valid key
 */
public record IndexAction(IndexActionType type, String key, ObjectNode document, String keyError) {
}
