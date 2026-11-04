package com.atsukigames.statelink.utils;

import net.minecraft.server.network.ServerPlayerEntity;

/** Trusted server-thread spatial lifecycle calls, never a packet/World-item permit. */
public final class SpatialMutationScope implements AutoCloseable {
    private record Permission(ServerPlayerEntity player, boolean loginRestore) {}
    private static final ThreadLocal<Permission> CURRENT = new ThreadLocal<>();
    private final Permission previous;
    private SpatialMutationScope(ServerPlayerEntity player, boolean loginRestore) {
        if (player.getServer() == null || !player.getServer().isOnThread())
            throw new IllegalStateException("Spatial lifecycle must run on server thread");
        previous = CURRENT.get();
        CURRENT.set(new Permission(player, loginRestore));
    }
    public static SpatialMutationScope vanillaLoginRestore(ServerPlayerEntity player) {
        return new SpatialMutationScope(player, true);
    }
    public static SpatialMutationScope authoritativeApply(ServerPlayerEntity player) {
        return new SpatialMutationScope(player, false);
    }
    public static boolean allowsWorldRestore(ServerPlayerEntity player) {
        Permission scope = CURRENT.get();
        return scope != null && scope.player == player && player.getServer().isOnThread();
    }
    public static boolean allowsAuthoritativeTeleport(ServerPlayerEntity player) {
        Permission scope = CURRENT.get();
        return scope != null && !scope.loginRestore && scope.player == player && player.getServer().isOnThread();
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
