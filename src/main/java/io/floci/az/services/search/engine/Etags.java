package io.floci.az.services.search.engine;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ETags for search resource definitions, shaped like the service's: a quoted hexadecimal timestamp
 * ({@code "0x8DD2E70E6C36D8E"}). Every ETag is distinct, even within one tick.
 */
public final class Etags {

    /** 100-nanosecond ticks between 0001-01-01 and the Unix epoch, as .NET timestamps count them. */
    private static final long TICKS_AT_EPOCH = 621_355_968_000_000_000L;
    private static final AtomicLong LAST = new AtomicLong();

    private Etags() {
    }

    public static String next() {
        Instant now = Instant.now();
        long ticks = TICKS_AT_EPOCH + now.getEpochSecond() * 10_000_000L + now.getNano() / 100;
        long unique = LAST.updateAndGet(previous -> Math.max(previous + 1, ticks));
        return "\"0x" + Long.toHexString(unique).toUpperCase(Locale.ROOT) + "\"";
    }

    /** Whether the {@code If-Match} or {@code If-None-Match} value {@code condition} names {@code etag}. */
    public static boolean matches(String condition, String etag) {
        if (condition == null || etag == null) {
            return false;
        }
        for (String candidate : condition.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.equals("*") || strip(trimmed).equals(strip(etag))) {
                return true;
            }
        }
        return false;
    }

    private static String strip(String etag) {
        String value = etag.startsWith("W/") ? etag.substring(2) : etag;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
