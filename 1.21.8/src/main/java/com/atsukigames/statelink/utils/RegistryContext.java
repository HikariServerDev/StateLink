package com.atsukigames.statelink.utils;

import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;

/**
 * Item stacks are encoded with data components, which need the server's registries.
 * The running server publishes its registry lookup here when it starts.
 */
public final class RegistryContext {
    private static volatile RegistryWrapper.WrapperLookup lookup;

    private RegistryContext() {}

    public static void set(MinecraftServer server) {
        lookup = server == null ? null : server.getRegistryManager();
    }

    public static RegistryWrapper.WrapperLookup lookup() {
        RegistryWrapper.WrapperLookup current = lookup;
        if (current == null) throw new IllegalStateException("Server registries are not available yet");
        return current;
    }
}
