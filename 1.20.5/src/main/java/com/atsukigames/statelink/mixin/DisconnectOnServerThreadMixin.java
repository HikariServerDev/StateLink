package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures StateLink state before vanilla removal and keeps the full lifecycle on the game thread. */
@Mixin(value = ServerPlayNetworkHandler.class, priority = 2000)
public abstract class DisconnectOnServerThreadMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(
        method = "onDisconnected(Lnet/minecraft/text/Text;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void statelink$dispatchDisconnectToServerThread(Text reason, CallbackInfo info) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!StateLink.isEnabled()) return;
        if (server.isOnThread()) {
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
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        try {
            server.execute(() -> handler.onDisconnected(reason));
        } catch (RuntimeException error) {
            StateLink.LOGGER.error(
                "Could not dispatch disconnect lifecycle to the Minecraft server thread uuid={}; "
                    + "no player snapshot or ownership release will be attempted",
                player.getUuid(), error);
        }
    }
}
