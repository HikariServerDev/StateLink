package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointDirtyStateTest {
    @Test void firstCheckpointThenIdleNeedsNoFurtherSave() {
        var dirty=new CheckpointDirtyState(); assertTrue(dirty.isDirty());
        dirty.committed(dirty.version()); assertFalse(dirty.isDirty());
        for(int i=0;i<100;i++) assertFalse(dirty.isDirty());
    }
    @Test void burstMutationsCoalesceUntilOneSuccessfulCheckpoint() {
        var dirty=new CheckpointDirtyState(); dirty.committed(dirty.version());
        for(int i=0;i<1000;i++) dirty.markChanged();
        long version=dirty.version(); assertTrue(dirty.isDirty());
        dirty.committed(version); assertFalse(dirty.isDirty());
    }
    @Test void olderCompletionCannotCleanLaterMutation() {
        var dirty=new CheckpointDirtyState(); long a=dirty.version();
        long b=dirty.markChanged(); dirty.committed(a); assertTrue(dirty.isDirty());
        dirty.committed(b); assertFalse(dirty.isDirty());
        dirty.committed(a); assertFalse(dirty.isDirty());
    }
    @Test void FailureDoesNotAcknowledgeMutationOrAcceptFutureEpoch() {
        var dirty=new CheckpointDirtyState(); dirty.committed(1000); assertTrue(dirty.isDirty());
        dirty.committed(dirty.version()); dirty.markChanged(); assertTrue(dirty.isDirty());
    }

    @Test void dataChangedFallbackRecapturesSnapshotWithItsOwnDirtyEpoch() {
        var dirty = new CheckpointDirtyState();
        dirty.committed(dirty.version());
        var live = new AtomicReference<>("inventory-before-change");
        var oldCapture = CheckpointCapture.capture(dirty::version, live::get, () -> 10L);

        // The compare runs asynchronously; the Player mutates before its DATA_CHANGED callback.
        live.set("inventory-after-change");
        dirty.markChanged();
        var fallbackCapture = CheckpointCapture.capture(dirty::version, live::get, () -> 20L);

        assertEquals("inventory-before-change", oldCapture.snapshot());
        assertEquals("inventory-after-change", fallbackCapture.snapshot());
        assertEquals(dirty.version(), fallbackCapture.changeVersion());

        // A completion for the old payload can acknowledge only its captured epoch.
        dirty.committed(oldCapture.changeVersion());
        assertTrue(dirty.isDirty(), "old snapshot completion must not clean the newer mutation");
        dirty.committed(fallbackCapture.changeVersion());
        assertFalse(dirty.isDirty());

        // A second mutation after recapture still remains dirty when that recaptured SAVE completes.
        live.set("inventory-mutated-again");
        long subsequentVersion = dirty.markChanged();
        dirty.committed(fallbackCapture.changeVersion());
        assertTrue(dirty.isDirty());
        var nextSave = CheckpointCapture.capture(dirty::version, live::get, () -> 30L);
        assertEquals("inventory-mutated-again", nextSave.snapshot());
        assertEquals(subsequentVersion, nextSave.changeVersion());
    }

    @Test void captureRejectsVersionChangeDuringSerialization() {
        AtomicLong version = new AtomicLong(7L);
        assertThrows(IllegalStateException.class, () -> CheckpointCapture.capture(
            version::get,
            () -> {
                version.incrementAndGet();
                return "partial-or-stale-snapshot";
            },
            () -> 100L));
    }
}
