package io.floci.az.services.search.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * A path into the enrichment tree, in the skill annotation language: {@code /document/pages/*}{@code /keyPhrases}. A
 * segment is a name (a property, an annotation or an array index), {@code *} (enumerate an array) or {@code #} (take an
 * array as one value). Names use the documented escapes: {@code ~0} for {@code ~}, {@code ~1} for {@code /}, and
 * {@code ~2...~2} around a sequence taken literally, so {@code ~2*~2} names a property called {@code *}.
 *
 * @param segments the segments, the first always the name {@code document}
 */
public record EnrichmentPath(List<Segment> segments) {

    /** The root, {@code /document}. */
    public static final EnrichmentPath ROOT = new EnrichmentPath(List.of(Segment.name("document")));

    public EnrichmentPath {
        segments = List.copyOf(segments);
    }

    /**
     * Parses {@code text}. The leading slash is optional ({@code document/content} is accepted, as output field
     * mappings write it); a trailing slash is ignored.
     *
     * @throws IllegalArgumentException when the path does not start at {@code /document} or has an empty segment
     */
    public static EnrichmentPath parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the path is empty");
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] raw = trimmed.split("/", -1);
        List<Segment> segments = new ArrayList<>(raw.length);
        for (String part : raw) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("the path '" + text + "' has an empty segment");
            }
            segments.add(decode(part));
        }
        Segment first = segments.get(0);
        if (first.kind() != Kind.NAME || !first.name().equals("document")) {
            throw new IllegalArgumentException("the path '" + text + "' does not start with '/document'");
        }
        return new EnrichmentPath(segments);
    }

    private static Segment decode(String part) {
        if (part.equals("*")) {
            return Segment.STAR;
        }
        if (part.equals("#")) {
            return Segment.HASH;
        }
        StringBuilder name = new StringBuilder(part.length());
        int index = 0;
        while (index < part.length()) {
            char current = part.charAt(index);
            char next = index + 1 < part.length() ? part.charAt(index + 1) : 0;
            if (current == '~' && next == '0') {
                name.append('~');
                index += 2;
            } else if (current == '~' && next == '1') {
                name.append('/');
                index += 2;
            } else if (current == '~' && next == '2') {
                // ~2 only delimits a literal sequence: the characters between two markers are kept as written.
                index += 2;
            } else {
                name.append(current);
                index++;
            }
        }
        return Segment.name(name.toString());
    }

    /** The number of segments. */
    public int length() {
        return segments.size();
    }

    public Segment segment(int index) {
        return segments.get(index);
    }

    /** This path with {@code name} appended. */
    public EnrichmentPath child(String name) {
        List<Segment> extended = new ArrayList<>(segments);
        extended.add(Segment.name(name));
        return new EnrichmentPath(extended);
    }

    /** How many leading segments this path and {@code other} share, segment for segment. */
    public int commonPrefix(EnrichmentPath other) {
        int shared = 0;
        int limit = Math.min(segments.size(), other.segments.size());
        while (shared < limit && segments.get(shared).equals(other.segments.get(shared))) {
            shared++;
        }
        return shared;
    }

    /**
     * Whether a node this path writes can be read through {@code reader}: {@code reader} is this path or a path below
     * it, where an array index and {@code *} on either side select the same nodes and {@code #} is skipped.
     */
    public boolean isPrefixOf(EnrichmentPath reader) {
        List<Segment> mine = withoutHashes(segments);
        List<Segment> theirs = withoutHashes(reader.segments);
        if (mine.size() > theirs.size()) {
            return false;
        }
        for (int index = 0; index < mine.size(); index++) {
            Segment own = mine.get(index);
            Segment other = theirs.get(index);
            boolean ownAny = own.kind() == Kind.STAR || own.isIndex();
            boolean otherAny = other.kind() == Kind.STAR || other.isIndex();
            if (!(ownAny && otherAny) && !own.equals(other)) {
                return false;
            }
        }
        return true;
    }

    private static List<Segment> withoutHashes(List<Segment> segments) {
        List<Segment> kept = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            if (segment.kind() != Kind.HASH) {
                kept.add(segment);
            }
        }
        return kept;
    }

    /** The path as the annotation language writes it, with names escaped. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            text.append('/').append(segment.text());
        }
        return text.toString();
    }

    /** The kind of a path segment. */
    public enum Kind {
        NAME,
        STAR,
        HASH
    }

    /**
     * One segment of a path.
     *
     * @param kind the kind
     * @param name the decoded name of a {@link Kind#NAME} segment, else null
     */
    public record Segment(Kind kind, String name) {

        public static final Segment STAR = new Segment(Kind.STAR, null);
        public static final Segment HASH = new Segment(Kind.HASH, null);

        public static Segment name(String name) {
            return new Segment(Kind.NAME, name);
        }

        /** Whether this is a name made of digits only, which selects an array element. */
        public boolean isIndex() {
            if (kind != Kind.NAME || name.isEmpty()) {
                return false;
            }
            for (int index = 0; index < name.length(); index++) {
                if (!Character.isDigit(name.charAt(index))) {
                    return false;
                }
            }
            return true;
        }

        /** The segment as the annotation language writes it. */
        public String text() {
            return switch (kind) {
                case STAR -> "*";
                case HASH -> "#";
                case NAME -> escape(name);
            };
        }

        private static String escape(String name) {
            String escaped = name.replace("~", "~0").replace("/", "~1");
            if (escaped.equals("*") || escaped.equals("#")) {
                return "~2" + escaped + "~2";
            }
            return escaped;
        }
    }
}
