package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.VectorSearchValidator;

/**
 * The length a vector must have to fit a vector field, and the message Azure reports when it does not ("There's a
 * mismatch in vector dimensions", as its indexer errors show).
 */
public final class VectorDimensions {

    private static final String DOCUMENTATION =
        "https://learn.microsoft.com/en-us/azure/search/vector-search-how-to-configure-compression-storage";

    private VectorDimensions() {
    }

    /** The number of values a vector of {@code field} holds: its dimensions, or one byte per 8 of them packed. */
    public static int expectedLength(SearchFieldDefinition field) {
        return VectorSearchValidator.PACKED_BIT.equals(field.vectorEncoding()) ? (field.dimensions() + 7) / 8
            : field.dimensions();
    }

    /** The message for a vector of {@code actual} values given to {@code field}. */
    public static String mismatch(SearchFieldDefinition field, int actual) {
        return "There's a mismatch in vector dimensions. The vector field '" + field.path() + "', with dimension of '"
            + field.dimensions() + "', expects a length of '" + expectedLength(field) + "'. However, the provided "
            + "vector has a length of '" + actual + "'. Please ensure that the vector length matches the expected "
            + "length of the vector field. Read the following documentation for more details: " + DOCUMENTATION
            + ".";
    }
}
