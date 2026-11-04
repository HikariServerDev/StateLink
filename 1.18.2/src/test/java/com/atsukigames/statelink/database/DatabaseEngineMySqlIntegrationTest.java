package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage-engine startup checks against an explicitly configured disposable MySQL schema. */
@EnabledIfEnvironmentVariable(named = "STATELINK_TEST_MYSQL_DATABASE", matches = ".+")
class DatabaseEngineMySqlIntegrationTest {
    @Test void wrongUuidTypeIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %p MODIFY uuid CHAR(36) NOT NULL", "uuid");
    }
    @Test void wrongOwnerBinaryTypeIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %c MODIFY owner_session BINARY(36) NULL", "owner_session");
    }
    @Test void wrongOwnerNullabilityIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %c MODIFY owner_server VARCHAR(64) NOT NULL DEFAULT ''", "owner_server");
    }
    @Test void wrongDatetimePrecisionIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %c MODIFY lease_until DATETIME(3) NULL", "lease_until");
    }
    @Test void missingAuditUuidIndexIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %a DROP INDEX idx_uuid", "idx_uuid");
    }
    @Test void wrongAuditUuidIndexIsRejected() throws Exception {
        runAlteredSchema("ALTER TABLE %a DROP INDEX idx_uuid, ADD INDEX idx_uuid (uuid,previous_fence)", "idx_uuid");
    }
    private static void runAlteredSchema(String mutation,String expected) throws Exception {
        var c=testConfig(); String coord=c.tableName+"_coord",audit=DatabaseManager.recoveryAuditTableName(coord);
        try {
            var manager=new DatabaseManager(c,coord); manager.close();
            try(var connection=rawConnection(c); var statement=connection.createStatement()) {
                statement.execute(mutation.replace("%p",c.tableName).replace("%c",coord).replace("%a",audit));
            }
            var error=assertThrows(IllegalStateException.class,()->new DatabaseManager(c,coord));
            assertTrue(error.getMessage().contains(expected),error.getMessage());
        } finally {
            try(var connection=rawConnection(c); var statement=connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS "+audit); statement.execute("DROP TABLE IF EXISTS "+coord);
                statement.execute("DROP TABLE IF EXISTS "+c.tableName);
            }
        }
    }
    @Test
    void missingTablesAreCreatedAsInnoDbAndAccepted() throws Exception {
        runScenario(null, null, false, true);
    }

    @Test
    void existingInnoDbPlayerAndCoordinationTablesAreAccepted() throws Exception {
        runScenario("InnoDB", "InnoDB", true, true);
    }

    @Test
    void existingMyIsamPlayerTableIsRejected() throws Exception {
        runScenario("MyISAM", null, true, false);
    }

    @Test
    void existingMyIsamCoordinationTableIsRejected() throws Exception {
        runScenario("InnoDB", "MyISAM", true, false);
    }

    @Test
    void bothMyIsamTablesAreRejected() throws Exception {
        runScenario("MyISAM", "MyISAM", true, false);
    }

    @Test
    void existingPlayerTableWithoutUuidPrimaryKeyIsRejected() throws Exception {
        runInvalidSchema(false, null, null, null, "primary key");
    }

    @Test
    void existingCoordinationTableWithoutUuidPrimaryKeyIsRejected() throws Exception {
        runInvalidSchema(true, null, "uuid VARCHAR(36) NOT NULL", null, "primary key");
    }

    @Test
    void signedCoordinationFenceIsRejected() throws Exception {
        runInvalidSchema(true, null, "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
            + coordinationColumns().replace("fencing_token BIGINT UNSIGNED", "fencing_token BIGINT"),
            null, "fencing_token");
    }

    @Test
    void unsafeCoordinationRecoveryDefaultIsRejected() throws Exception {
        runInvalidSchema(true, null, "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
            + coordinationColumns().replace("recovery_required BOOLEAN NOT NULL DEFAULT FALSE",
                "recovery_required BOOLEAN NOT NULL DEFAULT TRUE"),
            null, "recovery_required");
    }

    @Test
    void narrowAuthoritativeInventoryColumnIsRejected() throws Exception {
        runInvalidSchema(true, playerDataColumns().replace("inventory LONGTEXT NULL", "inventory TEXT NULL"),
            null, null, "inventory");
    }

    @Test
    void malformedRecoveryAuditPrimaryKeyIsRejected() throws Exception {
        runInvalidSchema(true, null, null,
            "operation_id CHAR(36) NOT NULL, uuid CHAR(36) NOT NULL, actor VARCHAR(64) NOT NULL, "
                + "resolution VARCHAR(32) NOT NULL, recovery_reason VARCHAR(64) NULL, "
                + "previous_owner_server VARCHAR(64) NULL, previous_owner_session CHAR(36) NULL, "
                + "previous_fence BIGINT UNSIGNED NOT NULL, previous_revision BIGINT UNSIGNED NOT NULL, "
                + "resolved_fence BIGINT UNSIGNED NOT NULL, resolved_revision BIGINT UNSIGNED NOT NULL, "
                + "resolved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_uuid (uuid)",
            "primary key");
    }

    private static void runScenario(
        String playerEngine,
        String coordinationEngine,
        boolean precreate,
        boolean shouldStart
    ) throws Exception {
        Configuration.DatabaseConfig config = testConfig();
        String playerTable = config.tableName;
        String coordinationTable = playerTable + "_coord";
        DatabaseManager manager = null;
        try {
            if (precreate) {
                try (Connection connection = rawConnection(config); Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + playerTable
                        + " (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16) NOT NULL, "
                        + playerDataColumns() + ") ENGINE="
                        + playerEngine);
                    if (coordinationEngine != null) {
                        statement.execute("CREATE TABLE " + coordinationTable
                            + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, " + coordinationColumns()
                            + ") ENGINE=" + coordinationEngine);
                    }
                }
            }

            if (shouldStart) {
                manager = new DatabaseManager(config, coordinationTable);
                assertTrue(engine(config, playerTable).equalsIgnoreCase("InnoDB"));
                assertTrue(engine(config, coordinationTable).equalsIgnoreCase("InnoDB"));
            } else {
                DatabaseManager finalManager = manager;
                IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new DatabaseManager(config, coordinationTable));
                assertTrue(error.getMessage().contains("requires InnoDB"));
                assertTrue(error.getMessage().contains(playerEngine.equals("MyISAM")
                    ? playerTable : coordinationTable));
                assertNull(finalManager);
            }
        } finally {
            if (manager != null) manager.close();
            try (Connection connection = rawConnection(config); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + coordinationTable);
                statement.execute("DROP TABLE IF EXISTS " + playerTable);
                statement.execute("DROP TABLE IF EXISTS " + DatabaseManager.recoveryAuditTableName(coordinationTable));
            }
        }
    }

    private static void runInvalidSchema(boolean validPlayerKey, String playerColumnDefinition,
        String coordinationDefinition, String recoveryAuditDefinition, String expectedMessage) throws Exception {
        Configuration.DatabaseConfig config = testConfig();
        String playerTable = config.tableName;
        String coordinationTable = playerTable + "_coord";
        String auditTable = DatabaseManager.recoveryAuditTableName(coordinationTable);
        try {
            try (Connection connection = rawConnection(config); Statement statement = connection.createStatement()) {
                String uuid = validPlayerKey ? "uuid VARCHAR(36) NOT NULL PRIMARY KEY" : "uuid VARCHAR(36) NOT NULL";
                statement.execute("CREATE TABLE " + playerTable + " (" + uuid
                    + ", username VARCHAR(16) NOT NULL, "
                    + (playerColumnDefinition == null ? playerDataColumns() : playerColumnDefinition)
                    + ") ENGINE=InnoDB");
                if (coordinationDefinition != null) {
                    statement.execute("CREATE TABLE " + coordinationTable + " (" + coordinationDefinition
                        + ") ENGINE=InnoDB");
                }
                if (recoveryAuditDefinition != null) {
                    statement.execute("CREATE TABLE " + auditTable + " (" + recoveryAuditDefinition
                        + ") ENGINE=InnoDB");
                }
            }
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseManager(config, coordinationTable));
            assertTrue(error.getMessage().toLowerCase().contains(expectedMessage.toLowerCase()),
                "unexpected validation failure: " + error.getMessage());
        } finally {
            try (Connection connection = rawConnection(config); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + auditTable);
                statement.execute("DROP TABLE IF EXISTS " + coordinationTable);
                statement.execute("DROP TABLE IF EXISTS " + playerTable);
            }
        }
    }

    private static String playerDataColumns() {
        return "inventory LONGTEXT NULL, pending_disconnect_items LONGTEXT NULL, enderchest LONGTEXT NULL, "
            + "armor LONGTEXT NULL, offhand LONGTEXT NULL, effects LONGTEXT NULL, "
            + "advancements LONGTEXT NULL, statistics LONGTEXT NULL, recipe_book LONGTEXT NULL, "
            + "experience_points INT DEFAULT 0";
    }

    private static String coordinationColumns() {
        return "owner_server VARCHAR(64) NULL, owner_session CHAR(36) NULL, "
            + "fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0, lease_until DATETIME(6) NULL, "
            + "data_revision BIGINT UNSIGNED NOT NULL DEFAULT 0, recovery_required BOOLEAN NOT NULL DEFAULT FALSE, "
            + "recovery_reason VARCHAR(64) NULL, recovery_required_at DATETIME(6) NULL, "
            + "last_checkpoint_at DATETIME(6) NULL, updated_at TIMESTAMP(6) NOT NULL "
            + "DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)";
    }

    private static String engine(Configuration.DatabaseConfig config, String table) throws SQLException {
        try (Connection connection = rawConnection(config);
             var statement = connection.prepareStatement(
                 "SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("expected isolated staging table to exist");
                return result.getString(1);
            }
        }
    }

    private static Connection rawConnection(Configuration.DatabaseConfig config) throws SQLException {
        String url = "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.name
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=3000";
        return DriverManager.getConnection(url, config.username, config.password);
    }

    private static Configuration.DatabaseConfig testConfig() {
        Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
        config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
        config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
        config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
        config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
        config.maxConnections = 3;
        config.connectionTimeoutMs = 3_000;
        config.socketTimeoutMs = 3_000;
        config.tableName = "pdc_engine_" + UUID.randomUUID().toString().replace("-", "");
        return config;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
