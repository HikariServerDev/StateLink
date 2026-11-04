package com.atsukigames.statelink.database;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedStateTimestampTest {
    @Test void delayedDatabaseResponseMovesAttestationEarlierByEntireCaptureAge() {
        Timestamp observedDbTime = Timestamp.from(Instant.parse("2026-10-12T12:00:10.000Z"));
        Timestamp capturedBound = VerifiedStateTimestamp.atOrBeforeCapture(
            observedDbTime, 2_000_000_000L, 8_000_000_000L);

        assertEquals(Instant.parse("2026-10-12T12:00:03.000Z"), capturedBound.toInstant());
        assertTrue(capturedBound.before(observedDbTime));
    }

    @Test void rejectsInvalidMonotonicCaptureOrdering() {
        Timestamp dbTime = Timestamp.from(Instant.parse("2026-10-12T12:00:10.000Z"));
        assertThrows(IllegalArgumentException.class,
            () -> VerifiedStateTimestamp.atOrBeforeCapture(dbTime, 9L, 8L));
    }
}
