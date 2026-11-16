package io.floci.az.services.search.dataplane;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A data-plane resource path in its normalised form: both URL forms the service accepts, {@code indexes('hotels')}
 * and {@code indexes/hotels}, become the same segments. A segment taken from inside {@code ('...')} is marked quoted,
 * so a key such as {@code docs('search.index')} is never mistaken for an action, and {@code ''} inside it is an escaped
 * quote. A {@code :action} suffix after the parentheses ({@code files('id'):ingest}) is its own segment.
 *
 * @param segments the segments, in order
 */
public record SearchPath(List<Segment> segments) {

    private static final Pattern KEYED = Pattern.compile("^([^(]+)\\('((?:[^']|'')*)'\\)(:[A-Za-z]+)?$");

    public SearchPath {
        segments = List.copyOf(segments);
    }

    /** Parses a resource path (the part after the service), ignoring empty segments. */
    public static SearchPath parse(String resourcePath) {
        List<Segment> segments = new ArrayList<>();
        if (resourcePath != null) {
            for (String raw : resourcePath.split("/")) {
                if (raw.isEmpty()) {
                    continue;
                }
                Matcher keyed = KEYED.matcher(raw);
                if (keyed.matches()) {
                    segments.add(new Segment(keyed.group(1), false));
                    segments.add(new Segment(keyed.group(2).replace("''", "'"), true));
                    if (keyed.group(3) != null) {
                        segments.add(new Segment(keyed.group(3), false));
                    }
                } else {
                    segments.add(new Segment(raw, false));
                }
            }
        }
        return new SearchPath(segments);
    }

    /**
     * One path segment.
     *
     * @param value  the segment text, unquoted
     * @param quoted whether it came from inside {@code ('...')}
     */
    public record Segment(String value, boolean quoted) {
    }
}
