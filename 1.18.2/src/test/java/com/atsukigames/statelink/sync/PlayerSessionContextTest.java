package com.atsukigames.statelink.sync;

import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionContextTest {
    @Test
    void atMostOnePeriodicCheckpointMayBeQueuedWithoutHavingStarted() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        assertTrue(context.tryReservePeriodicSave());
        // DB slower than the interval: later periodic ticks coalesce instead of queueing.
        for (int tick = 0; tick < 50; tick++) assertFalse(context.tryReservePeriodicSave());
        context.periodicSaveStarted();
        assertTrue(context.tryReservePeriodicSave(), "one more may queue behind the running save");
        assertFalse(context.tryReservePeriodicSave());
    }

    @Test
    void loadingSessionCannotSaveUntilAuthoritativeDataIsReady() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);

        assertTrue(context.state() == SyncState.LOADING);
        assertFalse(context.canQueueNormalSave());
        long anchor = System.nanoTime();
        context.markOwnership(10L, 0L, anchor);
        assertFalse(context.canQueueNormalSave());

        assertTrue(context.markReadyIfSafe(true, true, false, anchor + 1,
            TimeUnit.MILLISECONDS.toNanos(3_500)));
        assertTrue(context.canQueueNormalSave());
    }

    @Test
    void flushingSessionAllowsOnlyFinalFlush() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long anchor = System.nanoTime();
        context.markOwnership(10L, 0L, anchor);
        assertTrue(context.markReadyIfSafe(true, true, false, anchor + 1,
            TimeUnit.MILLISECONDS.toNanos(3_500)));

        assertTrue(context.beginQuiescingIfSafe(
            anchor + 2, TimeUnit.MILLISECONDS.toNanos(3_500), 10L));
        assertTrue(context.beginFlushing(
            true, true, anchor + 3, TimeUnit.MILLISECONDS.toNanos(3_500), 10L));
        assertFalse(context.canQueueNormalSave());
        assertTrue(context.canFlush());
    }

    @Test
    void flushingRejectsIncompleteCleanupSnapshotExpiredLeaseAndWrongFence() {
        long safetyBudget = TimeUnit.MILLISECONDS.toNanos(3_500);
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long anchor = System.nanoTime();
        context.markOwnership(10L, 0L, anchor);
        assertTrue(context.markReadyIfSafe(true, true, false, anchor + 1, safetyBudget));
        assertTrue(context.beginQuiescingIfSafe(anchor + 2, safetyBudget, 10L));

        assertFalse(context.beginFlushing(false, true, anchor + 3, safetyBudget, 10L));
        assertFalse(context.beginFlushing(true, false, anchor + 3, safetyBudget, 10L));
        assertFalse(context.beginFlushing(true, true, anchor + safetyBudget, safetyBudget, 10L));
        assertFalse(context.beginFlushing(true, true, anchor + 3, safetyBudget, 11L));
        assertEquals(SyncState.QUIESCING, context.state());

        assertTrue(context.beginFlushing(true, true, anchor + 3, safetyBudget, 10L));
    }

    @Test
    void anOlderSaveCompletionCannotClearANewerDirtySnapshot() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        CompletePlayerData older = sample(context.uuid(), "older");
        CompletePlayerData newer = sample(context.uuid(), "newer");

        long olderSequence = context.recordDirtySnapshot(older, SaveReason.PERIODIC);
        long newerSequence = context.recordDirtySnapshot(newer, SaveReason.PERIODIC);
        context.recordSuccessfulSave(older, olderSequence, 1L);

        assertSame(newer, context.dirtySnapshot());
        context.recordSaveFailure(older, SaveReason.PERIODIC, olderSequence);
        assertSame(newer, context.dirtySnapshot());
        context.recordSuccessfulSave(newer, newerSequence, 2L);
        assertNull(context.dirtySnapshot());
    }

    @Test
    void delayedFirstLoginCallbackCannotEnterReadyAfterLeaseSafetyExpires() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long leaseAnchor = 10_000L;
        long safetyBudget = TimeUnit.MILLISECONDS.toNanos(3_500);
        context.markOwnership(41L, 1L, leaseAnchor);

        // Models an initial SAVE commit whose main-thread completion was held until
        // after another backend could take over the expired lease.
        boolean ready = context.markReadyIfSafe(
            true, true, false, leaseAnchor + TimeUnit.MILLISECONDS.toNanos(5_001), safetyBudget);

        assertFalse(ready);
        assertFalse(context.isAuthoritativeDataLoaded());
        assertTrue(context.state() == SyncState.LOADING);
        assertFalse(context.canQueueNormalSave());
    }

    @Test
    void pausedInitialSaveCallbackCannotBecomeReadyAfterTakeoverWindow() throws Exception {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long leaseAnchor = 50_000L;
        long safetyBudget = TimeUnit.MILLISECONDS.toNanos(3_500);
        context.markOwnership(73L, 0L, leaseAnchor);
        CountDownLatch callbackReachedGate = new CountDownLatch(1);
        CountDownLatch allowCallback = new CountDownLatch(1);
        AtomicBoolean becameReady = new AtomicBoolean(true);

        Thread delayedServerCallback = new Thread(() -> {
            callbackReachedGate.countDown();
            try {
                if (!allowCallback.await(5, TimeUnit.SECONDS)) return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            becameReady.set(context.markReadyIfSafe(
                true, true, false,
                leaseAnchor + TimeUnit.MILLISECONDS.toNanos(5_001), safetyBudget));
        }, "initial-save-callback-test");
        delayedServerCallback.start();

        assertTrue(callbackReachedGate.await(5, TimeUnit.SECONDS));
        // Models S2 acquiring a new fence after the initial lease expires while the
        // old committed result is held before its main-thread READY transition.
        allowCallback.countDown();
        delayedServerCallback.join(5_000);

        assertFalse(delayedServerCallback.isAlive());
        assertFalse(becameReady.get());
        assertFalse(context.isAuthoritativeDataLoaded());
        assertTrue(context.state() == SyncState.LOADING);
        assertTrue(PlayerEligibility.shouldQuarantine(true, true, context.state(), false));
        assertFalse(context.canQueueNormalSave());
    }

    @Test
    void readyStateIsNoLongerUsableAfterMonotonicLeaseSafetyDeadline() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long leaseAnchor = 10_000L;
        long safetyBudget = TimeUnit.MILLISECONDS.toNanos(3_500);
        context.markOwnership(42L, 1L, leaseAnchor);
        assertTrue(context.markReadyIfSafe(true, true, false, leaseAnchor + 1, safetyBudget));

        assertFalse(context.hasLeaseSafetyAt(leaseAnchor + safetyBudget, safetyBudget));
        assertTrue(PlayerEligibility.shouldQuarantine(true, true, SyncState.READY, false));
    }

    @Test
    void ownershipWithoutRepositoryLeaseAnchorCannotBecomeReady() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        context.markOwnership(44L, 0L);

        assertFalse(context.hasLeaseSafetyAt(
            System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(3_500)));
        assertFalse(context.markReadyIfSafe(
            true, true, false, System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(3_500)));
        assertTrue(context.state() == SyncState.LOADING);
    }

    @Test
    void snapshotSerializationFailureCannotBeAcknowledgedByOlderSaveCompletion() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        CompletePlayerData prior = sample(context.uuid(), "prior");
        long sequence = context.recordDirtySnapshot(prior, SaveReason.PERIODIC);

        context.recordSnapshotCaptureFailure();
        context.recordSuccessfulSave(prior, sequence, 8L);

        assertTrue(context.hasSnapshotCaptureFailure());
        assertSame(prior, context.dirtySnapshot());
    }

    private static CompletePlayerData sample(UUID uuid, String username) {
        return new CompletePlayerData(
            uuid, username, null, null, null, null,
            20.0, 20, 5.0F, 0.0F, 300,
            0, 0, 0.0F, null, null,
            "minecraft:overworld", 0.0, 64.0, 0.0, 0.0F, 0.0F,
            "survival", false, false, false,
            username, null, null, null, null, null, 0, 0L, 1
        );
    }
}
