package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rename from PlayerDataConnector to StateLink preserves the shared data/coordination schema;
 * StateLink adds only its documented nullable 2.3.0 verified-state attestation columns.
 * These tests run the database layer of the RELEASED PlayerDataConnector 2.2.9 artifact and the
 * current StateLink classes against the same tables. 2.3.0 adds strict nullable attestation columns;
 * the old artifact must therefore fail closed on restart after upgrade, so rolling mixed-version
 * operation and jar-only rollback are not supported.
 */
@EnabledIfEnvironmentVariable(named = "STATELINK_TEST_MYSQL_DATABASE", matches = ".+")
class PlayerDataConnector229InteropMySqlIntegrationTest {
    private static final Path JAR_229 = Path.of("../staging-results/2026-10-05/2.2.9/artifact/PlayerDataConnector-2.2.9.jar");
    private static final String SHA_229 = "a1cd64393a391d3377b4427608f82852b00e633ee0d0a88f2ea0222b555d231a";
    private static final String LEGACY_PACKAGE = "com.atsukigames.playerdataconnector.";
    private static final String CURRENT_PACKAGE = "com.atsukigames.statelink.";
    private static final long LEASE_MS = 5_000L;

    private Configuration.DatabaseConfig config;
    private String coordination;
    private String jdbc;
    private URLClassLoader legacyLoader;
    private final List<Backend> open = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** One backend's database layer, driven reflectively so both generations use identical test code. */
    private final class Backend implements AutoCloseable {
        final String name;
        private final ClassLoader loader;
        private final String pkg;
        private final Object db, repository, sync;
        private final Class<?> repositoryClass, syncClass, dataClass, ownershipClass;

        Backend(String name, ClassLoader loader, String pkg) throws Exception {
            this.name = name; this.loader = loader; this.pkg = pkg;
            Class<?> configClass = type("config.Configuration$DatabaseConfig");
            Object copy = configClass.getConstructor().newInstance();
            for (String field : new String[] {"host", "port", "name", "username", "password", "tableName", "maxConnections"}) {
                configClass.getField(field).set(copy, Configuration.DatabaseConfig.class.getField(field).get(config));
            }
            Class<?> dbClass = type("database.DatabaseManager");
            db = unwrap(() -> dbClass.getConstructor(configClass, String.class).newInstance(copy, coordination));
            repositoryClass = type("database.PlayerDataRepository");
            repository = repositoryClass.getConstructor(dbClass).newInstance(db);
            syncClass = type("config.Configuration$SyncConfig");
            sync = syncClass.getConstructor().newInstance();
            dataClass = type("database.PlayerDataRepository$CompletePlayerData");
            ownershipClass = type("database.PlayerDataRepository$Ownership");
            open.add(this);
        }

        Class<?> type(String simple) throws ClassNotFoundException { return Class.forName(pkg + simple, true, loader); }

        Object acquire(UUID uuid) throws Exception {
            Method acquire = repositoryClass.getMethod("acquireOwnershipAndLoad",
                UUID.class, String.class, UUID.class, long.class, syncClass);
            return unwrap(() -> acquire.invoke(repository, uuid, name, UUID.randomUUID(), LEASE_MS, sync));
        }

        String status(Object result) throws Exception { return String.valueOf(call(result, "status")); }
        Object ownership(Object result) throws Exception { return call(result, "ownership"); }
        long revision(Object result) throws Exception { return (Long) call(ownership(result), "dataRevision"); }
        long fence(Object result) throws Exception { return (Long) call(ownership(result), "fencingToken"); }
        String ownerServer(Object result) throws Exception { return (String) call(result, "currentOwnerServer"); }

        /** Null when the acquired row has no player data yet. */
        String inventory(Object result) throws Exception {
            Optional<?> data = (Optional<?>) call(result, "data");
            return data.isEmpty() ? null : (String) dataClass.getField("inventoryJson").get(data.get());
        }

