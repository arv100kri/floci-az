package io.floci.az.services.search.model;

import java.util.Locale;

/** The {@code @search.action} of one document in an indexing batch. */
public enum IndexActionType {
    UPLOAD("upload"),
    MERGE("merge"),
    MERGE_OR_UPLOAD("mergeOrUpload"),
    DELETE("delete");

    private final String wireName;

    IndexActionType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Parses an action, ignoring case; a 400 for anything else. */
    public static IndexActionType parse(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (IndexActionType type : values()) {
            if (type.wireName.toLowerCase(Locale.ROOT).equals(lower)) {
                return type;
            }
        }
        throw SearchException.badRequest("The request is invalid. Details: '" + value + "' is not a valid "
            + "@search.action. Valid values are 'upload', 'merge', 'mergeOrUpload' and 'delete'.");
    }
}
