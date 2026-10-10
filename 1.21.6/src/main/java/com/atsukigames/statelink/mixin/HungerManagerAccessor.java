package com.atsukigames.statelink.mixin;

import net.minecraft.entity.player.HungerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The exhaustion getter/setter were removed from HungerManager in 1.21.2. */
@Mixin(HungerManager.class)
public interface HungerManagerAccessor {
    @Accessor("exhaustion") float statelink$getExhaustion();
    @Accessor("exhaustion") void statelink$setExhaustion(float value);
}
