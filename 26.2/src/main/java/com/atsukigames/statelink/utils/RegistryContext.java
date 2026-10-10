package com.atsukigames.statelink.utils;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;

/**
 * Item stacks are encoded with data components, which need the server's registries.
 * The running server publishes its registry lookup here when it starts.
 */
public final class RegistryContext {
    private static volatile HolderLookup.Provider lookup;

    private RegistryContext() {}

    public static void set(MinecraftServer server) {
        lookup = server == null ? null : server.registryAccess();
    }

    public static HolderLookup.Provider lookup() {
        HolderLookup.Provider current = lookup;
        if (current == null) throw new IllegalStateException("Server registries are not available yet");
        return current;
    }
}
