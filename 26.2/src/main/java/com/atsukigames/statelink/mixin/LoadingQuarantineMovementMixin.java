package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops server-side movement and requested teleports during quarantine. */
@Mixin(Entity.class)
public abstract class LoadingQuarantineMovementMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void statelink$blockServerMovement(
        MoverType movementType,
        Vec3 movement,
        CallbackInfo info
    ) {
        if ((Object) this instanceof ServerPlayer serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRequestedTeleport(CallbackInfo info) {
        if ((Object) this instanceof ServerPlayer serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }

    @Inject(method = "positionRider", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPassengerMovement(
        Entity passenger,
        CallbackInfo info
    ) {
        // A vehicle updates its passenger with a direct position callback,
        // bypassing Entity.move. Do not let a stale/loading player be carried
        // through the world while authoritative data is being applied.
        if (passenger instanceof ServerPlayer serverPlayer
                && StateLink.isPlayerQuarantined(serverPlayer)) {
            info.cancel();
        }
    }
}
