package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops server-side movement and requested teleports during quarantine. */
@Mixin(Entity.class)
public abstract class LoadingQuarantineMovementMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void statelink$blockServerMovement(
        MovementType movementType,
        Vec3d movement,
        CallbackInfo info
    ) {
        if ((Object) this instanceof ServerPlayerEntity serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }

    @Inject(method = "requestTeleport", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRequestedTeleport(CallbackInfo info) {
        if ((Object) this instanceof ServerPlayerEntity serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }

    @Inject(method = "updatePassengerPosition", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPassengerMovement(
        Entity passenger,
        CallbackInfo info
    ) {
        // A vehicle updates its passenger with a direct position callback,
        // bypassing Entity.move. Do not let a stale/loading player be carried
        // through the world while authoritative data is being applied.
        if (passenger instanceof ServerPlayerEntity serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }
}
