package com.atsukigames.statelink.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The exhaustion getter/setter were removed from HungerManager in 1.21.2. */
@Mixin(FoodData.class)
public interface HungerManagerAccessor {
    @Accessor("exhaustionLevel") float statelink$getExhaustion();
    @Accessor("exhaustionLevel") void statelink$setExhaustion(float value);
}
