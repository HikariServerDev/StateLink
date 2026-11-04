package com.atsukigames.statelink.database;

import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryFreshnessTest {
    private static Timestamp at(long seconds) { return new Timestamp(seconds * 1000); }

    @Test void aCheckpointFreshAtFailureDoesNotExpireWhileBothServersAreOffline() {
        assertTrue(RecoveryFreshness.eligible(at(990), at(100_000), at(1_000), 30));
        assertFalse(RecoveryFreshness.fresh(at(990), at(100_000), 30), "reproduces old wall-clock refusal");
    }
    @Test void aCheckpointAlreadyStaleAtFailureStaysRefusedAfterDowntime() {
        assertFalse(RecoveryFreshness.eligible(at(960), at(100_000), at(1_000), 30));
    }
    @Test void aLateScannerCannotMoveTheFreshnessBoundaryForward() {
        assertEquals(at(1_000), RecoveryFreshness.failureReference(at(100_000), at(100_000), at(1_000)));
        assertEquals(at(900), RecoveryFreshness.failureReference(at(100_000), at(900), at(1_000)));
    }
    @Test void missingOrFutureBoundariesNeverExtendCurrentTime() {
        assertEquals(at(1_000), RecoveryFreshness.failureReference(at(1_000), null, null));
        assertEquals(at(1_000), RecoveryFreshness.failureReference(at(1_000), at(2_000), at(3_000)));
        assertFalse(RecoveryFreshness.eligible(at(1_001), at(1_000), at(900), 30));
    }
    @Test void freshNowEligibilityIsPreservedForConservativeEarlierMarkers() {
        assertTrue(RecoveryFreshness.eligible(at(995), at(1_000), at(900), 30));
    }
    @Test void missingEvidenceAndAgeBoundsFailClosed() {
        assertFalse(RecoveryFreshness.eligible(null, at(1_000), at(900), 30));
        assertFalse(RecoveryFreshness.eligible(at(995), at(1_000), at(900), 0));
        assertTrue(RecoveryFreshness.fresh(at(970), at(1_000), 30));
        assertFalse(RecoveryFreshness.fresh(new Timestamp(969_999), at(1_000), 30));
    }
}
