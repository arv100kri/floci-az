package io.floci.az.services.search.engine.vector;

import io.floci.az.services.search.model.SearchException;

import java.util.Locale;

/**
 * When the request's filter applies to its vector queries, per "Vector query filters". The emulator holds each index
 * in one shard, so {@code postFilter} (filter each shard's top {@code k}) and {@code strictPostFilter} (filter the
 * global top {@code k}) both keep the matches of the unfiltered top {@code k} that pass the filter.
 */
public enum VectorFilterMode {
    PRE_FILTER("preFilter"),
    POST_FILTER("postFilter"),
    STRICT_POST_FILTER("strictPostFilter");

    private final String wireName;

    VectorFilterMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Whether the filter narrows the candidates before the nearest neighbours are chosen. */
    public boolean filtersCandidates() {
        return this == PRE_FILTER;
    }

    /** The mode {@code value} names, ignoring case; {@code preFilter} (the default for new indexes) when absent. */
    public static VectorFilterMode parse(String value) {
        if (value == null) {
            return PRE_FILTER;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (VectorFilterMode mode : values()) {
            if (mode.wireName.toLowerCase(Locale.ROOT).equals(lower)) {
                return mode;
            }
        }
        throw SearchException.badRequest("Invalid value '" + value + "' for parameter 'vectorFilterMode'. Valid values "
            + "are 'preFilter', 'postFilter' and 'strictPostFilter'.");
    }
}
