package com.atsukigames.statelink.mixin;

import java.util.Set;
import net.minecraft.stat.Stat;
import net.minecraft.stat.ServerStatHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerStatHandler.class)
public interface ServerStatHandlerAccess {
    @Accessor("pendingStats") Set<Stat<?>> statelink$pendingStats();
}
