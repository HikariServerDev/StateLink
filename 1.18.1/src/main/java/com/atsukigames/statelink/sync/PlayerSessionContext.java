package com.atsukigames.statelink.sync;

import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledFuture;

/**
 * 1回のJOINからDISCONNECTまでを表すローカルセッション。
 *
 * <p>UUIDだけをロックのキーにすると、旧JOINの非同期結果が再接続後の
 * 新しいPlayerへ適用される。そこでUUID、ランダムなlocalSessionId、単調増加
 * generationの三つをひとまとまりで扱う。</p>
 */
public final class PlayerSessionContext {
    private final UUID uuid;
    private final UUID localSessionId;
    private final long generation;
    private volatile ServerPlayerEntity player;
    private volatile SyncState state = SyncState.LOADING;
    private volatile boolean authoritativeDataLoaded;
    private volatile long fencingToken = -1L;
    private volatile long lastSuccessfulRevision;
    private volatile long lastLeaseRenewNanos;
    private volatile boolean leaseAnchorValid;
    private volatile boolean snapshotCaptureFailed;
    /** Durable cursor/crafting payload left over from an earlier disconnect. */
    private volatile String pendingDisconnectItemsJson;
    private final AtomicBoolean renewInFlight = new AtomicBoolean();
    private volatile CompletePlayerData dirtySnapshot;
    private volatile SaveReason dirtyReason;
    private final CheckpointDirtyState checkpointDirty = new CheckpointDirtyState();
    private final com.atsukigames.statelink.utils.PlayerChangeObserver changeObserver =
        new com.atsukigames.statelink.utils.PlayerChangeObserver();

    public void observeChangesOnServerThread(ServerPlayerEntity player,
            com.atsukigames.statelink.config.Configuration.SyncConfig sync) {
        if (changeObserver.observe(player, sync, pendingDisconnectItemsJson)) checkpointDirty.markChanged();
    }
    public boolean needsCheckpoint() { return checkpointDirty.isDirty(); }
    private final java.util.concurrent.atomic.AtomicBoolean periodicSaveQueued =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** At most one periodic checkpoint per player may be queued without having started. */
    public boolean tryReservePeriodicSave() { return periodicSaveQueued.compareAndSet(false, true); }
    public void periodicSaveStarted() { periodicSaveQueued.set(false); }
    public long checkpointChangeVersion() { return checkpointDirty.version(); }
    public void checkpointCommitted(long version) { checkpointDirty.committed(version); }
    private ScheduledFuture<?> joinTimeoutTask;
    private long nextSnapshotSequence;
    private long latestSnapshotSequence;
    private long dirtySnapshotSequence = -1L;
    private final long joinStartedNanos = System.nanoTime();

    public PlayerSessionContext(UUID uuid, ServerPlayerEntity player, long generation) {
        this.uuid = uuid;
        this.player = player;
        this.generation = generation;
        this.localSessionId = UUID.randomUUID();
    }

    public UUID uuid() {
        return uuid;
    }

    public UUID localSessionId() {
        return localSessionId;
    }

    public long generation() {
        return generation;
    }

    public long joinStartedNanos() {
        return joinStartedNanos;
    }

    /** MC thread上でのみ呼び出す。workerはこの参照を通じてMC stateへアクセスしてはいけない。 */
    public void updatePlayerOnServerThread(ServerPlayerEntity player) {
        this.player = player;
    }

    public ServerPlayerEntity playerForServerThread() {
        return player;
    }

    public SyncState state() {
        return state;
    }

    public boolean isAuthoritativeDataLoaded() {
        return authoritativeDataLoaded;
    }

    public long fencingToken() {
        return fencingToken;
    }

    public long lastSuccessfulRevision() {
        return lastSuccessfulRevision;
    }

    public long lastLeaseRenewNanos() {
        return lastLeaseRenewNanos;
    }

    public String pendingDisconnectItemsJson() {
        return pendingDisconnectItemsJson;
    }

    public void setPendingDisconnectItemsJson(String pendingDisconnectItemsJson) {
        this.pendingDisconnectItemsJson = pendingDisconnectItemsJson;
    }