        Object data(UUID uuid, String username, String inventory) throws Exception {
            for (Constructor<?> constructor : dataClass.getConstructors()) {
                if (constructor.getParameterCount() != 35) continue;
                return constructor.newInstance(uuid, username, inventory, "[ender]", "[armor]", "[offhand]",
                    20.0, 20, 5.0F, 0.0F, 300, 0, 0, 0.0F, null, "[]",
                    "minecraft:overworld", 0.0, 64.0, 0.0, 0.0F, 0.0F, "survival", false, false, false,
                    username, null, null, "{}", "{}", "{}", 0, 0L, 1);
            }
            throw new IllegalStateException("no 35-argument CompletePlayerData constructor in " + name);
        }

        /** Returns the save status name; a final flush also releases ownership in the same commit. */
        String save(Object acquired, UUID uuid, String inventory, boolean finalFlush) throws Exception {
            Method save = repositoryClass.getMethod("saveCompletePlayerData",
                dataClass, ownershipClass, syncClass, long.class, boolean.class);
            Object result = unwrap(() -> save.invoke(repository, data(uuid, "interop", inventory),
                ownership(acquired), sync, LEASE_MS, finalFlush));
            return String.valueOf(call(result, "status"));
        }

        String staticName(String method, String argument) throws Exception {
            return (String) type("database.DatabaseManager").getMethod(method, String.class).invoke(null, argument);
        }

        @Override public void close() throws Exception {
            open.remove(this);
            db.getClass().getMethod("close").invoke(db);
        }
    }

    private interface Reflective { Object run() throws Exception; }

