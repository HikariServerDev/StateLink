package com.atsukigames.statelink.sync;

import net.minecraft.server.level.ServerPlayer;

/**
 * Defines which server-side player entities are owned by StateLink.
 *
 * <p>Carpet FakePlayers are registered through the normal PlayerManager path,
 * but they are local automation entities rather than network players. Their
 * lifecycle is intentionally outside StateLink: no session, quarantine, database
 * ownership, load, save, lease, or final flush is created for them.</p>
 *
 * <p>This class deliberately does not link against Carpet. The optional Carpet
 * mod may be absent, so the decision is made from the stable binary class name
 * while walking the runtime superclass chain. Subclasses of the Carpet fake
 * player are excluded as well.</p>
 */
public final class PlayerEligibility {
    static final String CARPET_FAKE_PLAYER_CLASS = "carpet.patches.EntityPlayerMPFake";

    private PlayerEligibility() {
    }

    /** Returns whether StateLink may create and operate a session for this entity. */
    public static boolean shouldManagePlayer(ServerPlayer player) {
        return player != null && shouldManagePlayerClass(player.getClass());
    }

    /** Package-visible for deterministic tests without constructing a Minecraft player. */
    static boolean shouldManagePlayerClass(Class<?> playerType) {
        return !isCarpetFakePlayerClass(playerType);
    }

    /** Package-visible so tests can pin the optional-mod detection contract. */
    static boolean isCarpetFakePlayerClass(Class<?> playerType) {
        for (Class<?> type = playerType; type != null; type = type.getSuperclass()) {
            if (isCarpetFakePlayerClassName(type.getName())) return true;
        }
        return false;
    }

    static boolean isCarpetFakePlayerClassName(String className) {
        return CARPET_FAKE_PLAYER_CLASS.equals(className);
    }

    /** Pure state rule shared by the session manager and its unit tests. */
    static boolean shouldQuarantine(boolean managed, boolean sessionPresent, SyncState state) {
        return shouldQuarantine(managed, sessionPresent, state, state == SyncState.READY);
    }

    static boolean shouldQuarantine(
        boolean managed,
        boolean sessionPresent,
        SyncState state,
        boolean leaseWithinSafetyBudget
    ) {
        if (!managed) return false;
        return !sessionPresent || state != SyncState.READY || !leaseWithinSafetyBudget;
    }
}
