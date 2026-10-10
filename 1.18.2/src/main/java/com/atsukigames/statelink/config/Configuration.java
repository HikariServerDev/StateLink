package com.atsukigames.statelink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;

public class Configuration {

    public boolean enable = true;

    public RecoveryConfig recovery = new RecoveryConfig();
    public static class RecoveryConfig {
        public boolean enabled = true;
        public String mode = "safe";
        public int retryIntervalSeconds = 5;
        public int maxWaitSeconds = 120;
        public int lastCheckpointDelaySeconds = 60;
        public boolean acknowledgeUnsafeRecovery = false;
        public int autoRecoverDelaySeconds = 5;
        public int maxCheckpointAgeSeconds = 30;
        public int maxAutoRecoveryAttempts = 12;
        public boolean acknowledgePotentialRollbackOrDuplication = false;
    }

    public MigrationConfig migration = new MigrationConfig();
    public static class MigrationConfig {
        /** One-time provenance bootstrap of an exact PlayerDataConnector 2.1.11 database. */
        public boolean legacy211Bootstrap = false;
        /** Operator asserts the 2.1.11 database is the authority for every legacy-synchronized enabled domain. */
        public boolean acknowledgeLegacy211DatabaseAsAuthority = false;
        /** Optional pin: must equal the fingerprint printed at startup when set. */
        public String acknowledgedLegacy211DomainFingerprint = "";
        /** disabled | adopt-first-login-local, for domains 2.1.11 stored only as placeholders. */
        public String legacy211NewDomainPolicy = "disabled";
        /**
         * Maximum checkpoint age for automatic recovery of the backlog that already existed in the
         * 2.1.11 database. Applies only while a row's durable checkpoint is still the one the
         * acknowledged bootstrap adopted; every checkpoint written by 2.2.x uses recovery.maxCheckpointAgeSeconds.
         */
        public int legacy211RecoveryMaxCheckpointAgeSeconds = 2_592_000;
        /** Bootstrap the other rows and leave undecodable legacy rows fail-closed instead of refusing startup. */
        public boolean legacy211QuarantineUndecodableRows = false;
        /** Deliberate cluster-wide domain change after a bootstrap: must equal the fingerprint printed at startup. */
        public String acknowledgeClusterDomainFingerprint = "";
        /** Must equal the markerId of the local disabled marker to re-enable StateLink after enable=false. */
        public String acknowledgeReactivationMarker = "";
    }

    public DatabaseConfig database = new DatabaseConfig();
    public static class DatabaseConfig {
        // Empty connection fields make a first-run template inert until the operator configures it.
        public String host = "";
        public int    port = 3306;
        public String name = "";
        public String username = "";
        public String password = "";
        public String tableName = "player_data";
        public boolean sslEnabled = false;
        public int maxConnections = 10;
        public int connectionTimeoutMs = 2500;
        public int socketTimeoutMs = 2500;
        /** JDBC statement timeout; MySQL Connector/J applies this per statement. */
        public int queryTimeoutMs = 2500;
        /** MySQL/InnoDB row-lock wait timeout. Kept below the default lease safety budget. */
        public int lockWaitTimeoutMs = 2000;
        /** Detailed monotonic DB/pool timing for isolated staging diagnostics. */
        public boolean diagnosticLogging = false;
    }

    public SyncConfig sync = new SyncConfig();
    public static class SyncConfig {
        // 基本データ
        public boolean inventory = true;
        public boolean enderchest = true;
        public boolean armor = true;
        public boolean offhand = true;
        public boolean health = true;
        public boolean food = true;
        public boolean experience = true;
        public boolean effects = true;

        // 追加データ
        public boolean position = true;          // Coordinates; paired with dimension.
        // NULL/missing preserves pre-2.2.3 aggregate position configuration.
        public Boolean dimension;
        public Boolean rotation;
        public boolean dimensionEnabled() { return dimension == null ? position : dimension; }
        public boolean rotationEnabled() { return rotation == null ? position : rotation; }
        public boolean gamemode = true;          // ゲームモード・飛行状態
        public boolean advancements = true;      // 進捗・実績
        public boolean statistics = true;        // 統計データ
        public boolean playerProfile = true;     // スキン・名前
        @com.google.gson.annotations.SerializedName(value = "recipeBook", alternate = {"recipes"})
        public boolean recipeBook = true;        // レシピブック

