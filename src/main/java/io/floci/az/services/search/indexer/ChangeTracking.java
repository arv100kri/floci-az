package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An indexer's change tracking state: the high-water mark of the items it has indexed, and the items it indexed at
 * exactly that mark. Sources are read with the documented {@code >= HighWaterMark} condition ({@code Timestamp},
 * {@code _ts}, a blob's last-modified time), so an item written in the same tick as the mark is never missed, and the
 * boundary set keeps the items already indexed at the mark from being counted again: a run over unchanged content
 * processes nothing, as Learn documents ("0\0 documents processed").
 *
 * @param highWaterMark the mark: epoch nanoseconds for a timestamp, the value itself for a number; null for none
 * @param boundary      the identity and version ({@code name|etag}) of each item indexed at the mark
 * @param timestamp     whether the mark is a timestamp
 */
public record ChangeTracking(Long highWaterMark, Set<String> boundary, boolean timestamp) {

    /** No mark: every item is new. */
    public static final ChangeTracking NONE = new ChangeTracking(null, Set.of(), true);

    public ChangeTracking {
        boundary = Set.copyOf(boundary);
    }

    /** An instant as a mark value. */
    public static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    /** Whether an item at mark value {@code value} (null when it has none) with {@code identity} needs indexing. */
    public boolean admits(Long value, String identity) {
        if (highWaterMark == null || value == null) {
            return true;
        }
        int comparison = Long.compare(value, highWaterMark);
        return comparison > 0 || comparison == 0 && !boundary.contains(identity);
    }

    /** The state as the execution history shows it ({@code initialTrackingState}), or null without a mark. */
    public String describe() {
        if (highWaterMark == null) {
            return null;
        }
        ObjectNode state = SearchJson.object();
        if (timestamp) {
            state.put("highWaterMark", Instant.ofEpochSecond(0, highWaterMark).toString());
        } else {
            state.put("highWaterMark", highWaterMark);
        }
        return SearchJson.write(state);
    }

    ObjectNode toJson() {
        ObjectNode json = SearchJson.object();
        if (highWaterMark == null) {
            json.putNull("highWaterMark");
        } else {
            json.put("highWaterMark", highWaterMark);
        }
        json.put("timestamp", timestamp);
        ArrayNode items = json.putArray("boundary");
        boundary.forEach(items::add);
        return json;
    }

    static ChangeTracking fromJson(JsonNode json) {
        if (json == null || !json.isObject() || json.path("highWaterMark").isNull()
                || json.path("highWaterMark").isMissingNode()) {
            return NONE;
        }
        Set<String> boundary = new LinkedHashSet<>();
        json.path("boundary").forEach(item -> boundary.add(item.asText()));
        return new ChangeTracking(json.get("highWaterMark").asLong(), boundary, json.path("timestamp").asBoolean(true));
    }

    /** Collects the mark of a run from the items it went through. */
    static final class Builder {

        private final ChangeTracking previous;
        private final boolean timestamp;
        private Long max;
        private final Set<String> atMax = new LinkedHashSet<>();

        Builder(ChangeTracking previous, boolean timestamp) {
            this.previous = previous;
            this.timestamp = timestamp;
        }

        void track(Long value, String identity) {
            if (value == null) {
                return;
            }
            if (max == null || value > max) {
                max = value;
                atMax.clear();
            }
            if (value.longValue() == max) {
                atMax.add(identity);
            }
        }

        /** The state after the run: the previous one when nothing advanced it. */
        ChangeTracking build() {
            if (max == null || previous.highWaterMark() != null && max < previous.highWaterMark()) {
                return previous;
            }
            Set<String> boundary = new LinkedHashSet<>(atMax);
            if (previous.highWaterMark() != null && max.longValue() == previous.highWaterMark()) {
                boundary.addAll(previous.boundary());
            }
            return new ChangeTracking(max, boundary, timestamp);
        }
    }
}
