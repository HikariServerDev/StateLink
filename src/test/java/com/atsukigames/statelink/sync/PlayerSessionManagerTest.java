package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSessionManagerTest {
    @Test
    void staleSessionCannotRemainCurrentAfterImmediateReconnect() {
        PlayerSessionManager manager = new PlayerSessionManager();
        UUID uuid = UUID.randomUUID();

        PlayerSessionContext sessionA = manager.create(uuid);
        PlayerSessionContext sessionB = manager.create(uuid);

        assertFalse(manager.isCurrent(sessionA));
        assertTrue(manager.isCurrent(sessionB));
        assertNotEquals(sessionA.localSessionId(), sessionB.localSessionId());
        assertNotEquals(sessionA.generation(), sessionB.generation());
    }

    @Test
    void closedSessionCannotBecomeCurrentAgain() {
        PlayerSessionManager manager = new PlayerSessionManager();
        UUID uuid = UUID.randomUUID();
        PlayerSessionContext session = manager.create(uuid);

        session.closeWithoutSave();

        assertFalse(manager.isCurrent(session));
    }

    @Test
    void missingSessionIsBlockedFailClosed() {
        PlayerSessionManager manager = new PlayerSessionManager();

        assertTrue(manager.isBlocked(UUID.randomUUID()));
    }

    @Test
    void oldFlushingCompletionCannotRemoveAReconnectedSession() {
        PlayerSessionManager manager = new PlayerSessionManager();
        UUID uuid = UUID.randomUUID();
        PlayerSessionContext oldSession = manager.create(uuid);
        long anchor = System.nanoTime();
        oldSession.markOwnership(10L, 0L, anchor);
        assertTrue(oldSession.markReadyIfSafe(true, true, false, anchor + 1, Long.MAX_VALUE));
        assertTrue(oldSession.beginQuiescingIfSafe(anchor + 2, Long.MAX_VALUE, 10L));
        assertTrue(oldSession.beginFlushing(true, true, anchor + 3, Long.MAX_VALUE, 10L));

        PlayerSessionContext reconnected = manager.create(uuid);

        assertTrue(manager.isCurrent(reconnected));
        assertFalse(manager.remove(oldSession));
        assertTrue(manager.isCurrent(reconnected));
    }
}