    public synchronized void markOwnership(long fencingToken, long dataRevision) {
        // A fence alone does not prove when the database lease was extended.
        // Callers without a repository-returned monotonic anchor must fail closed.
        markOwnership(fencingToken, dataRevision, Long.MIN_VALUE);
    }

    /**
     * Repositoryがlease延長statementを開始する直前に取得した保守的な
     * monotonic anchorを保持する。callback到着時刻を起点にしてleaseを
     * 過大評価してはならない。
     */
    public synchronized void markOwnership(
        long fencingToken,
        long dataRevision,
        long leaseReferenceNanos
    ) {
        this.fencingToken = fencingToken;
        this.lastSuccessfulRevision = dataRevision;
        this.lastLeaseRenewNanos = leaseReferenceNanos;
        this.leaseAnchorValid = leaseReferenceNanos != Long.MIN_VALUE;
    }

    /**
     * The READY boundary is a conjunction of session/player identity, JOIN deadline,
     * state, and a monotonic lease safety budget. Callers must provide identity checks
     * performed against PlayerSessionManager and the current PlayerManager entry.
     */
    public synchronized boolean markReadyIfSafe(
        boolean currentSession,
        boolean currentPlayer,
        boolean joinTimedOut,
        long nowNanos,
        long leaseSafetyBudgetNanos
    ) {
        if (!currentSession || !currentPlayer || joinTimedOut || state != SyncState.LOADING
                || fencingToken < 0 || !hasLeaseSafetyAt(nowNanos, leaseSafetyBudgetNanos)) {
            return false;
        }
        authoritativeDataLoaded = true;
        state = SyncState.READY;
        cancelJoinTimeoutLocked();
        return true;
    }

    public synchronized boolean beginQuiescingIfSafe(
        long nowNanos,
        long leaseSafetyBudgetNanos,
        long expectedFence
    ) {
        if (state != SyncState.READY || !authoritativeDataLoaded
                || fencingToken != expectedFence
                || !hasLeaseSafetyAt(nowNanos, leaseSafetyBudgetNanos)) {
            return false;
        }
        state = SyncState.QUIESCING;
        return true;
    }

    public synchronized boolean beginFlushing(
        boolean cleanupCompleted,
        boolean snapshotComplete,
        long nowNanos,
        long leaseSafetyBudgetNanos,
        long expectedFence
    ) {
        if (!authoritativeDataLoaded || state != SyncState.QUIESCING
                || !cleanupCompleted || !snapshotComplete
                || fencingToken != expectedFence
                || !hasLeaseSafetyAt(nowNanos, leaseSafetyBudgetNanos)) return false;
        state = SyncState.FLUSHING;
        return true;
    }

    public synchronized void markDegraded() {
        if (state == SyncState.CLOSED) return;
        authoritativeDataLoaded = false;
        state = SyncState.DEGRADED;
        cancelJoinTimeoutLocked();
    }

    public synchronized void closeWithoutSave() {
        authoritativeDataLoaded = false;
        state = SyncState.CLOSED;
        cancelJoinTimeoutLocked();
    }

    /** JOIN deadlineをcontextのstate遷移と同じ境界で管理する。 */
    public synchronized void installJoinTimeout(ScheduledFuture<?> timeoutTask) {
        if (joinTimeoutTask != null) joinTimeoutTask.cancel(false);
        if (state == SyncState.LOADING) {
            joinTimeoutTask = timeoutTask;
        } else if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }

    private void cancelJoinTimeoutLocked() {
        if (joinTimeoutTask != null) {
            joinTimeoutTask.cancel(false);
            joinTimeoutTask = null;
        }
    }

    public boolean isClosed() {
        return state == SyncState.CLOSED;
    }

    public boolean canQueueNormalSave() {
        return authoritativeDataLoaded && state == SyncState.READY && fencingToken >= 0;
    }

    public boolean hasLeaseSafetyAt(long nowNanos, long leaseSafetyBudgetNanos) {
        long anchor = lastLeaseRenewNanos;
        if (!leaseAnchorValid) return false;
        long elapsed = nowNanos - anchor;
        return elapsed >= 0 && elapsed < leaseSafetyBudgetNanos;
    }

