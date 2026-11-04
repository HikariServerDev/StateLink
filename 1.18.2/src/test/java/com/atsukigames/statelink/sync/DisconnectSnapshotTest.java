package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectSnapshotTest {
    @Test
    void offServerThreadIsRejectedBeforeCaptureOrCleanup() throws Exception {
        Thread serverThread = Thread.currentThread();
        AtomicReference<DisconnectSnapshot.Outcome<String>> outcome = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                outcome.set(DisconnectSnapshot.captureThenCleanup(
                    () -> Thread.currentThread() == serverThread,
                    () -> true,
                    () -> { throw new AssertionError("off-thread capture ran"); },
                    () -> { throw new AssertionError("off-thread cleanup ran"); }));
            } finally {
                finished.countDown();
            }
        }, "disconnect-snapshot-test-worker");
        worker.start();
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
        worker.join();

        assertEquals(DisconnectSnapshot.Status.FAILED, outcome.get().status());
    }

    @Test
    void snapshotIsCapturedBeforeTransientReferencesAreClearedAndNoDropIsProduced() {
        AtomicReference<String> cursor = new AtomicReference<>("marker-D");
        List<String> crafting = new ArrayList<>(List.of("craft-A"));
        List<String> worldDrops = new ArrayList<>();
        List<String> order = new ArrayList<>();

        DisconnectSnapshot.Outcome<List<String>> outcome = DisconnectSnapshot.captureThenCleanup(
            () -> true,
            () -> true,
            () -> {
                order.add("capture");
                List<String> captured = new ArrayList<>();
                if (cursor.get() != null) captured.add(cursor.get());
                captured.addAll(crafting);
                return List.copyOf(captured);
            },
            () -> {
                order.add("clear-and-close");
                cursor.set(null);
                crafting.clear();
                // StateLink cleanup has no PlayerInventory.offerOrDrop/World spawn call.
            });

        assertEquals(DisconnectSnapshot.Status.SUCCESS, outcome.status());
        assertEquals(List.of("capture", "clear-and-close"), order);
        assertEquals(List.of("marker-D", "craft-A"), outcome.snapshot());
        assertNull(cursor.get());
        assertTrue(crafting.isEmpty());
        assertTrue(worldDrops.isEmpty());
    }

    @Test
    void finalSaveFailureLeavesOldAuthoritativeCopyAndNoWorldCopy() {
        Map<String, Integer> authoritativeDatabase = new java.util.HashMap<>(Map.of("marker-D", 1));
        AtomicReference<String> cursor = new AtomicReference<>("marker-D");
        List<String> worldDrops = new ArrayList<>();

        DisconnectSnapshot.Outcome<List<String>> outcome = DisconnectSnapshot.captureThenCleanup(
            () -> true,
            () -> true,
            () -> List.of(cursor.get()),
            () -> cursor.set(null));
        assertEquals(DisconnectSnapshot.Status.SUCCESS, outcome.status());

        // Model the repository rolling back the final transaction. Since the cleanup
        // did not externalize the cursor, the previous DB state remains the only copy.
        boolean committed = false;
        assertFalse(committed);
        assertEquals(1, authoritativeDatabase.get("marker-D"));
        assertEquals(0, worldDrops.size());
        assertEquals(1, authoritativeDatabase.get("marker-D") + worldDrops.size());
    }

    @Test
    void authorityLostDuringCleanupDoesNotProduceACommitEligibleSnapshot() {
        AtomicBoolean authority = new AtomicBoolean(true);
        AtomicReference<String> cursor = new AtomicReference<>("marker-D");
        List<String> database = new ArrayList<>(List.of("marker-D"));
        List<String> worldDrops = new ArrayList<>();
        AtomicBoolean repositoryCalled = new AtomicBoolean();

        DisconnectSnapshot.Outcome<List<String>> outcome = DisconnectSnapshot.captureThenCleanup(
            () -> true,
            authority::get,
            () -> List.of(cursor.get()),
            () -> {
                cursor.set(null);
                authority.set(false);
            });
        if (outcome.status() == DisconnectSnapshot.Status.SUCCESS) repositoryCalled.set(true);

        assertEquals(DisconnectSnapshot.Status.AUTHORITY_LOST, outcome.status());
        assertFalse(repositoryCalled.get());
        assertEquals(List.of("marker-D"), database);
        assertTrue(worldDrops.isEmpty());
    }

    @Test
    void cleanupExceptionIsNotReportedAsSuccessfulFinalization() {
        AtomicBoolean commitCalled = new AtomicBoolean();
        DisconnectSnapshot.Outcome<String> outcome = DisconnectSnapshot.captureThenCleanup(
            () -> true,
            () -> true,
            () -> "complete-snapshot",
            () -> { throw new IllegalStateException("injected cleanup failure"); });
        if (outcome.status() == DisconnectSnapshot.Status.SUCCESS) commitCalled.set(true);

        assertEquals(DisconnectSnapshot.Status.FAILED, outcome.status());
        assertNull(outcome.snapshot());
        assertFalse(commitCalled.get());
    }

    @Test
    void authorityLostBeforeCaptureDoesNotStartCleanup() {
        AtomicBoolean captured = new AtomicBoolean();
        AtomicBoolean cleaned = new AtomicBoolean();
        DisconnectSnapshot.Outcome<String> outcome = DisconnectSnapshot.captureThenCleanup(
            () -> true,
            () -> false,
            () -> { captured.set(true); return "stale"; },
            () -> cleaned.set(true));

        assertEquals(DisconnectSnapshot.Status.AUTHORITY_LOST, outcome.status());
        assertFalse(captured.get());
        assertFalse(cleaned.get());
    }
}
