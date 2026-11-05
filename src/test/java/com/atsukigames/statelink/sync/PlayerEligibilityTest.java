package com.atsukigames.statelink.sync;

import net.minecraft.server.network.ServerPlayerEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEligibilityTest {
    @Test
    void normalServerPlayerClassIsManaged() {
        assertTrue(PlayerEligibility.shouldManagePlayerClass(ServerPlayerEntity.class));
        assertFalse(PlayerEligibility.isCarpetFakePlayerClass(ServerPlayerEntity.class));
    }

    @Test
    void carpetFakePlayerBinaryNameIsUnmanagedWithoutCarpetDependency() {
        assertTrue(PlayerEligibility.isCarpetFakePlayerClassName(
            "carpet.patches.EntityPlayerMPFake"));
    }

    @Test
    void unrelatedPlayerNameIsNotExcluded() {
        assertFalse(PlayerEligibility.isCarpetFakePlayerClassName(
            "com.example.EntityPlayerMPFake"));
    }

    @Test
    void unmanagedPlayerBypassesQuarantineEvenWithoutASession() {
        assertFalse(PlayerEligibility.shouldQuarantine(false, false, null));
    }

    @Test
    void managedPlayerWithoutASessionStillFailsClosed() {
        assertTrue(PlayerEligibility.shouldQuarantine(true, false, null));
    }

    @Test
    void managedLoadingPlayerIsBlockedAndReadyPlayerIsNot() {
        assertTrue(PlayerEligibility.shouldQuarantine(true, true, SyncState.LOADING));
        assertFalse(PlayerEligibility.shouldQuarantine(true, true, SyncState.READY));
    }
}
