package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlayerData本体と、UUIDごとのDB ownershipを扱うrepository。
 *
 * <p>書き込みは必ず {@link Ownership} を要求する。旧来のUUIDだけのUPSERTを
 * 残すと、fencedされた古いserverがDBを巻き戻せるため、ownershipなしの
 * 書き込みAPIは提供しない。</p>
 */
public final class PlayerDataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDataRepository.class);
    public static final long NO_LEASE_ANCHOR = Long.MIN_VALUE;
    private final DatabaseManager db;
    private final TransactionFaultInjector transactionFaultInjector;

    public PlayerDataRepository(DatabaseManager db) {
        this(db, (ignoredPhase, ignoredFinalFlush) -> { });
    }

    /**
     * Package-private fault hook used by deterministic integration tests. It is
     * a no-op in production and is deliberately called inside the transaction
     * so tests can prove rollback after either table has been changed.
     */
    PlayerDataRepository(DatabaseManager db, TransactionFaultInjector transactionFaultInjector) {
        this.db = db;
        this.transactionFaultInjector = transactionFaultInjector == null
            ? (ignoredPhase, ignoredFinalFlush) -> { }
            : transactionFaultInjector;
    }

    enum TransactionPhase {
        AFTER_PLAYER_DATA_WRITE,
        AFTER_OWNERSHIP_UPDATE,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    @FunctionalInterface
    interface TransactionFaultInjector {
        void at(TransactionPhase phase, boolean finalFlush) throws SQLException;
    }

    public enum AcquireStatus {
        ACQUIRED,
        BUSY,
        EXPIRED,
        RECOVERY_REQUIRED
    }

    public enum RecoveryReason {
        LEASE_EXPIRED_UNCLEAN, INVALID_OWNERSHIP, SESSION_ABANDONED,
        FINAL_SAVE_FAILED, SHUTDOWN_TIMEOUT, LOCAL_AUTHORITY_LOST, DIRTY_DISCONNECT_WITHOUT_SAVE,
        FINAL_COMMIT_ACK_UNKNOWN, SERVER_CRASH, WORLD_MUTATION_UNCERTAIN,
        DOMAIN_REENABLE_REQUIRES_RECONCILIATION, DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION
    }

    public record Ownership(
        UUID uuid,
        String ownerServer,
        UUID ownerSession,
        long fencingToken,
        long dataRevision
    ) {}

    public record AcquireResult(
        AcquireStatus status,
        Ownership ownership,
        Optional<CompletePlayerData> data,
        String currentOwnerServer,
        String currentOwnerSession,
        long leaseAnchorNanos,
        String recoveryReason
    ) {
        public static AcquireResult busy(String ownerServer, String ownerSession) {
            return new AcquireResult(AcquireStatus.BUSY, null, Optional.empty(), ownerServer, ownerSession,
                NO_LEASE_ANCHOR, null);
        }

        public static AcquireResult expired() {
            return new AcquireResult(AcquireStatus.EXPIRED, null, Optional.empty(), null, null, NO_LEASE_ANCHOR, null);
        }

        public static AcquireResult recoveryRequired(String ownerServer, String ownerSession) {
            return recoveryRequired(ownerServer, ownerSession, null);
        }

        /** {@code reason} is a RecoveryReason name: safe to show, never SQL or internal detail. */
        public static AcquireResult recoveryRequired(String ownerServer, String ownerSession, String reason) {
            return new AcquireResult(AcquireStatus.RECOVERY_REQUIRED, null, Optional.empty(),
                ownerServer, ownerSession, NO_LEASE_ANCHOR, reason);
        }

        /** True when only an explicit operator reconciliation can clear the refusal. */
        public boolean requiresOperatorReconciliation() {
            return RecoveryReason.DOMAIN_REENABLE_REQUIRES_RECONCILIATION.name().equals(recoveryReason)
                || RecoveryReason.DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION.name().equals(recoveryReason);
        }

        public static AcquireResult acquired(
            Ownership ownership,
            Optional<CompletePlayerData> data,
            long leaseAnchorNanos
        ) {
            return new AcquireResult(AcquireStatus.ACQUIRED, ownership, data, null, null, leaseAnchorNanos, null);
        }
    }

    public enum SaveStatus {
        COMMITTED,
        FENCED
    }

    public record SaveResult(SaveStatus status, long dataRevision, long leaseAnchorNanos) {
        public boolean committed() {
            return status == SaveStatus.COMMITTED;
        }
    }

    public enum NoSaveReleaseStatus { CLEAN_RELEASED, RECOVERY_REQUIRED, FENCED }

    /** Freeze/capture occurs on the game thread; this transaction only sees immutable values. */
    public NoSaveReleaseStatus releaseWithoutDataSave(CompletePlayerData frozen, Ownership owner,
        Configuration.SyncConfig sync, boolean hasActiveTransient) throws SQLException {
        if (!frozen.uuid.equals(owner.uuid())) throw new IllegalArgumentException("snapshot ownership mismatch");
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SyncRow row = lockSyncRow(connection, owner.uuid());
                SaveResult receipt = verifiedFinalReceipt(connection, frozen, owner, sync);
                if (!hasActiveTransient && receipt != null) {
                    connection.rollback();
                    return NoSaveReleaseStatus.CLEAN_RELEASED;
                }
                if (!matchesLiveOwnership(row, owner, currentDatabaseTime(connection))) {
                    connection.rollback();
                    return NoSaveReleaseStatus.FENCED;
                }
                CompletePlayerData durable = loadCompletePlayerData(connection, owner.uuid()).orElse(null);
                if (hasActiveTransient || !com.atsukigames.statelink.utils.EnabledCheckpointEquality
                        .same(frozen, durable, sync)) {
                    setRecoveryRequiredLocked(connection, owner.uuid(), RecoveryReason.DIRTY_DISCONNECT_WITHOUT_SAVE);
                    connection.commit();
                    return NoSaveReleaseStatus.RECOVERY_REQUIRED;
                }
                try (PreparedStatement update = db.prepareStatement(connection, """
                    UPDATE %s SET owner_server=NULL, owner_session=NULL, lease_until=NULL,
                        updated_at=CURRENT_TIMESTAMP(6)
                    WHERE uuid=? AND owner_server=? AND owner_session=? AND fencing_token=?
                        AND recovery_required=FALSE AND lease_until>=CURRENT_TIMESTAMP(6)
                    """.formatted(db.getCoordinationTableName()))) {
                    update.setString(1, owner.uuid().toString());
                    update.setString(2, owner.ownerServer());
                    update.setString(3, owner.ownerSession().toString());
                    update.setLong(4, owner.fencingToken());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return NoSaveReleaseStatus.FENCED;
                    }
                }
                writeFinalReceipt(connection, frozen, owner, sync, row.dataRevision);
                DomainProvenance provenance = DomainProvenance.read(row.domainProvenanceManifest,
                    row.domainProvenanceRevision, row.domainProvenanceGeneration, row.dataRevision)
                    .markConfiguredDisabledDomains(sync);
                writeDomainProvenance(connection, owner.uuid(), provenance);
                connection.commit();
                return NoSaveReleaseStatus.CLEAN_RELEASED;
            } catch (Throwable error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public record LeaseRenewalResult(boolean renewed, long leaseAnchorNanos) {}

    public enum RecoveryAcknowledgement {
        SOURCE_FENCED_AND_WORLD_REVIEWED, UNSAFE_AUTOMATIC_LAST_CHECKPOINT_ACCEPTED,
        AUTOMATIC_LAST_CHECKPOINT_RISK_ACCEPTED
    }

    private void writeFinalReceipt(Connection connection, CompletePlayerData data, Ownership ownership,
        Configuration.SyncConfig sync, long revision) throws SQLException {
        try (PreparedStatement update = db.prepareStatement(connection, "UPDATE " + db.getCoordinationTableName()
                + " SET clean_release_session=?, clean_release_fence=?, clean_release_revision=?, "
                + "clean_release_digest=?, clean_release_domains=? WHERE uuid=?")) {
            update.setString(1, ownership.ownerSession().toString());
            update.setLong(2, ownership.fencingToken());
            update.setLong(3, revision);
            update.setString(4, com.atsukigames.statelink.utils.CheckpointDigest.digest(data, sync));
            update.setString(5, com.atsukigames.statelink.utils.CheckpointDigest.domains(sync));
            update.setString(6, data.uuid.toString());
            if (update.executeUpdate() != 1) throw new SQLException("final receipt row disappeared");
        }
    }

    private void writeDomainProvenance(Connection connection, UUID uuid, DomainProvenance provenance)
        throws SQLException {
        String sql = "UPDATE " + db.getCoordinationTableName()
            + " SET domain_provenance_revision=?,domain_provenance_generation=?,domain_provenance_manifest=? WHERE uuid=?";
        try (PreparedStatement update = db.prepareStatement(connection, sql)) {
            update.setLong(1, provenance.dataRevision());
            update.setLong(2, provenance.generation());
            update.setString(3, provenance.encode());
            update.setString(4, uuid.toString());
            if (update.executeUpdate() != 1) throw new SQLException("domain provenance coordination row disappeared");
        }
    }

    private SaveResult verifiedFinalReceipt(Connection connection, CompletePlayerData data,
        Ownership ownership, Configuration.SyncConfig sync) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(connection, "SELECT clean_release_session, "
                + "clean_release_fence,clean_release_revision,clean_release_digest FROM "
                + db.getCoordinationTableName() + " WHERE uuid=?")) {
            query.setString(1, ownership.uuid().toString());
            try (ResultSet row = query.executeQuery()) {
                if (row.next() && ownership.ownerSession().toString().equals(row.getString(1))
                        && ownership.fencingToken() == row.getLong(2)
                        && com.atsukigames.statelink.utils.CheckpointDigest.digest(data, sync).equals(row.getString(4))) {
                    return new SaveResult(SaveStatus.COMMITTED, row.getLong(3), NO_LEASE_ANCHOR);
                }
            }
        }
        return null;
    }
    public enum RecoveryResolutionStatus { RESOLVED, ALREADY_RESOLVED, CONFLICT, NOT_RECOVERY_REQUIRED,
        WAITING, STALE_CHECKPOINT, ATTEMPTS_EXHAUSTED, ATTEMPT_RESERVED, DOMAIN_STILL_REQUIRES_RECONCILIATION }
    public record RecoveryResolutionResult(RecoveryResolutionStatus status, long fence, long revision) {}

    /** Explicit operator decision to adopt the currently stored DB value for one domain. */
    public RecoveryResolutionResult adoptDatabaseDomain(UUID uuid, String domain, long expectedFence,
        long expectedRevision, UUID operationId, String actor, Configuration.SyncConfig sync) throws SQLException {
        if (uuid == null || operationId == null || actor == null || actor.isBlank() || actor.length() > 64
                || !DomainProvenance.isKnownDomain(domain) || sync == null || !DomainProvenance.enabled(sync, domain)) {
            throw new IllegalArgumentException("An enabled known domain and explicit operator identity are required");
        }
        String auditMode = "domain-db:" + domain;
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SyncRow row = lockSyncRow(connection, uuid);
                try (PreparedStatement priorQuery = db.prepareStatement(connection, "SELECT uuid,resolution,recovery_mode,actor,"
                        + "previous_fence,previous_revision,resolved_fence,resolved_revision FROM " + db.getRecoveryAuditTableName()
                        + " WHERE operation_id=?")) {
                    priorQuery.setString(1, operationId.toString());
                    try (ResultSet prior = priorQuery.executeQuery()) {
                        if (prior.next()) {
                            boolean same = uuid.toString().equals(prior.getString(1))
                                && "DOMAIN_ADOPT_DB".equals(prior.getString(2))
                                && auditMode.equals(prior.getString(3))
                                && actor.equals(prior.getString(4))
                                && expectedFence == prior.getLong(5)
                                && expectedRevision == prior.getLong(6);
                            connection.rollback();
                            return new RecoveryResolutionResult(same ? RecoveryResolutionStatus.ALREADY_RESOLVED
                                : RecoveryResolutionStatus.CONFLICT, prior.getLong(7), prior.getLong(8));
                        }
                    }
                }
                if (row.fencingToken != expectedFence || row.dataRevision != expectedRevision) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT,
                        row.fencingToken, row.dataRevision);
                }
                if (!row.recoveryRequired || row.ownerServer != null || row.ownerSession != null || row.leaseUntil != null
                        || !(RecoveryReason.DOMAIN_REENABLE_REQUIRES_RECONCILIATION.name().equals(row.recoveryReason)
                            || RecoveryReason.DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION.name().equals(row.recoveryReason))) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED,
                        row.fencingToken, row.dataRevision);
                }
                if (loadCompletePlayerData(connection, uuid).isEmpty()) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT,
                        row.fencingToken, row.dataRevision);
                }
                DomainProvenance before = DomainProvenance.read(row.domainProvenanceManifest,
                    row.domainProvenanceRevision, row.domainProvenanceGeneration, row.dataRevision);
                DomainProvenance after = before.adoptDatabaseDomain(domain, row.dataRevision)
                    .markConfiguredDisabledDomains(sync);
                boolean complete = after.authorizesEnabledDomains(sync);
                RecoveryReason remainingReason = complete ? null : unresolvedDomainReason(after, sync);
                long nextFence = Math.addExact(row.fencingToken, 1L);
                String updateSql = "UPDATE " + db.getCoordinationTableName()
                    + " SET fencing_token=?,recovery_required=?,recovery_reason=?,"
                    + "recovery_required_at=CASE WHEN ? THEN NULL ELSE COALESCE(recovery_required_at,CURRENT_TIMESTAMP(6)) END,"
                    + "owner_server=NULL,owner_session=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE uuid=?";
                try (PreparedStatement update = db.prepareStatement(connection, updateSql)) {
                    update.setLong(1, nextFence);
                    update.setBoolean(2, !complete);
                    update.setString(3, remainingReason == null ? null : remainingReason.name());
                    update.setBoolean(4, complete);
                    update.setString(5, uuid.toString());
                    if (update.executeUpdate() != 1) throw new SQLException("domain reconciliation coordination row disappeared");
                }
                writeDomainProvenance(connection, uuid, after);
                insertRecoveryAudit(connection, uuid, operationId, actor, "DOMAIN_ADOPT_DB", row,
                    nextFence, row.dataRevision, false, auditMode);
                writeDomainProvenanceAudit(connection, operationId, row.domainProvenanceManifest, after.encode());
                connection.commit();
                LOGGER.warn("Explicit domain reconciliation uuid={} domain={} source=DATABASE actor={} operation={} oldRevision={} newRevision={} fence={}",
                    uuid, domain, actor, operationId, row.dataRevision, row.dataRevision, nextFence);
                return new RecoveryResolutionResult(complete ? RecoveryResolutionStatus.RESOLVED
                    : RecoveryResolutionStatus.DOMAIN_STILL_REQUIRES_RECONCILIATION, nextFence, row.dataRevision);
            } catch (Throwable error) {
                connection.rollback();
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Domain reconciliation failed", error);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static RecoveryReason unresolvedDomainReason(DomainProvenance provenance,
        Configuration.SyncConfig sync) {
        for (var entry : provenance.states().entrySet()) {
            if (DomainProvenance.enabled(sync, entry.getKey())
                    && entry.getValue() == DomainProvenance.State.DISABLED) {
                return RecoveryReason.DOMAIN_REENABLE_REQUIRES_RECONCILIATION;
            }
        }
        return RecoveryReason.DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION;
    }
    public record RecoveryStatus(
        UUID uuid, CoordinationLifecycle.State state, String ownerServer, String ownerSession,
        long fence, long revision, Timestamp leaseUntil, String reason,
        Timestamp requiredAt, Timestamp lastCheckpointAt, int automaticAttempts, Timestamp lastAutomaticAttemptAt,
        String checkpointSourceVersion, Long domainProvenanceRevision, Long domainProvenanceGeneration,
        String domainProvenanceManifest
    ) {}

    /** Diagnostics only; this never returns PlayerData and never clears recovery. */
    public Optional<RecoveryStatus> recoveryStatus(UUID uuid) throws SQLException {
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = db.prepareStatement(connection,
                        "SELECT uuid FROM " + db.getCoordinationTableName() + " WHERE uuid=?")) {
                    query.setString(1, uuid.toString());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                }
                SyncRow row = lockSyncRow(connection, uuid);
                var state = CoordinationLifecycle.classify(row.recoveryRequired, row.ownerServer,
                    row.ownerSession, row.leaseUntil, currentDatabaseTime(connection));
                connection.rollback();
                return Optional.of(new RecoveryStatus(uuid, state, row.ownerServer, row.ownerSession,
                    row.fencingToken, row.dataRevision, row.leaseUntil, row.recoveryReason,
                    row.recoveryRequiredAt, row.lastCheckpointAt, row.automaticAttempts, row.lastAutomaticAttemptAt,
                    row.checkpointSourceVersion, row.domainProvenanceRevision, row.domainProvenanceGeneration,
                    row.domainProvenanceManifest));
            } catch (Throwable error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Bounded diagnostics/engine scan; no PlayerData is applied by this operation. */
    public List<UUID> recoveryCandidates(int limit) throws SQLException {
        return recoveryCandidates(limit, null);
    }

    public List<UUID> recoveryCandidates(int limit, UUID after) throws SQLException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("recovery limit must be 1..100");
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = db.getConnection(); PreparedStatement query = db.prepareStatement(connection,
                "SELECT uuid FROM " + db.getCoordinationTableName()
                    + " WHERE (recovery_required=TRUE OR (owner_server IS NOT NULL AND lease_until<CURRENT_TIMESTAMP(6)))"
                    + (after == null ? "" : " AND uuid>?") + " ORDER BY uuid LIMIT ?")) {
            int index = 1;
            if (after != null) query.setString(index++, after.toString());
            query.setInt(index, limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ids.add(UUID.fromString(rows.getString(1)));
            }
        }
        return List.copyOf(ids);
    }

    public record RecoveryInspection(RecoveryStatus status, String username, long pendingCount) {}

    public RecoveryInspection recoveryInspection(String player) throws SQLException {
        UUID uuid;
        try { uuid = UUID.fromString(player); }
        catch (IllegalArgumentException notUuid) {
            try (Connection connection = db.getConnection(); PreparedStatement query = db.prepareStatement(connection,
                    "SELECT uuid FROM " + db.getTableName() + " WHERE username=? LIMIT 2")) {
                query.setString(1, player);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("No synchronized player");
                    uuid = UUID.fromString(row.getString(1));
                    if (row.next()) throw new IllegalArgumentException("Ambiguous player name; use UUID");
                }
            }
        }
        RecoveryStatus status = recoveryStatus(uuid).orElseThrow(() -> new IllegalArgumentException("No coordination row"));
        String username = null;
        long pendingCount = 0;
        try (Connection connection = db.getConnection(); PreparedStatement query = db.prepareStatement(connection,
                "SELECT username,pending_disconnect_items FROM " + db.getTableName() + " WHERE uuid=?")) {
            query.setString(1, uuid.toString());
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    username = row.getString(1);
                    String json = row.getString(2);
                    if (json != null) pendingCount = com.google.gson.JsonParser.parseString(json).getAsJsonArray().size();
                }
            }
        }
        return new RecoveryInspection(status, username, pendingCount);
    }

    /** Safe means a matching COMMITTED final receipt plus the actual durable payload, never lease expiry. */
    public RecoveryResolutionResult resolveSafeFinalReceipt(UUID uuid, long expectedFence, long expectedRevision,
        UUID operation, String actor) throws SQLException {
        return resolveSafeFinalReceipt(uuid, expectedFence, expectedRevision, operation, actor, Integer.MAX_VALUE);
    }

    public RecoveryResolutionResult resolveSafeFinalReceipt(UUID uuid, long expectedFence, long expectedRevision,
        UUID operation, String actor, int maxWaitSeconds) throws SQLException {
        if (uuid == null || operation == null || actor == null || actor.isBlank() || actor.length() > 64) {
            throw new IllegalArgumentException("Valid safe recovery operation and actor required");
        }
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SyncRow row = lockSyncRow(connection, uuid);
                try (PreparedStatement query = db.prepareStatement(connection, "SELECT uuid,previous_fence,previous_revision,"
                        + "resolved_fence,resolved_revision,resolution FROM " + db.getRecoveryAuditTableName() + " WHERE operation_id=?")) {
                    query.setString(1, operation.toString());
                    try (ResultSet prior = query.executeQuery()) {
                        if (prior.next()) {
                            boolean same = uuid.toString().equals(prior.getString(1)) && expectedFence == prior.getLong(2)
                                && expectedRevision == prior.getLong(3) && "SAFE_FINAL_RECEIPT".equals(prior.getString(6));
                            connection.rollback();
                            return new RecoveryResolutionResult(same ? RecoveryResolutionStatus.ALREADY_RESOLVED
                                : RecoveryResolutionStatus.CONFLICT, prior.getLong(4), prior.getLong(5));
                        }
                    }
                }
                if (row.fencingToken != expectedFence || row.dataRevision != expectedRevision) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT, row.fencingToken, row.dataRevision);
                }
                if (!row.recoveryRequired || !"FINAL_COMMIT_ACK_UNKNOWN".equals(row.recoveryReason)
                        || row.ownerServer != null || row.ownerSession != null || row.leaseUntil != null
                        || row.recoveryRequiredAt == null || currentDatabaseTime(connection).getTime()
                            - row.recoveryRequiredAt.getTime() > maxWaitSeconds * 1000L) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED, row.fencingToken, row.dataRevision);
                }
                boolean proven = false;
                try (PreparedStatement query = db.prepareStatement(connection, "SELECT clean_release_fence, "
                        + "clean_release_revision,clean_release_digest,clean_release_domains FROM "
                        + db.getCoordinationTableName() + " WHERE uuid=?")) {
                    query.setString(1, uuid.toString());
                    try (ResultSet receipt = query.executeQuery()) {
                        if (receipt.next() && receipt.getLong(1) == row.fencingToken
                                && receipt.getLong(2) == row.dataRevision && receipt.getString(4) != null) {
                            var sync = new com.google.gson.Gson().fromJson(receipt.getString(4), Configuration.SyncConfig.class);
                            var data = loadCompletePlayerData(connection, uuid).orElse(null);
                            boolean legacy = !com.google.gson.JsonParser.parseString(receipt.getString(4))
                                .getAsJsonObject().has("dimension");
                            proven = data != null && (legacy
                                ? com.atsukigames.statelink.utils.CheckpointDigest.legacyDigest(data, sync)
                                : com.atsukigames.statelink.utils.CheckpointDigest.digest(data, sync))
                                .equals(receipt.getString(3));
                        }
                    }
                }
                if (!proven) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT, row.fencingToken, row.dataRevision);
                }
                long nextFence = Math.addExact(row.fencingToken, 1);
                try (PreparedStatement update = db.prepareStatement(connection, "UPDATE " + db.getCoordinationTableName()
                        + " SET recovery_required=FALSE,recovery_reason=NULL,recovery_required_at=NULL,fencing_token=? WHERE uuid=?")) {
                    update.setLong(1, nextFence);
                    update.setString(2, uuid.toString());
                    update.executeUpdate();
                }
                insertRecoveryAudit(connection, uuid, operation, actor, "SAFE_FINAL_RECEIPT", row, nextFence, row.dataRevision, false, "safe");
                connection.commit();
                return new RecoveryResolutionResult(RecoveryResolutionStatus.RESOLVED, nextFence, row.dataRevision);
            } catch (Throwable error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
    }

    /** Unsafe delay uses fresh DB time under the row lock, not the resolver's wall clock. */
    public RecoveryResolutionResult resolveUnsafeLastCheckpoint(UUID uuid, long fence, long revision,
        UUID operation, String actor, int delaySeconds, boolean acknowledged) throws SQLException {
        if (!acknowledged || delaySeconds < 0) throw new IllegalArgumentException("unsafe recovery acknowledgement required");
        return resolveRecovery(uuid, fence, revision, operation, actor,
            RecoveryAcknowledgement.UNSAFE_AUTOMATIC_LAST_CHECKPOINT_ACCEPTED, null, null, delaySeconds);
    }

    public RecoveryResolutionResult resolveAutomaticLastCheckpoint(UUID uuid, long fence, long revision,
        UUID operation, String actor, int delaySeconds, boolean acknowledged) throws SQLException {
        return resolveAutomaticLastCheckpoint(uuid,fence,revision,operation,actor,delaySeconds,30,12,5,acknowledged);
    }

    public RecoveryResolutionResult resolveAutomaticLastCheckpoint(UUID uuid, long fence, long revision,
        UUID operation, String actor, int delaySeconds, int maxAgeSeconds, int maxAttempts, int retrySeconds,
        boolean acknowledged) throws SQLException {
        return resolveAutomaticLastCheckpoint(uuid, fence, revision, operation, actor, delaySeconds, maxAgeSeconds,
            maxAgeSeconds, maxAttempts, retrySeconds, acknowledged);
    }

    /**
     * {@code legacyMaxAgeSeconds} is used instead of {@code maxAgeSeconds} only for a checkpoint that
     * the acknowledged 2.1.11 bootstrap adopted and that no 2.2.x writer has replaced since.
     */
    public RecoveryResolutionResult resolveAutomaticLastCheckpoint(UUID uuid, long fence, long revision,
        UUID operation, String actor, int delaySeconds, int maxAgeSeconds, int legacyMaxAgeSeconds, int maxAttempts,
        int retrySeconds, boolean acknowledged) throws SQLException {
        if (!acknowledged || delaySeconds < 0) throw new IllegalArgumentException("automatic rollback/duplication acknowledgement required");
        return resolveRecovery(uuid, fence, revision, operation, actor,
            RecoveryAcknowledgement.AUTOMATIC_LAST_CHECKPOINT_RISK_ACCEPTED, null, null, delaySeconds,
            new AutomaticLimits(maxAgeSeconds,legacyMaxAgeSeconds,maxAttempts,retrySeconds));
    }

    /** Bounds repeated deferral logging per player; see {@link DeferralLog}. */
    private final DeferralLog deferralLog = new DeferralLog(60_000L);

    private record AutomaticLimits(int maxAgeSeconds,int legacyMaxAgeSeconds,int maxAttempts,int retrySeconds) {
        AutomaticLimits { if(maxAgeSeconds<=0 || legacyMaxAgeSeconds<=0 || maxAttempts<=0 || retrySeconds<=0) throw new IllegalArgumentException("positive automatic recovery limits required"); }
    }

    /**
     * True only when database metadata proves the row's durable checkpoint is the one adopted by an
     * acknowledged, completed 2.1.11 bootstrap: no 2.2.x producer version is recorded for it and it
     * is not newer than that bootstrap. Any SAVE or source resolution after the migration makes this false.
     */
    private boolean isLegacyBootstrapCheckpoint(Connection connection, SyncRow row) throws SQLException {
        if (row.checkpointSourceVersion != null || row.lastCheckpointAt == null) return false;
        try (PreparedStatement query = db.prepareStatement(connection, "SELECT 1 FROM " + db.getMigrationTableName()
                + " WHERE migration_id=? AND state='COMPLETED' AND operator_acknowledged=TRUE AND completed_at>=?")) {
            query.setString(1, Legacy211Bootstrap.MIGRATION_ID);
            query.setTimestamp(2, row.lastCheckpointAt);
            try (ResultSet found = query.executeQuery()) { return found.next(); }
        }
    }

    /**
     * Explicit, audited operator reset of the automatic recovery attempt budget, for rows whose budget
     * was spent by policy deferrals of an older release rather than by real resolution failures.
     */
    public RecoveryResolutionResult resetAutomaticRecoveryAttempts(UUID uuid, long expectedFence, long expectedRevision,
        UUID operationId, String actor) throws SQLException {
        if (uuid == null || operationId == null || actor == null || actor.isBlank() || actor.length() > 64) {
            throw new IllegalArgumentException("explicit operator identity and operation id required");
        }
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SyncRow row = lockSyncRow(connection, uuid);
                try (PreparedStatement prior = db.prepareStatement(connection, "SELECT uuid,resolution,previous_fence,"
                        + "previous_revision FROM " + db.getRecoveryAuditTableName() + " WHERE operation_id=?")) {
                    prior.setString(1, operationId.toString());
                    try (ResultSet found = prior.executeQuery()) {
                        if (found.next()) {
                            boolean same = uuid.toString().equals(found.getString(1))
                                && "RESET_ATTEMPT_BUDGET".equals(found.getString(2))
                                && expectedFence == found.getLong(3) && expectedRevision == found.getLong(4);
                            connection.rollback();
                            return new RecoveryResolutionResult(same ? RecoveryResolutionStatus.ALREADY_RESOLVED
                                : RecoveryResolutionStatus.CONFLICT, row.fencingToken, row.dataRevision);
                        }
                    }
                }
                if (row.fencingToken != expectedFence || row.dataRevision != expectedRevision) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT, row.fencingToken, row.dataRevision);
                }
                if (CoordinationLifecycle.classify(row.recoveryRequired, row.ownerServer, row.ownerSession,
                        row.leaseUntil, currentDatabaseTime(connection)) != CoordinationLifecycle.State.RECOVERY_REQUIRED) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED,
                        row.fencingToken, row.dataRevision);
                }
                try (PreparedStatement reset = db.prepareStatement(connection, "UPDATE " + db.getCoordinationTableName()
                        + " SET automatic_recovery_attempts=0,automatic_recovery_last_attempt_at=NULL WHERE uuid=?")) {
                    reset.setString(1, uuid.toString());
                    if (reset.executeUpdate() != 1) throw new SQLException("attempt budget row disappeared");
                }
                // Recovery state, owner, fence and revision are unchanged: this resolves nothing by itself.
                insertRecoveryAudit(connection, uuid, operationId, actor, "RESET_ATTEMPT_BUDGET", row,
                    row.fencingToken, row.dataRevision, false, "attempts:" + row.automaticAttempts);
                connection.commit();
                LOGGER.warn("Automatic recovery attempt budget reset uuid={} actor={} operation={} previousAttempts={}",
                    uuid, actor, operationId, row.automaticAttempts);
                return new RecoveryResolutionResult(RecoveryResolutionStatus.RESOLVED, row.fencingToken, row.dataRevision);
            } catch (Throwable error) {
                connection.rollback();
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Attempt budget reset failed", error);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Dangerous explicit operator resolution. Never called by login, retries or timers. */
    public RecoveryResolutionResult forceLastCheckpoint(
        UUID uuid, long expectedFence, long expectedRevision, UUID operationId, String actor,
        RecoveryAcknowledgement acknowledgement
    ) throws SQLException {
        return resolveRecovery(uuid, expectedFence, expectedRevision, operationId, actor,
            acknowledgement, null, null, 0);
    }

    /**
     * An operator must first fence the source and verify/reconcile its World and
     * local state. Restarting a source or reading its vanilla player file alone
     * is NOT that verification. The snapshot must already be fully serialized.
     */
    public RecoveryResolutionResult resolveRecoveryFromSource(
        CompletePlayerData verifiedSourceSnapshot, Configuration.SyncConfig sync,
        long expectedFence, long expectedRevision, UUID operationId, String actor,
        RecoveryAcknowledgement acknowledgement
    ) throws SQLException {
        if (verifiedSourceSnapshot == null || sync == null || !sync.inventory) {
            throw new IllegalArgumentException("complete verified source snapshot with inventory sync is required");
        }
        return resolveRecovery(verifiedSourceSnapshot.uuid, expectedFence, expectedRevision, operationId,
            actor, acknowledgement, verifiedSourceSnapshot, sync, 0);
    }

    private RecoveryResolutionResult resolveRecovery(
        UUID uuid, long expectedFence, long expectedRevision, UUID operationId, String actor,
        RecoveryAcknowledgement acknowledgement, CompletePlayerData source, Configuration.SyncConfig sync, int delaySeconds
    ) throws SQLException {
        return resolveRecovery(uuid,expectedFence,expectedRevision,operationId,actor,acknowledgement,source,sync,delaySeconds,null);
    }

    private RecoveryResolutionResult resolveRecovery(
        UUID uuid, long expectedFence, long expectedRevision, UUID operationId, String actor,
        RecoveryAcknowledgement acknowledgement, CompletePlayerData source, Configuration.SyncConfig sync,
        int delaySeconds, AutomaticLimits limits
    ) throws SQLException {
        var result = runRecoveryTransaction(uuid,expectedFence,expectedRevision,operationId,actor,
            acknowledgement,source,sync,delaySeconds,limits,false);
        // Close the reservation connection before re-entering: never hold two
        // pool connections or a row lock while waiting for another connection.
        if (result.status()==RecoveryResolutionStatus.ATTEMPT_RESERVED)
            return runRecoveryTransaction(uuid,expectedFence,expectedRevision,operationId,actor,
                acknowledgement,source,sync,delaySeconds,limits,true);
        return result;
    }

    private RecoveryResolutionResult runRecoveryTransaction(
        UUID uuid, long expectedFence, long expectedRevision, UUID operationId, String actor,
        RecoveryAcknowledgement acknowledgement, CompletePlayerData source, Configuration.SyncConfig sync,
        int delaySeconds, AutomaticLimits limits, boolean attemptReserved
    ) throws SQLException {
        if (uuid == null || operationId == null || expectedFence < 0 || expectedRevision < 0
                || actor == null || actor.isBlank() || actor.length() > 64
                || acknowledgement == null || (source != null
                    && acknowledgement != RecoveryAcknowledgement.SOURCE_FENCED_AND_WORLD_REVIEWED)) {
            throw new IllegalArgumentException("explicit fenced-source recovery acknowledgement and valid expectations required");
        }
        boolean unsafeAutomatic = acknowledgement == RecoveryAcknowledgement.UNSAFE_AUTOMATIC_LAST_CHECKPOINT_ACCEPTED
            || acknowledgement == RecoveryAcknowledgement.AUTOMATIC_LAST_CHECKPOINT_RISK_ACCEPTED;
        String recoveryMode = acknowledgement == RecoveryAcknowledgement.AUTOMATIC_LAST_CHECKPOINT_RISK_ACCEPTED
            ? "automatic" : unsafeAutomatic ? "last_checkpoint" : "manual";
        String resolution = unsafeAutomatic ? "UNSAFE_AUTO_LAST_CHECKPOINT"
            : source == null ? "FORCE_LAST_CHECKPOINT" : "VERIFIED_SOURCE_SNAPSHOT";
        // The background scan and login retries call this every few seconds for a row that policy keeps
        // deferring. Announce an automatic resolution only when it is really attempted.
        if (limits == null || attemptReserved)
        LOGGER.warn("EXPLICIT RECOVERY uuid={} operation={} actor={} resolution={} expectedFence={} "
            + "expectedRevision={}: this may duplicate or discard externalized state unless the source "
            + "is fenced and World state has been reconciled", uuid, operationId, actor, resolution,
            expectedFence, expectedRevision);
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Global ordering: coordination row -> PlayerData (if used) -> audit.
                SyncRow row = lockSyncRow(connection, uuid);
                String auditLookup = "SELECT uuid, resolution, previous_fence, previous_revision, "
                    + "resolved_fence, resolved_revision, recovery_mode FROM " + db.getRecoveryAuditTableName()
                    + " WHERE operation_id=?";
                try (PreparedStatement query = db.prepareStatement(connection, auditLookup)) {
                    query.setString(1, operationId.toString());
                    try (ResultSet prior = query.executeQuery()) {
                        if (prior.next()) {
                            boolean same = uuid.toString().equals(prior.getString("uuid"))
                                && resolution.equals(prior.getString("resolution"))
                                && recoveryMode.equals(prior.getString("recovery_mode")==null ? "manual" : prior.getString("recovery_mode"))
                                && expectedFence == prior.getLong("previous_fence")
                                && expectedRevision == prior.getLong("previous_revision");
                            var result = new RecoveryResolutionResult(same
                                ? RecoveryResolutionStatus.ALREADY_RESOLVED : RecoveryResolutionStatus.CONFLICT,
                                prior.getLong("resolved_fence"), prior.getLong("resolved_revision"));
                            connection.rollback();
                            return result;
                        }
                    }
                }
                if (row.fencingToken != expectedFence || row.dataRevision != expectedRevision) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT,
                        row.fencingToken, row.dataRevision);
                }
                Timestamp dbNow = currentDatabaseTime(connection);
                boolean unowned = row.ownerServer == null && row.ownerSession == null && row.leaseUntil == null;
                boolean expiredOwner = row.ownerServer != null && row.ownerSession != null && row.leaseUntil != null
                    && row.leaseUntil.before(dbNow);
                if (unsafeAutomatic && !row.recoveryRequired && expiredOwner) {
                    // First persist UNCLEAN uncertainty; resolution is a later transaction after its delay.
                    setRecoveryRequiredLocked(connection, uuid, RecoveryReason.LEASE_EXPIRED_UNCLEAN);
                    connection.commit();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT,row.fencingToken,row.dataRevision);
                }
                Timestamp since = row.recoveryRequiredAt == null ? row.leaseUntil : row.recoveryRequiredAt;
                if (row.leaseUntil != null && (since == null || row.leaseUntil.after(since))) since = row.leaseUntil;
                if (unsafeAutomatic && (!row.recoveryRequired || (!unowned && !expiredOwner)
                        || !RecoveryPolicy.allowsAutomatic(row.recoveryReason)
                        || since == null || dbNow.getTime() - since.getTime() < delaySeconds * 1000L
                        || row.lastCheckpointAt == null || row.dataRevision <= 0
                        || loadCompletePlayerData(connection, uuid).isEmpty())) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.CONFLICT, row.fencingToken, row.dataRevision);
                }
                if (limits != null) {
                    // Eligibility first, attempt accounting last: a policy deferral (stale checkpoint,
                    // retry interval, delay, live owner) must never spend the bounded attempt budget.
                    boolean legacyCheckpoint = isLegacyBootstrapCheckpoint(connection, row);
                    int maxAgeSeconds = legacyCheckpoint ? limits.legacyMaxAgeSeconds : limits.maxAgeSeconds;
                    long age=dbNow.getTime()-row.lastCheckpointAt.getTime();
                    if (age<0 || age>maxAgeSeconds*1000L) {
                        connection.rollback();
                        long deferrals = deferralLog.permit(uuid, "STALE:" + row.fencingToken + ":" + row.dataRevision + ":" + maxAgeSeconds);
                        if (deferrals > 0) LOGGER.warn("Automatic recovery deferred: checkpoint too old uuid={} ageMs={} maxAgeSeconds={} policy={} deferrals={}; RECOVERY_REQUIRED retained, attempt budget unchanged",
                            uuid,age,maxAgeSeconds,legacyCheckpoint ? "legacy-2.1.11-backlog" : "normal",deferrals);
                        return new RecoveryResolutionResult(RecoveryResolutionStatus.STALE_CHECKPOINT,row.fencingToken,row.dataRevision);
                    }
                    if (!attemptReserved && row.automaticAttempts >= limits.maxAttempts) {
                        connection.rollback();
                        return new RecoveryResolutionResult(RecoveryResolutionStatus.ATTEMPTS_EXHAUSTED,row.fencingToken,row.dataRevision);
                    }
                    if (!attemptReserved && row.lastAutomaticAttemptAt != null && dbNow.getTime()-row.lastAutomaticAttemptAt.getTime()<limits.retrySeconds*1000L) {
                        connection.rollback();
                        return new RecoveryResolutionResult(RecoveryResolutionStatus.WAITING,row.fencingToken,row.dataRevision);
                    }
                    if (!attemptReserved) {
                      try (PreparedStatement attempt=db.prepareStatement(connection,"UPDATE "+db.getCoordinationTableName()
                            +" SET automatic_recovery_attempts=automatic_recovery_attempts+1,automatic_recovery_last_attempt_at=? WHERE uuid=?")) {
                        attempt.setTimestamp(1,dbNow); attempt.setString(2,uuid.toString()); attempt.executeUpdate();
                      }
                      connection.commit();
                      return new RecoveryResolutionResult(RecoveryResolutionStatus.ATTEMPT_RESERVED,row.fencingToken,row.dataRevision);
                    }
                }
                if (CoordinationLifecycle.classify(row.recoveryRequired, row.ownerServer, row.ownerSession,
                        row.leaseUntil, dbNow) != CoordinationLifecycle.State.RECOVERY_REQUIRED) {
                    connection.rollback();
                    return new RecoveryResolutionResult(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED,
                        row.fencingToken, row.dataRevision);
                }
                long nextFence = Math.addExact(row.fencingToken, 1);
                long nextRevision = source == null ? row.dataRevision : Math.addExact(row.dataRevision, 1);
                if (source != null) upsertPlayerData(connection, source, sync);
                String update = "UPDATE " + db.getCoordinationTableName()
                    + " SET owner_server=NULL, owner_session=NULL, lease_until=NULL, fencing_token=?, "
                    + "data_revision=?, recovery_required=FALSE, recovery_reason=NULL, recovery_required_at=NULL,"
                    + "automatic_recovery_attempts=0,automatic_recovery_last_attempt_at=NULL"
                    + (source == null ? "" : ", last_checkpoint_at=CURRENT_TIMESTAMP(6)") + " WHERE uuid=?";
                try (PreparedStatement statement = db.prepareStatement(connection, update)) {
                    statement.setLong(1, nextFence);
                    statement.setLong(2, nextRevision);
                    statement.setString(3, uuid.toString());
                    if (statement.executeUpdate() != 1) throw new SQLException("recovery row disappeared");
                }
                DomainProvenance resolvedProvenance = null;
                if (source != null) {
                    DomainProvenance prior = DomainProvenance.read(row.domainProvenanceManifest,
                        row.domainProvenanceRevision, row.domainProvenanceGeneration, row.dataRevision);
                    resolvedProvenance = prior.atRevision(sync, nextRevision);
                    writeDomainProvenance(connection, uuid, resolvedProvenance);
                }
                insertRecoveryAudit(connection, uuid, operationId, actor, resolution, row, nextFence, nextRevision, unsafeAutomatic, recoveryMode);
                if (resolvedProvenance != null) writeDomainProvenanceAudit(connection, operationId,
                    row.domainProvenanceManifest, resolvedProvenance.encode());
                connection.commit();
                return new RecoveryResolutionResult(RecoveryResolutionStatus.RESOLVED, nextFence, nextRevision);
            } catch (Throwable error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void insertRecoveryAudit(Connection connection, UUID uuid, UUID operation, String actor,
        String resolution, SyncRow row, long nextFence, long nextRevision, boolean unsafe, String mode) throws SQLException {
        String audit = "INSERT INTO " + db.getRecoveryAuditTableName()
            + " (operation_id,uuid,actor,resolution,recovery_reason,previous_owner_server,previous_owner_session,"
            + "previous_fence,previous_revision,resolved_fence,resolved_revision,unsafe_acknowledged,recovery_mode,checkpoint_at,source_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = db.prepareStatement(connection, audit)) {
            statement.setString(1, operation.toString()); statement.setString(2, uuid.toString());
            statement.setString(3, actor); statement.setString(4, resolution);
            statement.setString(5, row.recoveryReason == null ? "LEASE_EXPIRED_UNCLEAN" : row.recoveryReason);
            statement.setString(6, row.ownerServer); statement.setString(7, row.ownerSession);
            statement.setLong(8, row.fencingToken); statement.setLong(9, row.dataRevision);
            statement.setLong(10, nextFence); statement.setLong(11, nextRevision);
            statement.setBoolean(12, unsafe); statement.setString(13,mode); statement.setTimestamp(14,row.lastCheckpointAt);
            statement.setString(15,row.checkpointSourceVersion==null ? "2.1.11-legacy" : row.checkpointSourceVersion); statement.executeUpdate();
        }
    }

    private void writeDomainProvenanceAudit(Connection connection, UUID operation, String before, String after)
        throws SQLException {
        try (PreparedStatement update = db.prepareStatement(connection, "UPDATE " + db.getRecoveryAuditTableName()
                + " SET domain_provenance_before=?,domain_provenance_after=? WHERE operation_id=?")) {
            update.setString(1, before);
            update.setString(2, after);
            update.setString(3, operation.toString());
            if (update.executeUpdate() != 1) throw new SQLException("domain provenance audit row disappeared");
        }
    }

    private record ColumnValue(String name, Binder binder) {}

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement, int index) throws SQLException;
    }

    /**
     * coordination rowをロックし、ownershipを取得したtransaction内でPlayerDataを読む。
     * BUSY時はrollbackして直ちに返るため、row lockをbackoff中に保持しない。
     */
    public AcquireResult acquireOwnershipAndLoad(
        UUID uuid,
        String serverId,
        UUID localSessionId,
        long leaseDurationMs
    ) throws SQLException {
        return acquireOwnershipAndLoad(uuid, serverId, localSessionId, leaseDurationMs, new Configuration.SyncConfig());
    }
    public AcquireResult acquireOwnershipAndLoad(
        UUID uuid, String serverId, UUID localSessionId, long leaseDurationMs, Configuration.SyncConfig enabledDomains
    ) throws SQLException {
        long leaseMicros = leaseMicros(leaseDurationMs);
        String stateTable = db.getCoordinationTableName();
        DatabaseOperationTrace trace = db.beginOperation("ACQUIRE_LOAD", uuid, localSessionId, -1L, -1L);
        String outcome = "FAILURE";
        Throwable failure = null;

        try (Connection connection = beginConnection(trace)) {
            try {
                trace.start("transaction");
                connection.setAutoCommit(false);
                String ensureRowSql = "INSERT INTO " + stateTable
                    + " (uuid) VALUES (?) ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)";
                try (PreparedStatement statement = db.prepareStatement(connection, ensureRowSql)) {
                    statement.setString(1, uuid.toString());
                    statement.executeUpdate();
                }

                trace.start("selectForUpdate");
                SyncRow row = lockSyncRow(connection, uuid);
                trace.end("selectForUpdate");
                Timestamp dbNow = currentDatabaseTime(connection);
                boolean leaseLive = row.leaseUntil != null && !row.leaseUntil.before(dbNow);
                boolean sameSession = serverId.equals(row.ownerServer)
                    && localSessionId.toString().equals(row.ownerSession);

                if (row.fencingToken < 0 || row.dataRevision < 0) {
                    throw new SQLException("coordination counter exceeds Java long range");
                }

                CoordinationLifecycle.State state = CoordinationLifecycle.classify(
                    row.recoveryRequired, row.ownerServer, row.ownerSession, row.leaseUntil, dbNow);
                if (state == CoordinationLifecycle.State.RECOVERY_REQUIRED) {
                    if (!row.recoveryRequired) {
                        setRecoveryRequiredLocked(connection, uuid,
                            row.ownerServer != null && row.ownerSession != null && row.leaseUntil != null
                                ? RecoveryReason.LEASE_EXPIRED_UNCLEAN : RecoveryReason.INVALID_OWNERSHIP);
                    }
                    connection.commit();
                    trace.end("transaction");
                    outcome = "RECOVERY_REQUIRED";
                    LOGGER.warn("Unclean ownership requires explicit recovery uuid={} previousServer={} "
                        + "previousSession={} fence={} revision={}", uuid, row.ownerServer,
                        row.ownerSession, row.fencingToken, row.dataRevision);
                    // No PlayerData read, no new ownership, no fence increment.
                    return AcquireResult.recoveryRequired(row.ownerServer, row.ownerSession,
                        row.recoveryRequired ? row.recoveryReason
                            : row.ownerServer != null && row.ownerSession != null && row.leaseUntil != null
                                ? RecoveryReason.LEASE_EXPIRED_UNCLEAN.name() : RecoveryReason.INVALID_OWNERSHIP.name());
                }

                if (row.ownerSession != null && leaseLive && !sameSession) {
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "BUSY";
                    return AcquireResult.busy(row.ownerServer, row.ownerSession);
                }

                RecoveryReason provenanceProblem = prepareDomainProvenanceForAcquire(connection, uuid, row,
                    enabledDomains == null ? new Configuration.SyncConfig() : enabledDomains);
                if (provenanceProblem != null) {
                    setRecoveryRequiredLocked(connection, uuid, provenanceProblem);
                    connection.commit();
                    trace.end("transaction");
                    outcome = "DOMAIN_PROVENANCE_REQUIRED";
                    LOGGER.warn("PlayerData domain provenance is not authoritative; login is quarantined uuid={} reason={} revision={} provenanceRevision={} fence={}",
                        uuid, provenanceProblem, row.dataRevision, row.domainProvenanceRevision,
                        row.fencingToken);
                    return AcquireResult.recoveryRequired(row.ownerServer, row.ownerSession, provenanceProblem.name());
                }

                if (sameSession && leaseLive) {
                    long leaseAnchorNanos = extendLeaseLocked(
                        connection, uuid, serverId, localSessionId, row.fencingToken,
                        leaseDurationMs);
                    if (leaseAnchorNanos == NO_LEASE_ANCHOR) {
                        connection.rollback();
                        trace.end("transaction");
                        outcome = "EXPIRED";
                        return AcquireResult.expired();
                    }
                    trace.start("playerDataRead");
                    Optional<CompletePlayerData> data = loadCompletePlayerData(connection, uuid);
                    trace.end("playerDataRead");
                    trace.start("selectForUpdate");
                    if (!isOwnershipLive(
                            connection, uuid, serverId, localSessionId, row.fencingToken,
                            currentDatabaseTime(connection))) {
                        trace.end("selectForUpdate");
                        connection.rollback();
                        trace.end("transaction");
                        outcome = "EXPIRED";
                        return AcquireResult.expired();
                    }
                    trace.end("selectForUpdate");
                    trace.start("commit");
                    connection.commit();
                    trace.end("commit");
                    trace.end("transaction");
                    Ownership ownership = new Ownership(
                        uuid, serverId, localSessionId, row.fencingToken, row.dataRevision);
                    outcome = "ACQUIRED";
                    return AcquireResult.acquired(ownership, data, leaseAnchorNanos);
                }

                if (row.fencingToken < 0) throw new SQLException("fencing token exceeds Java long range");
                long nextFence = Math.addExact(row.fencingToken, 1L);
                long leaseAnchorNanos = System.nanoTime();
                String acquireSql = """
                    UPDATE %s
                    SET owner_server = ?, owner_session = ?, fencing_token = ?,
                        lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE uuid = ?
                    """.formatted(stateTable);
                try (PreparedStatement statement = db.prepareStatement(connection, acquireSql)) {
                    statement.setString(1, serverId);
                    statement.setString(2, localSessionId.toString());
                    statement.setLong(3, nextFence);
                    statement.setLong(4, leaseMicros);
                    statement.setString(5, uuid.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        throw new SQLException("ownership row disappeared during acquisition");
                    }
                }

                // PlayerDataの読み取りはownership取得と同じtransaction内で行う。
                trace.start("playerDataRead");
                Optional<CompletePlayerData> data = loadCompletePlayerData(connection, uuid);
                trace.end("playerDataRead");
                // 読み取りが長時間かかった場合、取得時に設定したleaseがcommit前に
                // 期限切れになり得る。DB側のCURRENT_TIMESTAMP(6)で終端を再検証し、
                // 期限切れのownershipを呼び出し側へ成功として返さない。
                trace.start("selectForUpdate");
                if (!isOwnershipLive(
                        connection, uuid, serverId, localSessionId, nextFence,
                        currentDatabaseTime(connection))) {
                    trace.end("selectForUpdate");
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "EXPIRED";
                    return AcquireResult.expired();
                }
                trace.end("selectForUpdate");
                trace.start("commit");
                connection.commit();
                trace.end("commit");
                trace.end("transaction");
                Ownership ownership = new Ownership(uuid, serverId, localSessionId, nextFence, row.dataRevision);
                outcome = "ACQUIRED";
                return AcquireResult.acquired(ownership, data, leaseAnchorNanos);
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                } finally {
                    trace.end("transaction");
                }
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Failed to acquire player ownership", error);
            }
        } catch (Throwable error) {
            failure = error;
            if (error instanceof SQLException sqlException) throw sqlException;
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("Failed to acquire player ownership", error);
        } finally {
            trace.finish(outcome, failure);
        }
    }

    /**
     * Validate per-domain data provenance independently of ownership fencing, and
     * durably record a disable transition before granting the next session access.
     * A fence-only recovery therefore cannot turn stale/missing domain history into
     * permission to load an old PlayerData value.
     */
    private RecoveryReason prepareDomainProvenanceForAcquire(Connection connection, UUID uuid, SyncRow row,
        Configuration.SyncConfig sync) throws SQLException {
        boolean dataExists;
        try (PreparedStatement query = db.prepareStatement(connection,
                "SELECT 1 FROM " + db.getTableName() + " WHERE uuid=?")) {
            query.setString(1, uuid.toString());
            try (ResultSet result = query.executeQuery()) { dataExists = result.next(); }
        }
        if (!dataExists && row.dataRevision == 0) {
            DomainProvenance initial = DomainProvenance.forConfiguration(sync, 0,
                row.domainProvenanceGeneration == null ? 1 : Math.addExact(row.domainProvenanceGeneration, 1));
            if (row.domainProvenanceManifest == null || row.domainProvenanceRevision == null
                    || row.domainProvenanceGeneration == null) writeDomainProvenance(connection, uuid, initial);
            return null;
        }
        if (!dataExists) return RecoveryReason.DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION;

        DomainProvenance provenance = DomainProvenance.read(row.domainProvenanceManifest,
            row.domainProvenanceRevision, row.domainProvenanceGeneration, row.dataRevision);
        if (!provenance.authorizesEnabledDomains(sync)) {
            for (var entry : provenance.states().entrySet()) {
                if (DomainProvenance.enabled(sync, entry.getKey())
                        && entry.getValue() == DomainProvenance.State.DISABLED) {
                    return RecoveryReason.DOMAIN_REENABLE_REQUIRES_RECONCILIATION;
                }
            }
            return RecoveryReason.DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION;
        }
        DomainProvenance updated = provenance.markConfiguredDisabledDomains(sync);
        if (updated.generation() != provenance.generation()) writeDomainProvenance(connection, uuid, updated);
        return null;
    }

    /** Compatibility wrapper for callers that only need the fencing result. */
    public boolean renewLease(Ownership ownership, long leaseDurationMs) throws SQLException {
        return renewLeaseWithResult(ownership, leaseDurationMs).renewed();
    }

    /**
     * Renews only after obtaining the coordination-row lock and reading fresh DB time
     * in a separate statement. CURRENT_TIMESTAMP in the locking SELECT can be frozen
     * at statement start while that statement waits for the lock.
     */
    public LeaseRenewalResult renewLeaseWithResult(Ownership ownership, long leaseDurationMs) throws SQLException {
        SQLException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return renewLeaseOnce(ownership, leaseDurationMs);
            } catch (SQLException error) {
                lastError = error;
                if (!isRetryableTransactionError(error) || attempt == 2) throw error;
                LOGGER.warn("Retrying whole lease renewal transaction uuid={} attempt={} error={}",
                    ownership.uuid(), attempt + 1, error.toString());
            }
        }
        throw lastError == null ? new SQLException("lease renewal retry failed") : lastError;
    }

    private LeaseRenewalResult renewLeaseOnce(Ownership ownership, long leaseDurationMs) throws SQLException {
        long leaseMicros = leaseMicros(leaseDurationMs);
        String stateTable = db.getCoordinationTableName();
        DatabaseOperationTrace trace = db.beginOperation(
            "RENEW_LEASE", ownership.uuid(), ownership.ownerSession(),
            ownership.fencingToken(), ownership.dataRevision());
        String outcome = "FAILURE";
        Throwable failure = null;
        try (Connection connection = beginConnection(trace)) {
            try {
                trace.start("transaction");
                connection.setAutoCommit(false);
                trace.start("selectForUpdate");
                SyncRow row = lockSyncRow(connection, ownership.uuid());
                trace.end("selectForUpdate");
                Timestamp dbNow = currentDatabaseTime(connection);
                if (!matchesLiveOwnership(row, ownership, dbNow)) {
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "FENCED";
                    return new LeaseRenewalResult(false, NO_LEASE_ANCHOR);
                }

                long leaseAnchorNanos = extendLeaseLocked(
                    connection, ownership.uuid(), ownership.ownerServer(), ownership.ownerSession(),
                    ownership.fencingToken(), leaseDurationMs);
                if (leaseAnchorNanos == NO_LEASE_ANCHOR) {
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "FENCED";
                    return new LeaseRenewalResult(false, NO_LEASE_ANCHOR);
                }
                trace.start("commit");
                connection.commit();
                trace.end("commit");
                trace.end("transaction");
                outcome = "RENEWED";
                return new LeaseRenewalResult(true, leaseAnchorNanos);
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                } finally {
                    trace.end("transaction");
                }
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Failed to renew player ownership", error);
            }
        } catch (Throwable error) {
            failure = error;
            if (error instanceof SQLException sqlException) throw sqlException;
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("Failed to renew player ownership", error);
        } finally {
            trace.finish(outcome, failure);
        }
    }

    /**
     * 通常saveまたはfinal flushを一つのtransactionで実行する。
     * finalFlush=trueの場合、PlayerData更新とownership解放が同一commitになる。
     */
    public SaveResult saveCompletePlayerData(
        CompletePlayerData data,
        Ownership ownership,
        Configuration.SyncConfig sync,
        long leaseDurationMs,
        boolean finalFlush
    ) throws SQLException {
        SQLException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return saveCompletePlayerDataOnce(data, ownership, sync, leaseDurationMs, finalFlush);
            } catch (SQLException error) {
                lastError = error;
                if (!isRetryableTransactionError(error) || attempt == 2) throw error;
                LOGGER.warn(
                    "Retrying whole player save transaction uuid={} attempt={} error={}",
                    data.uuid, attempt + 1, error.toString());
            }
        }
        throw lastError == null ? new SQLException("save transaction retry failed") : lastError;
    }

    private SaveResult saveCompletePlayerDataOnce(
        CompletePlayerData data,
        Ownership ownership,
        Configuration.SyncConfig sync,
        long leaseDurationMs,
        boolean finalFlush
    ) throws SQLException {
        if (!data.uuid.equals(ownership.uuid())) {
            throw new IllegalArgumentException("snapshot UUID and ownership UUID differ");
        }
        if (sync == null) sync = new Configuration.SyncConfig();

        String stateTable = db.getCoordinationTableName();
        DatabaseOperationTrace trace = db.beginOperation(
            finalFlush ? "SAVE_FINAL" : "SAVE",
            data.uuid,
            ownership.ownerSession(),
            ownership.fencingToken(),
            ownership.dataRevision());
        String outcome = "FAILURE";
        Throwable failure = null;
        try (Connection connection = beginConnection(trace)) {
            try {
                trace.start("transaction");
                connection.setAutoCommit(false);
                trace.start("selectForUpdate");
                SyncRow row = lockSyncRow(connection, ownership.uuid());
                trace.end("selectForUpdate");
                Timestamp dbNow = currentDatabaseTime(connection);
                if (!matchesLiveOwnership(row, ownership, dbNow)) {
                    if (finalFlush) {
                        SaveResult receipt = verifiedFinalReceipt(connection, data, ownership, sync);
                        if (receipt != null) {
                            connection.rollback();
                            outcome = "FINAL_COMMIT_PROVEN";
                            LOGGER.info("SAFE final ACK recovery uuid={} session={} fence={} revision={} proof=committed_receipt",
                                ownership.uuid(), ownership.ownerSession(), ownership.fencingToken(), receipt.dataRevision());
                            return receipt;
                        }
                    }
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "FENCED";
                    return new SaveResult(SaveStatus.FENCED, row.dataRevision, NO_LEASE_ANCHOR);
                }

                // commit後にrevision計算で例外を出すと、DBだけcommit済みなのに
                // callerが失敗扱いする。範囲検証はPlayerData更新より前に済ませる。
                if (row.dataRevision < 0) throw new SQLException("data revision exceeds Java long range");
                long nextRevision = Math.addExact(row.dataRevision, 1L);
                trace.start("playerDataWrite");
                upsertPlayerData(connection, data, sync);
                trace.end("playerDataWrite");
                transactionFaultInjector.at(TransactionPhase.AFTER_PLAYER_DATA_WRITE, finalFlush);

                String stateUpdate = finalFlush ? """
                    UPDATE %s
                    SET data_revision = data_revision + 1,
                        owner_server = NULL, owner_session = NULL, lease_until = NULL,
                        last_checkpoint_at = CURRENT_TIMESTAMP(6),
                        checkpoint_source_version = ?,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE uuid = ? AND owner_server = ? AND owner_session = ?
                      AND fencing_token = ? AND lease_until >= CURRENT_TIMESTAMP(6)
                      AND recovery_required = FALSE
                    """.formatted(stateTable) : """
                    UPDATE %s
                    SET data_revision = data_revision + 1,
                        lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        last_checkpoint_at = CURRENT_TIMESTAMP(6),
                        checkpoint_source_version = ?,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE uuid = ? AND owner_server = ? AND owner_session = ?
                      AND fencing_token = ? AND lease_until >= CURRENT_TIMESTAMP(6)
                      AND recovery_required = FALSE
                    """.formatted(stateTable);

                int updated;
                long leaseAnchorNanos = finalFlush ? NO_LEASE_ANCHOR : System.nanoTime();
                try (PreparedStatement statement = db.prepareStatement(connection, stateUpdate)) {
                    int index = 1;
                    if (!finalFlush) statement.setLong(index++, leaseMicros(leaseDurationMs));
                    statement.setString(index++, com.atsukigames.statelink.StateLink.VERSION);
                    statement.setString(index++, ownership.uuid().toString());
                    statement.setString(index++, ownership.ownerServer());
                    statement.setString(index++, ownership.ownerSession().toString());
                    statement.setLong(index, ownership.fencingToken());
                    trace.start("ownershipUpdate");
                    updated = statement.executeUpdate();
                    trace.end("ownershipUpdate");
                }
                if (updated != 1) {
                    connection.rollback();
                    trace.end("transaction");
                    outcome = "FENCED";
                    return new SaveResult(SaveStatus.FENCED, row.dataRevision, NO_LEASE_ANCHOR);
                }

                transactionFaultInjector.at(TransactionPhase.AFTER_OWNERSHIP_UPDATE, finalFlush);
                writeDomainProvenance(connection, data.uuid, DomainProvenance.forConfiguration(sync,
                    nextRevision, Math.addExact(row.domainProvenanceGeneration == null
                        ? 0L : row.domainProvenanceGeneration, 1L)));
                if (finalFlush) writeFinalReceipt(connection, data, ownership, sync, nextRevision);
                transactionFaultInjector.at(TransactionPhase.BEFORE_COMMIT, finalFlush);
                trace.start("commit");
                connection.commit();
                trace.end("commit");
                transactionFaultInjector.at(TransactionPhase.AFTER_COMMIT, finalFlush);
                trace.end("transaction");
                outcome = "COMMITTED";
                return new SaveResult(SaveStatus.COMMITTED, nextRevision, leaseAnchorNanos);
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                } finally {
                    trace.end("transaction");
                }
                if (error instanceof SQLException sqlException) throw sqlException;
                if (error instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Failed to save player data", error);
            }
        } catch (Throwable error) {
            failure = error;
            if (error instanceof SQLException sqlException) throw sqlException;
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("Failed to save player data", error);
        } finally {
            trace.finish(outcome, failure);
        }
    }

    /**
     * Bare abandonment is unclean: it must NEVER make the row automatically free.
     * Clean release exists only in saveCompletePlayerData(..., finalFlush=true).
     */
    public boolean releaseOwnership(Ownership ownership) throws SQLException {
        return markRecoveryRequired(ownership, RecoveryReason.SESSION_ABANDONED);
    }

    public boolean markRecoveryRequired(Ownership ownership, RecoveryReason reason) throws SQLException {
        String sql = """
            UPDATE %s
            SET recovery_required = TRUE,
                recovery_reason = COALESCE(recovery_reason, ?),
                recovery_required_at = COALESCE(recovery_required_at, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE uuid = ? AND owner_server = ? AND owner_session = ? AND fencing_token = ?
            """.formatted(db.getCoordinationTableName());
        DatabaseOperationTrace trace = db.beginOperation(
            "RELEASE", ownership.uuid(), ownership.ownerSession(),
            ownership.fencingToken(), ownership.dataRevision());
        String outcome = "FAILURE";
        Throwable failure = null;
        try (Connection connection = beginConnection(trace);
             PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setString(1, reason.name());
            statement.setString(2, ownership.uuid().toString());
            statement.setString(3, ownership.ownerServer());
            statement.setString(4, ownership.ownerSession().toString());
            statement.setLong(5, ownership.fencingToken());
            trace.start("ownershipUpdate");
            boolean released = statement.executeUpdate() == 1;
            trace.end("ownershipUpdate");
            outcome = released ? "RECOVERY_REQUIRED" : "FENCED";
            return released;
        } catch (Throwable error) {
            failure = error;
            if (error instanceof SQLException sqlException) throw sqlException;
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("Failed to release player ownership", error);
        } finally {
            trace.finish(outcome, failure);
        }
    }

    private void setRecoveryRequiredLocked(Connection connection, UUID uuid, RecoveryReason reason)
        throws SQLException {
        String sql = "UPDATE " + db.getCoordinationTableName()
            + " SET recovery_required=TRUE, recovery_reason=COALESCE(recovery_reason, ?),"
            + " recovery_required_at=COALESCE(recovery_required_at, CURRENT_TIMESTAMP(6)) WHERE uuid=?";
        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setString(1, reason.name());
            statement.setString(2, uuid.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("recovery row disappeared");
        }
    }

    /** 読み取り専用の互換API。書き込みはownership付きAPIだけを使用する。 */
    public Optional<CompletePlayerData> loadCompletePlayerData(UUID uuid) throws SQLException {
        DatabaseOperationTrace trace = db.beginOperation("LOAD", uuid, null, -1L, -1L);
        String outcome = "FAILURE";
        Throwable failure = null;
        try (Connection connection = beginConnection(trace)) {
            trace.start("playerDataRead");
            Optional<CompletePlayerData> result = loadCompletePlayerData(connection, uuid);
            trace.end("playerDataRead");
            outcome = result.isPresent() ? "FOUND" : "MISSING";
            return result;
        } catch (Throwable error) {
            failure = error;
            if (error instanceof SQLException sqlException) throw sqlException;
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("Failed to load player data", error);
        } finally {
            trace.finish(outcome, failure);
        }
    }

    private Optional<CompletePlayerData> loadCompletePlayerData(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM " + db.getTableName() + " WHERE uuid = ?";
        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) return Optional.of(mapCompleteData(resultSet));
            }
        }
        return Optional.empty();
    }

    private Connection beginConnection(DatabaseOperationTrace trace) throws SQLException {
        trace.start("connectionAcquisition");
        try {
            Connection connection = db.getConnection();
            trace.end("connectionAcquisition");
            return connection;
        } catch (SQLException error) {
            trace.end("connectionAcquisition");
            throw error;
        }
    }

    private SyncRow lockSyncRow(Connection connection, UUID uuid) throws SQLException {
        String sql = """
            SELECT owner_server, owner_session, fencing_token, lease_until, data_revision,
                recovery_required, recovery_reason, recovery_required_at, last_checkpoint_at,
                checkpoint_source_version,automatic_recovery_attempts,automatic_recovery_last_attempt_at,
                domain_provenance_revision,domain_provenance_generation,domain_provenance_manifest
            FROM %s WHERE uuid = ? FOR UPDATE
            """.formatted(db.getCoordinationTableName());
        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new SQLException("coordination row does not exist: " + uuid);
                long provenanceRevisionValue = resultSet.getLong("domain_provenance_revision");
                Long provenanceRevision = resultSet.wasNull() ? null : provenanceRevisionValue;
                long provenanceGenerationValue = resultSet.getLong("domain_provenance_generation");
                Long provenanceGeneration = resultSet.wasNull() ? null : provenanceGenerationValue;
                return new SyncRow(
                    resultSet.getString("owner_server"),
                    resultSet.getString("owner_session"),
                    resultSet.getLong("fencing_token"),
                    resultSet.getTimestamp("lease_until"),
                    resultSet.getLong("data_revision"),
                    resultSet.getBoolean("recovery_required"),
                    resultSet.getString("recovery_reason"),
                    resultSet.getTimestamp("recovery_required_at"),
                    resultSet.getTimestamp("last_checkpoint_at"),resultSet.getString("checkpoint_source_version"),
                    resultSet.getInt("automatic_recovery_attempts"),resultSet.getTimestamp("automatic_recovery_last_attempt_at"),
                    provenanceRevision, provenanceGeneration, resultSet.getString("domain_provenance_manifest")
                );
            }
        }
    }

    /** Must be called after the row lock has been acquired for ownership decisions. */
    private Timestamp currentDatabaseTime(Connection connection) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(connection, "SELECT CURRENT_TIMESTAMP(6) AS db_now");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new SQLException("database clock query returned no row");
            Timestamp timestamp = resultSet.getTimestamp("db_now");
            if (timestamp == null) throw new SQLException("database clock query returned NULL");
            return timestamp;
        }
    }

    /** Caller owns the coordination-row lock and has already verified a live lease. */
    private long extendLeaseLocked(
        Connection connection,
        UUID uuid,
        String ownerServer,
        UUID ownerSession,
        long fencingToken,
        long leaseDurationMs
    ) throws SQLException {
        String sql = """
            UPDATE %s
            SET lease_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE uuid = ? AND owner_server = ? AND owner_session = ?
              AND fencing_token = ? AND lease_until >= CURRENT_TIMESTAMP(6)
              AND recovery_required = FALSE
            """.formatted(db.getCoordinationTableName());
        long leaseAnchorNanos = System.nanoTime();
        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setLong(1, leaseMicros(leaseDurationMs));
            statement.setString(2, uuid.toString());
            statement.setString(3, ownerServer);
            statement.setString(4, ownerSession.toString());
            statement.setLong(5, fencingToken);
        return statement.executeUpdate() == 1 ? leaseAnchorNanos : NO_LEASE_ANCHOR;
        }
    }

    private boolean isOwnershipLive(
        Connection connection,
        UUID uuid,
        String ownerServer,
        UUID ownerSession,
        long fencingToken,
        Timestamp freshDbNow
    ) throws SQLException {
        String sql = """
            SELECT uuid
            FROM %s
            WHERE uuid = ? AND owner_server = ? AND owner_session = ?
              AND fencing_token = ? AND lease_until >= ?
              AND recovery_required = FALSE
            FOR UPDATE
            """.formatted(db.getCoordinationTableName());
        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, ownerServer);
            statement.setString(3, ownerSession.toString());
            statement.setLong(4, fencingToken);
            statement.setTimestamp(5, freshDbNow);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean matchesLiveOwnership(SyncRow row, Ownership ownership, Timestamp freshDbNow) {
        return !row.recoveryRequired
            && ownership.ownerServer().equals(row.ownerServer)
            && ownership.ownerSession().toString().equals(row.ownerSession)
            && ownership.fencingToken() == row.fencingToken
            && row.leaseUntil != null
            && !row.leaseUntil.before(freshDbNow);
    }

    private void upsertPlayerData(
        Connection connection,
        CompletePlayerData data,
        Configuration.SyncConfig sync
    ) throws SQLException {
        List<ColumnValue> values = new ArrayList<>();
        values.add(new ColumnValue("uuid", (statement, index) -> statement.setString(index, data.uuid.toString())));
        values.add(new ColumnValue("username", (statement, index) -> statement.setString(index, data.username)));
        if (sync.inventory) {
            values.add(string("pending_disconnect_items", data.pendingDisconnectItemsJson));
            values.add(string("inventory", data.inventoryJson));
            values.add(integer("selected_item_slot", data.selectedItemSlot));
        }
        if (sync.enderchest) values.add(string("enderchest", data.enderchestJson));
        if (sync.armor) values.add(string("armor", data.armorJson));
        if (sync.offhand) values.add(string("offhand", data.offhandJson));
        if (sync.health) {
            values.add(doubleValue("health", data.health));
            values.add(integer("air", data.air));
        }
        if (sync.food) {
            values.add(integer("food_level", data.foodLevel));
            values.add(floatValue("saturation", data.saturation));
            values.add(floatValue("exhaustion", data.exhaustion));
        }
        if (sync.experience) {
            values.add(integer("experience_level", data.experienceLevel));
            values.add(integer("experience_points", data.experiencePoints));
            values.add(floatValue("experience_total", data.experienceTotal));
            values.add(nullableFloatValue("experience_progress", data.experienceProgress));
            values.add(nullableLongValue("experience_points_into_level", data.experiencePointsIntoLevel));
        }
        if (sync.effects) values.add(string("effects", data.effectsJson));
        if (sync.dimensionEnabled()) values.add(string("dimension", data.dimension));
        if (sync.position) {
            values.add(doubleValue("pos_x", data.posX));
            values.add(doubleValue("pos_y", data.posY));
            values.add(doubleValue("pos_z", data.posZ));
        }
        if (sync.rotationEnabled()) {
            values.add(floatValue("yaw", data.yaw));
            values.add(floatValue("pitch", data.pitch));
        }
        if (sync.gamemode) {
            values.add(string("gamemode", data.gamemode));
            values.add(booleanValue("is_flying", data.isFlying));
            values.add(booleanValue("allow_flying", data.allowFlying));
            values.add(booleanValue("is_creative_flying", data.isCreativeFlying));
        }
        if (sync.playerProfile) {
            values.add(string("display_name", data.displayName));
            values.add(string("skin_texture", data.skinTexture));
            values.add(string("skin_signature", data.skinSignature));
        }
        if (sync.advancements) values.add(string("advancements", data.advancementsJson));
        if (sync.statistics) values.add(string("statistics", data.statisticsJson));
        if (sync.recipeBook) values.add(string("recipe_book", data.recipeBookJson));

        // これは同期対象データではなくメタデータなので、旧挙動を維持する。
        values.add(longValue("play_time_seconds", data.playTimeSeconds));
        values.add(integer("login_count", data.loginCount));

        String columns = values.stream().map(ColumnValue::name).reduce((a, b) -> a + ", " + b).orElseThrow();
        String placeholders = "?, ".repeat(values.size());
        placeholders = placeholders.substring(0, placeholders.length() - 2);
        String updates = values.stream()
            .map(ColumnValue::name)
            .filter(column -> !column.equals("uuid")
                && !column.equals("login_count")
                && !column.equals("play_time_seconds"))
            .map(column -> column + " = VALUES(" + column + ")")
            .reduce((a, b) -> a + ",\n                " + b)
            .orElse("username = VALUES(username)");

        String sql = """
            INSERT INTO %s (%s) VALUES (%s)
            ON DUPLICATE KEY UPDATE %s,
                last_login = CURRENT_TIMESTAMP(6)
            """.formatted(db.getTableName(), columns, placeholders, updates);

        try (PreparedStatement statement = db.prepareStatement(connection, sql)) {
            for (int index = 0; index < values.size(); index++) {
                values.get(index).binder.bind(statement, index + 1);
            }
            statement.executeUpdate();
        }
    }

    private static ColumnValue string(String name, String value) {
        return new ColumnValue(name, (statement, index) -> statement.setString(index, value));
    }

    private static ColumnValue integer(String name, int value) {
        return new ColumnValue(name, (statement, index) -> statement.setInt(index, value));
    }

    private static ColumnValue longValue(String name, long value) {
        return new ColumnValue(name, (statement, index) -> statement.setLong(index, value));
    }

    private static ColumnValue floatValue(String name, float value) {
        return new ColumnValue(name, (statement, index) -> statement.setFloat(index, value));
    }

    private static ColumnValue nullableFloatValue(String name, Float value) {
        return new ColumnValue(name, (statement, index) -> {
            if (value == null) statement.setNull(index, java.sql.Types.FLOAT);
            else statement.setFloat(index, value);
        });
    }

    private static ColumnValue nullableLongValue(String name, Long value) {
        return new ColumnValue(name, (statement, index) -> {
            if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
            else statement.setLong(index, value);
        });
    }

    private static ColumnValue doubleValue(String name, double value) {
        return new ColumnValue(name, (statement, index) -> statement.setDouble(index, value));
    }

    private static ColumnValue booleanValue(String name, boolean value) {
        return new ColumnValue(name, (statement, index) -> statement.setBoolean(index, value));
    }

    private static long leaseMicros(long leaseDurationMs) {
        if (leaseDurationMs <= 0 || leaseDurationMs > 300_000) {
            throw new IllegalArgumentException("lease duration is outside the safe range");
        }
        return Math.multiplyExact(leaseDurationMs, 1000L);
    }

    static boolean isRetryableTransactionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                int code = sqlException.getErrorCode();
                if ("40001".equals(state) || code == 1213 || code == 1205) return true;
                String message = sqlException.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("deadlock") || lower.contains("lock wait timeout")) return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private record SyncRow(
        String ownerServer,
        String ownerSession,
        long fencingToken,
        Timestamp leaseUntil,
        long dataRevision,
        boolean recoveryRequired,
        String recoveryReason,
        Timestamp recoveryRequiredAt,
        Timestamp lastCheckpointAt,
        String checkpointSourceVersion,
        int automaticAttempts,
        Timestamp lastAutomaticAttemptAt,
        Long domainProvenanceRevision,
        Long domainProvenanceGeneration,
        String domainProvenanceManifest
    ) {}

    private static CompletePlayerData mapCompleteData(ResultSet resultSet) throws SQLException {
        float progressValue = resultSet.getFloat("experience_progress");
        Float experienceProgress = resultSet.wasNull() ? null : progressValue;
        long pointsIntoLevelValue = resultSet.getLong("experience_points_into_level");
        Long pointsIntoLevel = resultSet.wasNull() ? null : pointsIntoLevelValue;
        return new CompletePlayerData(
            UUID.fromString(resultSet.getString("uuid")),
            resultSet.getString("username"),
            resultSet.getString("inventory"),
            resultSet.getString("enderchest"),
            resultSet.getString("armor"),
            resultSet.getString("offhand"),
            resultSet.getDouble("health"),
            resultSet.getInt("food_level"),
            resultSet.getFloat("saturation"),
            resultSet.getFloat("exhaustion"),
            resultSet.getInt("air"),
            resultSet.getInt("experience_level"),
            resultSet.getInt("experience_points"),
            resultSet.getFloat("experience_total"),
            experienceProgress,
            resultSet.getString("effects"),
            resultSet.getString("dimension"),
            resultSet.getDouble("pos_x"),
            resultSet.getDouble("pos_y"),
            resultSet.getDouble("pos_z"),
            resultSet.getFloat("yaw"),
            resultSet.getFloat("pitch"),
            resultSet.getString("gamemode"),
            resultSet.getBoolean("is_flying"),
            resultSet.getBoolean("allow_flying"),
            resultSet.getBoolean("is_creative_flying"),
            resultSet.getString("display_name"),
            resultSet.getString("skin_texture"),
            resultSet.getString("skin_signature"),
            resultSet.getString("advancements"),
            resultSet.getString("statistics"),
            resultSet.getString("recipe_book"),
            resultSet.getInt("selected_item_slot"),
            resultSet.getLong("play_time_seconds"),
            resultSet.getInt("login_count"),
            pointsIntoLevel,
            resultSet.getString("pending_disconnect_items")
        );
    }

    public static final class CompletePlayerData {
        public final UUID uuid;
        public final String username;
        public final String inventoryJson;
        public final String enderchestJson;
        public final String armorJson;
        public final String offhandJson;
        public final double health;
        public final int foodLevel;
        public final float saturation;
        public final float exhaustion;
        public final int air;
        public final int experienceLevel;
        public final int experiencePoints;
        public final float experienceTotal;
        /** Null marks a pre-2.1.4 row; experience_points remains cumulative XP for those rows. */
        public final Float experienceProgress;
        /** Exact integer XP earned within the current level; null identifies legacy rows. */
        public final Long experiencePointsIntoLevel;
        /** Durable cursor/crafting items captured during a prior disconnect. */
        public final String pendingDisconnectItemsJson;
        public final String effectsJson;
        public final String dimension;
        public final double posX;
        public final double posY;
        public final double posZ;
        public final float yaw;
        public final float pitch;
        public final String gamemode;
        public final boolean isFlying;
        public final boolean allowFlying;
        public final boolean isCreativeFlying;
        public final String displayName;
        public final String skinTexture;
        public final String skinSignature;
        public final String advancementsJson;
        public final String statisticsJson;
        public final String recipeBookJson;
        public final int selectedItemSlot;
        public final long playTimeSeconds;
        public final int loginCount;

        public CompletePlayerData(
            UUID uuid,
            String username,
            String inventoryJson,
            String enderchestJson,
            String armorJson,
            String offhandJson,
            double health,
            int foodLevel,
            float saturation,
            float exhaustion,
            int air,
            int experienceLevel,
            int experiencePoints,
            float experienceTotal,
            Float experienceProgress,
            String effectsJson,
            String dimension,
            double posX,
            double posY,
            double posZ,
            float yaw,
            float pitch,
            String gamemode,
            boolean isFlying,
            boolean allowFlying,
            boolean isCreativeFlying,
            String displayName,
            String skinTexture,
            String skinSignature,
            String advancementsJson,
            String statisticsJson,
            String recipeBookJson,
            int selectedItemSlot,
            long playTimeSeconds,
            int loginCount
        ) {
            this(
                uuid, username, inventoryJson, enderchestJson, armorJson, offhandJson,
                health, foodLevel, saturation, exhaustion, air, experienceLevel, experiencePoints,
                experienceTotal, experienceProgress, effectsJson, dimension, posX, posY, posZ,
                yaw, pitch, gamemode, isFlying, allowFlying, isCreativeFlying, displayName,
                skinTexture, skinSignature, advancementsJson, statisticsJson, recipeBookJson,
                selectedItemSlot, playTimeSeconds, loginCount, null, null);
        }

        public CompletePlayerData(
            UUID uuid,
            String username,
            String inventoryJson,
            String enderchestJson,
            String armorJson,
            String offhandJson,
            double health,
            int foodLevel,
            float saturation,
            float exhaustion,
            int air,
            int experienceLevel,
            int experiencePoints,
            float experienceTotal,
            Float experienceProgress,
            String effectsJson,
            String dimension,
            double posX,
            double posY,
            double posZ,
            float yaw,
            float pitch,
            String gamemode,
            boolean isFlying,
            boolean allowFlying,
            boolean isCreativeFlying,
            String displayName,
            String skinTexture,
            String skinSignature,
            String advancementsJson,
            String statisticsJson,
            String recipeBookJson,
            int selectedItemSlot,
            long playTimeSeconds,
            int loginCount,
            Long experiencePointsIntoLevel,
            String pendingDisconnectItemsJson
        ) {
            this.uuid = uuid;
            this.username = username;
            this.inventoryJson = inventoryJson;
            this.enderchestJson = enderchestJson;
            this.armorJson = armorJson;
            this.offhandJson = offhandJson;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.air = air;
            this.experienceLevel = experienceLevel;
            this.experiencePoints = experiencePoints;
            this.experienceTotal = experienceTotal;
            this.experienceProgress = experienceProgress;
            this.experiencePointsIntoLevel = experiencePointsIntoLevel;
            this.pendingDisconnectItemsJson = pendingDisconnectItemsJson;
            this.effectsJson = effectsJson;
            this.dimension = dimension;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.gamemode = gamemode;
            this.isFlying = isFlying;
            this.allowFlying = allowFlying;
            this.isCreativeFlying = isCreativeFlying;
            this.displayName = displayName;
            this.skinTexture = skinTexture;
            this.skinSignature = skinSignature;
            this.advancementsJson = advancementsJson;
            this.statisticsJson = statisticsJson;
            this.recipeBookJson = recipeBookJson;
            this.selectedItemSlot = selectedItemSlot;
            this.playTimeSeconds = playTimeSeconds;
            this.loginCount = loginCount;
        }

        public CompletePlayerData withInventoryAndPendingItems(
            String updatedInventoryJson,
            String updatedPendingDisconnectItemsJson
        ) {
            return new CompletePlayerData(
                uuid, username, updatedInventoryJson, enderchestJson, armorJson, offhandJson,
                health, foodLevel, saturation, exhaustion, air, experienceLevel, experiencePoints,
                experienceTotal, experienceProgress, effectsJson, dimension, posX, posY, posZ,
                yaw, pitch, gamemode, isFlying, allowFlying, isCreativeFlying, displayName,
                skinTexture, skinSignature, advancementsJson, statisticsJson, recipeBookJson,
                selectedItemSlot, playTimeSeconds, loginCount,
                experiencePointsIntoLevel, updatedPendingDisconnectItemsJson);
        }

        public CompletePlayerData withExperienceState(
            int level,
            int cumulativeExperience,
            float totalExperience,
            Float progress,
            Long pointsIntoLevel
        ) {
            return new CompletePlayerData(
                uuid, username, inventoryJson, enderchestJson, armorJson, offhandJson,
                health, foodLevel, saturation, exhaustion, air, level, cumulativeExperience,
                totalExperience, progress, effectsJson, dimension, posX, posY, posZ,
                yaw, pitch, gamemode, isFlying, allowFlying, isCreativeFlying, displayName,
                skinTexture, skinSignature, advancementsJson, statisticsJson, recipeBookJson,
                selectedItemSlot, playTimeSeconds, loginCount,
                pointsIntoLevel, pendingDisconnectItemsJson);
        }
    }
}
