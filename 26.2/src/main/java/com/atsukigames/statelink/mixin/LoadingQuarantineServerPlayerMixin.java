package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gates direct ServerPlayerEntity side-effect entry points. */
@Mixin(ServerPlayer.class)
public abstract class LoadingQuarantineServerPlayerMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDrop(CallbackInfo info) {
        if (StateLink.isPlayerDropQuarantined((ServerPlayer) (Object) this)) info.cancel();
    }

    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void statelink$blockDirectDrop(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        CallbackInfoReturnable<ItemEntity> info
    ) {
        if (StateLink.isPlayerDropQuarantined((ServerPlayer) (Object) this)) {
            info.setReturnValue(null);
        }
    }

    /**
     * Binds the in-flight vanilla drop to the session/fence seen at method entry.
     * The scope is restored even when vanilla or another mod throws.
     */
    @WrapMethod(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;")
    private ItemEntity statelink$scopePlayerDrop(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        Operation<ItemEntity> original
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        try (var scope = StateLink.beginPlayerDropOperation(player)) {
            if (!scope.allowedAtEntry()) return null;
            return original.call(stack, throwRandomly, retainOwnership);
        }
    }

    /**
     * R219-1 guard: an entry check cannot authorize a later irreversible world
     * mutation after the lease/session has become stale. This is deliberately at
     * ServerPlayerEntity's item-only spawn invocation, not a global world hook.
     */
    @Inject(
        method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void statelink$revalidateBeforeWorldSpawn(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        CallbackInfoReturnable<ItemEntity> info
    ) {
        if (StateLink.isPlayerDropSpawnBlocked((ServerPlayer) (Object) this)) {
            // The source stack has already left stale local inventory. Do not
            // reinsert it into an old session: the last committed DB checkpoint
            // remains the only state a successor may restore.
            info.setReturnValue(null);
        }
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDamage(CallbackInfoReturnable<Boolean> info) {
        if (StateLink.isPlayerQuarantined((ServerPlayer) (Object) this)) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("HEAD"), cancellable = true)
    private void statelink$blockTeleport(CallbackInfoReturnable<Boolean> info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!com.atsukigames.statelink.utils.SpatialMutationScope.allowsAuthoritativeTeleport(player)
                && StateLink.isPlayerQuarantined(player)) info.setReturnValue(false);
    }

    /** Dimension changes go through teleport(TeleportTransition). */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD"), cancellable = true)
    private void statelink$blockDimensionChange(
        net.minecraft.world.level.portal.TeleportTransition target,
        CallbackInfoReturnable<ServerPlayer> info
    ) {
        if (StateLink.isPlayerQuarantined((ServerPlayer) (Object) this)) {
            info.setReturnValue(null);
        }
    }

    @Inject(method = "setServerLevel", at = @At("HEAD"), cancellable = true)
    private void statelink$blockWorldChange(ServerLevel destination, CallbackInfo info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (!com.atsukigames.statelink.utils.SpatialMutationScope.allowsWorldRestore(player)
                && StateLink.isPlayerQuarantined(player)) info.cancel();
    }
}
