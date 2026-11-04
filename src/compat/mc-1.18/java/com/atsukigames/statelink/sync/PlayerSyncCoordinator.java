package com.atsukigames.statelink.sync;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.DatabaseManager;
import com.atsukigames.statelink.database.PlayerDataRepository;
import com.atsukigames.statelink.database.PlayerDataRepository.AcquireResult;
import com.atsukigames.statelink.database.PlayerDataRepository.AcquireStatus;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.atsukigames.statelink.database.PlayerDataRepository.Ownership;
import com.atsukigames.statelink.database.PlayerDataRepository.LeaseRenewalResult;
import com.atsukigames.statelink.database.PlayerDataRepository.SaveResult;
import com.atsukigames.statelink.database.PlayerDataRepository.SaveStatus;
import com.atsukigames.statelink.utils.CompletePlayerDataSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * JOINからshutdownまでのPlayerData同期フローを統括する。
 *
 * <p>Minecraft objectを触る処理はserver thread、JDBCはbounded workerへ分離する。
 * 同一UUIDのJDBC処理は {@link PerPlayerSerialExecutor} で直列化し、server間の
 * 排他はPlayerDataRepositoryのcoordination rowとfencing tokenに任せる。</p>
 */
public final class PlayerSyncCoordinator implements AutoCloseable {
    private final Configuration config;
    private final PlayerDataRepository repository;
    private final DatabaseManager database;
    private final PlayerSessionManager sessions = new PlayerSessionManager();
    private final PerPlayerSerialExecutor serialExecutor;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** イベント側のsession map更新とshutdownの初期化を同一の短い境界で直列化する。 */
    private final Object lifecycleLock = new Object();
    private final ConcurrentMap<CompletableFuture<Void>, Boolean> finalFlushes = new ConcurrentHashMap<>();
    /** Narrowly permits vanilla drop calls caused by explicit screen cleanup only. */
    private final DisconnectDropPermit<ServerPlayerEntity> disconnectDropPermit = new DisconnectDropPermit<>();
    private final PlayerDropOperationPermit<ServerPlayerEntity> playerDropOperationPermit =
        new PlayerDropOperationPermit<>();
    private volatile MinecraftServer server;
    private volatile ScheduledFuture<?> periodicTask;
    private final AtomicBoolean periodicDispatchPending = new AtomicBoolean();
    private final AtomicBoolean recoveryScanInFlight = new AtomicBoolean();
    private volatile UUID recoveryScanCursor;
    private static final UUID RECOVERY_SCAN_QUEUE = UUID.nameUUIDFromBytes("statelink-recovery-scan".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    /** schedulerはsingle-threadなので、この予定時刻もscheduler taskだけが更新する。 */
    private long nextRenewScheduledNanos;

    public PlayerSyncCoordinator(
        Configuration config,
        DatabaseManager database,
        PlayerDataRepository repository
    ) {
        Objects.requireNonNull(config, "config");
        this.config = config;
        this.database = database;
        this.repository = repository;
        int parallelism = Math.max(2, Math.min(config.database.maxConnections, 8));
        this.serialExecutor = new PerPlayerSerialExecutor(parallelism, Math.max(64, config.database.maxConnections * 64));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "StateLink-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.nextRenewScheduledNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(config.sync.leaseRenewIntervalMs);
        this.scheduler.scheduleAtFixedRate(
            this::renewReadySessions,
            config.sync.leaseRenewIntervalMs,
            config.sync.leaseRenewIntervalMs,
            TimeUnit.MILLISECONDS);
        if (config.recovery.enabled && !"manual".equals(config.recovery.mode)) {
            this.scheduler.scheduleWithFixedDelay(this::scanAutomaticRecovery,
                config.recovery.retryIntervalSeconds, config.recovery.retryIntervalSeconds, TimeUnit.SECONDS);
        }
        if ("last_checkpoint".equals(config.recovery.mode) || "automatic".equals(config.recovery.mode)) {
            StateLink.LOGGER.warn("UNSAFE recovery.mode={}: externalized World state may duplicate, disappear or roll back",config.recovery.mode);
        }
        StateLink.LOGGER.info(
            "Lease timing leaseDurationMs={} renewIntervalMs={} localSafetyDeadlineMs={} diagnosticLogging={}",
            config.sync.leaseDurationMs, config.sync.leaseRenewIntervalMs,
            Math.max(1L, config.sync.leaseDurationMs - config.sync.leaseRenewIntervalMs),
            config.database.diagnosticLogging);
    }

    public void startPeriodicSave() {
        if (!config.save.periodicSaveEnabled || config.save.saveIntervalSeconds <= 0) return;
        long interval = config.save.saveIntervalSeconds;
        periodicTask = scheduler.scheduleAtFixedRate(() -> {
            if (stopping.get()) return;
            MinecraftServer currentServer = server;
            if (currentServer != null && periodicDispatchPending.compareAndSet(false,true)) {
                try { currentServer.execute(() -> {
                    try { takePeriodicSnapshots(currentServer); }
                    finally { periodicDispatchPending.set(false); }
                }); } catch (RuntimeException error) { periodicDispatchPending.set(false); }
            }
        }, interval, interval, TimeUnit.SECONDS);
        StateLink.LOGGER.info("Periodic checkpoint every {} seconds dirtyOnly={}", interval,config.save.saveOnlyWhenDirty);
    }

    public boolean shouldManagePlayer(ServerPlayerEntity player) {
        return sessions.shouldManagePlayer(player);
    }

    public boolean isBlocked(ServerPlayerEntity player) {
        return sessions.isBlocked(player, leaseSafetyBudgetNanos());
    }

    public boolean isDropBlocked(ServerPlayerEntity player) {
        return config.sync.inventory && disconnectDropPermit.isBlocked(player, this::isBlockedForWorldMutation);
    }

    public PlayerDropOperationPermit.Scope<ServerPlayerEntity> beginPlayerDropOperation(
        ServerPlayerEntity player
    ) {
        if (!config.sync.inventory || !sessions.shouldManagePlayer(player)) {
            return playerDropOperationPermit.duringDrop(player, false, true, null, null);
        }
        PlayerSessionContext context = sessions.current(player.getUuid());
        if (context == null) {
            return playerDropOperationPermit.duringDrop(player, true, false, null, null);
        }
        DisconnectDropPermit.Identity identity = permitIdentity(context);
        boolean allowedAtEntry = !isDropBlocked(player)
            && sessions.isCurrent(context, player)
            && context.isAuthoritativeDataLoaded()
            && context.fencingToken() == identity.fence();
        return playerDropOperationPermit.duringDrop(
            player, true, allowedAtEntry, context, identity);
    }

    public boolean isPlayerDropSpawnBlocked(ServerPlayerEntity player) {
        return config.sync.inventory && playerDropOperationPermit.isBlocked(
            player, this::hasCurrentWorldDropAuthority, this::isDropBlocked);
    }

    private boolean isBlockedForWorldMutation(ServerPlayerEntity player) {
        return sessions.shouldManagePlayer(player)
            && sessions.isBlocked(player, worldMutationLeaseSafetyBudgetNanos());
    }

    private boolean hasCurrentWorldDropAuthority(
        ServerPlayerEntity player,
        PlayerDropOperationPermit.CapturedAuthority captured
    ) {
        PlayerSessionContext context = captured.context();
        DisconnectDropPermit.Identity identity = captured.identity();
        return sessions.shouldManagePlayer(player)
            && context != null
            && identity != null
            && identity.uuid().equals(context.uuid())
            && identity.localSessionId().equals(context.localSessionId())
            && identity.generation() == context.generation()
            && identity.fence() == context.fencingToken()
            && sessions.isCurrent(context, player)
            && context.isAuthoritativeDataLoaded()
            && !isDropBlocked(player);
    }

    public PlayerSessionContext currentSession(UUID uuid) {
        return sessions.current(uuid);
    }

    public CompletableFuture<java.util.Optional<PlayerDataRepository.RecoveryStatus>> recoveryStatus(UUID uuid) {
        return serialExecutor.submit(uuid, () -> {
            try {
                return repository.recoveryStatus(uuid);
            } catch (Exception error) {
                throw new DatabaseOperationException(error);
            }
        });
    }

    public String recoveryMode() { return config.recovery.mode; }

    public CompletableFuture<List<PlayerDataRepository.RecoveryStatus>> recoveryList() {
        return serialExecutor.submit(RECOVERY_SCAN_QUEUE, () -> {
            try {
                List<PlayerDataRepository.RecoveryStatus> rows = new ArrayList<>();
                for (UUID uuid : repository.recoveryCandidates(20)) repository.recoveryStatus(uuid).ifPresent(rows::add);
                return List.copyOf(rows);
            } catch (Exception error) { throw new DatabaseOperationException(error); }
        });
    }

    public CompletableFuture<PlayerDataRepository.RecoveryInspection> recoveryInspect(String player) {
        return serialExecutor.submit(RECOVERY_SCAN_QUEUE, () -> {
            try { return repository.recoveryInspection(player); }
            catch (Exception error) { throw new DatabaseOperationException(error); }
        });
    }

    private void scanAutomaticRecovery() {
        if (stopping.get() || !recoveryScanInFlight.compareAndSet(false, true)) return;
        CompletableFuture<Void> work;
        try {
            work = serialExecutor.submit(RECOVERY_SCAN_QUEUE, () -> {
                try { return repository.recoveryCandidates(16, recoveryScanCursor); }
                catch (Exception error) { throw new DatabaseOperationException(error); }
            }).thenCompose(ids -> {
                recoveryScanCursor = ids.isEmpty() ? null : ids.get(ids.size() - 1);
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (UUID uuid : ids) {
                    chain = chain.thenCompose(ignored -> serialExecutor.submit(uuid, () -> {
                        if (stopping.get() || sessions.current(uuid) != null) return null;
                        try {
                            attemptPolicyRecovery(uuid);
                        } catch (Exception error) {
                            StateLink.LOGGER.warn("Automatic recovery refused/failed uuid={}; state remains fail-closed", uuid);
                        }
                        return (Void) null;
                    }));
                }
                return chain;
            });
        } catch (RuntimeException error) {
            recoveryScanInFlight.set(false);
            return;
        }
        work.whenComplete((ignored, error) -> recoveryScanInFlight.set(false));
    }

    private boolean permitsRiskAcknowledgedAutomaticRecovery() {
        return config.recovery.enabled && ("automatic".equals(config.recovery.mode)
            || "last_checkpoint".equals(config.recovery.mode));
    }

    private final com.atsukigames.statelink.database.DeferralLog recoveryDeferralLog =
        new com.atsukigames.statelink.database.DeferralLog(60_000L);

    /** DB worker only: shared by bounded background scan and same-login recovery. */
    private PlayerDataRepository.RecoveryResolutionResult attemptPolicyRecovery(UUID uuid) throws Exception {
        var status = repository.recoveryStatus(uuid).orElse(null);
        if (status == null || status.state() != com.atsukigames.statelink.database.CoordinationLifecycle.State.RECOVERY_REQUIRED) return null;
        UUID operation = UUID.nameUUIDFromBytes((config.recovery.mode+":"+uuid+":"+status.fence()+":"+status.revision())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String actor="auto:"+config.sync.serverId;
        if(actor.length()>64) actor=actor.substring(0,64);
        PlayerDataRepository.RecoveryResolutionResult result;
        if("safe".equals(config.recovery.mode)) result=repository.resolveSafeFinalReceipt(uuid,status.fence(),status.revision(),operation,actor,config.recovery.maxWaitSeconds);
        else if("automatic".equals(config.recovery.mode)) result=repository.resolveAutomaticLastCheckpoint(uuid,status.fence(),status.revision(),operation,actor,
            config.recovery.autoRecoverDelaySeconds,config.recovery.maxCheckpointAgeSeconds,
            config.migration == null ? config.recovery.maxCheckpointAgeSeconds
                : config.migration.legacy211RecoveryMaxCheckpointAgeSeconds,
            config.recovery.maxAutoRecoveryAttempts,config.recovery.retryIntervalSeconds,
            config.recovery.acknowledgePotentialRollbackOrDuplication);
        else result=repository.resolveUnsafeLastCheckpoint(uuid,status.fence(),status.revision(),operation,actor,
            config.recovery.lastCheckpointDelaySeconds,config.recovery.acknowledgeUnsafeRecovery);
        if(permitsRiskAcknowledgedAutomaticRecovery() && (result.status()==PlayerDataRepository.RecoveryResolutionStatus.RESOLVED
                || result.status()==PlayerDataRepository.RecoveryResolutionStatus.ALREADY_RESOLVED)) {
            StateLink.LOGGER.warn("Automatically recovered player UUID={} reason={} revision={} mode={} fence={}; last checkpoint may roll back or duplicate externalized World state",
                uuid,status.reason(),result.revision(),config.recovery.mode,result.fence());
            recoveryDeferralLog.forget(uuid);
        } else {
            long repeats = recoveryDeferralLog.permit(uuid, result.status() + ":" + status.reason() + ":" + result.fence() + ":" + result.revision());
            if (repeats > 0) StateLink.LOGGER.info("{} auto recovery uuid={} reason={} result={} fence={} revision={} evaluations={}",
                config.recovery.mode,uuid,status.reason(),result.status(),result.fence(),result.revision(),repeats);
        }
        return result;
    }

    /** Explicit console action, separate from login/acquire and every retry/timer. */
    public CompletableFuture<PlayerDataRepository.RecoveryResolutionResult> forceLastCheckpoint(
        UUID uuid, long fence, long revision, UUID operationId, String actor
    ) {
        if (sessions.current(uuid) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Local session still exists; stop/fence the source and complete World review first"));
        }
        return serialExecutor.submit(uuid, () -> {
            if (sessions.current(uuid) != null) {
                throw new IllegalStateException("Local session appeared; recovery refused");
            }
            try {
                return repository.forceLastCheckpoint(uuid, fence, revision, operationId, actor,
                    PlayerDataRepository.RecoveryAcknowledgement.SOURCE_FENCED_AND_WORLD_REVIEWED);
            } catch (Exception error) {
                throw new DatabaseOperationException(error);
            }
        });
    }

    /** Explicit console action: audited reset of a row's automatic recovery attempt budget. */
    public CompletableFuture<PlayerDataRepository.RecoveryResolutionResult> resetAutomaticRecoveryAttempts(
        UUID uuid, long fence, long revision, UUID operationId, String actor
    ) {
        return serialExecutor.submit(uuid, () -> {
            try {
                return repository.resetAutomaticRecoveryAttempts(uuid, fence, revision, operationId, actor);
            } catch (Exception error) {
                throw new DatabaseOperationException(error);
            }
        });
    }

    /** Explicit, console-only recovery choice to trust the persisted DB value for one domain. */
    public CompletableFuture<PlayerDataRepository.RecoveryResolutionResult> adoptDatabaseDomain(
        UUID uuid, String domain, long fence, long revision, UUID operationId, String actor
    ) {
        if (sessions.current(uuid) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "A local session still exists; stop/fence it before domain reconciliation"));
        }
        return serialExecutor.submit(uuid, () -> {
            if (sessions.current(uuid) != null) throw new IllegalStateException("Local session appeared; reconciliation refused");
            try {
                return repository.adoptDatabaseDomain(uuid, domain, fence, revision, operationId, actor, config.sync);
            } catch (Exception error) {
                throw new DatabaseOperationException(error);
            }
        });
    }

