package com.kilari.agentic.orchestration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps for anything that gets persisted and read back.
 *
 * <p>{@link Instant#now()} resolves to nanoseconds on Linux and microseconds on
 * macOS, while the database column stores less than either. A timestamp that
 * changes when it round-trips makes every equality comparison on a recovered
 * value quietly platform-dependent — it holds on a developer's laptop and fails
 * in CI, or worse, holds in both and fails somewhere else.
 *
 * <p>Truncating at the source removes the discrepancy rather than teaching each
 * comparison to tolerate it. Milliseconds because the things measured here —
 * end-to-end latency, MTTR, how long a task took — are reported in seconds and
 * minutes, so nothing below a millisecond was ever meaningful.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /** The current instant, at the precision storage can actually preserve. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
