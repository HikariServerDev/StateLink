package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
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
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LoadingQuarantineNetworkMixin {
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerAction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBlockInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockItemInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void statelink$blockEntityInteraction(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSlotClick(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCraftRequest(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCreativeInventory(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "onPickFromInventory", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPickFromInventory(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRenameItem(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
    private void statelink$blockMerchantTrade(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void statelink$blockButtonClick(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSelectedSlot(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerMove(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void statelink$blockVehicleMove(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handlePaddleBoat", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBoatPaddle(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
    private void statelink$blockPlayerInput(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void statelink$blockClientCommand(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handlePlayerAbilities", at = @At("HEAD"), cancellable = true)
    private void statelink$blockAbilityUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleTeleportToEntityPacket", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSpectatorTeleport(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void statelink$blockClientStatus(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("HEAD"), cancellable = true)
    private void statelink$blockTeleportConfirm(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBookUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleRecipeBookSeenRecipePacket", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRecipeBookData(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleRecipeBookChangeSettingsPacket", at = @At("HEAD"), cancellable = true)
    private void statelink$blockRecipeCategoryOptions(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSeenAdvancements", at = @At("HEAD"), cancellable = true)
    private void statelink$blockAdvancementTab(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
    private void statelink$blockBeaconUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetCommandBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommandBlockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetCommandMinecart", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommandBlockMinecartUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetStructureBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockStructureBlockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSetJigsawBlock", at = @At("HEAD"), cancellable = true)
    private void statelink$blockJigsawUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleJigsawGenerate", at = @At("HEAD"), cancellable = true)
    private void statelink$blockJigsawGenerate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
    private void statelink$blockSignUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true)
    private void statelink$blockHandSwing(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCloseHandledScreen(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCustomPayload(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleChangeDifficulty", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDifficultyUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleLockDifficulty", at = @At("HEAD"), cancellable = true)
    private void statelink$blockDifficultyLockUpdate(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void statelink$blockChatMessage(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void statelink$blockCommand(CallbackInfo info) {
        if (StateLink.isPlayerQuarantined(((ServerGamePacketListenerImpl) (Object) this).player)) info.cancel();
    }
}
