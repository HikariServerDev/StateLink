package com.atsukigames.statelink.mixin;

import java.util.Set;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerStatsCounter.class)
public interface ServerStatHandlerAccess {
    @Accessor("dirty") Set<Stat<?>> statelink$pendingStats();
}
