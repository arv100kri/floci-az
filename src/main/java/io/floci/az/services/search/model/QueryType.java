package io.floci.az.services.search.model;

import java.util.Locale;

/** The syntax of the {@code search} parameter: {@code simple} (the default), {@code full} or {@code semantic}. */
public enum QueryType {
    SIMPLE("simple"),
    FULL("full"),
    SEMANTIC("semantic");

    private final String wireName;

    QueryType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Parses a query type, ignoring case; a 400 for anything else. */
    public static QueryType parse(String value) {
        for (QueryType type : values()) {
            if (type.wireName.equals(value.toLowerCase(Locale.ROOT))) {
                return type;
            }
        }
        throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'queryType'. Valid values are "
            + "'simple', 'full' and 'semantic'.");
    }
}