    /** JOIN callbackから呼び出す。固定loginDelayは存在せず、即座にownership確認を開始する。 */
    public void onJoin(ServerPlayerEntity player, MinecraftServer server) {
        synchronized (lifecycleLock) {
            this.server = server;
            if (!sessions.shouldManagePlayer(player)) {
                StateLink.LOGGER.debug(
                    "Ignoring JOIN for unmanaged player type={} uuid={} name={}",
                    player == null ? "null" : player.getClass().getName(),
                    player == null ? "null" : player.getUuid(),
                    player == null ? "null" : player.getName().getString());
                return;
            }
            UUID uuid = player.getUuid();
            if (stopping.get()) {
                disconnect(player, "StateLink is shutting down.");
                return;
            }

            supersedePreviousSession(uuid, server);
            PlayerSessionContext context = sessions.create(player);
            StateLink.LOGGER.info(
                "JOIN session created uuid={} session={} generation={} serverId={} state=LOADING",
                uuid, shortId(context.localSessionId()), context.generation(), config.sync.serverId);
            scheduleJoinTimeout(context, server, "JOIN synchronization timeout", null,
                config.sync.joinSyncTimeoutMs);
            submitAcquire(context, server, 0, 0);
        }
    }

    /** DISCONNECT callbackから呼び出す。PlayerDataの読み取りは必ずserver threadへ戻す。 */
    public void onDisconnect(ServerPlayerEntity player, MinecraftServer server) {
        // Fabric may emit DISCONNECT from the Netty callback before
        // onDisconnected() reaches our network-handler mixin. Do not inspect the
        // player/session or transition lifecycle state on that thread. Queue the
        // complete StateLink disconnect decision onto the game thread; the mixin's own
        // redispatch is harmless because the session state makes the second
        // callback idempotent.
        if (stopping.get()) return;
        try {
            if (dispatchIfOffServerThread(
                    server::isOnThread, server::execute, () -> onDisconnect(player, server))) {
                return;
            }
        } catch (RuntimeException schedulingError) {
            // No player snapshot, save, or release is safe here. Let the
            // database lease expire rather than touching Minecraft state off-thread.
            StateLink.LOGGER.error(
                "Could not dispatch StateLink disconnect processing to the server thread; "
                    + "snapshot and ownership release were skipped",
                schedulingError);
            return;
        }
        synchronized (lifecycleLock) {
            this.server = server;
            if (!sessions.shouldManagePlayer(player)) {
                StateLink.LOGGER.debug(
                    "Ignoring DISCONNECT for unmanaged player type={} uuid={}",
                    player == null ? "null" : player.getClass().getName(),
                    player == null ? "null" : player.getUuid());
                return;
            }
            // stopping後のDISCONNECTはshutdown側が取得したsnapshot/final flushの
            // 管理対象。ここで新しいflushを受け付けると、finalFlushesの待機一覧を
            // 作った後にDB taskが増えるため、停止処理との競合を閉じる。
            if (stopping.get()) return;
            UUID uuid = player.getUuid();
            PlayerSessionContext context = sessions.current(uuid);
            if (context == null || context.playerForServerThread() != player) {
                StateLink.LOGGER.debug("Ignoring stale DISCONNECT uuid={}", uuid);
                return;
            }
            SyncState state = context.state();
            if (state == SyncState.LOADING) {
                StateLink.LOGGER.warn(
                    "Player {} disconnected while LOADING; skipping local snapshot and scheduling ownership cleanup",
                    uuid);
                context.closeWithoutSave();
                sessions.remove(context);
                scheduleReleaseIfOwned(context, "loading-disconnect");
                return;
            }
            if (state == SyncState.FLUSHING || state == SyncState.CLOSED) {
                // 重複DISCONNECTでfinal saveの後ろにreleaseだけを追加すると、
                // final save失敗時にもownershipを先に解放し得る。既存flushに任せる。
                StateLink.LOGGER.debug(
                    "Ignoring duplicate DISCONNECT while final flush is active uuid={} session={}",
                    uuid, shortId(context.localSessionId()));
                return;
            }
            if (state == SyncState.DEGRADED) {
                context.closeWithoutSave();
                sessions.remove(context);
                // degraded ownerのsnapshotは信用できない。DB rowの解放はlease expiryに任せる。
                return;
            }
            if (state == SyncState.READY) {
                long expectedFence = context.fencingToken();
                if (!sessions.isCurrent(context, player)
                        || !context.beginQuiescingIfSafe(
                            System.nanoTime(), leaseSafetyBudgetNanos(), expectedFence)) {
                    abandonUnsafeDisconnect(context, "lease or session authority expired before QUIESCING");
                    return;
                }
            }
            if (context.state() != SyncState.QUIESCING) return;
            finalizeDisconnectOnServerThread(context, player, server, SaveReason.DISCONNECT_FINAL);
        }
    }

    /**
     * Queues a lifecycle callback without running it when the caller is off-thread.
     * Package-visible so the thread-boundary behavior can be tested deterministically.
     */
    static boolean dispatchIfOffServerThread(
        BooleanSupplier isServerThread,
        Consumer<Runnable> serverExecutor,
        Runnable callback
    ) {
        if (isServerThread.getAsBoolean()) return false;
        serverExecutor.accept(callback);
        return true;
    }

    private void finalizeDisconnectOnServerThread(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        MinecraftServer server,
        SaveReason reason
    ) {
        if (!server.isOnThread()) {
            StateLink.LOGGER.error(
                "Refusing off-thread disconnect finalization uuid={} session={}",
                context.uuid(), shortId(context.localSessionId()));
            return;
        }
        synchronized (lifecycleLock) {
            if (context.state() == SyncState.READY) {
                long expectedFence = context.fencingToken();
                if (!sessions.isCurrent(context, player)
                        || !context.beginQuiescingIfSafe(
                            System.nanoTime(), leaseSafetyBudgetNanos(), expectedFence)) {
                    abandonUnsafeDisconnect(context, "lease or session authority expired before QUIESCING");
                    return;
                }
            }
            if (!sessions.isCurrent(context, player) || context.state() != SyncState.QUIESCING) {
                return;
            }
            if (!config.save.saveOnDisconnect) {
                finalizeWithoutDataSave(context, player, server, reason);
                return;
            }
            DisconnectDropPermit.Identity identity = permitIdentity(context);
            CompletePlayerDataSerializer.TransientCapture[] transientCapture = new
                CompletePlayerDataSerializer.TransientCapture[1];
            DisconnectSnapshot.Outcome<CompletePlayerData> outcome = DisconnectSnapshot.captureThenCleanup(
                server::isOnThread,
                () -> hasSafeLocalAuthorityForWorldMutation(context, player, identity),
                () -> {
                    if (!config.sync.inventory) {
                        return extractCompletePlayerDataOnServerThread(server, player, null);
                    }
                    var captured = CompletePlayerDataSerializer.captureTransientState(player);
                    transientCapture[0] = captured;
                    String pendingItems = CompletePlayerDataSerializer.appendPendingDisconnectItems(
                        player, context.pendingDisconnectItemsJson(), captured.items());
                    return extractCompletePlayerDataOnServerThread(server, player, pendingItems);
                },
                () -> {
                    if (config.sync.inventory) {
                        CompletePlayerDataSerializer.clearDisconnectTransientStateAndCloseScreen(
                            player, Objects.requireNonNull(transientCapture[0], "disconnect transient capture"));
                    }
                    // Item domain OFF delegates all screen lifecycle to vanilla.
                });
            if (outcome.status() == DisconnectSnapshot.Status.AUTHORITY_LOST) {
                abandonUnsafeDisconnect(context, "lease or session authority expired during disconnect cleanup");
                return;
            }
            if (outcome.status() != DisconnectSnapshot.Status.SUCCESS) {
                context.recordSnapshotCaptureFailure();
                // Never release the lease when current complete state could not be captured.
                failClosed(context, player, "disconnect snapshot/cleanup failed", outcome.failure(), false);
                return;
            }
            CompletePlayerData snapshot = outcome.snapshot();
            long snapshotSequence = context.recordDirtySnapshot(snapshot, reason);
            if (!sessions.isCurrent(context, player)
                    || !context.beginFlushing(
                        true, snapshot != null, System.nanoTime(), leaseSafetyBudgetNanos(),
                        context.fencingToken())) {
                context.markDegraded();
                sessions.remove(context);
                StateLink.LOGGER.error(
                    "Disconnect snapshot captured but final flush transition failed; ownership left to lease expiry uuid={} session={}",
                    context.uuid(), shortId(context.localSessionId()));
                return;
            }
            StateLink.LOGGER.info(
                "Final flush queued uuid={} session={} fence={} reason={}",
                context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason);
            submitFinalFlush(context, snapshot, reason, snapshotSequence);
        }
    }

