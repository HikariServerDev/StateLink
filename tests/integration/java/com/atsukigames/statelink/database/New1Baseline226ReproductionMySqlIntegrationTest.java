package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic reproduction of review finding NEW-1 with the two released artifacts themselves:
 * a database created and written by 2.1.11, then opened by the reviewed 2.2.6 database layer.
 * It documents the baseline defect; the fix is covered by Legacy211MigrationMySqlIntegrationTest.
 */
class New1Baseline226ReproductionMySqlIntegrationTest {
    private static final Path JAR_226 = Path.of("../tests/fixtures/legacy/PlayerDataConnector-2.2.6.jar");
    private static final String SHA_226 = "c444bdd157f1243d360c090c1108ea719e9410522b7a9cc7c487bd28e7c76d4a";
    private static final String PACKAGE = "com.atsukigames.playerdataconnector.";

    @Test void released226QuarantinesEvery211PlayerAndAutomaticRecoveryLoopsBackIntoQuarantine() throws Exception {
        Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
        config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
        config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
        config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
        config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
        config.maxConnections = 4;
        config.tableName = "pdc_new1_" + UUID.randomUUID().toString().replace("-", "");
        String coord = config.tableName + "_sync_state";
        String audit = DatabaseManager.recoveryAuditTableName(coord);
        UUID clean = UUID.randomUUID(), backlog = UUID.randomUUID();
        String jdbc = "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.name
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo";
        try {
            try (Legacy211Runtime legacy = new Legacy211Runtime(config, coord)) {
                Object ownership = legacy.acquire(clean, "legacy-s1", 5_000);
                legacy.save(ownership, clean, "clean", Legacy211Runtime.items("inventory-clean"), 20, 20, 0, true);
                ownership = legacy.acquire(backlog, "legacy-s1", 5_000);
                legacy.save(ownership, backlog, "backlog", Legacy211Runtime.items("inventory-backlog"), 20, 20, 0, false);
                legacy.markRecoveryRequired(ownership, "LOCAL_AUTHORITY_LOST");
            }
            try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement()) {
                s.execute("UPDATE " + coord + " SET lease_until=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6)) WHERE uuid='" + backlog + "'");
            }

            byte[] bytes = Files.readAllBytes(JAR_226);
            assertEquals(SHA_226, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            try (URLClassLoader loader = new URLClassLoader(new URL[] {JAR_226.toUri().toURL()}, getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith(PACKAGE)) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            }) {
                Class<?> configClass = Class.forName(PACKAGE + "config.Configuration$DatabaseConfig", true, loader);
                Object oldConfig = configClass.getConstructor().newInstance();
                for (String field : new String[] {"host", "port", "name", "username", "password", "tableName", "maxConnections"}) {
                    configClass.getField(field).set(oldConfig, Configuration.DatabaseConfig.class.getField(field).get(config));
                }
                Class<?> dbClass = Class.forName(PACKAGE + "database.DatabaseManager", true, loader);
                // 2.2.6 starts on the 2.1.11 database with no warning and no bootstrap.
                Object db = dbClass.getConstructor(configClass, String.class).newInstance(oldConfig, coord);
                try {
                    Class<?> repoClass = Class.forName(PACKAGE + "database.PlayerDataRepository", true, loader);
                    Object repo = repoClass.getConstructor(dbClass).newInstance(db);
                    Class<?> syncClass = Class.forName(PACKAGE + "config.Configuration$SyncConfig", true, loader);
                    Object sync = syncClass.getConstructor().newInstance();
                    Method acquire = repoClass.getMethod("acquireOwnershipAndLoad", UUID.class, String.class, UUID.class, long.class, syncClass);

                    Object result = acquire.invoke(repo, clean, "s1", UUID.randomUUID(), 5_000L, sync);
                    assertEquals("RECOVERY_REQUIRED", String.valueOf(result.getClass().getMethod("status").invoke(result)),
                        "NEW-1: a cleanly released 2.1.11 player is quarantined by 2.2.6");
                    assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", reason(jdbc, config, coord, clean));

                    // The backlog is resolved by automatic recovery and immediately quarantined again.
                    Method automatic = null;
                    for (Method candidate : repoClass.getMethods()) {
                        if (candidate.getName().equals("resolveAutomaticLastCheckpoint") && candidate.getParameterCount() == 10) automatic = candidate;
                    }
                    long fence = number(jdbc, config, "SELECT fencing_token FROM " + coord + " WHERE uuid='" + backlog + "'");
                    long revision = number(jdbc, config, "SELECT data_revision FROM " + coord + " WHERE uuid='" + backlog + "'");
                    Object resolved = automatic.invoke(repo, backlog, fence, revision, UUID.randomUUID(), "auto:s1", 0, 86_400, 12, 5, true);
                    assertEquals("RESOLVED", String.valueOf(resolved.getClass().getMethod("status").invoke(resolved)));
                    result = acquire.invoke(repo, backlog, "s1", UUID.randomUUID(), 5_000L, sync);
                    assertEquals("RECOVERY_REQUIRED", String.valueOf(result.getClass().getMethod("status").invoke(result)),
                        "NEW-1: recovery -> acquire -> quarantine loop");
                    assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", reason(jdbc, config, coord, backlog));
                } finally {
                    dbClass.getMethod("close").invoke(db);
                }
            }
        } finally {
            try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + audit);
                s.execute("DROP TABLE IF EXISTS " + coord);
                s.execute("DROP TABLE IF EXISTS " + config.tableName);
            }
        }
    }

    private static String reason(String jdbc, Configuration.DatabaseConfig config, String coord, UUID uuid) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT recovery_reason FROM " + coord + " WHERE uuid='" + uuid + "'")) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static long number(String jdbc, Configuration.DatabaseConfig config, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
