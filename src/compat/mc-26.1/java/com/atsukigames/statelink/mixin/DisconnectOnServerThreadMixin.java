package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures StateLink state before vanilla removal and keeps the full lifecycle on the game thread. */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 2000)
public abstract class DisconnectOnServerThreadMixin {
    @Shadow public ServerPlayer player;

    @Inject(
        method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void statelink$dispatchDisconnectToServerThread(net.minecraft.network.DisconnectionDetails reason, CallbackInfo info) {
        if (!StateLink.isEnabled()) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        if (server.isSameThread()) {
            // Fabric's DISCONNECT event may run after PlayerManager has closed the
            // current ScreenHandler. Snapshot/clear transient inputs at the HEAD,
            // while the original server-side handler and its backing inventory are
            // still intact. The later Fabric callback is idempotently ignored once
            // this call has moved the session to FLUSHING.
            StateLink.onDisconnectBeforeVanillaRemoval(player, server);
            return;
        }

        // Fabric's DISCONNECT event is injected at this method's head. Cancel and
        // reschedule the whole method so neither StateLink snapshotting nor vanilla screen
        // cleanup/player removal can run on a Netty event-loop thread.
        info.cancel();
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        try {
            server.execute(() -> handler.onDisconnect(reason));
        } catch (RuntimeException error) {
            StateLink.LOGGER.error(
                "Could not dispatch disconnect lifecycle to the Minecraft server thread uuid={}; "
                    + "no player snapshot or ownership release will be attempted",
                player.getUUID(), error);
        }
    }
}
