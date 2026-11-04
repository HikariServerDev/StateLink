package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents item and experience pickup while a player is quarantined. */
@Mixin({ItemEntity.class, ExperienceOrb.class})
public abstract class LoadingQuarantinePickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPickup(Player player, CallbackInfo info) {
        if (player instanceof ServerPlayer serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }
}
