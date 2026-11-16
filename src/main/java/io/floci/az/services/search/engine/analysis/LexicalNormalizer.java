package io.floci.az.services.search.engine.analysis;

/**
 * Normalises a whole value of a filterable, sortable or facetable text field before it is compared, sorted or counted
 * (and the literal of a filter before it is compared with one). Thread-safe. Named after the API's
 * {@code LexicalNormalizer}.
 */
public interface LexicalNormalizer {

    String name();

    String normalize(String value);
}
