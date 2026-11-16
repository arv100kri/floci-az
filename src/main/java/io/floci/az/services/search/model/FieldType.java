package io.floci.az.services.search.model;

import java.util.Optional;

/**
 * The data type of a search field: an {@link EdmType} element, single-valued or a {@code Collection(...)}.
 *
 * @param element    the element type
 * @param collection whether the field holds a collection of elements
 */
public record FieldType(EdmType element, boolean collection) {

    private static final String COLLECTION_PREFIX = "Collection(";

    /** Parses {@code Edm.String} or {@code Collection(Edm.String)}; empty for anything else. */
    public static Optional<FieldType> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        if (trimmed.regionMatches(true, 0, COLLECTION_PREFIX, 0, COLLECTION_PREFIX.length()) && trimmed.endsWith(")")) {
            return EdmType.fromEdmName(trimmed.substring(COLLECTION_PREFIX.length(), trimmed.length() - 1).trim())
                .map(element -> new FieldType(element, true));
        }
        return EdmType.fromEdmName(trimmed).map(element -> new FieldType(element, false));
    }

    /** The EDM name, as the index definition spells it. */
    public String edmName() {
        return collection ? COLLECTION_PREFIX + element.edmName() + ")" : element.edmName();
    }

    public boolean isComplex() {
        return element == EdmType.COMPLEX;
    }

    /** Whether this is a vector field type ({@code Collection(Edm.Single)}, {@code Collection(Edm.Half)}, ...). */
    public boolean isVector() {
        return collection && element.isVectorElement();
    }

    /** Whether the field holds text that can be searchable: {@code Edm.String} or {@code Collection(Edm.String)}. */
    public boolean isText() {
        return element == EdmType.STRING;
    }

    @Override
    public String toString() {
        return edmName();
    }
}
