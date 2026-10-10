package com.atsukigames.statelink;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.config.LegacyNameMigration;
import com.atsukigames.statelink.database.DatabaseManager;
import com.atsukigames.statelink.database.PlayerDataRepository;
import com.atsukigames.statelink.sync.PlayerSyncCoordinator;
import com.atsukigames.statelink.sync.PlayerDropOperationPermit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * PlayerDataConnector 2.1.x同期基盤。
 * Fabric 1.18.2では、セッション世代・DB ownership・fencing tokenを使って
 * 同一UUIDの複数backend間の非同期raceを閉じる。
 */
public final class StateLink implements ModInitializer {
    public static final String MOD_ID = "statelink";
    public static final String VERSION = "2.2.9";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile StateLink activeInstance;

    private Configuration config;
    private DatabaseManager database;
    private PlayerDataRepository repository;
    private volatile PlayerSyncCoordinator coordinator;

    @Override
    public void onInitialize() {
        try {
            LOGGER.info("Initializing StateLink with session ownership synchronization");

            if (FabricLoader.getInstance().isModLoaded(LegacyNameMigration.LEGACY_MOD_ID)) {
                // Two copies of the synchronization engine would compete for every player.
                throw new com.atsukigames.statelink.config.ConfigurationException(
                    "STATELINK_LEGACY_MOD_PRESENT: the PlayerDataConnector jar is still installed. StateLink "
                        + "replaces it; remove the PlayerDataConnector jar from the mods folder");
            }
            Path configDirectory = FabricLoader.getInstance().getConfigDir();
            // Before anything is read: carry over the files written under the former mod name.
            reportLegacyMigration("configuration", LegacyNameMigration.migrateConfig(configDirectory));
            Path configPath = configDirectory.resolve(LegacyNameMigration.CONFIG_FILE);
            config = Configuration.load(configPath);
            config.normalize();
            try {
                reportLegacyMigration("disabled marker", LegacyNameMigration.migrateDisabledMarker(configDirectory));
            } catch (RuntimeException markerMigrationError) {
                // Enabled: without the marker the re-activation guard would be lost, so refuse.
                if (config.enable) throw markerMigrationError;
                // Disabled means inert: never fail the server over the marker itself.
                LOGGER.error("Could not carry over the PlayerDataConnector disabled marker; it stays in place and "
                    + "an enabled startup will be refused until it can be renamed: {}",
                    markerMigrationError.getMessage());
            }
            if (!config.enable) {
                LOGGER.info("StateLink disabled: no database, lifecycle hooks, quarantine or recovery engine");
                try {
                    var marker = com.atsukigames.statelink.config.DisabledMarker.recordDisabled(
                        configDirectory, config.sync == null ? null : config.sync.serverId, VERSION);
                    LOGGER.warn("StateLink disabled marker {} (since {}): re-enabling this backend will "
                        + "require migration.acknowledgeReactivationMarker={}", marker.markerId(), marker.disabledAt(),
                        marker.markerId());
                } catch (java.io.IOException | RuntimeException markerError) {
                    // Disabled means inert: never fail the server over the marker itself.
                    LOGGER.error("Could not write the StateLink disabled marker; re-enabling this backend "
                        + "will NOT be guarded. Review local player changes manually before setting enable=true",
                        markerError);
                }
                return;
            }
            var reactivated = com.atsukigames.statelink.config.DisabledMarker.requireReactivationAcknowledged(
                configDirectory, config.migration == null ? null : config.migration.acknowledgeReactivationMarker);
            if (reactivated != null) {
                LOGGER.warn("StateLink re-enabled after enable=false (marker={} disabledSince={}): the "
                    + "operator acknowledged that database state replaces local changes from that period",
                    reactivated.markerId(), reactivated.disabledAt());
            }
            LOGGER.info("StateLink enabled recoveryMode={} automaticRecovery={}",
                config.recovery.mode, config.recovery.enabled);
            if (config.sync.loginDelayMs > 0) {
                LOGGER.warn(
                    "sync.loginDelayMs={} is deprecated and ignored; JOIN now waits only for DB ownership state",
                    config.sync.loginDelayMs);
            }
            if ("server-unknown".equals(config.sync.serverId)) {
                LOGGER.warn("sync.serverId is still 'server-unknown'; set a distinct backend identifier for diagnostics");
            }

            database = new DatabaseManager(config.database, config.getCoordinationTableName(),
                DatabaseManager.LegacyBootstrapRequest.of(config));
            repository = new PlayerDataRepository(database);
            coordinator = new PlayerSyncCoordinator(config, database, repository);
            // coordinatorが完全に構築されるまでquarantine hookを有効化しない。
            // 初期化失敗時に半構築instanceがpacket判定を引き受けないため。
            activeInstance = this;
            LOGGER.info(
                "Database connection established playerTable={} coordinationTable={} serverId={}",
                database.getTableName(), database.getCoordinationTableName(), config.sync.serverId);
            LOGGER.info("Effective enabled domains={} saveIntervalSeconds={} saveOnlyWhenDirty={} saveOnDisconnect={} recoveryMode={}",
                com.atsukigames.statelink.utils.CheckpointDigest.domains(config.sync),
                config.save.saveIntervalSeconds, config.save.saveOnlyWhenDirty,
                config.save.saveOnDisconnect, config.recovery.mode);

            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(
                com.atsukigames.statelink.utils.RegistryContext::set);
            registerEventHandlers();
            RecoveryCommands.register(coordinator);
            coordinator.startPeriodicSave();

            ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
                try {
                    if (coordinator != null) coordinator.shutdown(server);
                } finally {
                    activeInstance = null;
                }
            });

