package io.floci.az.services.search.model;

import java.util.Locale;
import java.util.Optional;

/** The element types a search field can have, by their EDM names. */
public enum EdmType {
    STRING("Edm.String"),
    INT32("Edm.Int32"),
    INT64("Edm.Int64"),
    DOUBLE("Edm.Double"),
    BOOLEAN("Edm.Boolean"),
    DATE_TIME_OFFSET("Edm.DateTimeOffset"),
    GEOGRAPHY_POINT("Edm.GeographyPoint"),
    COMPLEX("Edm.ComplexType"),
    SINGLE("Edm.Single"),
    HALF("Edm.Half"),
    INT16("Edm.Int16"),
    SBYTE("Edm.SByte"),
    BYTE("Edm.Byte");

    private final String edmName;

    EdmType(String edmName) {
        this.edmName = edmName;
    }

    public String edmName() {
        return edmName;
    }

    /** The type named {@code name}, ignoring case, or empty for a name that is not an EDM type of the search API. */
    public static Optional<EdmType> fromEdmName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (EdmType type : values()) {
            if (type.edmName.toLowerCase(Locale.ROOT).equals(lower)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Whether the type is only valid as the element of a vector field ({@code Collection(Edm.Single)} and so on). */
    public boolean isVectorElement() {
        return switch (this) {
            case SINGLE, HALF, INT16, SBYTE, BYTE -> true;
            default -> false;
        };
    }

    /** Whether values of the type are numbers that filters compare numerically. */
    public boolean isNumeric() {
        return switch (this) {
            case INT32, INT64, DOUBLE -> true;
            default -> false;
        };
    }
}
