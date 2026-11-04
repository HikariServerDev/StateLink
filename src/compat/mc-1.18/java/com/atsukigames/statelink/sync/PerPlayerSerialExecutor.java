package com.atsukigames.statelink.sync;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UUIDごとにタスクを直列化し、異なるUUIDはbounded pool上で並列に実行する。
 * 先行タスクの例外は次のタスクへ伝播させず、queue全体を停止させない。
 */
public final class PerPlayerSerialExecutor implements AutoCloseable {
    private static final class QueueState {
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private int pending;
        /**
         * pendingが0になった後に、古いQueueStateへ新しいtaskを付けないための印。
         * queues.removeとsubmitの間には、必ずこのstate上の同期境界が必要になる。
         */
        private boolean retired;
    }

    private final Executor executor;
    private final ConcurrentMap<UUID, QueueState> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public PerPlayerSerialExecutor(int parallelism, int queueCapacity) {
        int threads = Math.max(2, parallelism);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads,
            threads,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(32, queueCapacity)),
            runnable -> {
                Thread thread = new Thread(runnable, "StateLink-db");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor = pool;
    }

    /** テストや呼び出し側がexecutorを制御する場合に使用する。 */
    public PerPlayerSerialExecutor(Executor executor) {
        this.executor = executor;
    }

    public CompletableFuture<Void> submit(UUID uuid, Runnable task) {
        return submit(uuid, () -> {
            task.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> submit(UUID uuid, java.util.function.Supplier<T> task) {
        if (!accepting.get()) {
            return failedFuture(new RejectedExecutionException("per-player executor is stopping"));
        }

        while (true) {
            if (!accepting.get()) {
                return failedFuture(new RejectedExecutionException("per-player executor is stopping"));
            }

            QueueState state = queues.computeIfAbsent(uuid, ignored -> new QueueState());
            CompletableFuture<T> next;
            CompletableFuture<T> exposed = new CompletableFuture<>();
            synchronized (state) {
                // finish()がこのstateをretireしてmapから外した直後に、古い参照を
                // 持っていたsubmitがここへ到達する可能性がある。その場合は新しい
                // stateを取り直す。これをしないと同一UUIDのqueueが二本に分岐する。
                if (state.retired) continue;
                if (!accepting.get()) {
                    return failedFuture(new RejectedExecutionException("per-player executor is stopping"));
                }
                state.pending++;
                try {
                    CompletableFuture<Void> predecessor = state.tail;
                    next = predecessor.handle((ignored, error) -> null)
                        .thenApplyAsync(ignored -> task.get(), executor);
                    state.tail = next.handle((ignored, error) -> null);
                } catch (Throwable schedulingError) {
                    // predecessorが既に完了している場合、thenApplyAsyncはexecutorの
                    // rejectをsubmit中に同期的に投げることがある。pendingを残すと
                    // queueが永久にleakするため、後続を止めないfailed futureにする。
                    next = failedFuture(schedulingError);
                }
            }
            next.whenComplete((value, error) -> {
                // The externally visible future is completed only after the
                // queue state has been retired.  Otherwise allOf()/get() can
                // observe a completed task while queueCount still contains a
                // one-element stale state, even though ordering is correct.
                finish(uuid, state);
                if (error != null) exposed.completeExceptionally(error);
                else exposed.complete(value);
            });
            return exposed;
        }
    }

    private void finish(UUID uuid, QueueState state) {
        synchronized (state) {
            state.pending--;
            if (state.pending == 0) {
                state.retired = true;
                queues.remove(uuid, state);
            }
        }
    }

    public int queueCount() {
        return queues.size();
    }

    /** Diagnostic snapshot; it has no bearing on scheduling or ordering. */
    public int queueDepth(UUID uuid) {
        QueueState state = queues.get(uuid);
        if (state == null) return 0;
        synchronized (state) {
            return state.pending;
        }
    }

    /** Returns the bounded pool state when this instance owns a ThreadPoolExecutor. */
    public ExecutorMetrics metrics() {
        if (executor instanceof ThreadPoolExecutor pool) {
            return new ExecutorMetrics(
                pool.getActiveCount(),
                pool.getPoolSize(),
                pool.getQueue().size(),
                pool.getCompletedTaskCount());
        }
        return ExecutorMetrics.unavailable();
    }

    public record ExecutorMetrics(int active, int poolSize, int queued, long completed) {
        public static ExecutorMetrics unavailable() {
            return new ExecutorMetrics(-1, -1, -1, -1L);
        }
    }

    public void stopAccepting() {
        accepting.set(false);
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public void shutdown() {
        stopAccepting();
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdown();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            return service.awaitTermination(timeout, unit);
        }
        return queues.isEmpty();
    }

    public void shutdownNow() {
        stopAccepting();
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    @Override
    public void close() {
        shutdown();
    }
}