    public boolean hasSnapshotCaptureFailure() {
        return snapshotCaptureFailed;
    }

    /** Invalidates in-flight save acknowledgements when current state could not be snapshotted. */
    public synchronized void recordSnapshotCaptureFailure() {
        snapshotCaptureFailed = true;
        latestSnapshotSequence = ++nextSnapshotSequence;
    }

    public boolean canFlush() {
        return authoritativeDataLoaded && state == SyncState.FLUSHING && fencingToken >= 0;
    }

    /** 古いrenew callbackが新しい基準時刻を巻き戻さない。 */
    public synchronized void recordLeaseRenewed(long leaseReferenceNanos) {
        if (leaseReferenceNanos == Long.MIN_VALUE) return;
        if (!leaseAnchorValid || leaseReferenceNanos - lastLeaseRenewNanos > 0) {
            lastLeaseRenewNanos = leaseReferenceNanos;
            leaseAnchorValid = true;
        }
    }

    public boolean tryBeginLeaseRenew() {
        return renewInFlight.compareAndSet(false, true);
    }

    public void endLeaseRenew() {
        renewInFlight.set(false);
    }

    /**
     * 保存したsnapshotが、現在dirtyとして保持している同じsnapshotである場合だけ
     * dirty markerを消す。古いAの完了通知が新しいBのdirty markerを消すことを防ぐ。
     */
    public synchronized void recordSuccessfulSave(
        CompletePlayerData snapshot,
        long snapshotSequence,
        long revision,
        long leaseAnchorNanos
    ) {
        if (revision > lastSuccessfulRevision) lastSuccessfulRevision = revision;
        if (leaseAnchorNanos != Long.MIN_VALUE) recordLeaseRenewed(leaseAnchorNanos);
        if (snapshotSequence == latestSnapshotSequence
                && dirtySnapshotSequence == snapshotSequence
                && dirtySnapshot == snapshot) {
            dirtySnapshot = null;
            dirtyReason = null;
            dirtySnapshotSequence = -1L;
            snapshotCaptureFailed = false;
        }
    }

    public synchronized void recordSuccessfulSave(
        CompletePlayerData snapshot,
        long snapshotSequence,
        long revision
    ) {
        recordSuccessfulSave(
            snapshot, snapshotSequence, revision,
            com.atsukigames.statelink.database.PlayerDataRepository.NO_LEASE_ANCHOR);
    }

    /** 旧呼び出し用。新しいコードではsnapshot世代付きのAPIを使う。 */
    public synchronized void recordSuccessfulSave(CompletePlayerData snapshot, long revision) {
        long sequence = dirtySnapshot == snapshot ? dirtySnapshotSequence : latestSnapshotSequence;
        recordSuccessfulSave(snapshot, sequence, revision);
    }

    /** 互換的なrevision更新用。snapshot世代を指定できる呼び出しを優先する。 */
    public synchronized void recordSuccessfulSave(long revision) {
        if (revision > lastSuccessfulRevision) lastSuccessfulRevision = revision;
    }

    /** 新しいimmutable snapshotをdirtyとして登録し、その世代番号を返す。 */
    public synchronized long recordDirtySnapshot(CompletePlayerData snapshot, SaveReason reason) {
        long sequence = ++nextSnapshotSequence;
        latestSnapshotSequence = sequence;
        dirtySnapshot = snapshot;
        dirtyReason = reason;
        dirtySnapshotSequence = sequence;
        return sequence;
    }

    /** 古い保存の失敗通知が、より新しいsnapshotをdirtyとして上書きしない。 */
    public synchronized void recordSaveFailure(
        CompletePlayerData snapshot,
        SaveReason reason,
        long snapshotSequence
    ) {
        if (snapshotSequence != latestSnapshotSequence) return;
        dirtySnapshot = snapshot;
        dirtyReason = reason;
        dirtySnapshotSequence = snapshotSequence;
    }

    public CompletePlayerData dirtySnapshot() {
        return dirtySnapshot;
    }

    public SaveReason dirtyReason() {
        return dirtyReason;
    }
}