            LOGGER.info("StateLink initialization finished successfully");
        } catch (Exception error) {
            LOGGER.error("Failed to initialize StateLink", error);
            throw new RuntimeException("StateLink initialization failed", error);
        }
    }

    /**
     * Server-side quarantine hook used by the small 1.18.2 mixins.
     *
     * <p>The mixins pass the concrete server-side player to the coordinator;
     * they never touch Minecraft state from a database worker. Eligibility is
     * resolved before the missing-session fail-closed rule, so an unmanaged
     * local entity such as a Carpet FakePlayer is left entirely alone while
     * normal players still fail closed during the JOIN callback window.</p>
     */
    public static boolean isPlayerQuarantined(net.minecraft.server.level.ServerPlayer player) {
        StateLink instance = activeInstance;
        return instance != null
            && instance.coordinator != null
            && player != null
            && instance.coordinator.isBlocked(player);
    }

    public static boolean isEnabled() {
        return activeInstance != null && activeInstance.coordinator != null;
    }

    /** Only the scoped vanilla screen-close path may drop its cursor/overflow items. */
    public static boolean isPlayerDropQuarantined(ServerPlayer player) {
        StateLink instance = activeInstance;
        return instance != null
            && instance.coordinator != null
            && player != null
            && instance.coordinator.isDropBlocked(player);
    }

    /** Opens an exception-safe identity scope for one vanilla player drop call. */
    public static PlayerDropOperationPermit.Scope<ServerPlayer> beginPlayerDropOperation(
        ServerPlayer player
    ) {
        StateLink instance = activeInstance;
        if (instance == null || instance.coordinator == null || player == null) {
            return PlayerDropOperationPermit.unmanagedScope(player);
        }
        return instance.coordinator.beginPlayerDropOperation(player);
    }

    /** Revalidates the captured session at the exact World.spawnEntity call site. */
    public static boolean isPlayerDropSpawnBlocked(ServerPlayer player) {
        StateLink instance = activeInstance;
        return instance != null
            && instance.coordinator != null
            && player != null
            && instance.coordinator.isPlayerDropSpawnBlocked(player);
    }

    /** Capture Player-owned screen inputs before vanilla removes/closes the handler. */
    public static void onDisconnectBeforeVanillaRemoval(ServerPlayer player, MinecraftServer server) {
        StateLink instance = activeInstance;
        if (instance != null && instance.coordinator != null && player != null && server != null) {
            instance.coordinator.onDisconnect(player, server);
        }
    }

    private void registerEventHandlers() {
        ServerTickEvents.END_SERVER_TICK.register(coordinator::observePlayerChanges);
        // LOADING/DEGRADED中の操作を止める。UUIDだけでなくcurrent session stateを見る。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
            isBlockedPlayer(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
            isBlockedPlayer(player)
                ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            isBlockedPlayer(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
            isBlockedPlayer(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            isBlockedPlayer(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            coordinator.onJoin(handler.player, server));

        // Coordinator enforces save or durable equality / recovery-required semantics.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            coordinator.onDisconnect(handler.player, server));

        // 死亡・respawn・dimension changeでownershipを取り直さない。
        ServerPlayerEvents.AFTER_RESPAWN.register(
            (oldPlayer, newPlayer, alive) -> coordinator.onAfterRespawn(oldPlayer, newPlayer, alive));
    }

    private static void reportLegacyMigration(String what, LegacyNameMigration.Result result) {
        if (!result.changedAnything()) return;
        LOGGER.warn("Migrated the PlayerDataConnector {} to StateLink: outcome={} {} -> {}{}", what,
            result.outcome(), result.legacy().getFileName(), result.target().getFileName(),
            result.archived() == null ? "" : " (set aside: " + result.archived().getFileName() + ")");
        if ("configuration".equals(what)) {
            LOGGER.warn("The migrated configuration is unchanged, so StateLink keeps using the same database and "
                + "tables that PlayerDataConnector used; no table is renamed or rewritten");
        }
    }

    private boolean isBlockedPlayer(Player player) {
        return player instanceof ServerPlayer serverPlayer
            && coordinator.isBlocked(serverPlayer);
    }
}
