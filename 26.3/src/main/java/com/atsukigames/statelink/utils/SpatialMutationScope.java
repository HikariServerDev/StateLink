package com.atsukigames.statelink.utils;

import net.minecraft.server.level.ServerPlayer;

/** Trusted server-thread spatial lifecycle calls, never a packet/World-item permit. */
public final class SpatialMutationScope implements AutoCloseable {
    private record Permission(ServerPlayer player, boolean loginRestore) {}
    private static final ThreadLocal<Permission> CURRENT = new ThreadLocal<>();
    private final Permission previous;
    private SpatialMutationScope(ServerPlayer player, boolean loginRestore) {
        if (player.level().getServer() == null || !player.level().getServer().isSameThread())
            throw new IllegalStateException("Spatial lifecycle must run on server thread");
        previous = CURRENT.get();
        CURRENT.set(new Permission(player, loginRestore));
    }
    public static SpatialMutationScope vanillaLoginRestore(ServerPlayer player) {
        return new SpatialMutationScope(player, true);
    }
    public static SpatialMutationScope authoritativeApply(ServerPlayer player) {
        return new SpatialMutationScope(player, false);
    }
    public static boolean allowsWorldRestore(ServerPlayer player) {
        Permission scope = CURRENT.get();
        return scope != null && scope.player == player && player.level().getServer().isSameThread();
    }
    public static boolean allowsAuthoritativeTeleport(ServerPlayer player) {
        Permission scope = CURRENT.get();
        return scope != null && !scope.loginRestore && scope.player == player && player.level().getServer().isSameThread();
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
