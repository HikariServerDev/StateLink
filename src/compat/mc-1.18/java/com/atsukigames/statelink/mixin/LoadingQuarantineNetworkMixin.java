package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents client packets from reaching vanilla's mutating handlers while a
 * PlayerData session is LOADING or DEGRADED. Fabric interaction callbacks are
 * useful, but they do not cover slot clicks, creative packets, drop actions,
 * movement, or all container paths, so the packet boundary is the quarantine
 * boundary here.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class LoadingQuarantineNetworkMixin {
    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerAction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBlockInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockItemInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void statelink$blockEntityInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSlotClick(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onCraftRequest", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCraftRequest(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCreativeInventory(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPickFromInventory", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPickFromInventory(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onRenameItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRenameItem(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onSelectMerchantTrade", at = @At("HEAD"), cancellable = true)
    private void statelink$blockMerchantTrade(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onButtonClick", at = @At("HEAD"), cancellable = true)
    private void statelink$blockButtonClick(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSelectedSlot(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerMove(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onVehicleMove", at = @At("HEAD"), cancellable = true)
    private void statelink$blockVehicleMove(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onBoatPaddleState", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBoatPaddle(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPlayerInput", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerInput(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onClientCommand", at = @At("HEAD"), cancellable = true)
    private void statelink$blockClientCommand(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdatePlayerAbilities", at = @At("HEAD"), cancellable = true)
    private void statelink$blockAbilityUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onSpectatorTeleport", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSpectatorTeleport(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onClientStatus", at = @At("HEAD"), cancellable = true)
    private void statelink$blockClientStatus(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onTeleportConfirm", at = @At("HEAD"), cancellable = true)
    private void statelink$blockTeleportConfirm(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onBookUpdate", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBookUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onRecipeBookData", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRecipeBookData(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onRecipeCategoryOptions", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRecipeCategoryOptions(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onAdvancementTab", at = @At("HEAD"), cancellable = true)
    private void statelink$blockAdvancementTab(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateBeacon", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBeaconUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateCommandBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommandBlockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateCommandBlockMinecart", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommandBlockMinecartUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateStructureBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockStructureBlockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateJigsaw", at = @At("HEAD"), cancellable = true)
    private void statelink$blockJigsawUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onJigsawGenerating", at = @At("HEAD"), cancellable = true)
    private void statelink$blockJigsawGenerate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateSign", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSignUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onHandSwing", at = @At("HEAD"), cancellable = true)
    private void statelink$blockHandSwing(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onCloseHandledScreen", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCloseHandledScreen(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCustomPayload(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateDifficulty", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDifficultyUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onUpdateDifficultyLock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDifficultyLockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void statelink$blockChatMessage(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }

    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommand(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerPlayNetworkHandler) (Object) this).player)) info.cancel();
    }
}
