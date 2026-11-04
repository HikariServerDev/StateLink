package com.atsukigames.statelink.sync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * UUIDごとのFIFOを保ちつつ、UUID間はround-robinでdispatchするbounded executor。
 * 保留上限は実行中を含み、Future chainやDB worker queueへ無制限にtaskを追加しない。
 */
public final class PerPlayerSerialExecutor implements AutoCloseable {
    public static final int DEFAULT_PER_PLAYER_LIMIT = 16;
    public static final int DEFAULT_TOTAL_PENDING_LIMIT = 256;

    public enum RejectionReason {
        PER_PLAYER_LIMIT,
        TOTAL_PENDING_LIMIT,
        SHUTTING_DOWN
    }

    /** Admissionはsubmit時点で即時確定する。rejected Futureは必ず理由付きで失敗する。 */
    public record Admission<T>(boolean accepted, RejectionReason rejectionReason, CompletableFuture<T> future) {
        public Admission {
            Objects.requireNonNull(future, "future");
            if (accepted == (rejectionReason != null)) {
                throw new IllegalArgumentException("accepted/rejectionReason mismatch");
            }
        }
    }

    public static final class QueueAdmissionException extends RejectedExecutionException {
        private final UUID uuid;
        private final RejectionReason reason;

        private QueueAdmissionException(UUID uuid, RejectionReason reason) {
            super("StateLink per-player DB queue rejected uuid=" + uuid + " reason=" + reason);
            this.uuid = uuid;
            this.reason = reason;
        }

        public UUID uuid() { return uuid; }
        public RejectionReason reason() { return reason; }
    }

    private enum TaskState { QUEUED, DISPATCHED, RUNNING, DONE, CANCELLED }

    private final class QueueState {
        private final UUID uuid;
        private final ArrayDeque<Task<?>> tasks = new ArrayDeque<>();
        private boolean running;
        private boolean inReadyQueue;

        private QueueState(UUID uuid) { this.uuid = uuid; }
    }

    private final class Task<T> {
        private final QueueState queue;
        private final Supplier<T> work;
        private final TaskFuture<T> future;
        private TaskState state = TaskState.QUEUED;

        private Task(QueueState queue, Supplier<T> work) {
            this.queue = queue;
            this.work = work;
            this.future = new TaskFuture<>(this);
        }
    }

    private final class TaskFuture<T> extends CompletableFuture<T> {
        private final Task<T> task;

        private TaskFuture(Task<T> task) { this.task = task; }

