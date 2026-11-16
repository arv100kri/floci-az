package io.floci.az.services.search.model;

import java.util.Locale;

/** How the terms of a query combine when no operator joins them: {@code any} (OR, the default) or {@code all}. */
public enum SearchMode {
    ANY("any"),
    ALL("all");

    private final String wireName;

    SearchMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Parses {@code any} or {@code all}, ignoring case; a 400 for anything else. */
    public static SearchMode parse(String value) {
        for (SearchMode mode : values()) {
            if (mode.wireName.equals(value.toLowerCase(Locale.ROOT))) {
                return mode;
            }
        }
        throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'searchMode'. Valid values are "
            + "'any' and 'all'.");
    }
}
