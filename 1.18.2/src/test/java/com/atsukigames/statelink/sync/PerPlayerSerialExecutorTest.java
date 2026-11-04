package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerPlayerSerialExecutorTest {
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final PerPlayerSerialExecutor serial = new PerPlayerSerialExecutor(worker, 2, 16, 256);

    @AfterEach
    void tearDown() throws Exception {
        serial.shutdown();
        worker.shutdownNow();
        worker.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void samePlayerTasksKeepFifoAfterFirstTaskIsDelayed() throws Exception {
        UUID uuid = UUID.randomUUID();
        List<String> order = new ArrayList<>();
        CompletableFuture<Void> firstMayFinish = new CompletableFuture<>();
        CompletableFuture<Void> firstStarted = new CompletableFuture<>();

        CompletableFuture<Void> first = serial.submit(uuid, () -> {
            firstStarted.complete(null);
            firstMayFinish.join();
            order.add("A");
        });
        firstStarted.get(1, TimeUnit.SECONDS);
        CompletableFuture<Void> second = serial.submit(uuid, () -> { order.add("B"); });

        assertFalse(second.isDone(), "B must wait for A in the same UUID queue");
        firstMayFinish.complete(null);
        CompletableFuture.allOf(first, second).get(2, TimeUnit.SECONDS);

        assertEquals(List.of("A", "B"), order);
        assertEquals(0, serial.queueCount());
    }

    @Test
    void differentPlayersCanRunInParallel() throws Exception {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        CompletableFuture<Void> aMayFinish = new CompletableFuture<>();
        CompletableFuture<Void> aStarted = new CompletableFuture<>();
        CompletableFuture<Void> bStarted = new CompletableFuture<>();

        CompletableFuture<Void> a = serial.submit(playerA, () -> {
            aStarted.complete(null);
            aMayFinish.join();
        });
        aStarted.get(1, TimeUnit.SECONDS);
        CompletableFuture<Void> b = serial.submit(playerB, () -> { bStarted.complete(null); });

        bStarted.get(1, TimeUnit.SECONDS);
        b.get(1, TimeUnit.SECONDS);
        aMayFinish.complete(null);
        CompletableFuture.allOf(a, b).get(2, TimeUnit.SECONDS);
    }

    @Test
    void samePlayerFloodNeverExceedsHardLimitAndRejectsExplicitly() throws Exception {
        ManualExecutor stoppedWorker = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(stoppedWorker, 1, 16, 32);
        try {
            UUID uuid = UUID.randomUUID();
            int accepted = 0;
            int rejected = 0;
            for (int i = 0; i < 10_000; i++) {
                int value = i;
                var admission = bounded.trySubmit(uuid, () -> iValue(value));
                if (admission.accepted()) accepted++;
                else {
                    rejected++;
                    assertEquals(PerPlayerSerialExecutor.RejectionReason.PER_PLAYER_LIMIT,
                        admission.rejectionReason());
                    assertInstanceOf(PerPlayerSerialExecutor.QueueAdmissionException.class,
                        completionCause(admission.future()));
                }
            }
            assertEquals(16, accepted);
            assertEquals(9_984, rejected);
            assertEquals(16, bounded.queueDepth(uuid));
            assertEquals(16, bounded.queueMetrics().maxObservedPending());
            assertEquals(16, bounded.queueMetrics().maxObservedPlayerPending());
            assertEquals(1, stoppedWorker.size(), "only one wrapper is dispatched with one worker");

            while (stoppedWorker.runNext()) { }
            assertEquals(0, bounded.queueMetrics().pending());
            bounded.shutdown();
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    void totalLimitBoundsManyPlayersAndRoundRobinPreventsOnePlayerMonopoly() throws Exception {
        ManualExecutor controlled = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(controlled, 1, 4, 5);
        try {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            List<String> order = new ArrayList<>();
            var a1 = bounded.trySubmit(a, () -> order.add("A1"));
            var a2 = bounded.trySubmit(a, () -> order.add("A2"));
            var a3 = bounded.trySubmit(a, () -> order.add("A3"));
            var b1 = bounded.trySubmit(b, () -> order.add("B1"));
            var c1 = bounded.trySubmit(UUID.randomUUID(), () -> order.add("C1"));
            var overflow = bounded.trySubmit(UUID.randomUUID(), () -> order.add("overflow"));

            assertTrue(a1.accepted() && a2.accepted() && a3.accepted() && b1.accepted() && c1.accepted());
            assertFalse(overflow.accepted());
            assertEquals(PerPlayerSerialExecutor.RejectionReason.TOTAL_PENDING_LIMIT,
                overflow.rejectionReason());
            assertEquals(5, bounded.queueMetrics().maxObservedPending());

            while (controlled.runNext()) { }
            CompletableFuture.allOf(a1.future(), a2.future(), a3.future(), b1.future(), c1.future())
                .get(1, TimeUnit.SECONDS);
            assertEquals(List.of("A1", "B1", "C1", "A2", "A3"), order);
            assertEquals(0, bounded.queueMetrics().pending());
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    void concurrentProducersCannotExceedGlobalAdmissionLimit() throws Exception {
        ManualExecutor stoppedWorker = new ManualExecutor();
        int producers = 8;
        int totalLimit = 64;
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(stoppedWorker, 1, 4, totalLimit);
        ExecutorService submitters = Executors.newFixedThreadPool(producers);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger perPlayerRejected = new AtomicInteger();
        AtomicInteger totalRejected = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Integer>> futures =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<UUID> players = java.util.stream.IntStream.range(0, 32)
            .mapToObj(ignored -> UUID.randomUUID())
            .toList();
        try {
            for (int producer = 0; producer < producers; producer++) {
                int producerId = producer;
                submitters.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < 256; i++) {
                            UUID uuid = players.get((producerId * 17 + i) % players.size());
                            var admission = bounded.trySubmit(uuid, () -> producerId);
                            if (admission.accepted()) {
                                accepted.incrementAndGet();
                                futures.add(admission.future());
                            } else {
                                assertTrue(admission.rejectionReason()
                                        == PerPlayerSerialExecutor.RejectionReason.PER_PLAYER_LIMIT
                                        || admission.rejectionReason()
                                        == PerPlayerSerialExecutor.RejectionReason.TOTAL_PENDING_LIMIT);
                                if (admission.rejectionReason()
                                        == PerPlayerSerialExecutor.RejectionReason.PER_PLAYER_LIMIT) {
                                    perPlayerRejected.incrementAndGet();
                                } else {
                                    totalRejected.incrementAndGet();
                                }
                                rejected.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            submitters.shutdown();
            assertTrue(submitters.awaitTermination(3, TimeUnit.SECONDS));

            assertEquals(totalLimit, accepted.get());
            assertEquals(producers * 256 - totalLimit, rejected.get());
            assertEquals(rejected.get(), perPlayerRejected.get() + totalRejected.get());
            assertEquals(totalLimit, bounded.queueMetrics().pending());
            assertEquals(totalLimit, bounded.queueMetrics().maxObservedPending());
            assertTrue(bounded.queueMetrics().maxObservedPlayerPending() <= 4);

            while (stoppedWorker.runNext()) { }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
            assertEquals(0, bounded.queueMetrics().pending());
        } finally {
            start.countDown();
            submitters.shutdownNow();
            bounded.shutdownNow();
        }
    }

    @Test
    void queueFullAdmissionIsExplicitForSaveRenewalFinalAndHandoffOperations() {
        ManualExecutor stoppedWorker = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(stoppedWorker, 1, 2, 2);
        try {
            UUID uuid = UUID.randomUUID();
            var first = bounded.trySubmit(uuid, () -> "worker-blocker");
            var second = bounded.trySubmit(uuid, () -> "queued");
            assertTrue(first.accepted());
            assertTrue(second.accepted());

            CheckpointDirtyState dirty = new CheckpointDirtyState();
            dirty.committed(dirty.version());
            long capturedEpoch = dirty.markChanged();
            var rejectedSave = bounded.trySubmit(uuid, () -> "save-result");
            rejectedSave.future().whenComplete((ignored, error) -> {
                if (error == null) dirty.committed(capturedEpoch);
            });
            assertFalse(rejectedSave.accepted());
            assertTrue(dirty.isDirty(), "rejected SAVE cannot acknowledge the dirty generation");

            AtomicBoolean released = new AtomicBoolean();
            var rejectedFinal = bounded.trySubmit(uuid, () -> {
                released.set(true);
                return "clean-release";
            });
            rejectedFinal.future().whenComplete((ignored, error) -> {
                if (error == null) released.set(true);
            });
            assertFalse(rejectedFinal.accepted());
            assertFalse(released.get(), "rejected final SAVE must never cleanly release ownership");

            for (String operation : List.of("SAVE", "RENEWAL", "FINAL_SAVE", "HANDOFF")) {
                AtomicBoolean ran = new AtomicBoolean();
                var denied = bounded.trySubmit(uuid, () -> {
                    ran.set(true);
                    return operation;
                });
                assertFalse(denied.accepted(), operation + " must not be silently discarded or run");
                assertEquals(PerPlayerSerialExecutor.RejectionReason.PER_PLAYER_LIMIT,
                    denied.rejectionReason());
                assertInstanceOf(PerPlayerSerialExecutor.QueueAdmissionException.class,
                    completionCause(denied.future()));
                assertFalse(ran.get());
            }
            assertEquals(2, bounded.queueDepth(uuid));
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    void renewalWorkerQueueFullTriggersProductionFailClosedAdmissionPolicy() throws Exception {
        ThreadPoolExecutor renewalPool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1));
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean authorityReady = new AtomicBoolean(true);
        AtomicInteger rejectedRenewalRuns = new AtomicInteger();
        try {
            renewalPool.execute(() -> {
                active.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(active.await(1, TimeUnit.SECONDS));
            renewalPool.execute(() -> { }); // occupy the only waiting slot
            RejectedExecutionException rejection = assertThrows(RejectedExecutionException.class,
                () -> CompletableFuture.supplyAsync(() -> {
                    rejectedRenewalRuns.incrementAndGet();
                    return true;
                }, renewalPool));

            boolean handled = PlayerSyncCoordinator.failClosedForRenewalAdmission(
                rejection, () -> authorityReady.set(false));
            assertTrue(handled);
            assertFalse(authorityReady.get(), "a rejected renewal must not preserve READY authority");
            assertEquals(0, rejectedRenewalRuns.get(), "rejected renewal must not be reported as success");
            assertEquals(1, renewalPool.getQueue().size());
        } finally {
            release.countDown();
            renewalPool.shutdownNow();
            renewalPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelledQueuedTaskReleasesCapacityButRunningTaskCannotBeCancelled() throws Exception {
        ManualExecutor controlled = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(controlled, 1, 3, 3);
        try {
            UUID uuid = UUID.randomUUID();
            var running = bounded.trySubmit(uuid, () -> "first");
            var cancelled = bounded.trySubmit(uuid, () -> "cancelled");
            var last = bounded.trySubmit(uuid, () -> "last");
            assertFalse(running.future().cancel(true), "dispatched work cannot be interrupted by the caller");
            assertTrue(cancelled.future().cancel(true));
            assertEquals(2, bounded.queueDepth(uuid));
            var replacement = bounded.trySubmit(uuid, () -> "replacement");
            assertTrue(replacement.accepted(), "queued cancellation returns its admission slot");

            while (controlled.runNext()) { }
            assertEquals("first", running.future().get(1, TimeUnit.SECONDS));
            assertEquals("last", last.future().get(1, TimeUnit.SECONDS));
            assertEquals("replacement", replacement.future().get(1, TimeUnit.SECONDS));
            assertTrue(cancelled.future().isCancelled());
            assertEquals(0, bounded.queueMetrics().pending());
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    void taskExceptionDoesNotStopQueueAndCountersReturnToZero() throws Exception {
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> failed = serial.submit(uuid, () -> { throw new IllegalStateException("expected"); });
        CompletableFuture<Void> next = serial.submit(uuid, () -> { });

        assertThrows(ExecutionException.class, () -> failed.get(1, TimeUnit.SECONDS));
        next.get(1, TimeUnit.SECONDS);
        assertEquals(0, serial.queueDepth(uuid));
        assertEquals(0, serial.queueMetrics().pending());
    }

    @Test
    void rejectedUnderlyingExecutorDoesNotLeakQueueState() throws Exception {
        AtomicBoolean rejectFirstCommand = new AtomicBoolean(true);
        PerPlayerSerialExecutor local = new PerPlayerSerialExecutor(command -> {
            if (rejectFirstCommand.getAndSet(false)) throw new RejectedExecutionException("expected");
            command.run();
        }, 1, 4, 8);
        try {
            UUID uuid = UUID.randomUUID();
            CompletableFuture<Void> rejected = local.submit(uuid, () -> { });
            assertInstanceOf(RejectedExecutionException.class, completionCause(rejected));
            assertEquals(0, local.queueCount());

            local.submit(uuid, () -> { }).get(1, TimeUnit.SECONDS);
            assertEquals(0, local.queueMetrics().pending());
        } finally {
            local.shutdownNow();
        }
    }

    @Test
    void shutdownNowCancelsNotStartedTasksAndReleasesAllTheirPendingCounts() throws Exception {
        ThreadPoolExecutor oneThread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(4));
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(oneThread, 2, 8, 8);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var running = bounded.trySubmit(a, () -> {
            started.countDown();
            while (finish.getCount() > 0) {
                try { finish.await(); } catch (InterruptedException ignored) { /* remain blocked for assertion */ }
            }
            return "running";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        var queued = bounded.trySubmit(b, () -> "never-started");
        assertEquals(2, bounded.queueMetrics().pending());

        bounded.shutdownNow();
        assertTrue(queued.future().isCancelled());
        assertEquals(1, bounded.queueMetrics().pending(), "the interrupted running operation remains accounted");
        finish.countDown();
        assertTrue(bounded.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(0, bounded.queueMetrics().pending());
        assertTrue(running.future().isDone());
    }

    @Test
    void shutdownRejectsNewAdmissionAndCancelsCentralQueue() throws Exception {
        ManualExecutor controlled = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(controlled, 1, 4, 4);
        UUID uuid = UUID.randomUUID();
        var dispatched = bounded.trySubmit(uuid, () -> "first");
        var queued = bounded.trySubmit(uuid, () -> "second");
        bounded.shutdownNow();
        assertFalse(bounded.trySubmit(UUID.randomUUID(), () -> "late").accepted());
        assertTrue(queued.future().isCancelled());
        // The injected non-service executor owns the already-dispatched wrapper; run it to retire it.
        controlled.runNext();
        assertEquals("first", dispatched.future().get(1, TimeUnit.SECONDS));
        assertEquals(0, bounded.queueMetrics().pending());
    }

    @Test
    void delayedStaleFenceOperationIsRejectedAndFreshWorkRemainsOrdered() throws Exception {
        ManualExecutor stoppedWorker = new ManualExecutor();
        PerPlayerSerialExecutor bounded = new PerPlayerSerialExecutor(stoppedWorker, 1, 4, 8);
        try {
            UUID uuid = UUID.randomUUID();
            AtomicInteger currentFence = new AtomicInteger(7);
            AtomicInteger writes = new AtomicInteger();
            var oldFenceWrite = bounded.trySubmit(uuid, () -> {
                if (currentFence.get() != 6) return false;
                writes.incrementAndGet();
                return true;
            });
            var afterRecovery = bounded.trySubmit(uuid, () -> {
                if (currentFence.get() != 7) return false;
                writes.incrementAndGet();
                return true;
            });
            // Represents another owner recovering while the queued operation has not reached
            // its repository fence predicate. The queue cannot make the stale write succeed.
            currentFence.set(7);
            while (stoppedWorker.runNext()) { }

            assertFalse(oldFenceWrite.future().get(1, TimeUnit.SECONDS));
            assertTrue(afterRecovery.future().get(1, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            assertEquals(0, bounded.queueMetrics().pending());
        } finally {
            bounded.shutdownNow();
        }
    }

    private static Throwable completionCause(CompletableFuture<?> future) {
        try {
            future.get(1, TimeUnit.SECONDS);
            throw new AssertionError("future unexpectedly succeeded");
        } catch (ExecutionException expected) {
            return expected.getCause();
        } catch (Exception other) {
            throw new AssertionError("unexpected future outcome", other);
        }
    }

    private static int iValue(int value) { return value; }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { tasks.addLast(command); }
        synchronized int size() { return tasks.size(); }
        boolean runNext() {
            Runnable task;
            synchronized (this) { task = tasks.pollFirst(); }
            if (task == null) return false;
            task.run();
            return true;
        }
    }
}
