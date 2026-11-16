package io.floci.az.services.search.indexer;

import java.time.Duration;
import java.time.Instant;

/**
 * An indexer's {@code schedule} ({@code IndexingSchedule}).
 *
 * @param interval  the time between the starts of two scheduled runs
 * @param startTime when the schedule starts; the time the schedule was set when the definition left it out
 */
public record IndexerSchedule(Duration interval, Instant startTime) {

    /** The first scheduled start at or after {@code now}, counting intervals from {@code baseline}. */
    public Instant nextRunAfter(Instant baseline, Instant now) {
        if (!baseline.isBefore(now)) {
            return baseline;
        }
        long intervalMillis = Math.max(1, interval.toMillis());
        long elapsed = Duration.between(baseline, now).toMillis();
        long periods = elapsed / intervalMillis + 1;
        return baseline.plusMillis(periods * intervalMillis);
    }
}