    /** No source clearing and no PlayerData write: equality is checked after preceding UUID work. */
    private void finalizeWithoutDataSave(PlayerSessionContext context, ServerPlayerEntity player,
        MinecraftServer server, SaveReason reason) {
        final CompletePlayerData snapshot;
        final boolean activeTransient;
        try {
            activeTransient = config.sync.inventory
                && !CompletePlayerDataSerializer.captureActiveTransientItems(player).isEmpty();
            snapshot = extractLiveCheckpointOnServerThread(server, player, context.pendingDisconnectItemsJson());
        } catch (RuntimeException error) {
            failClosed(context, player, "no-save disconnect snapshot failed", error, false);
            return;
        }
        if (!context.beginFlushing(true, true, System.nanoTime(), leaseSafetyBudgetNanos(), context.fencingToken())) {
            abandonUnsafeDisconnect(context, "no-save release lost authority");
            return;
        }
        Ownership capturedOwnership = ownership(context);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        finalFlushes.put(completion, Boolean.TRUE);
        serialExecutor.submit(context.uuid(), () -> {
            try {
                return repository.releaseWithoutDataSave(snapshot, capturedOwnership, config.sync, activeTransient);
            } catch (Exception error) {
                throw new DatabaseOperationException(error);
            }
        }).whenComplete((result, error) -> {
            context.closeWithoutSave();
            sessions.remove(context);
            finalFlushes.remove(completion);
            if (error != null) {
                scheduleRecoveryRequired(capturedOwnership,
                    PlayerDataRepository.RecoveryReason.DIRTY_DISCONNECT_WITHOUT_SAVE);
                completion.completeExceptionally(unwrap(error));
            } else {
                completion.complete(null);
            }
            StateLink.LOGGER.info("No-final-data-save termination uuid={} result={} reason={}",
                context.uuid(), error == null ? result : "FAILURE_RECOVERY_REQUIRED", reason);
        });
    }

    /** Build the stable session identity used by dynamic cleanup authority checks. */

    private DisconnectDropPermit.Identity permitIdentity(PlayerSessionContext context) {
        return new DisconnectDropPermit.Identity(
            context.uuid(), context.localSessionId(), context.generation(), context.fencingToken());
    }

    private boolean hasSafeLocalAuthorityForWorldMutation(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        DisconnectDropPermit.Identity identity
    ) {
        return sessions.shouldManagePlayer(player)
            && identity.uuid().equals(context.uuid())
            && identity.localSessionId().equals(context.localSessionId())
            && identity.generation() == context.generation()
            && identity.fence() == context.fencingToken()
            && context.state() == SyncState.QUIESCING
            && context.isAuthoritativeDataLoaded()
            && sessions.isCurrent(context, player)
            && context.hasLeaseSafetyAt(System.nanoTime(), worldMutationLeaseSafetyBudgetNanos());
    }

    /** Invalidates a disconnect session without screen drops, snapshot, SAVE, or release. */
    private void abandonUnsafeDisconnect(PlayerSessionContext context, String reason) {
        context.markDegraded();
        sessions.remove(context);
        StateLink.LOGGER.error(
            "Disconnect cleanup denied because local authority is unsafe uuid={} session={} generation={} fence={} lastSuccessfulRevision={} reason={}",
            context.uuid(), shortId(context.localSessionId()), context.generation(), context.fencingToken(),
            context.lastSuccessfulRevision(), reason);
    }

