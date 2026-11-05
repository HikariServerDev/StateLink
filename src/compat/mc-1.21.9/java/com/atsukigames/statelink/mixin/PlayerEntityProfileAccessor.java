package com.atsukigames.statelink.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GameProfile and its PropertyMap are immutable since 1.21.9, so applying stored properties replaces the profile. */
@Mixin(PlayerEntity.class)
public interface PlayerEntityProfileAccessor {
    @Mutable @Accessor("gameProfile") void statelink$setGameProfile(GameProfile profile);
}