        private boolean cancelFromExecutor() { return super.cancel(false); }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            // Once dispatched, cancellation cannot retract a JDBC transaction reliably. The
            // caller gets false and the authoritative operation is allowed to finish normally.
            if (!cancelQueued(task)) return false;
            return super.cancel(false);
        }
    }

    private final Object lock = new Object();
    private final Executor executor;
    private final ExecutorService ownedService;
    private final int parallelism;
    private final int perPlayerLimit;
    private final int totalPendingLimit;
    private final Map<UUID, QueueState> queues = new HashMap<>();
    private final ArrayDeque<QueueState> readyPlayers = new ArrayDeque<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private int totalPending;
    private int dispatched;
    private int maxObservedTotalPending;
    private int maxObservedPlayerPending;
    private long rejectedPerPlayer;
    private long rejectedTotal;
    private boolean pumping;
    private boolean serviceShutdownRequested;
    private boolean immediateShutdown;

    public PerPlayerSerialExecutor(int parallelism, int queueCapacity) {
        this(parallelism, DEFAULT_PER_PLAYER_LIMIT,
            Math.max(DEFAULT_TOTAL_PENDING_LIMIT, Math.max(32, queueCapacity)));
    }

    public PerPlayerSerialExecutor(int parallelism, int perPlayerLimit, int totalPendingLimit) {
        int threads = Math.max(2, parallelism);
        if (perPlayerLimit < 1 || totalPendingLimit < perPlayerLimit) {
            throw new IllegalArgumentException("invalid per-player/total pending limits");
        }
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads,
            threads,
            30L,
            TimeUnit.SECONDS,
            // At most `threads` wrappers are dispatched by this class at once. This secondary
            // queue is also bounded to protect against executor shutdown/rejection races.
            new ArrayBlockingQueue<>(threads),
            runnable -> {
                Thread thread = new Thread(runnable, "StateLink-db");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor = pool;
        this.ownedService = pool;
        this.parallelism = threads;
        this.perPlayerLimit = perPlayerLimit;
        this.totalPendingLimit = totalPendingLimit;
    }

    /** Test seam for a controlled executor; only a bounded number of wrappers are submitted. */
    public PerPlayerSerialExecutor(Executor executor) {
        this(executor, 2, DEFAULT_PER_PLAYER_LIMIT, DEFAULT_TOTAL_PENDING_LIMIT);
    }

    /** Test seam allowing deterministic control of dispatch concurrency and admission limits. */
    public PerPlayerSerialExecutor(Executor executor, int parallelism, int perPlayerLimit,
                                   int totalPendingLimit) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedService = executor instanceof ExecutorService service ? service : null;
        if (parallelism < 1 || perPlayerLimit < 1 || totalPendingLimit < perPlayerLimit) {
            throw new IllegalArgumentException("invalid executor limits");
        }
        this.parallelism = parallelism;
        this.perPlayerLimit = perPlayerLimit;
        this.totalPendingLimit = totalPendingLimit;
    }

    public CompletableFuture<Void> submit(UUID uuid, Runnable task) {
        Objects.requireNonNull(task, "task");
        return submit(uuid, () -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submit(UUID uuid, Supplier<T> task) {
        return trySubmit(uuid, task).future();
    }

    public <T> Admission<T> trySubmit(UUID uuid, Supplier<T> task) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(task, "task");
        Task<T> acceptedTask;
        synchronized (lock) {
            if (!accepting.get()) return rejected(uuid, RejectionReason.SHUTTING_DOWN);
            QueueState queue = queues.get(uuid);
            int playerPending = queue == null ? 0 : queue.tasks.size();
            if (playerPending >= perPlayerLimit) {
                rejectedPerPlayer++;
                return rejected(uuid, RejectionReason.PER_PLAYER_LIMIT);
            }
            if (totalPending >= totalPendingLimit) {
                rejectedTotal++;
                return rejected(uuid, RejectionReason.TOTAL_PENDING_LIMIT);
            }
            if (queue == null) {
                queue = new QueueState(uuid);
                queues.put(uuid, queue);
            }
            acceptedTask = new Task<>(queue, task);
            queue.tasks.addLast(acceptedTask);
            totalPending++;
            maxObservedTotalPending = Math.max(maxObservedTotalPending, totalPending);
            maxObservedPlayerPending = Math.max(maxObservedPlayerPending, queue.tasks.size());
            enqueueReady(queue);
        }
        pump();
        return new Admission<>(true, null, acceptedTask.future);
    }

    private <T> Admission<T> rejected(UUID uuid, RejectionReason reason) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new QueueAdmissionException(uuid, reason));
        return new Admission<>(false, reason, future);
    }

    private void enqueueReady(QueueState queue) {
        if (!queue.running && !queue.inReadyQueue && !queue.tasks.isEmpty()) {
            queue.inReadyQueue = true;
            readyPlayers.addLast(queue);
        }
    }

    /** Dispatches at most `parallelism` accepted wrappers and rotates UUIDs fairly. */
    private void pump() {
        synchronized (lock) {
            if (pumping) return;
            pumping = true;
        }
        while (true) {
            Task<?> task;
            synchronized (lock) {
                if (immediateShutdown || dispatched >= parallelism || readyPlayers.isEmpty()) {
                    pumping = false;
                    break;
                }
                QueueState queue = readyPlayers.removeFirst();
                queue.inReadyQueue = false;
                task = queue.tasks.peekFirst();
                if (task == null || queue.running) continue;
                queue.running = true;
                task.state = TaskState.DISPATCHED;
                dispatched++;
            }
            try {
                executor.execute(new DispatchRunnable(task));
            } catch (Throwable dispatchError) {
                finish(task, null, dispatchError, TaskState.DISPATCHED);
            }
        }
        requestServiceShutdownIfIdle();
    }

    @SuppressWarnings("unchecked")
    private <T> void run(Task<T> task) {
        synchronized (lock) {
            if (task.state != TaskState.DISPATCHED) return;
            task.state = TaskState.RUNNING;
        }
        T value = null;
        Throwable error = null;
        try {
            value = task.work.get();
        } catch (Throwable taskError) {
            error = taskError;
        }
        finish(task, value, error, TaskState.RUNNING);
    }

    @SuppressWarnings("unchecked")
    private <T> void finish(Task<T> task, T value, Throwable error, TaskState expectedState) {
        synchronized (lock) {
            if (task.state != expectedState) return;
            QueueState queue = task.queue;
            if (queue.tasks.peekFirst() != task) {
                // Even an internal ordering fault must not leak admission capacity.
                if (!queue.tasks.remove(task)) return;
                task.state = TaskState.DONE;
                totalPending--;
                dispatched--;
                queue.running = false;
                if (queue.tasks.isEmpty()) queues.remove(queue.uuid, queue);
                else enqueueReady(queue);
                error = new IllegalStateException("per-player FIFO head changed before completion");
            } else {
                queue.tasks.removeFirst();
                task.state = TaskState.DONE;
                totalPending--;
                dispatched--;
                queue.running = false;
                if (queue.tasks.isEmpty()) {
                    queues.remove(queue.uuid, queue);
                } else {
                    enqueueReady(queue);
                }
            }
        }
        if (error != null) task.future.completeExceptionally(error);
        else task.future.complete(value);
        pump();
        requestServiceShutdownIfIdle();
    }

    private boolean cancelQueued(Task<?> task) {
        boolean removed = false;
        synchronized (lock) {
            if (task.state != TaskState.QUEUED) return false;
            QueueState queue = task.queue;
            removed = queue.tasks.remove(task);
            if (!removed) return false;
            task.state = TaskState.CANCELLED;
            totalPending--;
            if (queue.tasks.isEmpty()) {
                if (queue.inReadyQueue) {
                    readyPlayers.remove(queue);
                    queue.inReadyQueue = false;
                }
                queues.remove(queue.uuid, queue);
            } else if (!queue.running) {
                enqueueReady(queue);
            }
        }
        pump();
        requestServiceShutdownIfIdle();
        return true;
    }

    private void cancelDispatched(Task<?> task) {
        synchronized (lock) {
            if (task.state != TaskState.DISPATCHED) return;
            QueueState queue = task.queue;
            if (queue.tasks.peekFirst() != task) return;
            queue.tasks.removeFirst();
            task.state = TaskState.CANCELLED;
            totalPending--;
            dispatched--;
            queue.running = false;
            if (queue.tasks.isEmpty()) queues.remove(queue.uuid, queue);
            else enqueueReady(queue);
        }
        task.future.cancelFromExecutor();
        pump();
        requestServiceShutdownIfIdle();
    }

    public int queueCount() {
        synchronized (lock) { return queues.size(); }
    }

    /** Includes queued and running work for this UUID. */
    public int queueDepth(UUID uuid) {
        synchronized (lock) {
            QueueState state = queues.get(uuid);
            return state == null ? 0 : state.tasks.size();
        }
    }

    public QueueMetrics queueMetrics() {
        synchronized (lock) {
            return new QueueMetrics(totalPending, maxObservedTotalPending, maxObservedPlayerPending,
                perPlayerLimit, totalPendingLimit, dispatched, queues.size(), rejectedPerPlayer,
                rejectedTotal, accepting.get());
        }
    }

    public record QueueMetrics(int pending, int maxObservedPending, int maxObservedPlayerPending,
                               int perPlayerLimit, int totalPendingLimit, int dispatched,
                               int activePlayers, long rejectedPerPlayer, long rejectedTotal,
                               boolean accepting) {}

    /** Diagnostic snapshot; it has no bearing on scheduling or ordering. */
    public ExecutorMetrics metrics() {
        if (executor instanceof ThreadPoolExecutor pool) {
            return new ExecutorMetrics(pool.getActiveCount(), pool.getPoolSize(),
                pool.getQueue().size(), pool.getCompletedTaskCount());
        }
        return ExecutorMetrics.unavailable();
    }

    public record ExecutorMetrics(int active, int poolSize, int queued, long completed) {
        public static ExecutorMetrics unavailable() {
            return new ExecutorMetrics(-1, -1, -1, -1L);
        }
    }

    public void stopAccepting() {
        synchronized (lock) {
            accepting.set(false);
        }
        pump();
    }

    public boolean isAccepting() { return accepting.get(); }

    public void shutdown() {
        stopAccepting();
        requestServiceShutdownIfIdle();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (ownedService != null) {
            requestServiceShutdownIfIdle();
            return ownedService.awaitTermination(timeout, unit);
        }
        synchronized (lock) { return totalPending == 0; }
    }

    /** Cancels work not yet started; running DB transactions are interrupted but accounted until exit. */
    public void shutdownNow() {
        List<Task<?>> queuedToCancel = new ArrayList<>();
        synchronized (lock) {
            accepting.set(false);
            immediateShutdown = true;
            for (QueueState queue : new ArrayList<>(queues.values())) {
                for (Task<?> task : new ArrayList<>(queue.tasks)) {
                    if (task.state == TaskState.QUEUED) {
                        queue.tasks.remove(task);
                        task.state = TaskState.CANCELLED;
                        totalPending--;
                        queuedToCancel.add(task);
                    }
                }
                if (!queue.running && queue.tasks.isEmpty()) {
                    if (queue.inReadyQueue) readyPlayers.remove(queue);
                    queue.inReadyQueue = false;
                    queues.remove(queue.uuid, queue);
                }
            }
        }
        queuedToCancel.forEach(task -> task.future.cancelFromExecutor());

        if (ownedService != null) {
            List<Runnable> dropped = ownedService.shutdownNow();
            for (Runnable runnable : dropped) {
                if (runnable instanceof DispatchRunnable dispatch) cancelDispatched(dispatch.task);
            }
        }
        requestServiceShutdownIfIdle();
    }

    private final class DispatchRunnable implements Runnable {
        private final Task<?> task;
        private DispatchRunnable(Task<?> task) { this.task = task; }
        @Override public void run() { PerPlayerSerialExecutor.this.run(task); }
    }

    private void requestServiceShutdownIfIdle() {
        boolean request;
        synchronized (lock) {
            request = !accepting.get() && totalPending == 0 && ownedService != null
                && !serviceShutdownRequested;
            if (request) serviceShutdownRequested = true;
        }
        if (request) ownedService.shutdown();
    }

    @Override public void close() { shutdown(); }
}
