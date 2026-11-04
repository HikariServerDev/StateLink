package com.atsukigames.statelink.sync;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Immutable checkpoint payload bound to the dirty epoch observed while it was captured.
 * A save must carry this object through queueing; it must never look up a newer epoch later.
 */
public final class CheckpointCapture<T> {
    private final T snapshot;
    private final long changeVersion;
    private final long capturedAtNanos;

    private CheckpointCapture(T snapshot, long changeVersion, long capturedAtNanos) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.changeVersion = changeVersion;
        this.capturedAtNanos = capturedAtNanos;
    }

    public static <T> CheckpointCapture<T> capture(LongSupplier currentVersion, Supplier<T> snapshotSupplier) {
        return capture(currentVersion, snapshotSupplier, System::nanoTime);
    }

    static <T> CheckpointCapture<T> capture(
        LongSupplier currentVersion,
        Supplier<T> snapshotSupplier,
        LongSupplier monotonicClock
    ) {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        Objects.requireNonNull(monotonicClock, "monotonicClock");
        long capturedVersion = currentVersion.getAsLong();
        long capturedAtNanos = monotonicClock.getAsLong();
        T snapshot = Objects.requireNonNull(snapshotSupplier.get(), "snapshotSupplier returned null");
        if (currentVersion.getAsLong() != capturedVersion) {
            throw new IllegalStateException("checkpoint changed while its snapshot was being captured");
        }
        return new CheckpointCapture<>(snapshot, capturedVersion, capturedAtNanos);
    }

    public T snapshot() { return snapshot; }
    public long changeVersion() { return changeVersion; }
    /** Monotonic lower-bound for when this captured state was observed. */
    public long capturedAtNanos() { return capturedAtNanos; }
}
