package com.atsukigames.statelink.database;

import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;
import static com.atsukigames.statelink.database.CoordinationLifecycle.State.*;

class CoordinationLifecycleTest {
    private final Timestamp now = new Timestamp(10_000);
    @Test void absentOwnerTupleIsCleanIncludingLegacyRows() {
        assertEquals(CLEAN_RELEASED, CoordinationLifecycle.classify(false, null, null, null, now));
    }
    @Test void liveOwnerIsBusyNotFree() {
        assertEquals(OWNED, CoordinationLifecycle.classify(false, "s1", "session", new Timestamp(11_000), now));
    }
    @Test void expiredOwnerRequiresRecoveryEvenWithoutNewFlag() {
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "s1", "session", new Timestamp(9_999), now));
    }
    @Test void flagCannotBeClearedByTimeOrMissingOwner() {
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(true, null, null, null, now));
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(true, "s1", "session", new Timestamp(99_000), now));
    }
    @Test void partialOwnerTupleFailsClosed() {
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "s1", null, new Timestamp(99_000), now));
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, null, "session", new Timestamp(99_000), now));
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "s1", "session", null, now));
    }
    @Test void blankOwnerTupleFailsClosed() {
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "", "session", new Timestamp(99_000), now));
    }
    @Test void pauseAfterCheckCannotAuthorizeDestination() {
        Timestamp expiry = new Timestamp(10_001);
        assertEquals(OWNED, CoordinationLifecycle.classify(false, "paused-s1", "session", expiry, now));
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "paused-s1", "session", expiry, new Timestamp(10_002)));
        // Resume cannot turn the expired tuple into a clean final commit.
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "paused-s1", "session", expiry, new Timestamp(20_000)));
    }
    @Test void crashAfterWorldMutationDoesNotMakeCheckpointAutomaticallyRestorable() {
        assertEquals(RECOVERY_REQUIRED, CoordinationLifecycle.classify(false, "killed-s1", "session", new Timestamp(1), now));
    }
}