    private static Object unwrap(Reflective call) throws Exception {
        try {
            return call.run();
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        Method accessor = target.getClass().getMethod(method);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }

    private Backend playerDataConnector(String name) throws Exception { return new Backend(name, legacyLoader, LEGACY_PACKAGE); }
    private Backend stateLink(String name) throws Exception { return new Backend(name, getClass().getClassLoader(), CURRENT_PACKAGE); }

    @BeforeEach void setUp() throws Exception {
        config = new Configuration.DatabaseConfig();
        config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
        config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
        config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
        config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
        config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
        config.maxConnections = 4;
        config.tableName = "pdc_interop_" + UUID.randomUUID().toString().replace("-", "");
        coordination = config.tableName + "_sync_state";
        jdbc = "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.name
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo";

        byte[] bytes = Files.readAllBytes(JAR_229);
        assertEquals(SHA_229, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            "the fixture must be the released PlayerDataConnector 2.2.9 artifact");
        legacyLoader = new URLClassLoader(new URL[] {JAR_229.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(LEGACY_PACKAGE)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
    }

    @AfterEach void tearDown() throws Exception {
        for (Backend runtime : new ArrayList<>(open)) runtime.close();
        legacyLoader.close();
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement()) {
            for (String table : tables()) s.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private List<String> tables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                 + "AND table_name LIKE '" + config.tableName + "%' ORDER BY table_name")) {
            while (r.next()) tables.add(r.getString(1));
        }
        return tables;
    }

    /** Column names, types and nullability of every table, in order: the schema as a database sees it. */
    private String schemaFingerprint() throws Exception {
        StringBuilder text = new StringBuilder();
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT table_name, column_name, column_type, is_nullable, column_default, extra "
                 + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE '"
                 + config.tableName + "%' ORDER BY table_name, ordinal_position")) {
            while (r.next()) {
                text.append(r.getString(1).substring(config.tableName.length())).append('|');
                for (int column = 2; column <= 6; column++) text.append(r.getString(column)).append('|');
                text.append('\n');
            }
        }
        return text.toString();
    }

    private String schemaFingerprintWithoutVerifiedStateColumns() throws Exception {
        StringBuilder result = new StringBuilder();
        for (String line : schemaFingerprint().split("\\n")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length > 1 && SchemaGeneration.VERIFIED_STATE_COLUMNS.contains(fields[1])) continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private void assertVerifiedStateColumns() throws Exception {
        record Definition(String type, int precision) {}
        var expected = java.util.Map.ofEntries(
            java.util.Map.entry("checkpoint_state_digest", new Definition("char(64)", -1)),
            java.util.Map.entry("verified_state_server", new Definition("varchar(64)", -1)),
            java.util.Map.entry("verified_state_session", new Definition("char(36)", -1)),
            java.util.Map.entry("verified_state_fence", new Definition("bigint unsigned", -1)),
            java.util.Map.entry("verified_state_revision", new Definition("bigint unsigned", -1)),
            java.util.Map.entry("verified_state_provenance_generation", new Definition("bigint unsigned", -1)),
            java.util.Map.entry("verified_state_provenance_manifest", new Definition("longtext", -1)),
            java.util.Map.entry("verified_state_domains", new Definition("longtext", -1)),
            java.util.Map.entry("verified_state_digest", new Definition("char(64)", -1)),
            java.util.Map.entry("verified_state_at", new Definition("datetime(6)", 6)));
        assertEquals(SchemaGeneration.VERIFIED_STATE_COLUMNS, expected.keySet());
        String coordinationTable = coordination;
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password);
             var query = c.prepareStatement("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,DATETIME_PRECISION "
                 + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? "
                 + "AND COLUMN_NAME IN (" + String.join(",", java.util.Collections.nCopies(expected.size(), "?")) + ")")) {
            query.setString(1, coordinationTable);
            int index = 2;
            for (String name : expected.keySet()) query.setString(index++, name);
            try (ResultSet rows = query.executeQuery()) {
                java.util.Map<String, Definition> actual = new java.util.HashMap<>();
                while (rows.next()) {
                    assertEquals("YES", rows.getString("IS_NULLABLE"), rows.getString("COLUMN_NAME") + " must be nullable");
                    assertNull(rows.getString("COLUMN_DEFAULT"), rows.getString("COLUMN_NAME") + " must default to NULL");
                    int precision = rows.getInt("DATETIME_PRECISION");
                    if (rows.wasNull()) precision = -1;
                    actual.put(rows.getString("COLUMN_NAME"), new Definition(
                        rows.getString("COLUMN_TYPE").toLowerCase(java.util.Locale.ROOT), precision));
                }
                assertEquals(expected, actual);
            }
        }
    }

    @Test void theLegacyArtifactReallyIsADifferentCodeBase() throws Exception {
        try (Backend legacy = playerDataConnector("pdc")) {
            assertNotSame(DatabaseManager.class, legacy.type("database.DatabaseManager"));
            assertSame(legacyLoader, legacy.type("database.DatabaseManager").getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> legacyLoader.loadClass(LEGACY_PACKAGE + "config.LegacyNameMigration"));
        }
    }

    @Test void bothGenerationsDeriveTheSameSharedIdentifiers() throws Exception {
        String longTable = "x".repeat(60);
        try (Backend legacy = playerDataConnector("pdc")) {
            for (String table : new String[] {coordination, longTable}) {
                assertEquals(legacy.staticName("migrationTableName", table), DatabaseManager.migrationTableName(table));
                assertEquals(legacy.staticName("recoveryAuditTableName", table), DatabaseManager.recoveryAuditTableName(table));
            }
        }
        // Renaming these would split a mixed cluster into two sets of bookkeeping tables.
        assertTrue(DatabaseManager.migrationTableName(longTable).startsWith("pdc_migration_"));
        assertTrue(DatabaseManager.recoveryAuditTableName(longTable).startsWith("pdc_rec_audit_"));
    }

    @Test void stateLinkAddsOnlyNullableVerifiedStateColumnsToTheSharedSchema() throws Exception {
        try (Backend legacy = playerDataConnector("pdc")) { assertNotNull(legacy); }
        List<String> legacyTables = tables();
        String legacySchema = schemaFingerprint();
        assertFalse(legacySchema.isEmpty());
        try (Connection c = DriverManager.getConnection(jdbc, config.username, config.password); Statement s = c.createStatement()) {
            for (String table : legacyTables) s.execute("DROP TABLE " + table);
        }
        try (Backend current = stateLink("sl")) { assertNotNull(current); }
        assertEquals(legacyTables, tables());
        assertEquals(legacySchema, schemaFingerprintWithoutVerifiedStateColumns());
        assertVerifiedStateColumns();
    }

    @Test void stateLinkStartsOnAPlayerDataConnectorDatabaseWithIdempotentAttestationMigration() throws Exception {
        UUID player = UUID.randomUUID();
        try (Backend legacy = playerDataConnector("pdc-s1")) {
            Object acquired = legacy.acquire(player);
            assertEquals("ACQUIRED", legacy.status(acquired));
            assertEquals("COMMITTED", legacy.save(acquired, player, "[written-by-pdc]", true));
        }
        List<String> tablesBefore = tables();
        String schemaBefore = schemaFingerprintWithoutVerifiedStateColumns();
        try (Backend current = stateLink("sl-s1")) {
            // No migration flag, no acknowledgement: this is a plain restart as far as the database is concerned.
            assertEquals(SchemaGeneration.Generation.CURRENT_PROVENANCE,
                ((DatabaseManager) current.db).detectedGeneration().generation());
            assertEquals(tablesBefore, tables());
            assertEquals(schemaBefore, schemaFingerprintWithoutVerifiedStateColumns());
            assertVerifiedStateColumns();
        }
    }

    @Test void dataSurvivesUpgradeAndOldArtifactFailsClosedAfterSchemaUpgrade() throws Exception {
        UUID player = UUID.randomUUID();
        try (Backend legacy = playerDataConnector("pdc-s1")) {
            Object acquired = legacy.acquire(player);
            assertNull(legacy.inventory(acquired), "a new player has no data yet");
            assertEquals("COMMITTED", legacy.save(acquired, player, "[written-by-pdc]", true));
        }
        try (Backend current = stateLink("sl-s1")) {
            Object acquired = current.acquire(player);
            assertEquals("ACQUIRED", current.status(acquired), "a cleanly released player must load, not be quarantined");
            assertEquals("[written-by-pdc]", current.inventory(acquired));
            assertEquals("COMMITTED", current.save(acquired, player, "[written-by-statelink]", true));
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> playerDataConnector("pdc-after-upgrade"),
            "2.2.9 must not silently run against a schema whose attestation semantics it does not understand");
        assertTrue(refused.getMessage().contains("UNKNOWN_SCHEMA"));
        try (Backend current = stateLink("sl-s1")) {
            Object acquired = current.acquire(player);
            assertEquals("ACQUIRED", current.status(acquired));
            assertEquals("[written-by-statelink]", current.inventory(acquired),
                "failed old-version startup must not mutate or erase PlayerData");
        }
    }

    @Test void concurrentStateLinkBackendsStartingTogetherOnAnEmptyDatabaseAreSerialized() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Backend>> starting = new ArrayList<>();
            for (int backend = 0; backend < 6; backend++) {
                final int index = backend;
                starting.add(pool.submit(() -> {
                    start.await();
                    return stateLink("sl-" + index);
                }));
            }
            start.countDown();
            List<Backend> started = new ArrayList<>();
            for (Future<Backend> future : starting) started.add(future.get(120, TimeUnit.SECONDS));
            UUID player = UUID.randomUUID();
            String expected = null;
            for (Backend runtime : started) {
                Object acquired = runtime.acquire(player);
                assertEquals("ACQUIRED", runtime.status(acquired), runtime.name);
                assertEquals(expected, runtime.inventory(acquired), runtime.name);
                expected = "[" + runtime.name + "]";
                assertEquals("COMMITTED", runtime.save(acquired, player, expected, true));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