        // タイミング設定
        /**
         * 旧設定との互換性のために残しているが、現在の実装では使用しない。
         * ownership の状態を確認する条件待ちと固定待機は別物であるため、JOIN時にsleepしてはいけない。
         */
        @Deprecated
        public int loginDelayMs = 0;
        public String serverId = "server-unknown";
        public String coordinationTableName = "";
        public long leaseDurationMs = 5000;
        public long leaseRenewIntervalMs = 1500;
        public long handoffRetryInitialMs = 10;
        public long handoffRetryMaxMs = 250;
        public long joinSyncTimeoutMs = 15000;
        public boolean syncOnDimensionChange = true;
    }

    public SaveConfig save = new SaveConfig();
    public static class SaveConfig {
        public boolean saveOnDisconnect = true;
        public boolean saveOnDeath = true;
        public boolean saveOnDimensionChange = true;
        public boolean periodicSaveEnabled = true;
        @Deprecated public int saveIntervalMinutes = 5;
        public int saveIntervalSeconds = 10;
        public boolean saveOnlyWhenDirty = true;
        public boolean backupOldData = true;
        public int     maxBackupsPerPlayer = 3;
        public long finalFlushTimeoutMs = 10000;
        public long shutdownFlushTimeoutMs = 10000;
    }

    public SecurityConfig security = new SecurityConfig();
    public static class SecurityConfig {
        public boolean enableWhitelist = false;
        public boolean requireSameIP = false;
        public int     maxLoginAttemptsPerMinute = 5;
        public boolean logDataAccess = true;
        public boolean encryptSensitiveData = false;
    }

    /* ---------- JSON (de)serialize ---------- */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern HOST = Pattern.compile(
        "(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\])");

