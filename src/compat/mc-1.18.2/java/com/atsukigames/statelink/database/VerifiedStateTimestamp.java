package com.atsukigames.statelink.database;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Conservative DB-clock estimate of when a server-thread checkpoint was captured. */
public final class VerifiedStateTimestamp {
    private static final long SAFETY_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(1);

    private VerifiedStateTimestamp() {}

    /**
     * The database timestamp is sampled after capture and returned to the caller. Subtracting
     * the entire monotonic capture-to-response interval (plus a small clock/rounding margin)
     * yields a timestamp no newer than the captured state under normal monotonic-clock rates.
     * Delayed JDBC/network responses therefore make the attestation older, never fresher.
     */
    public static Timestamp atOrBeforeCapture(
        Timestamp observedDatabaseTime,
        long capturedAtNanos,
        long databaseTimeObservedAtNanos
    ) {
        Objects.requireNonNull(observedDatabaseTime, "observedDatabaseTime");
        long elapsedNanos = databaseTimeObservedAtNanos - capturedAtNanos;
        if (capturedAtNanos <= 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException("invalid monotonic checkpoint capture time");
        }
        return Timestamp.from(observedDatabaseTime.toInstant()
            .minusNanos(elapsedNanos)
            .minusNanos(SAFETY_MARGIN_NANOS));
    }
}
