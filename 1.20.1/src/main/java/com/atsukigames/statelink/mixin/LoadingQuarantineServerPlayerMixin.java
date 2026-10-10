package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gates direct ServerPlayerEntity side-effect entry points. */
@Mixin(ServerPlayerEntity.class)
public abstract class LoadingQuarantineServerPlayerMixin {
    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDrop(CallbackInfoReturnable<Boolean> info) {
        if (StateLink.isPlayerDropQuarantined((ServerPlayerEntity) (Object) this)) {
            info.setReturnValue(false);
        }
    }

    @Inject(
        method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void statelink$blockDirectDrop(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        CallbackInfoReturnable<ItemEntity> info
    ) {
        if (StateLink.isPlayerDropQuarantined((ServerPlayerEntity) (Object) this)) {
            info.setReturnValue(null);
        }
    }

    /**
     * Binds the in-flight vanilla drop to the session/fence seen at method entry.
     * The scope is restored even when vanilla or another mod throws.
     */
    @WrapMethod(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;")
    private ItemEntity statelink$scopePlayerDrop(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        Operation<ItemEntity> original
    ) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
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
        method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z",
            ordinal = 0
        ),
        cancellable = true,
        require = 1
    )
    private void statelink$revalidateBeforeWorldSpawn(
        ItemStack stack,
        boolean throwRandomly,
        boolean retainOwnership,
        CallbackInfoReturnable<ItemEntity> info
    ) {
        if (StateLink.isPlayerDropSpawnBlocked((ServerPlayerEntity) (Object) this)) {
            // The source stack has already left stale local inventory. Do not
            // reinsert it into an old session: the last committed DB checkpoint
            // remains the only state a successor may restore.
            info.setReturnValue(null);
        }
    }

    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDamage(CallbackInfoReturnable<Boolean> info) {
        if (StateLink.isPlayerQuarantined((ServerPlayerEntity) (Object) this)) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/server/world/ServerWorld;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void statelink$blockTeleport(CallbackInfo info) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!com.atsukigames.statelink.utils.SpatialMutationScope.allowsAuthoritativeTeleport(player)
                && StateLink.isPlayerQuarantined(player)) info.cancel();
    }

    @Inject(method = "teleport(Lnet/minecraft/server/world/ServerWorld;DDDLjava/util/Set;FF)Z", at = @At("HEAD"), cancellable = true)
    private void statelink$blockTeleportFlags(CallbackInfoReturnable<Boolean> info) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!com.atsukigames.statelink.utils.SpatialMutationScope.allowsAuthoritativeTeleport(player)
                && StateLink.isPlayerQuarantined(player)) info.setReturnValue(false);
    }

    @Inject(method = "moveToWorld", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDimensionChange(
        ServerWorld destination,
        CallbackInfoReturnable<Entity> info
    ) {
        if (StateLink.isPlayerQuarantined((ServerPlayerEntity) (Object) this)) {
            info.setReturnValue(null);
        }
    }

    @Inject(method = "setServerWorld", at = @At("HEAD"), cancellable = true)
    private void statelink$blockWorldChange(ServerWorld destination, CallbackInfo info) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!com.atsukigames.statelink.utils.SpatialMutationScope.allowsWorldRestore(player)
                && StateLink.isPlayerQuarantined(player)) info.cancel();
    }
}
