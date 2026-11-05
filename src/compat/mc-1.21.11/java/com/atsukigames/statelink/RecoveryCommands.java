package com.atsukigames.statelink;

import com.atsukigames.statelink.sync.PlayerSyncCoordinator;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import net.minecraft.server.command.CommandManager;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Recovery is console-only, explicit, audited, and never part of normal acquire. */
final class RecoveryCommands {
    /** "pdc" is the former command name, kept so that existing runbooks keep working. */
    private static final String[] COMMAND_ROOTS = {"statelink", "pdc"};

    private RecoveryCommands() {}

    static void register(PlayerSyncCoordinator coordinator) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String root : COMMAND_ROOTS) dispatcher.register(
            literal(root).requires(source -> CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK).test(source) && source.getEntity() == null)
                .then(recoveryCommands(coordinator))
                .then(domainCommands(coordinator)));
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
        recoveryCommands(PlayerSyncCoordinator coordinator) {
        var forceConfirm = literal("confirm-source-fenced-and-world-reviewed").executes(context -> {
            ServerCommandSource source = context.getSource();
            source.sendFeedback(() -> Text.of("WARNING: force recovery may duplicate or discard externalized state. "
                + "Source fencing and World review are operator responsibilities."), false);
            coordinator.forceLastCheckpoint(UuidArgumentType.getUuid(context, "uuid"),
                LongArgumentType.getLong(context, "fence"), LongArgumentType.getLong(context, "revision"),
                UuidArgumentType.getUuid(context, "operation-id"), source.getName())
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        StateLink.LOGGER.error("Explicit recovery failed", error);
                        source.sendError(Text.of("Explicit recovery failed; state was not silently reset. Inspect server log."));
                    } else source.sendFeedback(() -> Text.of("Explicit recovery result: " + result), false);
                }));
            return 1;
        });
        var forceArguments = argument("uuid", UuidArgumentType.uuid())
            .then(argument("fence", LongArgumentType.longArg(0))
                .then(argument("revision", LongArgumentType.longArg(0))
                    .then(argument("operation-id", UuidArgumentType.uuid()).then(forceConfirm))));
        return literal("recovery")
            .then(literal("list").executes(context -> {
                var source = context.getSource();
                coordinator.recoveryList().whenComplete((rows, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendError(Text.of("Recovery list failed; inspect server log."));
                    else {
                        source.sendFeedback(() -> Text.of("Recovery mode=" + coordinator.recoveryMode() + "; first 20 rows"), false);
                        for (var row : rows) source.sendFeedback(() -> Text.of(row
                            + "; policy=" + coordinator.recoveryMode() + "; safe=proof-required; automatic=acknowledged-checkpoint-risk"), false);
                    }
                }));
                return 1;
            }))
            .then(literal("inspect").then(argument("player", StringArgumentType.word()).executes(context -> {
                var source = context.getSource();
                coordinator.recoveryInspect(StringArgumentType.getString(context, "player"))
                    .whenComplete((inspection, error) -> source.getServer().execute(() -> {
                        if (error != null) source.sendError(Text.of("Recovery inspect failed; use a unique player name or UUID."));
                        else source.sendFeedback(() -> Text.of(inspection + "; mode=" + coordinator.recoveryMode()
                            + "; safe=durable-proof-required; automatic=expired-owner-checkpoint-risk"), false);
                    }));
                return 1;
            })))
            .then(literal("status").then(argument("uuid", UuidArgumentType.uuid()).executes(context -> {
                ServerCommandSource source = context.getSource();
                var uuid = UuidArgumentType.getUuid(context, "uuid");
                coordinator.recoveryStatus(uuid).whenComplete((status, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendError(Text.of("Recovery status failed; inspect server log."));
                    else source.sendFeedback(() -> Text.of(status.map(Object::toString)
                        .orElse("No coordination row for " + uuid) + "; AutoRecoveryMode=" + coordinator.recoveryMode()), false);
                }));
                return 1;
            })))
            .then(literal("reset-attempts").then(argument("uuid", UuidArgumentType.uuid())
                .then(argument("fence", LongArgumentType.longArg(0))
                    .then(argument("revision", LongArgumentType.longArg(0))
                        .then(argument("operation-id", UuidArgumentType.uuid())
                            .then(literal("confirm-attempts-were-policy-deferrals").executes(context -> {
                                ServerCommandSource source = context.getSource();
                                coordinator.resetAutomaticRecoveryAttempts(UuidArgumentType.getUuid(context, "uuid"),
                                    LongArgumentType.getLong(context, "fence"), LongArgumentType.getLong(context, "revision"),
                                    UuidArgumentType.getUuid(context, "operation-id"), source.getName())
                                    .whenComplete((result, error) -> source.getServer().execute(() -> {
                                        if (error != null) {
                                            StateLink.LOGGER.error("Attempt budget reset failed", error);
                                            source.sendError(Text.of("Attempt budget reset failed; nothing was changed. Inspect server log."));
                                        } else source.sendFeedback(() -> Text.of("Attempt budget reset result: " + result
                                            + " (recovery state itself is unchanged)"), false);
                                    }));
                                return 1;
                            })))))))
            .then(literal("force-last-checkpoint").then(forceArguments));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource>
        domainCommands(PlayerSyncCoordinator coordinator) {
        var adoptConfirm = literal("confirm-db-domain-is-authoritative").executes(context -> {
            ServerCommandSource source = context.getSource();
            var uuid = UuidArgumentType.getUuid(context, "uuid");
            String domain = StringArgumentType.getString(context, "domain");
            source.sendFeedback(() -> Text.of("WARNING: this explicitly adopts the current DB value for "
                + domain + "; it does not verify that value against local gameplay or World state."), false);
            coordinator.adoptDatabaseDomain(uuid, domain, LongArgumentType.getLong(context, "fence"),
                LongArgumentType.getLong(context, "revision"),
                UuidArgumentType.getUuid(context, "operation-id"), source.getName())
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        StateLink.LOGGER.error("Domain reconciliation failed", error);
                        source.sendError(Text.of("Domain reconciliation failed; state remains fail-closed. Inspect server log."));
                    } else source.sendFeedback(() -> Text.of("Domain reconciliation result: " + result), false);
                }));
            return 1;
        });
        var adoptArguments = argument("uuid", UuidArgumentType.uuid())
            .then(argument("domain", StringArgumentType.word())
                .then(argument("fence", LongArgumentType.longArg(0))
                    .then(argument("revision", LongArgumentType.longArg(0))
                        .then(argument("operation-id", UuidArgumentType.uuid()).then(adoptConfirm)))));
        return literal("domain")
            .then(literal("status").then(argument("uuid", UuidArgumentType.uuid()).executes(context -> {
                ServerCommandSource source = context.getSource();
                var uuid = UuidArgumentType.getUuid(context, "uuid");
                coordinator.recoveryStatus(uuid).whenComplete((status, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendError(Text.of("Domain provenance status failed; inspect server log."));
                    else source.sendFeedback(() -> Text.of(status.map(row -> "uuid=" + row.uuid() + " revision=" + row.revision()
                        + " provenanceRevision=" + row.domainProvenanceRevision() + " generation="
                        + row.domainProvenanceGeneration() + " manifest=" + row.domainProvenanceManifest()
                        + " recovery=" + row.reason()).orElse("No coordination row for " + uuid)), false);
                }));
                return 1;
            })))
            .then(literal("adopt-db").then(adoptArguments));
    }
}
