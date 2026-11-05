package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents item and experience pickup while a player is quarantined. */
@Mixin({ItemEntity.class, ExperienceOrbEntity.class})
public abstract class LoadingQuarantinePickupMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPickup(PlayerEntity player, CallbackInfo info) {
        if (player instanceof ServerPlayerEntity serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }
}
