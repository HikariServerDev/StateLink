package com.atsukigames.statelink.mixin;

import net.minecraft.entity.EntityEquipment;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Equipment moved out of PlayerInventory in 1.21.5; it is stored on the entity. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("equipment") EntityEquipment statelink$equipment();
}