    public static Configuration load(Path path) {
        if (Files.notExists(path)) {
            Configuration template = new Configuration();
            template.save(path);
            throw new ConfigurationException(
                "Created an inactive configuration template at " + path
                    + "; set database.host, database.name, database.username, and database.password before startup");
        }
        if (!Files.exists(path)) {
            throw new ConfigurationException("Cannot determine whether configuration exists at " + path);
        }

        final String contents;
        try {
            if (!Files.isRegularFile(path)) {
                throw new ConfigurationException("Configuration path is not a regular file: " + path);
            }
            contents = Files.readString(path);
        } catch (ConfigurationException error) {
            throw error;
        } catch (IOException | SecurityException error) {
            // Do not attach the underlying exception: OS/JDBC diagnostics may contain secrets.
            throw new ConfigurationException("Cannot read configuration at " + path);
        }

        final Configuration loaded;
        try {
            loaded = GSON.fromJson(contents, Configuration.class);
            var parsed = JsonParser.parseString(contents).getAsJsonObject();
            if (parsed.has("sync") && parsed.get("sync").isJsonObject()) {
                var fields = parsed.getAsJsonObject("sync");
                for (String field : new String[]{"inventory", "enderchest", "armor", "offhand", "health", "food",
                        "experience", "effects", "position", "dimension", "rotation", "gamemode", "advancements",
                        "statistics", "playerProfile", "recipeBook", "recipes"}) {
                    if (fields.has(field) && (!fields.get(field).isJsonPrimitive()
                            || !fields.get(field).getAsJsonPrimitive().isBoolean()))
                        throw new ConfigurationException("Invalid sync." + field + ": expected boolean");
                }
                if (fields.has("recipes") && fields.has("recipeBook")
                        && fields.get("recipes").getAsBoolean() != fields.get("recipeBook").getAsBoolean())
                    throw new ConfigurationException("Conflicting sync.recipes and sync.recipeBook");
            }
            if (loaded != null && loaded.save != null) {
                var root = JsonParser.parseString(contents).getAsJsonObject();
                if (root.has("save") && root.get("save").isJsonObject()) {
                    var values = root.getAsJsonObject("save");
                    if (values.has("saveIntervalSeconds")) {
                        loaded.save.saveIntervalSeconds = values.get("saveIntervalSeconds").getAsBigDecimal().intValueExact();
                    } else if (values.has("saveIntervalMinutes")) {
                        loaded.save.saveIntervalSeconds = Math.multiplyExact(
                            values.get("saveIntervalMinutes").getAsBigDecimal().intValueExact(), 60);
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException | ArithmeticException | UnsupportedOperationException | NumberFormatException | NullPointerException error) {
            // Gson parse diagnostics can quote source text, which may include credentials.
            throw new ConfigurationException("Malformed JSON in configuration at " + path);
        }
        if (loaded == null) {
            throw new ConfigurationException("Configuration at " + path + " must contain a JSON object");
        }
        loaded.normalize();
        return loaded;
    }

    public void save(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException | SecurityException error) {
            throw new ConfigurationException("Cannot write configuration template at " + path);
        }
    }

    /** Validate configuration without silently redirecting the database or repairing unsafe values. */
    public void normalize() {
        // Disabled StateLink never consumes DB settings or registers any runtime writer.
        // JSON parsing is still strict; do not rewrite existing files.
        if (!enable) return;
        if (sync != null && sync.position != sync.dimensionEnabled()) {
            throw new ConfigurationException("sync.position and sync.dimension must be enabled together; rotation may be independent");
        }
        require(database != null, "database", "section is required");
        require(sync != null, "sync", "section is required");
        require(save != null, "save", "section is required");
        require(security != null, "security", "section is required");
        require(recovery != null, "recovery", "section is required");
        if (migration == null) migration = new MigrationConfig();
        require("disabled".equals(migration.legacy211NewDomainPolicy)
                || "adopt-first-login-local".equals(migration.legacy211NewDomainPolicy),
            "migration.legacy211NewDomainPolicy", "must be disabled or adopt-first-login-local");
        require(migration.legacy211Bootstrap == migration.acknowledgeLegacy211DatabaseAsAuthority,
            "migration.acknowledgeLegacy211DatabaseAsAuthority",
            "must be set together with migration.legacy211Bootstrap");
        require(migration.legacy211RecoveryMaxCheckpointAgeSeconds > 0,
            "migration.legacy211RecoveryMaxCheckpointAgeSeconds", "must be positive");
        require(migration.acknowledgeClusterDomainFingerprint == null
                || migration.acknowledgeClusterDomainFingerprint.isBlank()
                || migration.acknowledgeClusterDomainFingerprint.matches("[0-9a-f]{64}"),
            "migration.acknowledgeClusterDomainFingerprint", "must be blank or a lowercase SHA-256 hex value");
        require(migration.acknowledgedLegacy211DomainFingerprint == null
                || migration.acknowledgedLegacy211DomainFingerprint.isBlank()
                || migration.acknowledgedLegacy211DomainFingerprint.matches("[0-9a-f]{64}"),
            "migration.acknowledgedLegacy211DomainFingerprint", "must be blank or a lowercase SHA-256 hex value");
        require("manual".equals(recovery.mode) || "safe".equals(recovery.mode)
                || "automatic".equals(recovery.mode) || "last_checkpoint".equals(recovery.mode),
            "recovery.mode", "must be manual, safe, automatic or legacy last_checkpoint");
        require(!"automatic".equals(recovery.mode) || recovery.acknowledgePotentialRollbackOrDuplication,
            "recovery.acknowledgePotentialRollbackOrDuplication", "must explicitly acknowledge automatic recovery rollback/duplication risk");
        require(recovery.autoRecoverDelaySeconds >= 0, "recovery.autoRecoverDelaySeconds", "must be zero or greater");
        require(recovery.maxCheckpointAgeSeconds > 0, "recovery.maxCheckpointAgeSeconds", "must be positive");
        require(recovery.maxAutoRecoveryAttempts > 0 && recovery.maxAutoRecoveryAttempts <= 1000,
            "recovery.maxAutoRecoveryAttempts", "must be between 1 and 1000");
        require(!"last_checkpoint".equals(recovery.mode) || recovery.acknowledgeUnsafeRecovery,
            "recovery.acknowledgeUnsafeRecovery", "must explicitly acknowledge unsafe last_checkpoint recovery");
        require(recovery.retryIntervalSeconds > 0, "recovery.retryIntervalSeconds", "must be positive");
        require(recovery.maxWaitSeconds >= recovery.retryIntervalSeconds,
            "recovery.maxWaitSeconds", "must be at least retryIntervalSeconds");
        require(recovery.lastCheckpointDelaySeconds >= 0,
            "recovery.lastCheckpointDelaySeconds", "must be zero or greater");

        require(database.host != null && HOST.matcher(database.host).matches(),
            "database.host", "must be a non-empty hostname or IP address");
        require(database.name != null && isSafeIdentifier(database.name),
            "database.name", "must match [A-Za-z0-9_]+");
        require(database.username != null && !database.username.isBlank(), "database.username", "is required");
        require(database.password != null && !database.password.isEmpty(), "database.password", "is required");
        require(database.port > 0 && database.port <= 65535, "database.port", "must be between 1 and 65535");
        require(database.maxConnections >= 2, "database.maxConnections", "must be at least 2");
        require(database.tableName != null && isSafeIdentifier(database.tableName),
            "database.tableName", "must match [A-Za-z0-9_]+");
        require(sync.coordinationTableName == null || sync.coordinationTableName.isBlank()
                || isSafeIdentifier(sync.coordinationTableName),
            "sync.coordinationTableName", "must be blank or match [A-Za-z0-9_]+");
        require(sync.serverId != null && !sync.serverId.isBlank() && sync.serverId.length() <= 64,
            "sync.serverId", "must contain 1 to 64 characters");
        require(sync.inventory || !sync.armor, "sync.armor", "must be false when sync.inventory=false");
        require(sync.inventory || !sync.offhand, "sync.offhand", "must be false when sync.inventory=false");
        require(sync.inventory || !sync.enderchest, "sync.enderchest", "must be false when sync.inventory=false");

        require(database.connectionTimeoutMs >= 250 && database.connectionTimeoutMs <= 60_000,
            "database.connectionTimeoutMs", "must be between 250 and 60000");
        require(database.socketTimeoutMs >= 500 && database.socketTimeoutMs <= 60_000,
            "database.socketTimeoutMs", "must be between 500 and 60000");
        require(database.queryTimeoutMs >= 1_000 && database.queryTimeoutMs <= 60_000,
            "database.queryTimeoutMs", "must be between 1000 and 60000");
        require(database.lockWaitTimeoutMs >= 1_000 && database.lockWaitTimeoutMs <= 60_000,
            "database.lockWaitTimeoutMs", "must be between 1000 and 60000");
        require(sync.leaseDurationMs >= 3_000 && sync.leaseDurationMs <= 300_000,
            "sync.leaseDurationMs", "must be between 3000 and 300000");
        require(sync.leaseRenewIntervalMs >= 100
                && sync.leaseRenewIntervalMs < sync.leaseDurationMs,
            "sync.leaseRenewIntervalMs", "must be at least 100 and less than leaseDurationMs");
        long safetyBudget = sync.leaseDurationMs - sync.leaseRenewIntervalMs - 250L;
        require(safetyBudget >= 1_000L, "sync.leaseRenewIntervalMs", "leaves less than 1000ms of local safety budget");
        require(database.connectionTimeoutMs <= safetyBudget, "database.connectionTimeoutMs",
            "must not exceed the lease safety budget");
        require(database.socketTimeoutMs <= safetyBudget, "database.socketTimeoutMs",
            "must not exceed the lease safety budget");
        require(database.queryTimeoutMs <= safetyBudget, "database.queryTimeoutMs",
            "must not exceed the lease safety budget");
        long lockWaitBudgetMs = (safetyBudget / 1_000L) * 1_000L;
        require(database.lockWaitTimeoutMs <= lockWaitBudgetMs, "database.lockWaitTimeoutMs",
            "must not exceed the lease safety budget in whole seconds");
        require(sync.handoffRetryInitialMs >= 1 && sync.handoffRetryInitialMs <= 10_000,
            "sync.handoffRetryInitialMs", "must be between 1 and 10000");
        require(sync.handoffRetryMaxMs >= sync.handoffRetryInitialMs && sync.handoffRetryMaxMs <= 60_000,
            "sync.handoffRetryMaxMs", "must be at least handoffRetryInitialMs and at most 60000");
        require(sync.joinSyncTimeoutMs >= 1_000 && sync.joinSyncTimeoutMs <= 300_000,
            "sync.joinSyncTimeoutMs", "must be between 1000 and 300000");
        require(save.saveIntervalMinutes >= 0, "save.saveIntervalMinutes", "must be zero or greater");
        require(save.saveIntervalSeconds >= 0 && save.saveIntervalSeconds <= 86400,
            "save.saveIntervalSeconds", "must be between zero and 86400 (zero disables periodic checkpoints)");
        require(save.finalFlushTimeoutMs >= 1_000, "save.finalFlushTimeoutMs", "must be at least 1000");
        require(save.shutdownFlushTimeoutMs >= 1_000, "save.shutdownFlushTimeoutMs", "must be at least 1000");
    }

    public String getCoordinationTableName() {
        String configured = sync == null ? "" : sync.coordinationTableName;
        if (configured != null && !configured.isBlank()) return configured;
        return database.tableName + "_sync_state";
    }

    public static boolean isSafeIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static void require(boolean condition, String field, String reason) {
        if (!condition) throw new ConfigurationException("Invalid configuration field " + field + ": " + reason);
    }
}
