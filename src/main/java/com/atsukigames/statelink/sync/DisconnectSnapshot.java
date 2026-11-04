package com.atsukigames.statelink.sync;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Server-thread-only prepare/capture/cleanup boundary for a disconnect snapshot. */
final class DisconnectSnapshot {
    private DisconnectSnapshot() {
    }

    static <T> Outcome<T> captureThenCleanup(
        BooleanSupplier onServerThread,
        BooleanSupplier authorityValid,
        Supplier<T> captureCompleteSnapshot,
        Runnable clearCapturedTransientStateAndCloseScreen
    ) {
        Objects.requireNonNull(onServerThread, "onServerThread");
        Objects.requireNonNull(authorityValid, "authorityValid");
        Objects.requireNonNull(captureCompleteSnapshot, "captureCompleteSnapshot");
        Objects.requireNonNull(clearCapturedTransientStateAndCloseScreen, "cleanup");

        if (!onServerThread.getAsBoolean()) {
            return Outcome.failed(new IllegalStateException(
                "Player disconnect snapshot must run on the Minecraft server thread"));
        }
        if (!authorityValid.getAsBoolean()) return Outcome.authorityLost();

        try {
            T snapshot = Objects.requireNonNull(captureCompleteSnapshot.get(), "snapshot");
            // The immutable payload exists before any transient reference is cleared.
            if (!authorityValid.getAsBoolean()) return Outcome.authorityLost();

            clearCapturedTransientStateAndCloseScreen.run();
            // Cleanup may invoke vanilla/mod hooks. Require authority again at its end,
            // even though StateLink itself performs no World drop during this operation.
            if (!authorityValid.getAsBoolean()) return Outcome.authorityLost();
            return Outcome.success(snapshot);
        } catch (Throwable failure) {
            return Outcome.failed(failure);
        }
    }

    enum Status {
        SUCCESS,
        FAILED,
        AUTHORITY_LOST
    }

    record Outcome<T>(Status status, T snapshot, Throwable failure) {
        static <T> Outcome<T> success(T snapshot) {
            return new Outcome<>(Status.SUCCESS, Objects.requireNonNull(snapshot), null);
        }

        static <T> Outcome<T> failed(Throwable failure) {
            return new Outcome<>(Status.FAILED, null, Objects.requireNonNull(failure));
        }

        static <T> Outcome<T> authorityLost() {
            return new Outcome<>(Status.AUTHORITY_LOST, null, null);
        }
    }
}
