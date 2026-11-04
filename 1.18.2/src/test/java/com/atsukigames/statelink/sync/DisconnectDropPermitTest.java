package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DisconnectDropPermitTest {
    private static final long SAFETY_BUDGET = 100L;

    @Test
    void validCurrentQuiescingAuthorityAllowsOnlyTheScopedPlayersCleanupDrop() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object player = new Object();
        PlayerSessionContext context = quiescing(17L, 1_000L);
        AtomicLong now = new AtomicLong(1_001L);
        AtomicReference<DisconnectDropPermit.Identity> current = new AtomicReference<>(identity(context, 17L));

        permit.duringCleanup(player, context, 17L,
            (candidate, token) -> candidate == player
                && token.equals(current.get())
                && context.hasLeaseSafetyAt(now.get(), SAFETY_BUDGET),
            () -> {
                assertFalse(permit.isBlocked(player, ignored -> true));
                assertTrue(permit.isBlocked(new Object(), ignored -> true));
            });

        assertTrue(permit.isBlocked(player, ignored -> true));
    }

    @Test
    void expiredReadySessionCannotEnterQuiescingOrBeginScreenCleanup() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object player = new Object();
        PlayerSessionContext context = ready(17L, 1_000L);

        assertFalse(context.beginQuiescingIfSafe(1_000L + SAFETY_BUDGET, SAFETY_BUDGET, 17L));
        assertEquals(SyncState.READY, context.state());

        List<String> worldDrops = new ArrayList<>();
        if (!permit.isBlocked(player, ignored -> true)) worldDrops.add("cursor-D");
        assertTrue(worldDrops.isEmpty());
    }

    @Test
    void leaseExpiryDuringCleanupImmediatelyDeniesTheNextDrop() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object player = new Object();
        PlayerSessionContext context = quiescing(17L, 1_000L);
        AtomicLong now = new AtomicLong(1_001L);
        AtomicReference<DisconnectDropPermit.Identity> current = new AtomicReference<>(identity(context, 17L));
        List<String> worldDrops = new ArrayList<>();

        permit.duringCleanup(player, context, 17L,
            (candidate, token) -> candidate == player
                && token.equals(current.get())
                && context.hasLeaseSafetyAt(now.get(), SAFETY_BUDGET),
            () -> {
                assertFalse(permit.isBlocked(player, ignored -> true));
                now.set(1_000L + SAFETY_BUDGET);
                if (!permit.isBlocked(player, ignored -> true)) worldDrops.add("cursor-D");
            });

        assertTrue(worldDrops.isEmpty());
    }

    @Test
    void wrongPlayerAndStaleSessionGenerationAreRejected() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object player = new Object();
        Object wrongPlayer = new Object();
        PlayerSessionContext context = quiescing(17L, 1_000L);
        AtomicReference<DisconnectDropPermit.Identity> current = new AtomicReference<>(identity(context, 17L));

        permit.duringCleanup(player, context, 17L,
            (candidate, token) -> candidate == player && token.equals(current.get()),
            () -> {
                // Even a normally-READY unrelated player cannot use another
                // player's active cleanup scope to authorize a nested drop.
                assertTrue(permit.isBlocked(wrongPlayer, ignored -> false));
                assertFalse(permit.isBlocked(player, ignored -> true));
                PlayerSessionContext replacement = new PlayerSessionContext(context.uuid(), null, context.generation() + 1);
                current.set(identity(replacement, 17L));
                assertTrue(permit.isBlocked(player, ignored -> true));
            });
    }

    @Test
    void staleFenceIsRejectedEvenIfPlayerAndSessionMatch() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object player = new Object();
        PlayerSessionContext context = quiescing(17L, 1_000L);

        permit.duringCleanup(player, context, 17L,
            (candidate, token) -> candidate == player && token.fence() == context.fencingToken(),
            () -> {
                context.markOwnership(18L, 2L, 1_001L);
                assertTrue(permit.isBlocked(player, ignored -> true));
            });
    }

    @Test
    void expiredOldOwnerCannotDropCursorAfterTakeoverEvenBeforeWatchdogRuns() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object oldPlayer = new Object();
        PlayerSessionContext oldContext = ready(100L, 1_000L);
        AtomicLong now = new AtomicLong(1_001L);
        AtomicReference<DisconnectDropPermit.Identity> databaseOwner =
            new AtomicReference<>(identity(oldContext, 100L));
        List<String> oldWorldDrops = new ArrayList<>();
        List<String> newOwnerInventory = new ArrayList<>(List.of("cursor-D"));
        AtomicInteger finalSaves = new AtomicInteger();

        // S2 took over and loaded the only authoritative copy. S1's old local
        // READY state has not yet been updated by the watchdog.
        databaseOwner.set(new DisconnectDropPermit.Identity(
            oldContext.uuid(), UUID.randomUUID(), oldContext.generation() + 1L, 101L));
        now.set(1_000L + SAFETY_BUDGET);

        boolean enteredQuiescing = oldContext.beginQuiescingIfSafe(now.get(), SAFETY_BUDGET, 100L);
        if (enteredQuiescing) {
            permit.duringCleanup(oldPlayer, oldContext, 100L,
                (candidate, identity) -> candidate == oldPlayer && identity.equals(databaseOwner.get())
                    && oldContext.hasLeaseSafetyAt(now.get(), SAFETY_BUDGET),
                () -> {
                    if (!permit.isBlocked(oldPlayer, ignored -> false)) oldWorldDrops.add("cursor-D");
                    finalSaves.incrementAndGet();
                });
        }

        assertFalse(enteredQuiescing);
        assertTrue(oldWorldDrops.isEmpty());
        assertEquals(0, finalSaves.get());
        assertEquals(List.of("cursor-D"), newOwnerInventory);
    }

    @Test
    void nestedPermitIsRestoredAndExceptionAlwaysClearsTheScope() {
        DisconnectDropPermit<Object> permit = new DisconnectDropPermit<>();
        Object first = new Object();
        Object second = new Object();
        PlayerSessionContext firstContext = quiescing(17L, 1_000L);
        PlayerSessionContext secondContext = quiescing(18L, 2_000L);

        assertThrows(IllegalStateException.class, () -> permit.duringCleanup(
            first, firstContext, 17L, (candidate, token) -> candidate == first,
            () -> {
                assertFalse(permit.isBlocked(first, ignored -> true));
                permit.duringCleanup(second, secondContext, 18L,
                    (candidate, token) -> candidate == second,
                    () -> {
                        assertTrue(permit.isBlocked(first, ignored -> true));
                        assertFalse(permit.isBlocked(second, ignored -> true));
                    });
                assertFalse(permit.isBlocked(first, ignored -> true));
                throw new IllegalStateException("injected cleanup failure");
            }));

        assertTrue(permit.isBlocked(first, ignored -> true));
        assertTrue(permit.isBlocked(second, ignored -> true));
    }

    private static PlayerSessionContext ready(long fence, long anchor) {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 4L);
        context.markOwnership(fence, 3L, anchor);
        assertTrue(context.markReadyIfSafe(true, true, false, anchor + 1L, SAFETY_BUDGET));
        return context;
    }

    private static PlayerSessionContext quiescing(long fence, long anchor) {
        PlayerSessionContext context = ready(fence, anchor);
        assertTrue(context.beginQuiescingIfSafe(anchor + 2L, SAFETY_BUDGET, fence));
        return context;
    }

    private static DisconnectDropPermit.Identity identity(PlayerSessionContext context, long fence) {
        return new DisconnectDropPermit.Identity(
            context.uuid(), context.localSessionId(), context.generation(), fence);
    }
}
