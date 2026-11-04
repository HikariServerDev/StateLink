package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDropOperationPermitTest {
    @Test
    void currentReadyAuthorityAllowsSpawnAtFinalBoundary() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object player = new Object();
        PlayerSessionContext context = ready(42L, 1_000L);
        var identity = identity(context, 42L);

        try (var scope = permit.duringDrop(player, true, true, context, identity)) {
            assertTrue(scope.allowedAtEntry());
            assertFalse(permit.isBlocked(player,
                (candidate, captured) -> candidate == player
                    && captured.context() == context
                    && captured.identity().equals(identity),
                ignored -> true));
        }
        assertTrue(permit.isBlocked(player, (candidate, captured) -> false, ignored -> true));
    }

    @Test
    void authorityLostAfterEntryDeniesWorldSpawnAndDoesNotRestoreStaleItem() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object player = new Object();
        PlayerSessionContext context = ready(42L, 1_000L);
        var identity = identity(context, 42L);
        AtomicBoolean spawned = new AtomicBoolean();

        try (var scope = permit.duringDrop(player, true, true, context, identity)) {
            assertTrue(scope.allowedAtEntry());
            context.markDegraded();
            if (!permit.isBlocked(player, (candidate, captured) ->
                    candidate == player && context.state() == SyncState.READY,
                    ignored -> true)) {
                spawned.set(true);
            }
            // No compensation into the stale Player is allowed after loss of authority.
        }

        assertFalse(spawned.get());
    }

    @Test
    void wrongPlayerAndReplacementSessionAreDenied() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object player = new Object();
        PlayerSessionContext original = ready(42L, 1_000L);
        var identity = identity(original, 42L);

        try (var scope = permit.duringDrop(player, true, true, original, identity)) {
            assertTrue(scope.allowedAtEntry());
            assertTrue(permit.isBlocked(new Object(), (candidate, captured) -> true, ignored -> false));
            PlayerSessionContext replacement = ready(43L, 1_000L);
            assertTrue(permit.isBlocked(player,
                (candidate, captured) -> captured.context() == replacement,
                ignored -> false));
        }
    }

    @Test
    void fenceMismatchAndLeaseExpiryAtBoundaryAreDenied() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object player = new Object();
        PlayerSessionContext context = ready(42L, 1_000L);
        var identity = identity(context, 42L);
        AtomicLong currentFence = new AtomicLong(43L);

        try (var scope = permit.duringDrop(player, true, true, context, identity)) {
            assertTrue(scope.allowedAtEntry());
            assertTrue(permit.isBlocked(player,
                (candidate, captured) -> captured.identity().fence() == currentFence.get(),
                ignored -> false));
            assertTrue(permit.isBlocked(player,
                (candidate, captured) -> context.hasLeaseSafetyAt(2_000L, 1_000L),
                ignored -> false));
        }
    }

    @Test
    void fakePlayerIsUnmanagedAndNestedScopesRestoreTheirParent() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object fake = new Object();
        Object normal = new Object();
        PlayerSessionContext context = ready(42L, 1_000L);

        try (var outer = permit.duringDrop(fake, false, true, null, null)) {
            assertTrue(outer.allowedAtEntry());
            assertFalse(permit.isBlocked(fake, (candidate, captured) -> false, ignored -> true));
            try (var nested = permit.duringDrop(normal, true, true, context, identity(context, 42L))) {
                assertTrue(nested.allowedAtEntry());
                assertTrue(permit.isBlocked(fake, (candidate, captured) -> false, ignored -> false));
            }
            assertFalse(permit.isBlocked(fake, (candidate, captured) -> false, ignored -> true));
        }
        assertTrue(permit.isBlocked(normal, (candidate, captured) -> false, ignored -> true));
    }

    @Test
    void exceptionAlwaysRestoresThreadLocalScope() {
        PlayerDropOperationPermit<Object> permit = new PlayerDropOperationPermit<>();
        Object player = new Object();
        PlayerSessionContext context = ready(42L, 1_000L);

        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = permit.duringDrop(player, true, true, context, identity(context, 42L))) {
                throw new IllegalStateException("injected vanilla failure");
            }
        });
        assertTrue(permit.isBlocked(player, (candidate, captured) -> false, ignored -> true));
    }

    private static PlayerSessionContext ready(long fence, long anchor) {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, fence);
        context.markOwnership(fence, 1L, anchor);
        assertTrue(context.markReadyIfSafe(true, true, false, anchor + 1, 10_000));
        return context;
    }

    private static DisconnectDropPermit.Identity identity(PlayerSessionContext context, long fence) {
        return new DisconnectDropPermit.Identity(
            context.uuid(), context.localSessionId(), context.generation(), fence);
    }
}
