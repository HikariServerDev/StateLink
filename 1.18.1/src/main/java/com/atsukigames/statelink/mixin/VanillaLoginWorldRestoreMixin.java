package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.utils.SpatialMutationScope;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.18.2 onPlayerConnect loads local NBT and calls setWorld BEFORE Fabric JOIN.
 * Missing-session quarantine must not replace the locally saved dimension with
 * the constructor's Overworld. Only this exact vanilla initialization call is
 * exempt; packet handling and ordinary setWorld remain quarantined.
 */
@Mixin(PlayerManager.class)
public abstract class VanillaLoginWorldRestoreMixin {
    @Redirect(method = "onPlayerConnect", require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/ServerPlayerEntity;setWorld(Lnet/minecraft/server/world/ServerWorld;)V"))
    private void statelink$restoreVanillaWorld(ServerPlayerEntity player, ServerWorld world) {
        try (var ignored = SpatialMutationScope.vanillaLoginRestore(player)) {
            player.setWorld(world);
        }
    }
}
