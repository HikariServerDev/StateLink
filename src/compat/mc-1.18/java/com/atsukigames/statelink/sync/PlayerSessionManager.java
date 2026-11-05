package com.atsukigames.statelink.sync;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 現在のUUIDごとのセッションを管理する。 */
public final class PlayerSessionManager {
    private final ConcurrentMap<UUID, PlayerSessionContext> current = new ConcurrentHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();

    public PlayerSessionContext create(ServerPlayerEntity player) {
        return create(player.getUuid(), player);
    }

    public PlayerSessionContext create(UUID uuid) {
        return create(uuid, null);
    }

    private PlayerSessionContext create(UUID uuid, ServerPlayerEntity player) {
        PlayerSessionContext context = new PlayerSessionContext(uuid, player, nextGeneration.incrementAndGet());
        current.put(uuid, context);
        return context;
    }

    public PlayerSessionContext current(UUID uuid) {
        return current.get(uuid);
    }

    /**
     * Central eligibility gate. All lifecycle and quarantine callers use this
     * method so an unmanaged local entity cannot be mistaken for a broken
     * network-player session.
     */
    public boolean shouldManagePlayer(ServerPlayerEntity player) {
        return PlayerEligibility.shouldManagePlayer(player);
    }

    /**
     * 非同期処理の結果を適用してよいかをMC thread上で判定するための一元化された検査。
     */
    public boolean isCurrent(PlayerSessionContext context, ServerPlayerEntity currentPlayer) {
        return context != null
            && current.get(context.uuid()) == context
            && !context.isClosed()
            && currentPlayer != null
            && context.playerForServerThread() == currentPlayer
            && currentPlayer.getUuid().equals(context.uuid());
    }

    public boolean isCurrent(PlayerSessionContext context) {
        return context != null && current.get(context.uuid()) == context && !context.isClosed();
    }

    public void updatePlayer(PlayerSessionContext context, ServerPlayerEntity player) {
        if (isCurrent(context) && player.getUuid().equals(context.uuid())) {
            context.updatePlayerOnServerThread(player);
        }
    }

    public boolean remove(PlayerSessionContext context) {
        return current.remove(context.uuid(), context);
    }

    public boolean isBlocked(UUID uuid) {
        PlayerSessionContext context = current.get(uuid);
        // StateLinkが有効な間にsessionがまだ作られていないPlayerもfail closedにする。
        // JOIN callbackとの間にpacketが届く短い窓や、異常終了後にmapだけが
        // 欠落した状態で、vanillaの操作を通してlocal stateをworldへ出さない。
        return context == null || context.state() != SyncState.READY;
    }

    /**
     * Quarantine decision for a concrete entity. Unmanaged entities, such as
     * Carpet FakePlayers, must never enter the missing-session fail-closed path.
     * Managed normal players retain the existing fail-closed behavior.
     */
    public boolean isBlocked(ServerPlayerEntity player) {
        return isBlocked(player, Long.MAX_VALUE);
    }

    public boolean isBlocked(ServerPlayerEntity player, long leaseSafetyBudgetNanos) {
        if (!shouldManagePlayer(player)) return false;
        PlayerSessionContext context = current.get(player.getUuid());
        return context == null
            || context.playerForServerThread() != player
            || !context.isAuthoritativeDataLoaded()
            || PlayerEligibility.shouldQuarantine(
            true,
            true,
            context.state(),
            context.hasLeaseSafetyAt(System.nanoTime(), leaseSafetyBudgetNanos));
    }

    public java.util.Collection<PlayerSessionContext> snapshot() {
        return java.util.List.copyOf(current.values());
    }
}