    /**
     * AFTER_RESPAWNは同一backend内のdimension/respawn通知として扱う。
     * ownershipをrelease/reacquireせず、current Player参照だけをMC threadで更新する。
     */
    public void onAfterRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        synchronized (lifecycleLock) {
            if (stopping.get()) return;
            if (!sessions.shouldManagePlayer(oldPlayer) || !sessions.shouldManagePlayer(newPlayer)) {
                StateLink.LOGGER.debug(
                    "Ignoring AFTER_RESPAWN for unmanaged player oldType={} newType={} uuid={}",
                    oldPlayer == null ? "null" : oldPlayer.getClass().getName(),
                    newPlayer == null ? "null" : newPlayer.getClass().getName(),
                    newPlayer == null ? "null" : newPlayer.getUuid());
                return;
            }
            MinecraftServer currentServer = newPlayer.server;
            this.server = currentServer;
            UUID uuid = newPlayer.getUuid();
            PlayerSessionContext context = sessions.current(uuid);
            if (context == null) return;
            if (context.playerForServerThread() != oldPlayer) {
                StateLink.LOGGER.warn(
                    "Ignoring stale AFTER_RESPAWN uuid={} trackedPlayerMatchesOld=false", uuid);
                return;
            }

            CompletePlayerData deathSnapshot = null;
            if (!alive && config.save.saveOnDeath && context.state() == SyncState.READY) {
                deathSnapshot = extractLiveCheckpointOnServerThread(
                    currentServer, oldPlayer, context.pendingDisconnectItemsJson());
            }
            boolean dimensionChanged = !oldPlayer.getWorld().equals(newPlayer.getWorld());
            sessions.updatePlayer(context, newPlayer);

            if (deathSnapshot != null) {
                queueSnapshot(context, deathSnapshot, SaveReason.DEATH);
            }
            if (alive && dimensionChanged && config.sync.syncOnDimensionChange && config.save.saveOnDimensionChange
                    && context.state() == SyncState.READY) {
                queueSnapshot(context,
                    extractLiveCheckpointOnServerThread(
                        currentServer, newPlayer, context.pendingDisconnectItemsJson()), SaveReason.DIMENSION);
            }
        }
    }

    /** READY playerのleaseを定期的に確認する。renewもUUID単位のqueueを通す。 */
    private void renewReadySessions() {
        if (stopping.get()) return;
        long scheduledAtNanos = nextRenewScheduledNanos;
        long actualExecutionNanos = System.nanoTime();
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(config.sync.leaseRenewIntervalMs);
        nextRenewScheduledNanos = scheduledAtNanos + intervalNanos;
        if (nextRenewScheduledNanos < actualExecutionNanos - intervalNanos) {
            nextRenewScheduledNanos = actualExecutionNanos + intervalNanos;
        }
        for (PlayerSessionContext context : sessions.snapshot()) {
            ServerPlayerEntity trackedPlayer = context.playerForServerThread();
            if (trackedPlayer == null || !sessions.shouldManagePlayer(trackedPlayer)) continue;
            if (context.state() != SyncState.READY) continue;
            if (!withinLeaseSafetyMargin(context)) {
                // DB workerがsocket timeout等で塞がっていても、lease expiration後までREADYを維持しない。
                loseAuthority(context, "lease renewal safety margin exceeded", null);
                continue;
            }
            if (!context.tryBeginLeaseRenew()) continue;
            submitRenew(context, scheduledAtNanos, actualExecutionNanos);
        }
    }

    private void submitRenew(
        PlayerSessionContext context,
        long scheduledAtNanos,
        long schedulerExecutionNanos
    ) {
        Ownership ownership = ownership(context);
        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        try {
            serialExecutor.submit(context.uuid(), () -> {
                long workerStartNanos = System.nanoTime();
                logDbQueueDiagnostic(
                        "RENEW_LEASE", context, enqueueNanos, workerStartNanos, queueDepthAtSubmit);
                long dbStartNanos = workerStartNanos;
                try {
                    long leaseReferenceNanos = workerStartNanos;
                    if (context.state() != SyncState.READY) {
                        return RenewOutcome.notExecuted(
                            leaseReferenceNanos, scheduledAtNanos, schedulerExecutionNanos,
                            workerStartNanos, queueDepthAtSubmit);
                    }
                    dbStartNanos = System.nanoTime();
                    LeaseRenewalResult renewal = repository.renewLeaseWithResult(
                        ownership, config.sync.leaseDurationMs);
                    long dbEndNanos = System.nanoTime();
                    return new RenewOutcome(
                        renewal.renewed(), renewal.leaseAnchorNanos(), scheduledAtNanos, schedulerExecutionNanos,
                        workerStartNanos, dbStartNanos, dbEndNanos, queueDepthAtSubmit,
                        serialExecutor.metrics(), database.poolMetrics());
                } catch (Exception error) {
                    long failedAtNanos = System.nanoTime();
                    logRenewDiagnostic(
                        context, ownership, scheduledAtNanos, schedulerExecutionNanos,
                        workerStartNanos, dbStartNanos, failedAtNanos, queueDepthAtSubmit,
                        "FAILURE", error);
                    throw new RenewException(error);
                }
            }).whenComplete((renewed, error) -> {
                context.endLeaseRenew();
                if (renewed != null) {
                    logRenewDiagnostic(
                        context, ownership, renewed.scheduledAtNanos(), renewed.schedulerExecutionNanos(),
                        renewed.workerStartNanos(), renewed.dbStartNanos(), renewed.dbEndNanos(),
                        renewed.queueDepthAtSubmit(), renewed.renewed() ? "RENEWED" : "NOT_EXECUTED", null,
                        renewed);
                }
                if (renewed != null && renewed.renewed()) {
                    if (sessions.isCurrent(context)
                            && context.fencingToken() == ownership.fencingToken()
                            && context.state() == SyncState.READY) {
                        context.recordLeaseRenewed(renewed.leaseReferenceNanos());
                    }
                    StateLink.LOGGER.debug(
                        "Lease renewed uuid={} session={} fence={}",
                        context.uuid(), shortId(context.localSessionId()), context.fencingToken());
                    return;
                }
                if (context.state() != SyncState.READY) return;
                Throwable cause = unwrap(error);
                boolean elapsed = !withinLeaseSafetyMargin(context);
                if (cause == null || !(cause instanceof RenewException)) {
                    // affected rows=0はidentity不一致またはDB側での期限切れであり、fencedとして扱う。
                    loseAuthority(context, "lease renewal rejected", cause);
                } else if (elapsed) {
                    loseAuthority(context, "lease renewal timeout exceeded safety margin", cause.getCause());
                } else {
                    StateLink.LOGGER.warn(
                        "Lease renew temporarily failed uuid={} session={} fence={} elapsedMs={}",
                        context.uuid(), shortId(context.localSessionId()), context.fencingToken(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.lastLeaseRenewNanos()));
                }
            });
        } catch (RuntimeException error) {
            context.endLeaseRenew();
            StateLink.LOGGER.error(
                "Lease renew could not be queued uuid={} session={} fence={}",
                context.uuid(), shortId(context.localSessionId()), context.fencingToken(), error);
        }
    }

    private void logDbQueueDiagnostic(
        String operation,
        PlayerSessionContext context,
        long enqueueNanos,
        long workerStartNanos,
        int queueDepthAtSubmit
    ) {
        if (!config.database.diagnosticLogging) return;
        PerPlayerSerialExecutor.ExecutorMetrics executorMetrics = serialExecutor.metrics();
        DatabaseManager.PoolMetrics poolMetrics = database.poolMetrics();
        StateLink.LOGGER.info(
            "STATELINK_DB_QUEUE operation={} uuid={} session={} fence={} revision={} thread={} "
                + "enqueueMonoNanos={} workerStartMonoNanos={} queueDelayMs={} "
                + "perPlayerQueueDepthAtSubmit={} perPlayerQueueDepthAtWorker={} "
                + "dbWorkerActive={} dbWorkerPoolSize={} dbWorkerQueue={} "
                + "poolActive={} poolIdle={} poolTotal={} poolWaiting={}",
            operation, context.uuid(), shortId(context.localSessionId()), context.fencingToken(),
            context.lastSuccessfulRevision(), Thread.currentThread().getName(), enqueueNanos,
            workerStartNanos, elapsedMillis(workerStartNanos - enqueueNanos), queueDepthAtSubmit,
            serialExecutor.queueDepth(context.uuid()), executorMetrics.active(), executorMetrics.poolSize(),
            executorMetrics.queued(), poolMetrics.active(), poolMetrics.idle(), poolMetrics.total(),
            poolMetrics.waiting());
    }

    private void logRenewDiagnostic(
        PlayerSessionContext context,
        Ownership ownership,
        long scheduledAtNanos,
        long schedulerExecutionNanos,
        long workerStartNanos,
        long dbStartNanos,
        long dbEndNanos,
        int queueDepthAtSubmit,
        String outcome,
        Throwable failure
    ) {
        logRenewDiagnostic(
            context, ownership, scheduledAtNanos, schedulerExecutionNanos, workerStartNanos,
            dbStartNanos, dbEndNanos, queueDepthAtSubmit, outcome, failure,
            serialExecutor.metrics(), database.poolMetrics());
    }

    private void logRenewDiagnostic(
        PlayerSessionContext context,
        Ownership ownership,
        long scheduledAtNanos,
        long schedulerExecutionNanos,
        long workerStartNanos,
        long dbStartNanos,
        long dbEndNanos,
        int queueDepthAtSubmit,
        String outcome,
        Throwable failure,
        RenewOutcome metrics
    ) {
        logRenewDiagnostic(
            context, ownership, scheduledAtNanos, schedulerExecutionNanos, workerStartNanos,
            dbStartNanos, dbEndNanos, queueDepthAtSubmit, outcome, failure,
            metrics.executorMetrics(), metrics.poolMetrics());
    }

    private void logRenewDiagnostic(
        PlayerSessionContext context,
        Ownership ownership,
        long scheduledAtNanos,
        long schedulerExecutionNanos,
        long workerStartNanos,
        long dbStartNanos,
        long dbEndNanos,
        int queueDepthAtSubmit,
        String outcome,
        Throwable failure,
        PerPlayerSerialExecutor.ExecutorMetrics executorMetrics,
        DatabaseManager.PoolMetrics poolMetrics
    ) {
        if (!config.database.diagnosticLogging) return;
        long nowNanos = System.nanoTime();
        long ageNanos = Math.max(0L, nowNanos - context.lastLeaseRenewNanos());
        long safetyBudgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L,
            config.sync.leaseDurationMs - config.sync.leaseRenewIntervalMs));
        long remainingBudgetNanos = safetyBudgetNanos - ageNanos;
        SqlFailure failureInfo = SqlFailure.from(failure);
        StateLink.LOGGER.info(
            "STATELINK_RENEW_TRACE operation=RENEW_LEASE uuid={} session={} fence={} revision={} thread={} "
                + "scheduledAtMonoNanos={} actualExecutionAtMonoNanos={} workerStartMonoNanos={} "
                + "schedulerDelayMs={} queueDelayMs={} dbDurationMs={} "
                + "timeSinceLastSuccessfulRenewMs={} remainingLocalSafetyBudgetMs={} "
                + "perPlayerQueueDepthAtSubmit={} perPlayerQueueDepthAtWorker={} "
                + "dbWorkerActive={} dbWorkerPoolSize={} dbWorkerQueue={} "
                + "poolActive={} poolIdle={} poolTotal={} poolWaiting={} outcome={} "
                + "sqlState={} vendorCode={} failureType={}",
            context.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(),
            context.lastSuccessfulRevision(), Thread.currentThread().getName(), scheduledAtNanos,
            schedulerExecutionNanos, workerStartNanos,
            elapsedMillis(schedulerExecutionNanos - scheduledAtNanos),
            elapsedMillis(workerStartNanos - schedulerExecutionNanos),
            elapsedMillis(Math.max(0L, dbEndNanos - dbStartNanos)), elapsedMillis(ageNanos),
            elapsedMillis(remainingBudgetNanos), queueDepthAtSubmit, serialExecutor.queueDepth(context.uuid()),
            executorMetrics.active(), executorMetrics.poolSize(), executorMetrics.queued(),
            poolMetrics.active(), poolMetrics.idle(), poolMetrics.total(), poolMetrics.waiting(), outcome,
            failureInfo.sqlState(), failureInfo.vendorCode(), failureInfo.failureType());
    }

    private static long elapsedMillis(long nanos) {
        return nanos <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private void submitAcquire(
        PlayerSessionContext context,
        MinecraftServer server,
        int attempt,
        long delayMs
    ) {
        if (stopping.get() || context.isClosed() || !sessions.isCurrent(context)) return;
        if (joinTimedOut(context)) {
            server.execute(() -> failLoadingOnServerThread(
                context, server, "JOIN synchronization timeout before acquire retry", null));
            return;
        }
        if (delayMs > 0) {
            try {
                scheduler.schedule(() -> submitAcquire(context, server, attempt, 0), delayMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                server.execute(() -> failLoadingOnServerThread(
                    context, server, "acquire retry scheduling failed", error));
            }
            return;
        }

        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        serialExecutor.submit(context.uuid(), () -> {
            logDbQueueDiagnostic(
                "ACQUIRE_LOAD", context, enqueueNanos, System.nanoTime(), queueDepthAtSubmit);
            if (stopping.get() || context.isClosed() || !sessions.isCurrent(context)) return null;
            try {
                AcquireResult result = repository.acquireOwnershipAndLoad(
                    context.uuid(),
                    config.sync.serverId,
                    context.localSessionId(),
                    config.sync.leaseDurationMs, config.sync
                );
                if (result.status()==AcquireStatus.RECOVERY_REQUIRED && permitsRiskAcknowledgedAutomaticRecovery()) {
                    var recovered=attemptPolicyRecovery(context.uuid());
                    if(recovered!=null && (recovered.status()==PlayerDataRepository.RecoveryResolutionStatus.RESOLVED
                            || recovered.status()==PlayerDataRepository.RecoveryResolutionStatus.ALREADY_RESOLVED)
                            && !stopping.get() && !context.isClosed() && sessions.isCurrent(context)) {
                        result=repository.acquireOwnershipAndLoad(context.uuid(),config.sync.serverId,context.localSessionId(),config.sync.leaseDurationMs,config.sync);
                    }
                }
                // acquireのtransactionが完了した直後にDISCONNECT、再接続、shutdownが
                // 起きることがある。staleな結果をserver threadへ運ぶだけでなく、ここで
                // ownershipを同じworker上から回収して、executor終了後のrelease漏れを防ぐ。
                if (result != null && result.ownership() != null) {
                    // ownership取得済みという事実をcallbackより先にcontextへ記録する。
                    // これにより、server threadがhandleAcquireResultを実行する前に
                    // shutdown/disconnectしても、後始末のfenceを失わない。
                    context.markOwnership(
                        result.ownership().fencingToken(), result.ownership().dataRevision(),
                        result.leaseAnchorNanos());
                    if (stopping.get() || context.isClosed() || !sessions.isCurrent(context)) {
                        releaseOwnershipFromWorker(result.ownership(), "stale-acquire-result");
                        return null;
                    }
                }
                return result;
            } catch (Exception databaseError) {
                throw new DatabaseOperationException(databaseError);
            }
        }).whenComplete((completion, error) -> {
            if (completion == null && error == null) return;
            AcquireResult result = completion;
            // stopping/stale判定をserver thread callbackまで遅らせない。shutdownが
            // datasourceを閉じる前に、ownershipを取得済みならこのcompletion threadで回収する。
            if (result != null && result.ownership() != null
                    && (stopping.get() || context.isClosed() || !sessions.isCurrent(context))) {
                releaseOwnershipFromWorker(result.ownership(), "stale-acquire-completion");
                return;
            }
            server.execute(() -> handleAcquireResult(
                context, server, result, error, attempt));
        });
    }

    /** このメソッドはserver thread上で実行される。 */
    private void handleAcquireResult(
        PlayerSessionContext context,
        MinecraftServer server,
        AcquireResult result,
        Throwable error,
        int attempt
    ) {
        if (!sessions.isCurrent(context)) {
            if (result != null && result.ownership() != null) {
                context.markOwnership(
                    result.ownership().fencingToken(), result.ownership().dataRevision(),
                    result.leaseAnchorNanos());
                context.closeWithoutSave();
                scheduleRelease(result.ownership(), "stale-load-result");
                StateLink.LOGGER.warn(
                    "Stale LOAD discarded uuid={} session={} fence={}",
                    context.uuid(), shortId(context.localSessionId()), result.ownership().fencingToken());
            }
            return;
        }

        if (error != null) {
            scheduleAcquireRetryOrFail(context, server, attempt, unwrap(error), "LOAD failure");
            return;
        }
        if (result == null) {
            scheduleAcquireRetryOrFail(
                context, server, attempt,
                new IllegalStateException("acquire completed without a result"),
                "LOAD returned no result");
            return;
        }
        if (result.status() == AcquireStatus.BUSY) {
            StateLink.LOGGER.debug(
                "Ownership BUSY uuid={} localSession={} currentOwnerServer={} currentOwnerSession={}",
                context.uuid(), shortId(context.localSessionId()), result.currentOwnerServer(),
                shortId(result.currentOwnerSession()));
            scheduleAcquireRetryOrFail(context, server, attempt, null, "ownership BUSY");
            return;
        }
        if (result.status() == AcquireStatus.RECOVERY_REQUIRED) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(context.uuid());
            boolean current = sessions.isCurrent(context, player);
            boolean operatorOnly = result.requiresOperatorReconciliation();
            if(permitsRiskAcknowledgedAutomaticRecovery() && !operatorOnly && current && !joinTimedOut(context)) {
                if(attempt==0) player.sendMessage(net.minecraft.text.Text.of("Recovering synchronized player data..."),false);
                submitAcquire(context,server,attempt+1,config.recovery.retryIntervalSeconds*1000L);
                return;
            }
            context.markDegraded();
            sessions.remove(context);
            StateLink.LOGGER.warn(
                "RECOVERY_REQUIRED login refused without LOAD/apply uuid={} reason={} previousOwnerServer={} "
                    + "previousOwnerSession={} localSession={}", context.uuid(), result.recoveryReason(),
                result.currentOwnerServer(), shortId(result.currentOwnerSession()), shortId(context.localSessionId()));
            if (current && player != null) {
                disconnect(player, RecoveryMessages.refusal(result.recoveryReason(), operatorOnly,
                    permitsRiskAcknowledgedAutomaticRecovery()));
            }
            return;
        }
        if (result.status() == AcquireStatus.EXPIRED || result.ownership() == null) {
            failLoadingOnServerThread(context, server, "local ownership session expired", null);
            return;
        }

        context.markOwnership(
            result.ownership().fencingToken(), result.ownership().dataRevision(), result.leaseAnchorNanos());
        StateLink.LOGGER.info(
            "Ownership acquired uuid={} serverId={} session={} fence={} revision={}",
            context.uuid(), config.sync.serverId, shortId(context.localSessionId()),
            context.fencingToken(), context.lastSuccessfulRevision());
        ServerPlayerEntity currentPlayer = server.getPlayerManager().getPlayer(context.uuid());
        if (!sessions.isCurrent(context, currentPlayer) || context.state() != SyncState.LOADING) {
            context.closeWithoutSave();
            scheduleRelease(result.ownership(), "stale-after-acquire");
            StateLink.LOGGER.warn(
                "Acquired ownership for stale session; released uuid={} session={} fence={}",
                context.uuid(), shortId(context.localSessionId()), result.ownership().fencingToken());
            return;
        }
        if (joinTimedOut(context)) {
            failClosed(context, currentPlayer, "JOIN synchronization timeout after ownership acquire", null, true);
            return;
        }
        if (!withinLeaseSafetyMargin(context)) {
            failClosed(context, currentPlayer, "lease expired while applying LOAD", null, true);
            return;
        }

        if (result.data().isPresent()) {
            CompletePlayerDataSerializer.PreparedPlayerData prepared;
            try {
                prepared = CompletePlayerDataSerializer.prepare(currentPlayer, result.data().get(), config.sync);
            } catch (Throwable applyError) {
                failClosed(context, currentPlayer, "authoritative PlayerData validation failed", applyError, true);
                return;
            }
            if (prepared.pendingNormalizationRequired()) {
                CompletePlayerData normalized;
                try {
                    normalized = prepared.normalizedCompleteData();
                } catch (Throwable serializationError) {
                    failClosed(context, currentPlayer,
                        "pending disconnect item normalization failed", serializationError, true);
                    return;
                }
                long sequence = context.recordDirtySnapshot(normalized, SaveReason.INITIAL);
                submitPendingNormalization(context, currentPlayer, prepared, normalized, server, 0, sequence);
                return;
            }
            applyLoadedDataAndReady(context, currentPlayer, prepared, server);
            return;
        }

        // row不存在はfirst-loginとして扱い、current Minecraft stateをinitial authoritative snapshotへする。
        CompletePlayerData initialData;
        try {
            initialData = extractCompletePlayerDataOnServerThread(
                server, currentPlayer, context.pendingDisconnectItemsJson());
        } catch (RuntimeException extractError) {
            context.recordSnapshotCaptureFailure();
            failClosed(context, currentPlayer, "initial snapshot failed", extractError, true);
            return;
        }
        long snapshotSequence = context.recordDirtySnapshot(initialData, SaveReason.INITIAL);
        submitInitialSave(context, currentPlayer, initialData, server, 0, snapshotSequence);
    }

    /**
     * Returns overflow transient stacks to inventory only after the normalized
     * inventory + remaining pending payload are durable under the current fence.
     */
    private void submitPendingNormalization(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerDataSerializer.PreparedPlayerData prepared,
        CompletePlayerData normalized,
        MinecraftServer server,
        int attempt,
        long snapshotSequence
    ) {
        if (!sessions.isCurrent(context, player) || context.isClosed()
                || context.state() != SyncState.LOADING) {
            context.closeWithoutSave();
            scheduleReleaseIfOwned(context, "stale-pending-item-normalization");
            return;
        }
        if (joinTimedOut(context)) {
            failClosed(context, player, "JOIN timeout before pending item normalization", null, true);
            return;
        }

        Ownership ownership = ownership(context);
        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        serialExecutor.submit(context.uuid(), () -> {
            logDbQueueDiagnostic(
                "PENDING_ITEM_NORMALIZE", context, enqueueNanos, System.nanoTime(), queueDepthAtSubmit);
            if (stopping.get() || context.isClosed() || !sessions.isCurrent(context)) {
                releaseOwnershipFromWorker(ownership, "stale-pending-item-normalization-before-start");
                return null;
            }
            if (joinTimedOut(context) || !withinLeaseSafetyMargin(context)) {
                throw new IllegalStateException("JOIN or lease safety deadline expired before pending item normalization");
            }
            try {
                SaveResult result = repository.saveCompletePlayerData(
                    normalized, ownership, config.sync, config.sync.leaseDurationMs, false);
                if (result.committed()
                        && (stopping.get() || context.isClosed() || !sessions.isCurrent(context))) {
                    releaseOwnershipFromWorker(ownership, "stale-pending-item-normalization-result");
                    return null;
                }
                return result;
            } catch (Exception databaseError) {
                throw new DatabaseOperationException(databaseError);
            }
        }).whenComplete((result, error) -> {
            if (result == null && error == null) return;
            if (result != null && result.committed()
                    && (stopping.get() || context.isClosed() || !sessions.isCurrent(context))) {
                releaseOwnershipFromWorker(ownership, "stale-pending-item-normalization-completion");
                return;
            }
            try {
                server.execute(() -> handlePendingNormalizationResult(
                    context, player, prepared, normalized, server, attempt, snapshotSequence, result, error));
            } catch (RuntimeException dispatchError) {
                if (result != null && result.committed()) {
                    releaseOwnershipFromWorker(ownership, "pending-item-normalization-callback-dispatch-failed");
                }
                StateLink.LOGGER.error(
                    "Could not dispatch pending item normalization result uuid={} session={} fence={}",
                    context.uuid(), shortId(context.localSessionId()), context.fencingToken(), dispatchError);
            }
        });
    }

    private void handlePendingNormalizationResult(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerDataSerializer.PreparedPlayerData prepared,
        CompletePlayerData normalized,
        MinecraftServer server,
        int attempt,
        long snapshotSequence,
        SaveResult result,
        Throwable error
    ) {
        if (!sessions.isCurrent(context, player) || context.state() != SyncState.LOADING) {
            context.closeWithoutSave();
            scheduleReleaseIfOwned(context, "stale-pending-item-normalization-result");
            return;
        }
        if (error != null) {
            schedulePendingNormalizationRetry(
                context, player, prepared, normalized, server, attempt, snapshotSequence, unwrap(error));
            return;
        }
        if (result == null) {
            schedulePendingNormalizationRetry(
                context, player, prepared, normalized, server, attempt, snapshotSequence,
                new IllegalStateException("pending item normalization returned no result"));
            return;
        }
        if (result.status() == SaveStatus.FENCED) {
            failClosed(context, player, "pending item normalization fenced", null, true);
            return;
        }
        context.recordSuccessfulSave(
            normalized, snapshotSequence, result.dataRevision(), result.leaseAnchorNanos());
        if (joinTimedOut(context)) {
            failClosed(context, player, "JOIN timeout after pending item normalization", null, true);
            return;
        }
        applyLoadedDataAndReady(context, player, prepared, server);
    }

    private void schedulePendingNormalizationRetry(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerDataSerializer.PreparedPlayerData prepared,
        CompletePlayerData normalized,
        MinecraftServer server,
        int attempt,
        long snapshotSequence,
        Throwable error
    ) {
        if (!sessions.isCurrent(context, player) || context.state() != SyncState.LOADING) {
            context.closeWithoutSave();
            scheduleReleaseIfOwned(context, "stale-pending-item-normalization-failure");
            return;
        }
        if (joinTimedOut(context)) {
            failClosed(context, player, "pending item normalization failed before READY", error, true);
            return;
        }
        long delay = backoff(attempt);
        long remainingMs = remainingJoinTimeMs(context);
        if (delay >= remainingMs) {
            scheduleJoinTimeout(context, server, "pending item normalization timeout", error, remainingMs);
            return;
        }
        StateLink.LOGGER.warn(
            "Pending item normalization retry uuid={} session={} fence={} attempt={} delayMs={} error={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), attempt + 1, delay, error);
        try {
            scheduler.schedule(() -> submitPendingNormalization(
                context, player, prepared, normalized, server, attempt + 1, snapshotSequence),
                delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingError) {
            failClosed(context, player, "pending item normalization retry scheduling failed", schedulingError, true);
        }
    }

    private void applyLoadedDataAndReady(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerDataSerializer.PreparedPlayerData prepared,
        MinecraftServer server
    ) {
        try {
            if (!withinLeaseSafetyMargin(context)) {
                failClosed(context, player, "lease expired while applying LOAD", null, true);
                return;
            }
            CompletePlayerDataSerializer.applyPrepared(player, prepared);
            if (!withinLeaseSafetyMargin(context)) {
                failClosed(context, player, "lease expired while applying LOAD", null, true);
                return;
            }
            if (joinTimedOut(context)) {
                failClosed(context, player, "JOIN synchronization timeout while applying LOAD", null, true);
                return;
            }
            context.setPendingDisconnectItemsJson(prepared.pendingDisconnectItemsJson());
            if (!markReadyIfSafe(context, player, server)) {
                failClosed(context, player, "lease/session safety check failed before READY", null, true);
                return;
            }
            StateLink.LOGGER.info(
                "LOAD success uuid={} session={} fence={} revision={} state=READY",
                context.uuid(), shortId(context.localSessionId()), context.fencingToken(),
                context.lastSuccessfulRevision());
        } catch (Throwable applyError) {
            // prepare/apply failure never produces a READY or a local-state SAVE.
            failClosed(context, player, "authoritative PlayerData apply failed", applyError, true);
        }
    }

    private void submitInitialSave(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerData data,
        MinecraftServer server,
        int attempt,
        long snapshotSequence
    ) {
        if (!sessions.isCurrent(context, player)
                || context.isClosed()
                || context.state() != SyncState.LOADING) {
            context.closeWithoutSave();
            scheduleReleaseIfOwned(context, "stale-initial-snapshot");
            return;
        }
        if (joinTimedOut(context)) {
            server.execute(() -> failLoadingOnServerThread(
                context, server, "JOIN synchronization timeout before initial save", null));
            return;
        }
        Ownership ownership = ownership(context);
        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        serialExecutor.submit(context.uuid(), () -> {
            logDbQueueDiagnostic(
                "INITIAL_SAVE", context, enqueueNanos, System.nanoTime(), queueDepthAtSubmit);
            if (context.isClosed() || !sessions.isCurrent(context)) {
                releaseOwnershipFromWorker(ownership, "stale-initial-save-before-start");
                return null;
            }
            try {
                SaveResult result = repository.saveCompletePlayerData(
                    data, ownership, config.sync, config.sync.leaseDurationMs, false);
                // first-loginのinitial save中にsessionが消えた場合も、成功したownershipを
                // 後段のserver callbackへ任せず、この同じper-player taskの末尾で解放する。
                if (result != null && result.committed()
                        && (stopping.get() || context.isClosed() || !sessions.isCurrent(context))) {
                    releaseOwnershipFromWorker(ownership, "stale-initial-save-result");
                    return null;
                }
                return result;
            } catch (Exception databaseError) {
                throw new DatabaseOperationException(databaseError);
            }
        }).whenComplete((result, error) -> {
            if (result == null && error == null) return;
            if (result != null && result.committed()
                    && (stopping.get() || context.isClosed() || !sessions.isCurrent(context))) {
                releaseOwnershipFromWorker(ownership, "stale-initial-save-completion");
                return;
            }
            server.execute(() -> {
                if (!sessions.isCurrent(context, player)) {
                    context.closeWithoutSave();
                    scheduleRelease(ownership, "stale-initial-save-result");
                    return;
                }
                if (error != null) {
                    scheduleInitialSaveRetryOrFail(
                        context, player, data, server, attempt, snapshotSequence, unwrap(error));
                    return;
                }
                if (joinTimedOut(context)) {
                    failClosed(context, player, "JOIN synchronization timeout after initial save", null, true);
                    return;
                }
                if (result.status() == SaveStatus.FENCED) {
                    failClosed(context, player, "initial save fenced", null, false);
                    return;
                }
                context.recordSuccessfulSave(
                    data, snapshotSequence, result.dataRevision(), result.leaseAnchorNanos());
                if (!markReadyIfSafe(context, player, server)) {
                    if (joinTimedOut(context)) {
                        failClosed(context, player, "JOIN timeout before READY", null, true);
                    } else if (!withinLeaseSafetyMargin(context)) {
                        submitLoadingLeaseRevalidation(context, player, server);
                    } else {
                        failClosed(context, player, "session/player identity changed before READY", null, false);
                    }
                    return;
                }
                StateLink.LOGGER.info(
                    "First-login initial SAVE committed uuid={} session={} fence={} revision={} state=READY",
                    context.uuid(), shortId(context.localSessionId()), context.fencingToken(), result.dataRevision());
            });
        });
    }

    private void scheduleInitialSaveRetryOrFail(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        CompletePlayerData data,
        MinecraftServer server,
        int attempt,
        long snapshotSequence,
        Throwable error
    ) {
        if (!sessions.isCurrent(context, player)) {
            context.closeWithoutSave();
            scheduleReleaseIfOwned(context, "stale-initial-save-result");
            return;
        }
        if (joinTimedOut(context)) {
            failClosed(context, player, "initial save failed before READY", error, true);
            return;
        }
        long delay = backoff(attempt);
        long remainingMs = remainingJoinTimeMs(context);
        if (delay >= remainingMs) {
            scheduleJoinTimeout(context, server, "initial save retry timeout", error, remainingMs);
            return;
        }
        StateLink.LOGGER.warn(
            "Initial SAVE retry uuid={} session={} attempt={} delayMs={} error={}",
            context.uuid(), shortId(context.localSessionId()), attempt + 1, delay,
            error == null ? "unknown" : error.toString());
        try {
            scheduler.schedule(
                () -> submitInitialSave(context, player, data, server, attempt + 1, snapshotSequence),
                delay,
                TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingError) {
            failClosed(context, player, "initial save retry scheduling failed", schedulingError, true);
        }
    }

    /** Revalidates a committed first-login lease without ever exposing a stale READY state. */
    private void submitLoadingLeaseRevalidation(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        MinecraftServer server
    ) {
        if (!sessions.isCurrent(context, player) || context.state() != SyncState.LOADING) return;
        if (joinTimedOut(context)) {
            failClosed(context, player, "JOIN timeout before lease revalidation", null, true);
            return;
        }
        Ownership ownership = ownership(context);
        try {
            serialExecutor.submit(context.uuid(), () -> {
                if (!sessions.isCurrent(context) || context.state() != SyncState.LOADING) return null;
                try {
                    return repository.renewLeaseWithResult(ownership, config.sync.leaseDurationMs);
                } catch (Exception databaseError) {
                    throw new DatabaseOperationException(databaseError);
                }
            }).whenComplete((renewal, error) -> {
                if (stopping.get() || context.isClosed() || !sessions.isCurrent(context)) {
                    if (renewal != null && renewal.renewed()) {
                        releaseOwnershipFromWorker(ownership, "stale-loading-lease-revalidation");
                    }
                    return;
                }
                try {
                    server.execute(() -> {
                        if (!sessions.isCurrent(context, player) || context.state() != SyncState.LOADING) {
                            if (renewal != null && renewal.renewed()) {
                                scheduleRelease(ownership, "stale-loading-lease-revalidation-result");
                            }
                            return;
                        }
                        if (error != null || renewal == null || !renewal.renewed()) {
                            failClosed(context, player, "lease revalidation failed before READY", unwrap(error), false);
                            return;
                        }
                        context.recordLeaseRenewed(renewal.leaseAnchorNanos());
                        if (joinTimedOut(context) || !markReadyIfSafe(context, player, server)) {
                            failClosed(context, player, "lease safety expired before READY revalidation callback", null, false);
                            return;
                        }
                        StateLink.LOGGER.info(
                            "First-login lease revalidated uuid={} session={} fence={} revision={} state=READY",
                            context.uuid(), shortId(context.localSessionId()), context.fencingToken(),
                            context.lastSuccessfulRevision());
                    });
                } catch (RuntimeException dispatchError) {
                    if (renewal != null && renewal.renewed()) {
                        releaseOwnershipFromWorker(ownership, "loading-revalidation-dispatch-failed");
                    }
                }
            });
        } catch (RuntimeException queueError) {
            failClosed(context, player, "lease revalidation could not be queued", queueError, false);
        }
    }

    private void scheduleAcquireRetryOrFail(
        PlayerSessionContext context,
        MinecraftServer server,
        int attempt,
        Throwable error,
        String reason
    ) {
        if (!sessions.isCurrent(context) || context.state() != SyncState.LOADING) return;
        if (joinTimedOut(context)) {
            failLoadingOnServerThread(context, server, reason + "; timeout", error);
            return;
        }
        long delay = backoff(attempt + 1);
        long remainingMs = remainingJoinTimeMs(context);
        if (delay >= remainingMs) {
            scheduleJoinTimeout(context, server, reason + "; timeout", error, remainingMs);
            return;
        }
        StateLink.LOGGER.warn(
            "{}; retrying uuid={} session={} attempt={} delayMs={} error={}",
            reason, context.uuid(), shortId(context.localSessionId()), attempt + 1, delay,
            error == null ? "none" : error.toString());
        try {
            scheduler.schedule(() -> submitAcquire(context, server, attempt + 1, 0), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingError) {
            failLoadingOnServerThread(context, server, "acquire retry scheduling failed", schedulingError);
        }
    }

    private boolean joinTimedOut(PlayerSessionContext context) {
        return System.nanoTime() - context.joinStartedNanos()
            >= TimeUnit.MILLISECONDS.toNanos(config.sync.joinSyncTimeoutMs);
    }

    private long remainingJoinTimeMs(PlayerSessionContext context) {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(config.sync.joinSyncTimeoutMs)
            - (System.nanoTime() - context.joinStartedNanos());
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    /**
     * 再試行delayがJOIN期限をまたぐ場合、期限時刻にserver threadでcloseする。
     * timeout callbackはDB workerを待たず、後から返る結果をstale扱いにする。
     */
    private void scheduleJoinTimeout(
        PlayerSessionContext context,
        MinecraftServer server,
        String reason,
        Throwable error,
        long delayMs
    ) {
        try {
            ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
                try {
                    server.execute(() -> failLoadingOnServerThread(context, server, reason, error));
                } catch (RuntimeException schedulingError) {
                    StateLink.LOGGER.error(
                        "Could not dispatch JOIN timeout to server thread uuid={} session={} reason={}",
                        context.uuid(), shortId(context.localSessionId()), reason, schedulingError);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            context.installJoinTimeout(timeoutTask);
        } catch (RuntimeException schedulingError) {
            // このメソッドは通常server thread callbackから呼ばれる。schedulerが
            // 閉じている場合だけ、その境界で直接fail-closedする。
            failLoadingOnServerThread(context, server, reason + "; timeout scheduling failed", schedulingError);
        }
    }

    private long contextCreatedNanos(PlayerSessionContext context) {
        // generationはidentity用でありclockではないため、最初のlease renew時刻をJOIN開始時刻として使わない。
        // contextに保存したjoin timestampがない古いcontextにも安全なfallbackを与える。
        return context.joinStartedNanos();
    }

    private long backoff(int attempt) {
        long initial = config.sync.handoffRetryInitialMs;
        long max = config.sync.handoffRetryMaxMs;
        if (attempt <= 0) return 0;
        long multiplier = 1L << Math.min(attempt - 1, 20);
        return Math.min(max, Math.multiplyExact(initial, multiplier));
    }

    private final java.util.concurrent.atomic.AtomicLong periodicCoalesced = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger maxSaveQueueDepth = new java.util.concurrent.atomic.AtomicInteger();

    private void observeSaveQueueDepth(int depth) {
        int previous = maxSaveQueueDepth.getAndAccumulate(depth, Math::max);
        if (depth > previous && depth > 2) {
            StateLink.LOGGER.warn("Per-player DB queue depth reached {} (periodic checkpoints coalesced={})",
                depth, periodicCoalesced.get());
        }
    }

    /** Diagnostics: periodic checkpoints skipped because one was still queued, and the deepest per-player queue seen. */
    public long periodicCheckpointsCoalesced() { return periodicCoalesced.get(); }
    public int maxObservedSaveQueueDepth() { return maxSaveQueueDepth.get(); }

    private void queueSnapshot(PlayerSessionContext context, CompletePlayerData snapshot, SaveReason reason) {
        if (stopping.get() || context == null || !context.canQueueNormalSave()) {
            if (reason == SaveReason.PERIODIC && context != null) context.periodicSaveStarted();
            return;
        }
        if (!withinLeaseSafetyMargin(context)) {
            if (reason == SaveReason.PERIODIC) context.periodicSaveStarted();
            loseAuthority(context, "lease safety expired before save snapshot was queued", null);
            return;
        }
        long snapshotSequence = context.recordDirtySnapshot(snapshot, reason);
        long changeVersion = context.checkpointChangeVersion();
        StateLink.LOGGER.debug(
            "SAVE queued uuid={} session={} fence={} reason={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason);
        submitNormalSave(context, snapshot, reason, snapshotSequence, changeVersion);
    }

    private void submitNormalSave(
        PlayerSessionContext context,
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence,
        long changeVersion
    ) {
        Ownership ownership = ownership(context);
        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        observeSaveQueueDepth(queueDepthAtSubmit + 1);
        // The periodic reservation is released exactly once: when this task starts, or when it
        // completes without ever having started (executor shutdown).
        java.util.concurrent.atomic.AtomicBoolean reservationReleased =
            new java.util.concurrent.atomic.AtomicBoolean(reason != SaveReason.PERIODIC);
        serialExecutor.submit(context.uuid(), () -> {
            if (reservationReleased.compareAndSet(false, true)) context.periodicSaveStarted();
            logDbQueueDiagnostic(
                "SAVE", context, enqueueNanos, System.nanoTime(), queueDepthAtSubmit);
            if (!context.canQueueNormalSave()) return null;
            if (!withinLeaseSafetyMargin(context)) {
                throw new IllegalStateException("local lease safety deadline expired before SAVE");
            }
            try {
                return repository.saveCompletePlayerData(
                    snapshot, ownership, config.sync, config.sync.leaseDurationMs, false);
            } catch (Exception databaseError) {
                throw new DatabaseOperationException(databaseError);
            }
        }).whenComplete((result, error) -> {
            if (reservationReleased.compareAndSet(false, true)) context.periodicSaveStarted();
            if (result == null && error == null) return;
            if (error != null) {
                context.recordSaveFailure(snapshot, reason, snapshotSequence);
                Throwable cause = unwrap(error);
                if (!withinLeaseSafetyMargin(context)) {
                    loseAuthority(context, "lease safety expired while SAVE was queued", cause);
                    return;
                }
                StateLink.LOGGER.warn(
                    "SAVE failed uuid={} session={} fence={} reason={} revision={} error={}",
                    context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason,
                    context.lastSuccessfulRevision(), cause);
                return;
            }
            if (result.status() == SaveStatus.FENCED) {
                loseAuthority(context, "SAVE fenced", null);
                return;
            }
            long leaseAnchor = sessions.isCurrent(context) && context.state() == SyncState.READY
                    && context.fencingToken() == ownership.fencingToken()
                ? result.leaseAnchorNanos() : PlayerDataRepository.NO_LEASE_ANCHOR;
            context.recordSuccessfulSave(snapshot, snapshotSequence, result.dataRevision(), leaseAnchor);
            if (sessions.isCurrent(context) && context.state()==SyncState.READY
                    && context.fencingToken()==ownership.fencingToken()) context.checkpointCommitted(changeVersion);
            if (reason == SaveReason.PERIODIC) {
                StateLink.LOGGER.debug(
                    "PERIODIC SAVE committed uuid={} session={} fence={} revision={}",
                    context.uuid(), shortId(context.localSessionId()), context.fencingToken(), result.dataRevision());
            } else {
                StateLink.LOGGER.info(
                    "SAVE committed uuid={} session={} fence={} reason={} revision={}",
                    context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason,
                    result.dataRevision());
            }
        });
    }

    private CompletableFuture<Void> submitFinalFlush(
        PlayerSessionContext context,
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence
    ) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        finalFlushes.put(completion, Boolean.TRUE);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            reason == SaveReason.SHUTDOWN_FINAL ? config.save.shutdownFlushTimeoutMs : config.save.finalFlushTimeoutMs);
        attemptFinalFlush(context, snapshot, reason, snapshotSequence, 0, deadline, completion);
        return completion;
    }

    private void attemptFinalFlush(
        PlayerSessionContext context,
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence,
        int attempt,
        long deadline,
        CompletableFuture<Void> completion
    ) {
        if (completion.isDone()) return;
        if (System.nanoTime() >= deadline) {
            finalFailure(context, snapshot, reason, snapshotSequence,
                new java.util.concurrent.TimeoutException("final flush deadline exceeded"), completion);
            return;
        }
        if (!context.canFlush()) {
            finalFailure(context, snapshot, reason, snapshotSequence,
                new IllegalStateException("session is not flushable"), completion);
            return;
        }
        Ownership ownership = ownership(context);
        long enqueueNanos = System.nanoTime();
        int queueDepthAtSubmit = serialExecutor.queueDepth(context.uuid());
        CompletableFuture<SaveResult> saveFuture;
        try {
            saveFuture = serialExecutor.submit(context.uuid(), () -> {
                logDbQueueDiagnostic(
                    "SAVE_FINAL", context, enqueueNanos, System.nanoTime(), queueDepthAtSubmit);
                try {
                    return repository.saveCompletePlayerData(
                        snapshot, ownership, config.sync, config.sync.leaseDurationMs, true);
                } catch (Exception databaseError) {
                    throw new DatabaseOperationException(databaseError);
                }
            });
        } catch (RuntimeException schedulingError) {
            finalFailure(context, snapshot, reason, snapshotSequence, schedulingError, completion);
            return;
        }
        saveFuture.whenComplete((result, error) -> {
            if (completion.isDone()) return;
            if (error != null) {
                // shutdown中も、schedulerがまだ開いているshutdown flush window内はbounded retryを許可する。
                if (System.nanoTime() < deadline) {
                    scheduleFinalRetry(
                        context, snapshot, reason, snapshotSequence, attempt, deadline, completion, unwrap(error));
                } else {
                    finalFailure(context, snapshot, reason, snapshotSequence, unwrap(error), completion);
                }
                return;
            }
            if (result.status() == SaveStatus.FENCED) {
                finalFailure(context, snapshot, reason, snapshotSequence,
                    new IllegalStateException("final flush rejected by fencing token"), completion);
                return;
            }
            context.recordSuccessfulSave(snapshot, snapshotSequence, result.dataRevision());
            context.closeWithoutSave();
            sessions.remove(context);
            finalFlushes.remove(completion);
            completion.complete(null);
            StateLink.LOGGER.info(
                "Final flush committed and ownership released uuid={} session={} fence={} reason={} revision={}",
                context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason,
                result.dataRevision());
        });
    }

    private void scheduleFinalRetry(
        PlayerSessionContext context,
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence,
        int attempt,
        long deadline,
        CompletableFuture<Void> completion,
        Throwable error
    ) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            finalFailure(context, snapshot, reason, snapshotSequence, error, completion);
            return;
        }
        long delay = backoff(attempt + 1);
        long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        if (delay >= remainingMs) {
            finalFailure(context, snapshot, reason, snapshotSequence, error, completion);
            return;
        }
        StateLink.LOGGER.warn(
            "Final flush retry uuid={} session={} fence={} reason={} attempt={} delayMs={} error={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason, attempt + 1,
            delay, error);
        try {
            scheduler.schedule(
                () -> attemptFinalFlush(
                    context, snapshot, reason, snapshotSequence, attempt + 1, deadline, completion),
                delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingError) {
            finalFailure(context, snapshot, reason, snapshotSequence, schedulingError, completion);
        }
    }

    private void finalFailure(
        PlayerSessionContext context,
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence,
        Throwable error,
        CompletableFuture<Void> completion
    ) {
        context.recordSaveFailure(snapshot, reason, snapshotSequence);
        // 先にcurrent mapから外す。shutdown側がこのcontextを見た瞬間に
        // 「新しいsaveなしrelease」と解釈しないよう、final failureの所有権は
        // lease expiryへ委ねる境界をmap identityで先に公開する。
        sessions.remove(context);
        context.markDegraded();
        scheduleRecoveryRequired(ownership(context), reason == SaveReason.SHUTDOWN_FINAL
            ? PlayerDataRepository.RecoveryReason.SHUTDOWN_TIMEOUT
            : PlayerDataRepository.RecoveryReason.FINAL_SAVE_FAILED);
        finalFlushes.remove(completion);
        completion.completeExceptionally(error);
        StateLink.LOGGER.error(
            "Final flush failed; ownership is intentionally not force-released uuid={} session={} fence={} reason={} failure={} lastSuccessfulRevision={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason, error,
            context.lastSuccessfulRevision());
    }

    /**
     * worker threadから呼べる、stale ownershipの後始末。
     * Minecraft objectには触れず、acquire/initial-save taskの直後に同じ
     * per-player queue上で実行されるため、shutdown後のserver callbackに依存しない。
     */
    private void releaseOwnershipFromWorker(Ownership ownership, String reason) {
        try {
            boolean released = repository.releaseOwnership(ownership);
            StateLink.LOGGER.info(
                "Ownership abandoned to RECOVERY_REQUIRED uuid={} session={} fence={} reason={} marked={}",
                ownership.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(), reason, released);
        } catch (Exception error) {
            // 別fenceへ触れず、失敗時はDBのlease expiryに任せる。
            StateLink.LOGGER.error(
                "Ownership release failed after stale worker result uuid={} session={} fence={} reason={} error={}",
                ownership.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(), reason, error);
        }
    }

    private boolean withinLeaseSafetyMargin(PlayerSessionContext context) {
        return context.hasLeaseSafetyAt(System.nanoTime(), leaseSafetyBudgetNanos());
    }

    private long leaseSafetyBudgetNanos() {
        long safetyDeadlineMs = Math.max(1L,
            config.sync.leaseDurationMs - config.sync.leaseRenewIntervalMs);
        return TimeUnit.MILLISECONDS.toNanos(safetyDeadlineMs);
    }

    /**
     * Player-originated World side effects retain at least three seconds before
     * the DB lease's conservative expiry estimate. With the default 5s/1.5s
     * lease this accepts anchors younger than 2s; a longer bounded JVM/server
     * pause is still not made safe by a local check alone.
     */
    private long worldMutationLeaseSafetyBudgetNanos() {
        long minimumRemainingMs = Math.max(3_000L, config.sync.leaseRenewIntervalMs);
        long maximumAnchorAgeMs = Math.max(1L, config.sync.leaseDurationMs - minimumRemainingMs);
        return TimeUnit.MILLISECONDS.toNanos(maximumAnchorAgeMs);
    }

    private boolean markReadyIfSafe(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        MinecraftServer server
    ) {
        boolean currentPlayer = server.getPlayerManager().getPlayer(context.uuid()) == player;
        return context.markReadyIfSafe(
            sessions.isCurrent(context, player),
            currentPlayer,
            joinTimedOut(context),
            System.nanoTime(),
            leaseSafetyBudgetNanos());
    }

    private void scheduleReleaseIfOwned(PlayerSessionContext context, String reason) {
        if (context.fencingToken() < 0) return;
        scheduleRelease(ownership(context), reason);
    }

    private void scheduleRelease(Ownership ownership, String reason) {
        try {
            serialExecutor.submit(ownership.uuid(), () -> {
                try {
                    boolean released = repository.releaseOwnership(ownership);
                    StateLink.LOGGER.info(
                        "Ownership abandoned to RECOVERY_REQUIRED uuid={} session={} fence={} reason={} marked={}",
                        ownership.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(), reason, released);
                    return released;
                } catch (Exception error) {
                    StateLink.LOGGER.warn(
                        "Ownership release failed uuid={} session={} fence={} reason={} error={}",
                        ownership.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(), reason, error);
                    return false;
                }
            });
        } catch (RuntimeException error) {
            StateLink.LOGGER.error(
                "Ownership release could not be queued uuid={} session={} fence={} reason={}",
                ownership.uuid(), shortId(ownership.ownerSession()), ownership.fencingToken(), reason, error);
        }
    }

    private void scheduleRecoveryRequired(Ownership ownership, PlayerDataRepository.RecoveryReason reason) {
        if (ownership.fencingToken() < 0) return;
        try {
            serialExecutor.submit(ownership.uuid(), () -> {
                try {
                    return repository.markRecoveryRequired(ownership, reason);
                } catch (Exception error) {
                    StateLink.LOGGER.warn(
                        "Could not record recovery reason uuid={} fence={} reason={}; unclean expiry still blocks acquire",
                        ownership.uuid(), ownership.fencingToken(), reason, error);
                    return false;
                }
            });
        } catch (RuntimeException error) {
            StateLink.LOGGER.warn(
                "Could not queue recovery mark uuid={} fence={}; unclean expiry still blocks acquire",
                ownership.uuid(), ownership.fencingToken(), error);
        }
    }

    private void loseAuthority(PlayerSessionContext context, String reason, Throwable cause) {
        if (context.state() != SyncState.READY) return;
        context.markDegraded();
        scheduleRecoveryRequired(ownership(context), PlayerDataRepository.RecoveryReason.LOCAL_AUTHORITY_LOST);
        StateLink.LOGGER.error(
            "Lease lost / fencing detected uuid={} session={} fence={} reason={} lastSuccessfulRevision={} cause={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason,
            context.lastSuccessfulRevision(), cause);
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.execute(() -> {
                ServerPlayerEntity player = currentServer.getPlayerManager().getPlayer(context.uuid());
                boolean current = sessions.isCurrent(context, player);
                sessions.remove(context);
                if (current) {
                    disconnect(player, "Player data authority was lost. Please reconnect.");
                }
            });
        }
    }

    private void failLoadingOnServerThread(
        PlayerSessionContext context,
        MinecraftServer server,
        String reason,
        Throwable error
    ) {
        if (!sessions.isCurrent(context)) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(context.uuid());
        if (player != null) {
            failClosed(context, player, reason, error, true);
        } else {
            // DISCONNECT callbackが先に消費されていなくても、stale LOADING sessionを残さない。
            context.closeWithoutSave();
            sessions.remove(context);
            scheduleReleaseIfOwned(context, reason + "; player missing");
            StateLink.LOGGER.error(
                "Fail closed without current player uuid={} session={} reason={} error={}",
                context.uuid(), shortId(context.localSessionId()), reason, error);
        }
    }

    private void failClosed(
        PlayerSessionContext context,
        ServerPlayerEntity player,
        String reason,
        Throwable error,
        boolean releaseOwnership
    ) {
        boolean currentPlayer = sessions.isCurrent(context, player);
        context.markDegraded();
        sessions.remove(context);
        StateLink.LOGGER.error(
            "Fail closed uuid={} session={} fence={} reason={} error={}",
            context.uuid(), shortId(context.localSessionId()), context.fencingToken(), reason, error);
        if (releaseOwnership) scheduleReleaseIfOwned(context, reason);
        if (currentPlayer && player != null) {
            disconnect(player, "Player data could not be synchronized safely. Please reconnect.");
        }
    }

    /** No full serialization on ticks. Scalar/item equality and mutation counters only. */
    public void observePlayerChanges(MinecraftServer server) {
        if (stopping.get() || !server.isOnThread()) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!sessions.shouldManagePlayer(player)) continue;
            PlayerSessionContext context=sessions.current(player.getUuid());
            if (context==null || !sessions.isCurrent(context,player) || !context.canQueueNormalSave()) continue;
            try { context.observeChangesOnServerThread(player,config.sync); }
            catch (RuntimeException error) {
                context.recordSnapshotCaptureFailure();
                failClosed(context,player,"dirty observation failed",error,false);
            }
        }
    }

    private void takePeriodicSnapshots(MinecraftServer server) {
        if (stopping.get()) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!sessions.shouldManagePlayer(player)) continue;
            PlayerSessionContext context = sessions.current(player.getUuid());
            if (context == null || !sessions.isCurrent(context, player) || !context.canQueueNormalSave()) continue;
            if (!withinLeaseSafetyMargin(context)) {
                loseAuthority(context, "lease safety expired before periodic snapshot", null);
                continue;
            }
            try {
                context.observeChangesOnServerThread(player,config.sync);
                if (config.save.saveOnlyWhenDirty && !context.needsCheckpoint()) continue;
                // Coalesce: while an earlier periodic checkpoint has not even started, a second
                // one would only lengthen this player's queue. The dirty epoch stays set, so the
                // next interval captures a fresh snapshot instead.
                if (!context.tryReservePeriodicSave()) {
                    periodicCoalesced.incrementAndGet();
                    continue;
                }
                queueSnapshot(context, extractLiveCheckpointOnServerThread(
                    server, player, context.pendingDisconnectItemsJson()), SaveReason.PERIODIC);
            } catch (RuntimeException error) {
                context.periodicSaveStarted();
                context.recordSnapshotCaptureFailure();
                StateLink.LOGGER.warn("Periodic snapshot failed uuid={}", player.getUuid(), error);
            }
        }
    }

    /** SERVER_STOPPINGから呼び出す。DB closeは全workerの停止後に行う。 */
    public void shutdown(MinecraftServer server) {
        synchronized (lifecycleLock) {
            if (!stopping.compareAndSet(false, true)) return;
            this.server = server;
            if (periodicTask != null) periodicTask.cancel(false);

            // 新規JOIN/DISCONNECT/RESPAWNとsession map更新を、final snapshotの受付と
            // 同じ短い境界で直列化する。DB待機はlockの外で行う。
            Set<UUID> playersSeenDuringShutdown = new HashSet<>();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!sessions.shouldManagePlayer(player)) continue;
                playersSeenDuringShutdown.add(player.getUuid());
                PlayerSessionContext context = sessions.current(player.getUuid());
                if (context == null) continue;
                // final callback may have closed/removed this context while the
                // shutdown thread was entering the list. Do not reinterpret a
                // completed or failed final flush as a new release request.
                if (!sessions.isCurrent(context)) continue;
                if (context.state() == SyncState.FLUSHING) {
                    // DISCONNECT callbackとSERVER_STOPPINGの順序によっては、
                    // final flush中のPlayerが一時的にplayer listにも残る。
                    // 既存flushを閉じたりreleaseしたりせず、下のfuture待機に任せる。
                    continue;
                }
                if (context.state() == SyncState.READY || context.state() == SyncState.QUIESCING) {
                    try {
                        finalizeDisconnectOnServerThread(context, player, server, SaveReason.SHUTDOWN_FINAL);
                    } catch (RuntimeException error) {
                        // final snapshotなしでownershipを解放しない。保存不能なownerは
                        // lease expiryまでDB上で保護し、強制的な古いデータ適用を防ぐ。
                        failClosed(context, player, "shutdown snapshot failed", error, false);
                    }
                } else {
                    context.closeWithoutSave();
                    sessions.remove(context);
                    scheduleReleaseIfOwned(context, "shutdown-without-save");
                }
            }

            // 通常のplayer listから既に外れたcontextも明示的に閉じる。READYの
            // contextにPlayer参照がない場合は安全な最終snapshotを作れないため、
            // ownershipを先にreleaseせずlease expiryへ任せる。
            for (PlayerSessionContext context : sessions.snapshot()) {
                if (!sessions.isCurrent(context)) continue;
                if (playersSeenDuringShutdown.contains(context.uuid())) continue;
                if (context.state() == SyncState.FLUSHING) continue;
                if (context.state() == SyncState.READY || context.state() == SyncState.QUIESCING) {
                    context.markDegraded();
                    sessions.remove(context);
                    StateLink.LOGGER.error(
                        "Shutdown found READY session without a current Player; ownership is left to lease expiry uuid={} session={} fence={}",
                        context.uuid(), shortId(context.localSessionId()), context.fencingToken());
                    continue;
                }
                context.closeWithoutSave();
                sessions.remove(context);
                scheduleReleaseIfOwned(context, "shutdown-orphan-session");
            }
        }

        // 既にDISCONNECT済みでcurrent mapから外れたPlayerのfinal flushも待つ。
        List<CompletableFuture<Void>> allFinalFlushes = new ArrayList<>(finalFlushes.keySet());
        awaitFutures(allFinalFlushes, config.save.shutdownFlushTimeoutMs);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException error) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        serialExecutor.shutdown();
        try {
            if (!serialExecutor.awaitTermination(config.save.shutdownFlushTimeoutMs, TimeUnit.MILLISECONDS)) {
                StateLink.LOGGER.error("DB executor did not terminate before shutdown timeout");
                serialExecutor.shutdownNow();
                if (!serialExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    StateLink.LOGGER.error(
                        "DB executor is still running; datasource close may abort an in-flight transaction");
                }
            }
        } catch (InterruptedException error) {
            serialExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        database.close();
        StateLink.LOGGER.info("StateLink shutdown completed; datasource closed after DB executor");
    }

    private static void awaitFutures(Collection<CompletableFuture<Void>> futures, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (CompletableFuture<Void> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                StateLink.LOGGER.error("Shutdown flush timeout; ownership is left to lease expiry");
                break;
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (Exception error) {
                StateLink.LOGGER.error("Shutdown final flush failed; ownership is left to lease expiry", error);
            }
        }
    }

    private Ownership ownership(PlayerSessionContext context) {
        return new Ownership(
            context.uuid(),
            config.sync.serverId,
            context.localSessionId(),
            context.fencingToken(),
            context.lastSuccessfulRevision());
    }

    private CompletePlayerData extractCompletePlayerDataOnServerThread(
        MinecraftServer server,
        ServerPlayerEntity player,
        String pendingDisconnectItemsJson
    ) {
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("CompletePlayerData snapshots require the Minecraft server thread");
        }
        return extractCompletePlayerData(player, pendingDisconnectItemsJson);
    }

    /**
     * Captures a live authority checkpoint without mutating the player. The
     * active cursor/screen inputs overlay, rather than update, the context's
     * durable pending payload so repeated periodic saves replace the DB view
     * instead of appending the same still-live item again.
     */
    private CompletePlayerData extractLiveCheckpointOnServerThread(
        MinecraftServer server,
        ServerPlayerEntity player,
        String durablePendingItemsJson
    ) {
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("Live checkpoint capture requires the Minecraft server thread");
        }
        if (!config.sync.inventory) return extractCompletePlayerData(player, null);
        List<net.minecraft.item.ItemStack> activeTransient =
            CompletePlayerDataSerializer.captureActiveTransientItems(player);
        String effectivePending = CompletePlayerDataSerializer.composeCheckpointPendingItems(
            player, durablePendingItemsJson, activeTransient);
        return extractCompletePlayerData(player, effectivePending);
    }

    private CompletePlayerData extractCompletePlayerData(
        ServerPlayerEntity player,
        String pendingDisconnectItemsJson
    ) {
        return new CompletePlayerData(
            player.getUuid(),
            player.getName().getString(),
            config.sync.inventory ? CompletePlayerDataSerializer.serializeInventory(player) : null,
            config.sync.enderchest ? CompletePlayerDataSerializer.serializeEnderChest(player) : null,
            config.sync.armor ? CompletePlayerDataSerializer.serializeArmor(player) : null,
            config.sync.offhand ? CompletePlayerDataSerializer.serializeOffhand(player) : null,
            player.getHealth(),
            player.getHungerManager().getFoodLevel(),
            player.getHungerManager().getSaturationLevel(),
            player.getHungerManager().getExhaustion(),
            player.getAir(),
            player.experienceLevel,
            (int) player.totalExperience,
            (float) player.totalExperience,
            player.experienceProgress,
            config.sync.effects ? CompletePlayerDataSerializer.serializeEffects(player) : null,
            config.sync.dimensionEnabled() ? player.getWorld().getRegistryKey().getValue().toString() : "minecraft:overworld",
            config.sync.position ? player.getX() : 0.0,
            config.sync.position ? player.getY() : 64.0,
            config.sync.position ? player.getZ() : 0.0,
            config.sync.rotationEnabled() ? player.getYaw() : 0F,
            config.sync.rotationEnabled() ? player.getPitch() : 0F,
            config.sync.gamemode ? player.interactionManager.getGameMode().getName() : "survival",
            config.sync.gamemode && player.getAbilities().flying,
            config.sync.gamemode && player.getAbilities().allowFlying,
            config.sync.gamemode && player.getAbilities().creativeMode && player.getAbilities().flying,
            config.sync.playerProfile ? player.getDisplayName().getString() : player.getName().getString(),
            config.sync.playerProfile ? CompletePlayerDataSerializer.serializeSkinData(player) : null,
            null,
            config.sync.advancements ? CompletePlayerDataSerializer.serializeAdvancements(player) : null,
            config.sync.statistics ? CompletePlayerDataSerializer.serializeStatistics(player) : null,
            config.sync.recipeBook ? CompletePlayerDataSerializer.serializeRecipeBook(player) : null,
            config.sync.inventory ? player.getInventory().selectedSlot : 0,
            0L,
            1,
            com.atsukigames.statelink.utils.ExperienceProgress.pointsIntoLevel(
                player.experienceLevel, player.experienceProgress),
            pendingDisconnectItemsJson
        );
    }

    private void supersedePreviousSession(UUID uuid, MinecraftServer server) {
        PlayerSessionContext previous = sessions.current(uuid);
        if (previous == null) return;
        if (previous.state() == SyncState.FLUSHING) return;
        ServerPlayerEntity previousPlayer = previous.playerForServerThread();
        if ((previous.state() == SyncState.READY || previous.state() == SyncState.QUIESCING)
                && previousPlayer != null) {
            try {
                finalizeDisconnectOnServerThread(
                    previous, previousPlayer, server, SaveReason.DISCONNECT_FINAL);
                return;
            } catch (RuntimeException error) {
                StateLink.LOGGER.error("Could not snapshot superseded session uuid={}", uuid, error);
                previous.markDegraded();
                sessions.remove(previous);
                // snapshotを取得できなかったsessionはreleaseして次serverへ渡さず、
                // 現在のlease/fenceをexpiryまで保持する。
                return;
            }
        }
        previous.closeWithoutSave();
        sessions.remove(previous);
        scheduleReleaseIfOwned(previous, "superseded-session");
    }

    private static void disconnect(ServerPlayerEntity player, String message) {
        if (player.networkHandler != null) player.networkHandler.disconnect(Text.of(message));
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return null;
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String shortId(UUID value) {
        if (value == null) return "none";
        String text = value.toString();
        return text.substring(0, Math.min(8, text.length()));
    }

    private static String shortId(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static final class RenewException extends RuntimeException {
        private RenewException(Throwable cause) {
            super(cause);
        }
    }

    private static final class DatabaseOperationException extends RuntimeException {
        private DatabaseOperationException(Throwable cause) {
            super(cause);
        }
    }

    private record RenewOutcome(
        boolean renewed,
        long leaseReferenceNanos,
        long scheduledAtNanos,
        long schedulerExecutionNanos,
        long workerStartNanos,
        long dbStartNanos,
        long dbEndNanos,
        int queueDepthAtSubmit,
        PerPlayerSerialExecutor.ExecutorMetrics executorMetrics,
        DatabaseManager.PoolMetrics poolMetrics
    ) {
        private static RenewOutcome notExecuted(
            long leaseReferenceNanos,
            long scheduledAtNanos,
            long schedulerExecutionNanos,
            long workerStartNanos,
            int queueDepthAtSubmit
        ) {
            return new RenewOutcome(
                false, leaseReferenceNanos, scheduledAtNanos, schedulerExecutionNanos,
                workerStartNanos, workerStartNanos, workerStartNanos, queueDepthAtSubmit,
                PerPlayerSerialExecutor.ExecutorMetrics.unavailable(),
                DatabaseManager.PoolMetrics.unavailable());
        }
    }

    private record SqlFailure(String sqlState, int vendorCode, String failureType) {
        private static SqlFailure from(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof java.sql.SQLException sqlException) {
                    return new SqlFailure(
                        sqlException.getSQLState() == null ? "none" : sqlException.getSQLState(),
                        sqlException.getErrorCode(),
                        sqlException.getClass().getSimpleName());
                }
                current = current.getCause();
            }
            return failure == null
                ? new SqlFailure("none", 0, "none")
                : new SqlFailure("none", 0, failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        MinecraftServer currentServer = server;
        if (currentServer != null) shutdown(currentServer);
    }
}
