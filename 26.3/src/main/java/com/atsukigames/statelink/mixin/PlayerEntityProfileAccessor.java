package com.atsukigames.statelink.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GameProfile and its PropertyMap are immutable since 1.21.9, so applying stored properties replaces the profile. */
@Mixin(Player.class)
public interface PlayerEntityProfileAccessor {
    @Mutable @Accessor("gameProfile") void statelink$setGameProfile(GameProfile profile);
}
