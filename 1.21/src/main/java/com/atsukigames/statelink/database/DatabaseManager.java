package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;
    private final String tableName;
    private final String coordinationTableName;
    private final int queryTimeoutSeconds;
    private final boolean diagnosticLogging;

    public DatabaseManager(Configuration.DatabaseConfig config) {
        this(config, config == null ? null : config.tableName + "_sync_state");
    }

    /**
     * Operator input for the one-time 2.1.11 provenance bootstrap. {@code acknowledged} is the
     * explicit statement that the database is the authority for every legacy-synchronized
     * domain enabled in {@code sync}, and that those switches match the 2.1.11-era configuration.
     */
    public record LegacyBootstrapRequest(Configuration.SyncConfig sync, boolean acknowledged,
        String pinnedFingerprint, String newDomainPolicy, String serverId,
        String acknowledgedClusterFingerprint, boolean quarantineUndecodableRows) {
        public LegacyBootstrapRequest(Configuration.SyncConfig sync, boolean acknowledged,
            String pinnedFingerprint, String newDomainPolicy, String serverId) {
            this(sync, acknowledged, pinnedFingerprint, newDomainPolicy, serverId, "", false);
        }
        public static LegacyBootstrapRequest of(Configuration configuration) {
            Configuration.MigrationConfig migration = configuration.migration == null
                ? new Configuration.MigrationConfig() : configuration.migration;
            return new LegacyBootstrapRequest(configuration.sync,
                migration.legacy211Bootstrap && migration.acknowledgeLegacy211DatabaseAsAuthority,
                migration.acknowledgedLegacy211DomainFingerprint, migration.legacy211NewDomainPolicy,
                configuration.sync == null ? null : configuration.sync.serverId,
                migration.acknowledgeClusterDomainFingerprint, migration.legacy211QuarantineUndecodableRows);
        }
    }
    private String pendingClusterFingerprint;
    private String pendingClusterPrevious;

    /** Rows per bootstrap transaction; package-visible so tests can cross batch boundaries cheaply. */
    static volatile int BOOTSTRAP_BATCH_ROWS = 200;
    /** Test-only fault injection after each committed bootstrap batch. */
    static volatile java.util.function.IntConsumer bootstrapBatchHook;
    private static final int MIGRATION_LOCK_WAIT_SECONDS = 180;
    private String databaseName;
    private SchemaGeneration.Detection detectedGeneration;

    public DatabaseManager(Configuration.DatabaseConfig config, String configuredCoordinationTableName) {
        this(config, configuredCoordinationTableName, null);
    }

    public DatabaseManager(Configuration.DatabaseConfig config, String configuredCoordinationTableName,
        LegacyBootstrapRequest legacyBootstrap) {
        if (config == null) throw new IllegalArgumentException("database config must not be null");
        if (!Configuration.isSafeIdentifier(config.tableName)) {
            throw new IllegalArgumentException("Unsafe player data table name: " + config.tableName);
        }
        if (!Configuration.isSafeIdentifier(config.name)) {
            throw new IllegalArgumentException("Unsafe database name: " + config.name);
        }
        if (configuredCoordinationTableName != null
                && !configuredCoordinationTableName.isBlank()
                && !Configuration.isSafeIdentifier(configuredCoordinationTableName)) {
            throw new IllegalArgumentException("Unsafe coordination table name: " + configuredCoordinationTableName);
        }
        if (configuredCoordinationTableName != null
                && configuredCoordinationTableName.equals(config.tableName)) {
            throw new IllegalArgumentException("Coordination table must differ from player data table");
        }
        HikariConfig hikariConfig = new HikariConfig();

        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo&connectTimeout=%d&socketTimeout=%d",
            config.host, config.port, config.name, config.sslEnabled,
            config.connectionTimeoutMs, config.socketTimeoutMs);

        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.username);
        hikariConfig.setPassword(config.password);
        hikariConfig.setMaximumPoolSize(config.maxConnections);
        hikariConfig.setConnectionTimeout(config.connectionTimeoutMs);
        // MySQL's default innodb_lock_wait_timeout is commonly 50 seconds,
        // which is longer than a 5 second StateLink lease. Every pooled connection
        // gets a bounded session value so a row-lock stall returns through the
        // repository rollback path instead of occupying a worker indefinitely.
        int lockWaitSeconds = Math.max(1, (config.lockWaitTimeoutMs + 999) / 1000);
        hikariConfig.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = " + lockWaitSeconds);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setLeakDetectionThreshold(60000);

        this.dataSource = new HikariDataSource(hikariConfig);
        this.databaseName = config.name;
        this.tableName = config.tableName;
        this.queryTimeoutSeconds = Math.max(1, config.queryTimeoutMs / 1_000);
        this.diagnosticLogging = config.diagnosticLogging;
        this.coordinationTableName = configuredCoordinationTableName == null || configuredCoordinationTableName.isBlank()
            ? config.tableName + "_sync_state"
            : configuredCoordinationTableName;

        LOGGER.info(
            "Database timeout policy connectionTimeoutMs={} socketTimeoutMs={} queryTimeoutMs={} "
                + "effectiveQueryTimeoutSeconds={} lockWaitTimeoutMs={} effectiveLockWaitSeconds={} "
                + "lease safety is enforced by coordinator",
            config.connectionTimeoutMs, config.socketTimeoutMs, config.queryTimeoutMs, queryTimeoutSeconds,
            config.lockWaitTimeoutMs, lockWaitSeconds);
        LOGGER.info(
            "Hikari pool policy maximumPoolSize={} minimumIdle={} connectionTimeoutMs={} validationTimeoutMs={} "
                + "idleTimeoutMs={} maxLifetimeMs={} diagnosticLogging={}",
            dataSource.getMaximumPoolSize(), dataSource.getMinimumIdle(), dataSource.getConnectionTimeout(),
            dataSource.getValidationTimeout(), dataSource.getIdleTimeout(), dataSource.getMaxLifetime(),
            diagnosticLogging);

        try {
            // Never alter an existing engine. The repository's atomic transactions
            // require both tables to be transactional before schema migration or use.
            initializeSchema(legacyBootstrap);
        } catch (RuntimeException failure) {
            dataSource.close();
            throw failure;
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Creates a MySQL statement with the configured bounded query timeout.
     * Repository statements use this helper so a timeout is followed by the
     * same transaction rollback path as any other SQL exception.
     */
    public PreparedStatement prepareStatement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    public boolean isDiagnosticLoggingEnabled() {
        return diagnosticLogging;
    }

    public DatabaseOperationTrace beginOperation(
        String operation,
        java.util.UUID uuid,
        java.util.UUID session,
        long fence,
        long revision
    ) {
        return new DatabaseOperationTrace(
            diagnosticLogging,
            operation,
            uuid,
            session == null ? null : session.toString(),
            fence,
            revision,
            this::poolMetrics);
    }

    public PoolMetrics poolMetrics() {
        if (dataSource.isClosed()) return PoolMetrics.unavailable();
        HikariPoolMXBean bean = dataSource.getHikariPoolMXBean();
        if (bean == null) return PoolMetrics.unavailable();
        return new PoolMetrics(
            bean.getActiveConnections(),
            bean.getIdleConnections(),
            bean.getTotalConnections(),
            bean.getThreadsAwaitingConnection());
    }

    public record PoolMetrics(int active, int idle, int total, int waiting) {
        public static PoolMetrics unavailable() {
            return new PoolMetrics(-1, -1, -1, -1);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public String getCoordinationTableName() {
        return coordinationTableName;
    }

    public String getRecoveryAuditTableName() {
        return recoveryAuditTableName(coordinationTableName);
    }

    /*
     * The "pdc" prefixes below are part of the protocol shared through the database, not branding:
     * every backend of a cluster must derive the same lock and table names, including backends that
     * still run under the former mod name during a rolling upgrade. They must never be renamed.
     */
    /** Keep MySQL's 64-character identifier limit even for long custom table names. */
    public static String recoveryAuditTableName(String coordinationTable) {
        if (coordinationTable.length() <= 49) return coordinationTable + "_recovery_audit";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(coordinationTable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "pdc_rec_audit_" + java.util.HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public boolean isClosed() {
        return dataSource.isClosed();
    }

    /** Schema generation observed before this process applied any DDL. */
    public SchemaGeneration.Detection detectedGeneration() { return detectedGeneration; }

    public String getMigrationTableName() { return migrationTableName(coordinationTableName); }

    public static String migrationTableName(String coordinationTable) {
        if (coordinationTable.length() <= 54) return coordinationTable + "_migration";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(coordinationTable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "pdc_migration_" + java.util.HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private record MigrationRecord(String state, String fingerprint, String manifest, boolean acknowledged,
        String policy, String clusterFingerprint) {}
    private record LegacyBootstrapRun(Legacy211Bootstrap.Plan plan, boolean resumed, String serverId,
        java.util.Set<String> quarantined) {}

    /**
     * Classify the existing schema, decide the legacy bootstrap, apply additive DDL and run the
     * bootstrap, all while holding one MySQL advisory lock so that concurrently starting backends
     * are serialized. Any failure propagates and the caller closes the pool: no writer starts on
     * a partially migrated database.
     */
    private void initializeSchema(LegacyBootstrapRequest request) {
        try (Connection lockConnection = getConnection()) {
            String lockName = acquireMigrationLock(lockConnection);
            try {
                LegacyBootstrapRun run = classifyBeforeDdl(request);
                // Never alter an existing engine. The repository's atomic transactions
                // require both tables to be transactional before schema migration or use.
                initializeTables();
                initializeCoordinationTable();
                initializeMigrationTable();
                if (run != null) runLegacyBootstrap(run);
                applyClusterFingerprint();
            } finally {
                try (PreparedStatement release = lockConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, lockName);
                    release.execute();
                } catch (SQLException ignored) {
                    // Closing the session below releases the advisory lock as well.
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not serialize StateLink schema migration", error);
        }
    }

    private String acquireMigrationLock(Connection connection) throws SQLException {
        final String name;
        try {
            name = "pdc:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((databaseName + "/" + coordinationTableName).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .substring(0, 56);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
        // One-second waits keep every statement inside the configured socket timeout.
        for (int attempt = 0; attempt < MIGRATION_LOCK_WAIT_SECONDS; attempt++) {
            try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 1)")) {
                lock.setString(1, name);
                try (var result = lock.executeQuery()) {
                    if (result.next() && result.getInt(1) == 1) return name;
                }
            }
            if (attempt == 0) LOGGER.info("Waiting for another backend to finish StateLink schema migration");
        }
        throw new IllegalStateException("Timed out waiting for the StateLink schema migration lock");
    }

    private LegacyBootstrapRun classifyBeforeDdl(LegacyBootstrapRequest request) {
        final long playerRows, coordinationRows;
        final MigrationRecord record;
        try (Connection connection = getConnection()) {
            detectedGeneration = SchemaGeneration.detect(connection, tableName, coordinationTableName,
                getRecoveryAuditTableName());
            playerRows = rowCountIfExists(connection, tableName);
            coordinationRows = rowCountIfExists(connection, coordinationTableName);
            record = readLegacyMigration(connection);
        } catch (SQLException error) {
            throw new IllegalStateException("Could not classify the existing StateLink schema", error);
        }
        LOGGER.info("Schema generation={} detail='{}' playerRows={} coordinationRows={} legacyBootstrapRecord={}",
            detectedGeneration.generation(), detectedGeneration.detail(), playerRows, coordinationRows,
            record == null ? "none" : record.state());
        boolean acknowledged = request != null && request.acknowledged() && request.sync() != null;

        if (record != null) {
            // The decision that this database was an exact 2.1.11 schema is already durable.
            if ("COMPLETED".equals(record.state())) {
                // Cluster consistency is independent of the migration acknowledgement: every enabled
                // backend of a bootstrapped database is checked, with or without the bootstrap flags.
                if (record.acknowledged() && record.manifest() != null && request != null && request.sync() != null) {
                    requireClusterFingerprint(request, record);
                }
                return null;
            }
            if (!"STARTED".equals(record.state())) {
                throw new IllegalStateException("UNKNOWN_SCHEMA: unrecognized legacy bootstrap state " + record.state());
            }
            if (!acknowledged) {
                throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED",
                    "an interrupted 2.1.11 provenance bootstrap must be resumed with the same acknowledged configuration");
            }
            Legacy211Bootstrap.Plan resumedPlan = requireSameFingerprint(request, record);
            try (Connection connection = getConnection()) {
                return new LegacyBootstrapRun(resumedPlan, true, request.serverId(),
                    preflightLegacyRows(connection, resumedPlan, request.quarantineUndecodableRows()));
            } catch (SQLException error) {
                throw new IllegalStateException("Could not preflight the 2.1.11 database before resuming", error);
            }
        }

        switch (detectedGeneration.generation()) {
            case FRESH, PRE_PROVENANCE_2_2, CURRENT_PROVENANCE -> {
                if (acknowledged) LOGGER.warn("migration.legacy211Bootstrap is set but the database is {} and not an "
                    + "exact 2.1.11 schema; no legacy bootstrap is performed and provenance rules are unchanged",
                    detectedGeneration.generation());
                return null;
            }
            case UNKNOWN_SCHEMA -> {
                if (playerRows == 0 && coordinationRows == 0) {
                    // No PlayerData authority exists yet; strict column validation below still applies.
                    LOGGER.warn("Unrecognized but empty StateLink schema ({}); continuing with strict validation",
                        detectedGeneration.detail());
                    return null;
                }
                throw new IllegalStateException("UNKNOWN_SCHEMA: " + detectedGeneration.detail()
                    + "; refusing to migrate or bootstrap a database that is not a known StateLink schema");
            }
            default -> { }
        }

        // Exact 2.1.11 schema.
        if (playerRows == 0) return new LegacyBootstrapRun(null, false, request == null ? null : request.serverId(),
            java.util.Set.of());
        if (!acknowledged) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED",
                "this is a PlayerDataConnector 2.1.11 database with " + playerRows + " player rows and no domain "
                    + "provenance. Stop every backend, confirm that the sync.* domain switches equal the 2.1.11-era "
                    + "configuration, then set migration.legacy211Bootstrap=true and "
                    + "migration.acknowledgeLegacy211DatabaseAsAuthority=true for the one-time bootstrap");
        }
        Legacy211Bootstrap.Plan plan = Legacy211Bootstrap.plan(request.sync(), request.newDomainPolicy());
        LOGGER.warn("Legacy 2.1.11 provenance bootstrap acknowledged fingerprint={} newDomainPolicy={} manifest={}",
            plan.fingerprint(), plan.newDomainPolicy(), plan.manifest());
        requirePinnedFingerprint(request, plan);
        final java.util.Set<String> quarantined;
        try (Connection connection = getConnection()) {
            requireNoLiveOwner(connection);
            requireLegacyValueEvidence(connection, plan);
            quarantined = preflightLegacyRows(connection, plan, request.quarantineUndecodableRows());
        } catch (SQLException error) {
            throw new IllegalStateException("Could not verify the 2.1.11 database before bootstrap", error);
        }
        // Durable before the first ALTER: an interrupted upgrade is recognized by this row,
        // not by guessing from a half-altered column set.
        initializeMigrationTable();
        try (Connection connection = getConnection(); PreparedStatement insert = prepareStatement(connection,
                "INSERT INTO " + getMigrationTableName() + " (migration_id,state,source_schema,target_version,"
                    + "config_fingerprint,domain_manifest,new_domain_policy,operator_acknowledged,server_id,row_count,"
                    + "cluster_fingerprint) VALUES (?,'STARTED',?,?,?,?,?,TRUE,?,0,?)")) {
            insert.setString(1, Legacy211Bootstrap.MIGRATION_ID);
            insert.setString(2, Legacy211Bootstrap.SOURCE_SCHEMA);
            insert.setString(3, com.atsukigames.statelink.StateLink.VERSION);
            insert.setString(4, plan.fingerprint());
            insert.setString(5, plan.manifest());
            insert.setString(6, plan.newDomainPolicy());
            insert.setString(7, request.serverId());
            insert.setString(8, plan.fingerprint());
            insert.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Could not record the start of the 2.1.11 provenance bootstrap", error);
        }
        return new LegacyBootstrapRun(plan, false, request.serverId(), quarantined);
    }

    private Legacy211Bootstrap.Plan requireSameFingerprint(LegacyBootstrapRequest request, MigrationRecord record) {
        Legacy211Bootstrap.Plan plan = Legacy211Bootstrap.plan(request.sync(), request.newDomainPolicy());
        requirePinnedFingerprint(request, plan);
        if (!plan.fingerprint().equals(record.fingerprint())) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH",
                "this backend's bootstrap domain configuration (" + plan.fingerprint() + ") differs from the one "
                    + "recorded in the database (" + record.fingerprint() + "). Use identical sync.* domain switches "
                    + "on every backend; after the bootstrap has completed, remove the migration.legacy211* "
                    + "acknowledgement before changing domains");
        }
        return plan;
    }

    /**
     * Applies to every enabled backend once a 2.1.11 bootstrap has completed. A backend whose 16
     * domain switches differ from the recorded cluster decision is refused, unless the operator
     * deliberately moves the whole cluster by acknowledging exactly the new fingerprint.
     */
    private void requireClusterFingerprint(LegacyBootstrapRequest request, MigrationRecord record) {
        String recorded = record.clusterFingerprint() != null ? record.clusterFingerprint()
            : Legacy211Bootstrap.clusterFingerprintOfManifest(record.manifest(), record.policy());
        if (request.acknowledged() && !java.util.Objects.equals(record.policy(),
                request.newDomainPolicy() == null || request.newDomainPolicy().isBlank()
                    ? Legacy211Bootstrap.POLICY_DISABLED : request.newDomainPolicy())) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH",
                "migration.legacy211NewDomainPolicy differs from the policy recorded by the completed bootstrap ("
                    + record.policy() + ")");
        }
        String own = Legacy211Bootstrap.clusterFingerprint(request.sync(), record.policy());
        if (request.acknowledged()) requirePinnedFingerprintValue(request.pinnedFingerprint(), own);
        if (own.equals(recorded)) {
            if (record.clusterFingerprint() == null) { pendingClusterFingerprint = own; pendingClusterPrevious = null; }
            return;
        }
        String acknowledged = request.acknowledgedClusterFingerprint();
        if (acknowledged != null && own.equals(acknowledged.trim())) {
            pendingClusterFingerprint = own;
            pendingClusterPrevious = recorded;
            return;
        }
        throw new Legacy211Bootstrap.BootstrapRefusedException("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH",
            "this backend's sync.* domain switches (" + own + ") differ from the cluster decision recorded in the "
                + "database (" + recorded + "). Use identical domain switches on every backend. To change the "
                + "cluster deliberately, stop every backend, apply the same switches everywhere and set "
                + "migration.acknowledgeClusterDomainFingerprint=" + own + "; per-player domain provenance rules "
                + "still apply to the changed domains");
    }

    private void applyClusterFingerprint() {
        if (pendingClusterFingerprint == null) return;
        try (Connection connection = getConnection(); PreparedStatement update = prepareStatement(connection,
                "UPDATE " + getMigrationTableName() + " SET cluster_fingerprint=?,cluster_fingerprint_changed_at="
                    + "CASE WHEN ? IS NULL THEN cluster_fingerprint_changed_at ELSE CURRENT_TIMESTAMP(6) END"
                    + " WHERE migration_id=? AND state='COMPLETED' AND (cluster_fingerprint IS NULL OR cluster_fingerprint=?"
                    + " OR cluster_fingerprint=?)")) {
            update.setString(1, pendingClusterFingerprint);
            update.setString(2, pendingClusterPrevious);
            update.setString(3, Legacy211Bootstrap.MIGRATION_ID);
            update.setString(4, pendingClusterPrevious == null ? pendingClusterFingerprint : pendingClusterPrevious);
            update.setString(5, pendingClusterFingerprint);
            if (update.executeUpdate() != 1) {
                throw new Legacy211Bootstrap.BootstrapRefusedException("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH",
                    "the recorded cluster fingerprint changed concurrently; restart with the cluster's current switches");
            }
            if (pendingClusterPrevious != null) {
                LOGGER.warn("Cluster domain fingerprint changed by explicit acknowledgement previous={} current={}",
                    pendingClusterPrevious, pendingClusterFingerprint);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not record the cluster domain fingerprint", error);
        }
    }

    private static void requirePinnedFingerprintValue(String pinned, String own) {
        if (pinned != null && !pinned.isBlank() && !pinned.equals(own)) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH",
                "migration.acknowledgedLegacy211DomainFingerprint does not match this configuration (" + own + ")");
        }
    }

    /**
     * Validate every row that would be adopted before anything is recorded or altered. By default a
     * single undecodable row stops the bootstrap; with the explicit quarantine option such rows are
     * left without provenance, so exactly those players stay fail-closed at login.
     */
    private java.util.Set<String> preflightLegacyRows(Connection connection, Legacy211Bootstrap.Plan plan,
        boolean quarantine) throws SQLException {
        java.util.Map<String, String> failed = new java.util.LinkedHashMap<>();
        long scanned = 0;
        String after = "";
        while (true) {
            int seen = 0;
            try (PreparedStatement query = prepareStatement(connection, "SELECT uuid,inventory,pending_disconnect_items,"
                    + "enderchest,armor,offhand,effects,health,air,food_level,saturation,exhaustion,experience_level,"
                    + "experience_points,experience_total,experience_progress,experience_points_into_level,gamemode,"
                    + "selected_item_slot,dimension,pos_x,pos_y,pos_z,yaw,pitch FROM " + tableName
                    + " WHERE uuid>? ORDER BY uuid LIMIT " + BOOTSTRAP_BATCH_ROWS)) {
                query.setString(1, after);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        seen++;
                        scanned++;
                        after = rows.getString(1);
                        float progress = rows.getFloat(16);
                        Float progressValue = rows.wasNull() ? null : progress;
                        long into = rows.getLong(17);
                        Long intoValue = rows.wasNull() ? null : into;
                        var problems = LegacyRowPreflight.problems(new LegacyRowPreflight.Row(rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7),
                            rows.getDouble(8), rows.getInt(9), rows.getInt(10), rows.getFloat(11), rows.getFloat(12),
                            rows.getInt(13), rows.getInt(14), rows.getFloat(15), progressValue, intoValue,
                            rows.getString(18), rows.getInt(19), rows.getString(20), rows.getDouble(21),
                            rows.getDouble(22), rows.getDouble(23), rows.getFloat(24), rows.getFloat(25)), plan.states());
                        if (!problems.isEmpty()) failed.put(after, String.join("; ", problems));
                    }
                }
            }
            if (seen < BOOTSTRAP_BATCH_ROWS) break;
        }
        LOGGER.info("Legacy 2.1.11 preflight scanned={} undecodable={}", scanned, failed.size());
        if (failed.isEmpty()) return java.util.Set.of();
        int shown = 0;
        for (var entry : failed.entrySet()) {
            if (shown++ >= 50) break;
            LOGGER.error("Legacy 2.1.11 preflight: uuid={} cannot be adopted: {}", entry.getKey(), entry.getValue());
        }
        if (!quarantine) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_PREFLIGHT_FAILED",
                failed.size() + " of " + scanned + " player rows cannot be decoded as authoritative data (see the "
                    + "preceding log lines for each uuid and reason). Nothing was changed. Repair those rows, or set "
                    + "migration.legacy211QuarantineUndecodableRows=true to bootstrap the others and leave exactly "
                    + "these players fail-closed until an operator reconciles them");
        }
        LOGGER.warn("Legacy 2.1.11 preflight: {} undecodable rows are quarantined (no provenance is established)",
            failed.size());
        return java.util.Set.copyOf(failed.keySet());
    }

    private static void requirePinnedFingerprint(LegacyBootstrapRequest request, Legacy211Bootstrap.Plan plan) {
        String pinned = request.pinnedFingerprint();
        if (pinned != null && !pinned.isBlank() && !pinned.equals(plan.fingerprint())) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH",
                "migration.acknowledgedLegacy211DomainFingerprint does not match this configuration ("
                    + plan.fingerprint() + ")");
        }
    }

    private long rowCountIfExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement exists = prepareStatement(connection,
                "SELECT 1 FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            exists.setString(1, table);
            try (var row = exists.executeQuery()) { if (!row.next()) return 0; }
        }
        try (PreparedStatement count = prepareStatement(connection, "SELECT COUNT(*) FROM " + table);
             var row = count.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private MigrationRecord readLegacyMigration(Connection connection) throws SQLException {
        var migrationColumns = SchemaGeneration.columns(connection, getMigrationTableName());
        if (migrationColumns.isEmpty()) return null;
        // A table written by 2.2.7 does not have the cluster column until this startup adds it.
        boolean hasCluster = migrationColumns.containsKey("cluster_fingerprint");
        try (PreparedStatement query = prepareStatement(connection, "SELECT state,config_fingerprint,domain_manifest,"
                + "operator_acknowledged,new_domain_policy" + (hasCluster ? ",cluster_fingerprint" : "") + " FROM "
                + getMigrationTableName() + " WHERE migration_id=?")) {
            query.setString(1, Legacy211Bootstrap.MIGRATION_ID);
            try (var row = query.executeQuery()) {
                return row.next() ? new MigrationRecord(row.getString(1), row.getString(2), row.getString(3),
                    row.getBoolean(4), row.getString(5), hasCluster ? row.getString(6) : null) : null;
            }
        }
    }

    /** A live lease means an old writer is still running; bootstrapping under it is unsafe. */
    private void requireNoLiveOwner(Connection connection) throws SQLException {
        try (PreparedStatement query = prepareStatement(connection, "SELECT COUNT(*) FROM " + coordinationTableName
                + " WHERE owner_session IS NOT NULL AND lease_until IS NOT NULL AND lease_until>=CURRENT_TIMESTAMP(6)");
             var row = query.executeQuery()) {
            row.next();
            if (row.getLong(1) > 0) {
                throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_BOOTSTRAP_LIVE_OWNER",
                    row.getLong(1) + " player rows still have a live ownership lease. Stop every 2.1.11 backend and "
                        + "wait for the leases to expire before bootstrapping");
            }
        }
    }

    /**
     * The acknowledgement is not trusted blindly. 2.1.11 wrote non-NULL JSON on every checkpoint
     * of an enabled item/effect domain and a constant for each placeholder-only domain; rows which
     * contradict the acknowledged manifest stop the bootstrap for the whole cluster.
     */
    private void requireLegacyValueEvidence(Connection connection, Legacy211Bootstrap.Plan plan) throws SQLException {
        java.util.List<String> jsonDomains = new java.util.ArrayList<>();
        for (String domain : new String[] {"inventory", "enderchest", "armor", "offhand", "effects"}) {
            if (plan.states().get(domain) == DomainProvenance.State.ENABLED_AUTHORITATIVE) jsonDomains.add(domain);
        }
        for (String domain : jsonDomains) {
            try (PreparedStatement query = prepareStatement(connection, "SELECT COUNT(*) FROM " + tableName
                    + " WHERE " + domain + " IS NULL"); var row = query.executeQuery()) {
                row.next();
                if (row.getLong(1) > 0) {
                    throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_DOMAIN_EVIDENCE_MISMATCH",
                        row.getLong(1) + " player rows have no stored " + domain + " value, so 2.1.11 did not "
                            + "synchronize sync." + domain + " for them. Set sync." + domain + "=false to match the "
                            + "2.1.11-era configuration");
                }
            }
        }
        if (plan.placeholderAdoptions().isEmpty()) return;
        StringBuilder columns = new StringBuilder("uuid");
        for (var legacy : plan.placeholderAdoptions()) columns.append(',').append(legacy.column());
        java.util.Map<String, Long> offending = new java.util.LinkedHashMap<>();
        String after = "";
        while (true) {
            int seen = 0;
            try (PreparedStatement query = prepareStatement(connection, "SELECT " + columns + " FROM " + tableName
                    + " WHERE uuid>? ORDER BY uuid LIMIT " + BOOTSTRAP_BATCH_ROWS)) {
                query.setString(1, after);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        seen++;
                        after = rows.getString(1);
                        int index = 2;
                        for (var legacy : plan.placeholderAdoptions()) {
                            if (!Legacy211Bootstrap.isLegacyPlaceholder(legacy.domain(), rows.getString(index++))) {
                                offending.merge(legacy.domain(), 1L, Long::sum);
                            }
                        }
                    }
                }
            }
            if (seen < BOOTSTRAP_BATCH_ROWS) break;
        }
        if (!offending.isEmpty()) {
            throw new Legacy211Bootstrap.BootstrapRefusedException("LEGACY_2_1_11_NEW_DOMAIN_VALUES_NOT_PLACEHOLDER",
                "rows whose stored value is not the 2.1.11 placeholder: " + offending + ". Those values cannot be "
                    + "adopted as first-login local state; set the listed sync.* domains to false for the bootstrap");
        }
    }

    private void initializeMigrationTable() {
        String table = getMigrationTableName();
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                migration_id VARCHAR(64) NOT NULL,
                state VARCHAR(16) NOT NULL,
                source_schema VARCHAR(32) NOT NULL,
                target_version VARCHAR(32) NOT NULL,
                config_fingerprint CHAR(64) NULL,
                domain_manifest LONGTEXT NULL,
                new_domain_policy VARCHAR(32) NULL,
                operator_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
                server_id VARCHAR(64) NULL,
                row_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
                started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                completed_at DATETIME(6) NULL,
                PRIMARY KEY (migration_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.formatted(table);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException error) {
            throw new IllegalStateException("Could not initialize migration table '" + table + "'", error);
        }
        verifyInnoDb(table);
        ensureColumn(table, "cluster_fingerprint", "CHAR(64) NULL", "cluster domain authority fingerprint");
        ensureColumn(table, "cluster_fingerprint_changed_at", "DATETIME(6) NULL", "cluster fingerprint change time");
        ensureColumn(table, "synthetic_revision_rows", "BIGINT UNSIGNED NOT NULL DEFAULT 0",
            "legacy rows given a synthetic durable revision");
        ensureColumn(table, "quarantined_rows", "BIGINT UNSIGNED NOT NULL DEFAULT 0", "undecodable legacy rows");
        validateColumn(table, "cluster_fingerprint", "char", true, 64, null, null, true, null, null);
        validateColumn(table, "synthetic_revision_rows", "bigint", false, 0, true, "0", true, null, null);
        validateColumn(table, "quarantined_rows", "bigint", false, 0, true, "0", true, null, null);
        validatePrimaryKey(table, "migration_id");
        validateColumn(table, "migration_id", "varchar", false, 64);
        validateColumn(table, "state", "varchar", false, 16);
        validateColumn(table, "config_fingerprint", "char", true, 64, null, null, true, null, null);
        validateColumn(table, "domain_manifest", "longtext", true, 0, null, null, true, null, null);
        validateColumn(table, "row_count", "bigint", false, 0, true, "0", true, null, null);
        validateColumn(table, "completed_at", "datetime", true, 0, null, null, true, 6, null);
    }

    /**
     * Establish provenance for every existing 2.1.11 PlayerData row at its own data revision.
     * Ownership, fence, lease and recovery state are left exactly as 2.1.11 wrote them, so an
     * existing RECOVERY_REQUIRED backlog is still resolved only by the configured recovery policy.
     * Bounded batches are individually transactional and skip rows that already have provenance,
     * so a crashed bootstrap can be resumed without advancing any generation twice.
     */
    private void runLegacyBootstrap(LegacyBootstrapRun run) {
        String migrationTable = getMigrationTableName();
        if (run.plan() == null) {
            try (Connection connection = getConnection(); PreparedStatement insert = prepareStatement(connection,
                    "INSERT IGNORE INTO " + migrationTable + " (migration_id,state,source_schema,target_version,"
                        + "operator_acknowledged,server_id,row_count,completed_at) VALUES (?,'COMPLETED',?,?,FALSE,?,0,"
                        + "CURRENT_TIMESTAMP(6))")) {
                insert.setString(1, Legacy211Bootstrap.MIGRATION_ID);
                insert.setString(2, Legacy211Bootstrap.SOURCE_SCHEMA);
                insert.setString(3, com.atsukigames.statelink.StateLink.VERSION);
                insert.setString(4, run.serverId());
                insert.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("Could not record the empty 2.1.11 schema upgrade", error);
            }
            LOGGER.info("Empty 2.1.11 schema upgraded; no provenance bootstrap or acknowledgement was needed");
            return;
        }
        long startedNanos = System.nanoTime();
        long bootstrapped = 0;
        int batches = 0;
        String after = "";
        try {
            while (true) {
                java.util.List<String> uuids = new java.util.ArrayList<>();
                try (Connection connection = getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        try (PreparedStatement query = prepareStatement(connection, "SELECT uuid FROM " + tableName
                                + " WHERE uuid>? ORDER BY uuid LIMIT " + BOOTSTRAP_BATCH_ROWS)) {
                            query.setString(1, after);
                            try (var rows = query.executeQuery()) { while (rows.next()) uuids.add(rows.getString(1)); }
                        }
                        java.util.List<String> adopt = new java.util.ArrayList<>(uuids);
                        adopt.removeAll(run.quarantined());
                        if (!adopt.isEmpty()) {
                            java.util.List<String> scanned = uuids;
                            uuids = adopt;
                            String placeholders = String.join(",", java.util.Collections.nCopies(uuids.size(), "?"));
                            // PlayerData written before the coordination table existed has no state row. The
                            // adopted row is itself the durable checkpoint, so it starts at synthetic revision 1
                            // (first SAVE is 2) with the row's own last write time: a crash before the first
                            // 2.2.x checkpoint is then recoverable like any other legacy checkpoint. Existing
                            // coordination rows are never touched by this statement.
                            int synthetic;
                            try (PreparedStatement ensure = prepareStatement(connection, "INSERT IGNORE INTO "
                                    + coordinationTableName + " (uuid,data_revision,last_checkpoint_at) SELECT p.uuid,1,"
                                    + "COALESCE(p.last_login,CURRENT_TIMESTAMP(6)) FROM " + tableName
                                    + " p WHERE p.uuid IN (" + placeholders + ")")) {
                                for (int i = 0; i < uuids.size(); i++) ensure.setString(i + 1, uuids.get(i));
                                synthetic = ensure.executeUpdate();
                            }
                            if (synthetic > 0) {
                                try (PreparedStatement count = prepareStatement(connection, "UPDATE " + migrationTable
                                        + " SET synthetic_revision_rows=synthetic_revision_rows+? WHERE migration_id=?")) {
                                    count.setInt(1, synthetic);
                                    count.setString(2, Legacy211Bootstrap.MIGRATION_ID);
                                    count.executeUpdate();
                                }
                            }
                            try (PreparedStatement update = prepareStatement(connection, "UPDATE " + coordinationTableName
                                    + " SET domain_provenance_revision=data_revision,domain_provenance_generation=1,"
                                    + "domain_provenance_manifest=? WHERE uuid IN (" + placeholders + ")"
                                    + " AND domain_provenance_manifest IS NULL AND domain_provenance_revision IS NULL"
                                    + " AND domain_provenance_generation IS NULL")) {
                                update.setString(1, run.plan().manifest());
                                for (int i = 0; i < uuids.size(); i++) update.setString(i + 2, uuids.get(i));
                                bootstrapped += update.executeUpdate();
                            }
                            uuids = scanned;
                        }
                        connection.commit();
                    } catch (SQLException | RuntimeException error) {
                        connection.rollback();
                        throw error;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                }
                if (uuids.isEmpty()) break;
                batches++;
                java.util.function.IntConsumer hook = bootstrapBatchHook;
                if (hook != null) hook.accept(batches);
                after = uuids.get(uuids.size() - 1);
                if (uuids.size() < BOOTSTRAP_BATCH_ROWS) break;
            }
            try (Connection connection = getConnection()) {
                connection.setAutoCommit(false);
                try {
                    long missing, total;
                    try (PreparedStatement query = prepareStatement(connection, "SELECT COUNT(*),"
                            + "COALESCE(SUM(c.domain_provenance_manifest IS NULL OR c.domain_provenance_revision IS NULL"
                            + " OR c.domain_provenance_revision<>c.data_revision),0) FROM " + tableName + " p LEFT JOIN "
                            + coordinationTableName + " c ON c.uuid=p.uuid"); var row = query.executeQuery()) {
                        row.next();
                        total = row.getLong(1);
                        missing = row.getLong(2);
                    }
                    if (missing != run.quarantined().size()) {
                        throw new IllegalStateException("LEGACY_2_1_11_BOOTSTRAP_INCOMPLETE: " + missing
                            + " player rows still lack provenance");
                    }
                    try (PreparedStatement complete = prepareStatement(connection, "UPDATE " + migrationTable
                            + " SET state='COMPLETED',row_count=?,quarantined_rows=?,completed_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE migration_id=? AND state='STARTED' AND config_fingerprint=?")) {
                        complete.setLong(1, total);
                        complete.setLong(2, run.quarantined().size());
                        complete.setString(3, Legacy211Bootstrap.MIGRATION_ID);
                        complete.setString(4, run.plan().fingerprint());
                        if (complete.executeUpdate() != 1) {
                            throw new IllegalStateException("LEGACY_2_1_11_BOOTSTRAP_INCOMPLETE: migration record changed");
                        }
                    }
                    connection.commit();
                    LOGGER.warn("Legacy 2.1.11 provenance bootstrap COMPLETED rows={} updatedNow={} batches={} "
                        + "resumed={} elapsedMs={} fingerprint={}; remove the migration.legacy211* acknowledgement "
                        + "once every backend has started", total, bootstrapped, batches, run.resumed(),
                        (System.nanoTime() - startedNanos) / 1_000_000L, run.plan().fingerprint());
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Legacy 2.1.11 provenance bootstrap failed; startup is refused and the "
                + "bootstrap can be resumed", error);
        }
    }

    private void initializeTables() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid VARCHAR(36) PRIMARY KEY,
                username VARCHAR(16) NOT NULL,

                -- インベントリデータ
                inventory LONGTEXT,
                pending_disconnect_items LONGTEXT NULL,
                enderchest LONGTEXT,
                armor LONGTEXT,
                offhand LONGTEXT,

                -- ステータスデータ
                health DOUBLE DEFAULT 20.0,
                food_level INT DEFAULT 20,
                saturation FLOAT DEFAULT 5.0,
                exhaustion FLOAT DEFAULT 0.0,
                air INT DEFAULT 300,

                -- 経験値
                experience_level INT DEFAULT 0,
                experience_points INT DEFAULT 0,
                experience_total FLOAT DEFAULT 0.0,
                experience_progress FLOAT NULL DEFAULT NULL,
                experience_points_into_level BIGINT NULL DEFAULT NULL,

                -- エフェクト
                effects LONGTEXT,

                -- 位置情報
                dimension VARCHAR(64) DEFAULT 'minecraft:overworld',
                pos_x DOUBLE DEFAULT 0.0,
                pos_y DOUBLE DEFAULT 64.0,
                pos_z DOUBLE DEFAULT 0.0,
                yaw FLOAT DEFAULT 0.0,
                pitch FLOAT DEFAULT 0.0,

                -- ゲーム状態
                gamemode VARCHAR(16) DEFAULT 'survival',
                is_flying BOOLEAN DEFAULT FALSE,
                allow_flying BOOLEAN DEFAULT FALSE,
                is_creative_flying BOOLEAN DEFAULT FALSE,

                -- プレイヤープロフィール
                display_name VARCHAR(32),
                skin_texture LONGTEXT,
                skin_signature LONGTEXT,

                -- 進捗・統計
                advancements LONGTEXT,
                statistics LONGTEXT,

                -- 特殊データ
                recipe_book LONGTEXT,
                selected_item_slot INT DEFAULT 0,

                -- メタデータ
                first_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                login_count INT DEFAULT 1,
                play_time_seconds BIGINT DEFAULT 0,

                -- バックアップ
                backup_data LONGTEXT,
                backup_timestamp TIMESTAMP NULL,

                INDEX idx_username (username),
                INDEX idx_last_login (last_login),
                INDEX idx_dimension (dimension)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.formatted(tableName);

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            LOGGER.info("Database table '{}' initialized successfully", tableName);
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database table", e);
            throw new RuntimeException(e);
        }
        verifyInnoDb(tableName);
        validateColumn(tableName, "uuid", "varchar", false, 36);
        validatePrimaryKey(tableName, "uuid");
        ensureExperienceProgressColumn();
        ensureExperiencePointsIntoLevelColumn();
        ensurePendingDisconnectItemsColumn();
        ensurePlayerDataColumn("advancements", "LONGTEXT NULL", "real advancement progress");
        ensurePlayerDataColumn("statistics", "LONGTEXT NULL", "real statistic values");
        // These columns contain authoritative serialized player state. A narrower
        // existing type could truncate a successful checkpoint even when the table
        // itself is transactional. Validate after additive migrations so supported
        // legacy schemas can receive the nullable columns before validation.
        for (String column : new String[] {
            "inventory", "pending_disconnect_items", "enderchest", "armor", "offhand",
            "effects", "advancements", "statistics", "recipe_book"
        }) {
            validateColumn(tableName, column, "longtext", true, 0);
        }
        validateColumn(tableName, "experience_points_into_level", "bigint", true, 0,
            false, null, true, null, null);
    }

    private void initializeCoordinationTable() {
        String createCoordinationTableSql = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid VARCHAR(36) NOT NULL,
                owner_server VARCHAR(64) NULL,
                owner_session CHAR(36) NULL,
                fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
                lease_until DATETIME(6) NULL,
                data_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
                recovery_required BOOLEAN NOT NULL DEFAULT FALSE,
                recovery_reason VARCHAR(64) NULL,
                recovery_required_at DATETIME(6) NULL,
                last_checkpoint_at DATETIME(6) NULL,
                updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
                PRIMARY KEY (uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.formatted(coordinationTableName);

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createCoordinationTableSql);
            LOGGER.info("Database coordination table '{}' initialized successfully", coordinationTableName);
            LOGGER.warn(
                "All writers for {} MUST use PlayerDataConnector 2.1.11+; older writers bypass "
                    + "RECOVERY_REQUIRED and may automatically restore an unsafe checkpoint",
                coordinationTableName);
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database coordination table", e);
            throw new RuntimeException(e);
        }
        verifyInnoDb(coordinationTableName);
        validateColumn(coordinationTableName, "uuid", "varchar", false, 36);
        validatePrimaryKey(coordinationTableName, "uuid");
        ensureColumn(coordinationTableName, "recovery_required", "BOOLEAN NOT NULL DEFAULT FALSE",
            "strict recovery flag");
        ensureColumn(coordinationTableName, "recovery_reason", "VARCHAR(64) NULL", "recovery reason");
        ensureColumn(coordinationTableName, "recovery_required_at", "DATETIME(6) NULL", "recovery timestamp");
        ensureColumn(coordinationTableName, "last_checkpoint_at", "DATETIME(6) NULL", "checkpoint timestamp");
        ensureColumn(coordinationTableName, "checkpoint_source_version", "VARCHAR(32) NULL", "checkpoint producer version (NULL means legacy)");
        ensureColumn(coordinationTableName, "automatic_recovery_attempts", "INT NOT NULL DEFAULT 0", "bounded recovery attempts");
        ensureColumn(coordinationTableName, "automatic_recovery_last_attempt_at", "DATETIME(6) NULL", "bounded recovery retry timestamp");
        validateColumn(coordinationTableName, "owner_server", "varchar", true, 64);
        validateColumn(coordinationTableName, "owner_session", "char", true, 36);
        validateColumn(coordinationTableName, "fencing_token", "bigint", false, 0,
            true, "0", true, null, null);
        validateColumn(coordinationTableName, "lease_until", "datetime", true, 0,
            null, null, false, 6, null);
        validateColumn(coordinationTableName, "data_revision", "bigint", false, 0,
            true, "0", true, null, null);
        validateColumn(coordinationTableName, "recovery_required", "tinyint", false, 0,
            false, "0", true, null, null);
        validateColumn(coordinationTableName, "recovery_reason", "varchar", true, 64,
            null, null, true, null, null);
        validateColumn(coordinationTableName, "recovery_required_at", "datetime", true, 0,
            null, null, true, 6, null);
        validateColumn(coordinationTableName, "last_checkpoint_at", "datetime", true, 0,
            null, null, true, 6, null);
        validateColumn(coordinationTableName, "updated_at", "timestamp", false, 0,
            null, "CURRENT_TIMESTAMP(6)", true, 6, "on update current_timestamp(6)");
        validateColumn(coordinationTableName, "checkpoint_source_version", "varchar", true, 32,
            null, null, true, null, null);
        validateColumn(coordinationTableName, "automatic_recovery_attempts", "int", false, 0,
            false, "0", true, null, null);
        validateColumn(coordinationTableName, "automatic_recovery_last_attempt_at", "datetime", true, 0,
            null, null, true, 6, null);
        ensureColumn(coordinationTableName, "clean_release_session", "CHAR(36) NULL", "final receipt session");
        ensureColumn(coordinationTableName, "clean_release_fence", "BIGINT UNSIGNED NULL", "final receipt fence");
        ensureColumn(coordinationTableName, "clean_release_revision", "BIGINT UNSIGNED NULL", "final receipt revision");
        ensureColumn(coordinationTableName, "clean_release_digest", "CHAR(64) NULL", "final receipt digest");
        ensureColumn(coordinationTableName, "clean_release_domains", "LONGTEXT NULL", "final receipt domain manifest");
        ensureColumn(coordinationTableName, "domain_provenance_revision", "BIGINT UNSIGNED NULL", "domain provenance data revision");
        ensureColumn(coordinationTableName, "domain_provenance_generation", "BIGINT UNSIGNED NULL", "domain provenance generation");
        ensureColumn(coordinationTableName, "domain_provenance_manifest", "LONGTEXT NULL", "domain provenance state manifest");
        validateColumn(coordinationTableName, "clean_release_session", "char", true, 36,
            null, null, true, null, null);
        validateColumn(coordinationTableName, "clean_release_fence", "bigint", true, 0,
            true, null, true, null, null);
        validateColumn(coordinationTableName, "clean_release_revision", "bigint", true, 0,
            true, null, true, null, null);
        validateColumn(coordinationTableName, "clean_release_digest", "char", true, 64,
            null, null, true, null, null);
        validateColumn(coordinationTableName, "clean_release_domains", "longtext", true, 0,
            null, null, true, null, null);
        validateColumn(coordinationTableName, "domain_provenance_revision", "bigint", true, 0,
            true, null, true, null, null);
        validateColumn(coordinationTableName, "domain_provenance_generation", "bigint", true, 0,
            true, null, true, null, null);
        validateColumn(coordinationTableName, "domain_provenance_manifest", "longtext", true, 0,
            null, null, true, null, null);
        migrateLegacyDomainProvenance();
        validateColumn(tableName, "advancements", "longtext", true, 0);
        validateColumn(tableName, "statistics", "longtext", true, 0);
        initializeRecoveryAuditTable();
    }

    private void initializeRecoveryAuditTable() {
        String table = getRecoveryAuditTableName();
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                operation_id CHAR(36) PRIMARY KEY,
                uuid CHAR(36) NOT NULL,
                actor VARCHAR(64) NOT NULL,
                resolution VARCHAR(32) NOT NULL,
                recovery_reason VARCHAR(64) NULL,
                previous_owner_server VARCHAR(64) NULL,
                previous_owner_session CHAR(36) NULL,
                previous_fence BIGINT UNSIGNED NOT NULL,
                previous_revision BIGINT UNSIGNED NOT NULL,
                resolved_fence BIGINT UNSIGNED NOT NULL,
                resolved_revision BIGINT UNSIGNED NOT NULL,
                resolved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                INDEX idx_uuid (uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.formatted(table);
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException error) {
            throw new IllegalStateException("Could not initialize recovery audit table '" + table + "'", error);
        }
        verifyInnoDb(table);
        ensureColumn(table, "unsafe_acknowledged", "BOOLEAN NOT NULL DEFAULT FALSE", "unsafe recovery acknowledgement");
        validatePrimaryKey(table, "operation_id");
        validateIndex(table, "idx_uuid", "uuid");
        validateColumn(table, "operation_id", "char", false, 36);
        validateColumn(table, "uuid", "char", false, 36);
        validateColumn(table, "actor", "varchar", false, 64);
        validateColumn(table, "resolution", "varchar", false, 32);
        validateColumn(table, "previous_fence", "bigint", false, 0, true, null, true, null, null);
        validateColumn(table, "previous_revision", "bigint", false, 0, true, null, true, null, null);
        validateColumn(table, "resolved_fence", "bigint", false, 0, true, null, true, null, null);
        validateColumn(table, "resolved_revision", "bigint", false, 0, true, null, true, null, null);
        validateColumn(table, "resolved_at", "datetime", false, 0,
            null, "CURRENT_TIMESTAMP(6)", true, 6, null);
        validateColumn(table, "unsafe_acknowledged", "tinyint", false, 0, false, "0", true, null, null);
        validateColumn(table, "recovery_reason", "varchar", true, 64, null, null, true, null, null);
        validateColumn(table, "previous_owner_server", "varchar", true, 64, null, null, true, null, null);
        validateColumn(table, "previous_owner_session", "char", true, 36, null, null, true, null, null);
        ensureColumn(table, "recovery_mode", "VARCHAR(32) NULL", "recovery policy mode");
        ensureColumn(table, "checkpoint_at", "DATETIME(6) NULL", "recovery checkpoint timestamp");
        ensureColumn(table, "source_version", "VARCHAR(32) NULL", "recovered checkpoint producer version");
        ensureColumn(table, "domain_provenance_before", "LONGTEXT NULL", "domain provenance audit before");
        ensureColumn(table, "domain_provenance_after", "LONGTEXT NULL", "domain provenance audit after");
        validateColumn(table, "source_version", "varchar", true, 32, null, null, true, null, null);
        validateColumn(table, "recovery_mode", "varchar", true, 32, null, null, true, null, null);
        validateColumn(table, "checkpoint_at", "datetime", true, 0, null, null, true, 6, null);
        validateColumn(table, "domain_provenance_before", "longtext", true, 0, null, null, true, null, null);
        validateColumn(table, "domain_provenance_after", "longtext", true, 0, null, null, true, null, null);
    }

    /**
     * Backfill only when an old clean receipt names the exact current data revision.
     * The old ownership fence is intentionally irrelevant: a recovery may advance
     * the fence while preserving the same PlayerData checkpoint. Missing/stale
     * receipts remain UNKNOWN and are rejected at acquire time.
     */
    private void migrateLegacyDomainProvenance() {
        record Candidate(String uuid, long revision, String legacyManifest) {}
        java.util.List<Candidate> candidates = new java.util.ArrayList<>();
        String select = "SELECT uuid,data_revision,clean_release_domains FROM " + coordinationTableName
            + " WHERE domain_provenance_manifest IS NULL AND clean_release_domains IS NOT NULL"
            + " AND clean_release_session IS NOT NULL AND clean_release_digest IS NOT NULL"
            + " AND clean_release_revision=data_revision AND data_revision>0";
        try (Connection connection = getConnection(); PreparedStatement query = prepareStatement(connection, select);
             var rows = query.executeQuery()) {
            while (rows.next()) candidates.add(new Candidate(rows.getString(1), rows.getLong(2), rows.getString(3)));
        } catch (SQLException error) {
            throw new IllegalStateException("Could not inspect legacy PlayerData domain provenance", error);
        }
        for (Candidate candidate : candidates) {
            DomainProvenance provenance = DomainProvenance.read(candidate.legacyManifest(), candidate.revision(), 1L,
                candidate.revision());
            String update = "UPDATE " + coordinationTableName
                + " SET domain_provenance_revision=?,domain_provenance_generation=1,domain_provenance_manifest=?"
                + " WHERE uuid=? AND domain_provenance_manifest IS NULL AND data_revision=?"
                + " AND clean_release_revision=? AND clean_release_domains=?";
            try (Connection connection = getConnection(); PreparedStatement statement = prepareStatement(connection, update)) {
                statement.setLong(1, candidate.revision());
                statement.setString(2, provenance.encode());
                statement.setString(3, candidate.uuid());
                statement.setLong(4, candidate.revision());
                statement.setLong(5, candidate.revision());
                statement.setString(6, candidate.legacyManifest());
                statement.executeUpdate(); // zero means another backend won the migration race
            } catch (SQLException error) {
                throw new IllegalStateException("Could not migrate legacy PlayerData domain provenance", error);
            }
        }
    }

    private void validateColumn(String table, String column, String type, boolean nullable, long length) {
        validateColumn(table, column, type, nullable, length, null, null, false, null, null);
    }

    private void validateColumn(String table, String column, String type, boolean nullable, long length,
        Boolean unsigned, String defaultValue, boolean validateDefault, Integer datetimePrecision,
        String requiredExtra) {
        try (Connection connection = getConnection(); PreparedStatement query = prepareStatement(connection,
                "SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,COLUMN_DEFAULT,"
                    + "DATETIME_PRECISION,EXTRA FROM information_schema.columns "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")) {
            query.setString(1, table);
            query.setString(2, column);
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("Incompatible schema for " + table + "." + column);
                }
                String columnType = row.getString(2);
                String extra = row.getString(7);
                boolean actualUnsigned = columnType != null
                    && columnType.toLowerCase(java.util.Locale.ROOT).contains("unsigned");
                boolean defaultMatches = !validateDefault
                    || java.util.Objects.equals(normalizeSchemaDefault(defaultValue),
                        normalizeSchemaDefault(row.getString(5)));
                boolean precisionMatches = datetimePrecision == null
                    || row.getInt(6) == datetimePrecision;
                boolean extraMatches = requiredExtra == null
                    || (extra != null && extra.toLowerCase(java.util.Locale.ROOT)
                        .contains(requiredExtra.toLowerCase(java.util.Locale.ROOT)));
                if (!type.equalsIgnoreCase(row.getString(1))
                        || nullable != "YES".equals(row.getString(3))
                        || (length > 0 && length != row.getLong(4))
                        || (unsigned != null && unsigned != actualUnsigned)
                        || !defaultMatches || !precisionMatches || !extraMatches) {
                    throw new IllegalStateException("Incompatible schema for " + table + "." + column);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot validate schema for " + table + "." + column, error);
        }
    }

    private static String normalizeSchemaDefault(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace("'", "");
        return normalized.replaceAll("\\s+", "");
    }

    private void validatePrimaryKey(String table, String expectedColumn) {
        String sql = "SELECT COLUMN_NAME FROM information_schema.statistics "
            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX";
        try (Connection connection = getConnection(); PreparedStatement query = prepareStatement(connection, sql)) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !expectedColumn.equalsIgnoreCase(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("Incompatible primary key for " + table
                        + "; expected only " + expectedColumn);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot validate primary key for " + table, error);
        }
    }

    private void validateIndex(String table, String index, String expectedColumn) {
        String sql = "SELECT COLUMN_NAME,NON_UNIQUE FROM information_schema.statistics "
            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX";
        try (Connection connection = getConnection(); PreparedStatement query = prepareStatement(connection, sql)) {
            query.setString(1, table);
            query.setString(2, index);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !expectedColumn.equalsIgnoreCase(rows.getString(1))
                        || rows.getInt(2) != 1 || rows.next()) {
                    throw new IllegalStateException("Incompatible index " + table + "." + index);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot validate index " + table + "." + index, error);
        }
    }

    private void verifyInnoDb(String table) {
        String sql = "SELECT ENGINE FROM information_schema.tables "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                        "Required database table '" + table + "' does not exist after initialization");
                }
                String engine = result.getString(1);
                if (engine == null || !"InnoDB".equalsIgnoreCase(engine)) {
                    throw new IllegalStateException(
                        "Database table '" + table + "' requires InnoDB but is "
                            + (engine == null ? "unknown" : engine));
                }
            }
        } catch (SQLException error) {
            // SQL messages can include connection details; expose only table and operation.
            throw new IllegalStateException(
                "Could not verify that database table '" + table + "' uses InnoDB", error);
        }
    }

    private void ensureExperienceProgressColumn() {
        ensurePlayerDataColumn(
            "experience_progress", "FLOAT NULL DEFAULT NULL AFTER experience_points",
            "legacy-compatible XP progress");
    }

    private void ensureExperiencePointsIntoLevelColumn() {
        ensurePlayerDataColumn(
            "experience_points_into_level", "BIGINT NULL DEFAULT NULL AFTER experience_progress",
            "exact XP points within level");
    }

    private void ensurePendingDisconnectItemsColumn() {
        ensurePlayerDataColumn(
            "pending_disconnect_items", "LONGTEXT NULL AFTER inventory",
            "durable disconnect cursor/crafting payload");
    }

    private void ensurePlayerDataColumn(String column, String definition, String description) {
        ensureColumn(tableName, column, definition, description);
    }

    private void ensureColumn(String table, String column, String definition, String description) {
        String checkSql = "SELECT 1 FROM information_schema.columns "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(checkSql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                if (result.next()) return;
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                "Could not inspect schema column '" + column + "' for database table '" + table + "'", error);
        }

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            LOGGER.info("Added {} column '{}' to table '{}'", description, column, table);
        } catch (SQLException error) {
            // Concurrent backends may both observe the old schema. Accept only a
            // duplicate-column race after confirming that the expected column exists.
            if (error.getErrorCode() != 1060 || !columnExists(table, column)) {
                throw new IllegalStateException(
                    "Could not add " + description + " column '" + column + "' to table '" + table + "'",
                    error);
            }
        }
    }

    private boolean columnExists(String table, String column) {
        String checkSql = "SELECT 1 FROM information_schema.columns "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(checkSql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
