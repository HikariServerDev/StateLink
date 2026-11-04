package com.atsukigames.statelink.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @Test void newRecoveryDefaultsUseLastCheckpointWithoutPerPlayerCommands() {
        var config = validConfiguration();
        assertTrue(config.recovery.enabled);
        assertEquals("last_checkpoint", config.recovery.mode);
        assertTrue(config.recovery.acknowledgeUnsafeRecovery);
        assertEquals(5, config.recovery.lastCheckpointDelaySeconds);
        assertDoesNotThrow(config::normalize);
    }

    @Test void existingExplicitSafeAndManualRecoverySettingsArePreserved() throws IOException {
        for (String mode : new String[]{"safe", "manual"}) {
            var config = validConfiguration();
            config.recovery.mode = mode;
            config.recovery.acknowledgeUnsafeRecovery = false;
            Path path = tempDir.resolve(mode + "-preserved.json");
            config.save(path);
            String before = read(path);
            var loaded = Configuration.load(path);
            assertEquals(mode, loaded.recovery.mode);
            assertFalse(loaded.recovery.acknowledgeUnsafeRecovery);
            assertEquals(before, read(path));
        }
    }

    @Test void spatialMatrixRejectsUnpairedDimensionAndCoordinates() {
        boolean[][] matrix = {{false,false,false},{true,false,false},{false,true,false},
            {true,true,false},{false,false,true},{true,true,true}};
        for (boolean[] flags : matrix) {
            var c = validConfiguration(); c.sync.position=flags[0]; c.sync.rotation=flags[1]; c.sync.dimension=flags[2];
            if (flags[0] == flags[2]) assertDoesNotThrow(c::normalize);
            else assertThrows(ConfigurationException.class,c::normalize);
        }
    }
    @Test void legacyPositionOffKeepsAllSpatialDomainsOff() {
        var s = new Configuration.SyncConfig(); s.position=false;
        assertFalse(s.dimensionEnabled()); assertFalse(s.rotationEnabled());
        s.position=true; assertTrue(s.dimensionEnabled()); assertTrue(s.rotationEnabled());
    }
    @Test void explicitSpatialFlagsAndRecipesAliasAreNotIgnored() throws IOException {
        Path path=tempDir.resolve("spatial.json"); var c=validConfiguration(); c.save(path);
        var json=com.google.gson.JsonParser.parseString(read(path)).getAsJsonObject(); var s=json.getAsJsonObject("sync");
        s.addProperty("position",false); s.addProperty("dimension",false); s.addProperty("rotation",true);
        s.remove("recipeBook"); s.addProperty("recipes",false); Files.writeString(path,json.toString());
        var loaded=Configuration.load(path); assertFalse(loaded.sync.position); assertFalse(loaded.sync.dimensionEnabled());
        assertTrue(loaded.sync.rotationEnabled()); assertFalse(loaded.sync.recipeBook);
    }
    @Test void explicitNullOrStringDomainFlagsFailWithoutRewrite() throws IOException {
        Path path=tempDir.resolve("bad-domain.json"); var c=validConfiguration(); c.save(path);
        var json=com.google.gson.JsonParser.parseString(read(path)).getAsJsonObject();
        for (String value : new String[]{"null","\"false\""}) {
            json.getAsJsonObject("sync").add("dimension",com.google.gson.JsonParser.parseString(value));
            String text=json.toString(); Files.writeString(path,text);
            assertThrows(ConfigurationException.class,()->Configuration.load(path)); assertEquals(text,read(path));
        }
    }
    @TempDir
    Path tempDir;

    @Test void tenSecondDirtyOnlyDefaultsAndLegacyMinutesPrecedence() throws IOException {
        var template=validConfiguration(); Path path=tempDir.resolve("interval.json"); template.save(path);
        var gson=new com.google.gson.Gson(); var json=com.google.gson.JsonParser.parseString(read(path)).getAsJsonObject();
        var save=json.getAsJsonObject("save"); save.remove("saveIntervalSeconds"); save.remove("saveIntervalMinutes");
        Files.writeString(path,gson.toJson(json)); assertEquals(10,Configuration.load(path).save.saveIntervalSeconds);
        assertTrue(Configuration.load(path).save.saveOnlyWhenDirty);
        save.addProperty("saveIntervalMinutes",5); Files.writeString(path,gson.toJson(json));
        assertEquals(300,Configuration.load(path).save.saveIntervalSeconds);
        save.addProperty("saveIntervalSeconds",10); Files.writeString(path,gson.toJson(json));
        assertEquals(10,Configuration.load(path).save.saveIntervalSeconds);
    }

    @Test void invalidOrOverflowingIntervalsFailWithoutRewrite() throws IOException {
        var config=validConfiguration(); Path path=tempDir.resolve("invalid-interval.json"); config.save(path);
        var json=com.google.gson.JsonParser.parseString(read(path)).getAsJsonObject(); var save=json.getAsJsonObject("save");
        for (String invalid : new String[]{"-1","0.5","2147483648","null"}) {
            save.add("saveIntervalSeconds",com.google.gson.JsonParser.parseString(invalid));
            String contents=json.toString(); Files.writeString(path,contents);
            assertThrows(ConfigurationException.class,()->Configuration.load(path)); assertEquals(contents,read(path));
        }
        save.remove("saveIntervalSeconds"); save.addProperty("saveIntervalMinutes",Integer.MAX_VALUE);
        Files.writeString(path,json.toString()); assertThrows(ConfigurationException.class,()->Configuration.load(path));
    }

    @Test void automaticRequiresExplicitRiskAckAndPositiveLimits() {
        var config=validConfiguration(); config.recovery.mode="automatic";
        assertThrows(ConfigurationException.class,config::normalize);
        config.recovery.acknowledgePotentialRollbackOrDuplication=true; assertDoesNotThrow(config::normalize);
        config.recovery.maxCheckpointAgeSeconds=0; assertThrows(ConfigurationException.class,config::normalize);
        config.recovery.maxCheckpointAgeSeconds=30; config.recovery.maxAutoRecoveryAttempts=0;
        assertThrows(ConfigurationException.class,config::normalize);
    }

    @Test
    void firstRunWritesInactiveTemplateAndRefusesToStart() {
        Path path = tempDir.resolve("statelink.json");

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("inactive configuration template"));
        assertTrue(Files.exists(path));
        String contents = read(path);
        assertTrue(contents.contains("\"host\": \"\""));
        assertTrue(contents.contains("\"saveOnDisconnect\": true"));
    }

    @Test
    void malformedExistingConfigFailsWithoutReplacingFileOrLoggingSecrets() throws IOException {
        Path path = tempDir.resolve("statelink.json");
        String broken = "{\"database\":{\"password\":\"do-not-log-this-secret\",}";
        Files.writeString(path, broken);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("Malformed JSON"));
        assertFalse(error.getMessage().contains("do-not-log-this-secret"));
        assertEquals(broken, Files.readString(path));
    }

    @Test
    void unreadableExistingPathFailsInsteadOfCreatingDefaults() throws IOException {
        Path path = tempDir.resolve("not-a-config-file");
        Files.createDirectory(path);

        assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(Files.isDirectory(path));
        try (var children = Files.list(path)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void unreadableExistingFileFailsWithoutReplacingIt() throws IOException {
        Path path = tempDir.resolve("statelink.json");
        String contents = "{\"not\":\"valid config\"}";
        Files.writeString(path, contents);
        var permissions = Files.getPosixFilePermissions(path);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
            assertThrows(ConfigurationException.class, () -> Configuration.load(path));
        } finally {
            Files.setPosixFilePermissions(path, permissions);
        }
        assertEquals(contents, Files.readString(path));
    }

    @Test
    void invalidTableIdentifiersFailWithoutChangingTheConfiguredName() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.database.tableName = "player_data; DROP TABLE other_table";
        Path path = tempDir.resolve("statelink.json");
        configuration.save(path);
        String before = read(path);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("database.tableName"));
        assertEquals(before, read(path));
    }

    @Test
    void invalidTimingFailsInsteadOfBeingSilentlyClamped() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.sync.leaseDurationMs = 1_000;
        Path path = tempDir.resolve("statelink.json");
        configuration.save(path);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("sync.leaseDurationMs"));
    }

    @Test
    void explicitSaveOnDisconnectFalseSelectsGuardedRuntimeComparison() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.save.saveOnDisconnect = false;
        Path path = tempDir.resolve("statelink.json");
        configuration.save(path);

        assertFalse(Configuration.load(path).save.saveOnDisconnect);
    }

    @Test
    void omittedSaveOnDisconnectKeepsTheSafeTrueDefault() throws IOException {
        Path path = tempDir.resolve("statelink.json");
        Files.writeString(path, "{\"database\":{\"host\":\"127.0.0.1\",\"name\":\"pdc_test\","
            + "\"username\":\"test\",\"password\":\"test-only\"}}");

        Configuration loaded = Configuration.load(path);

        assertTrue(loaded.save.saveOnDisconnect);
    }

    @Test
    void explicitInventorySyncFalseIsRejectedForTransientItemRecovery() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.sync.inventory = false;
        Path path = tempDir.resolve("statelink.json");
        configuration.save(path);
        String before = read(path);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("sync.armor"));
        assertTrue(error.getMessage().contains("sync.inventory=false"));
        assertEquals(before, read(path), "unsafe configuration must not be silently rewritten");
    }

    @Test
    void omittedInventorySyncKeepsTheSafeTrueDefault() throws IOException {
        Path path = tempDir.resolve("statelink.json");
        Files.writeString(path, "{\"database\":{\"host\":\"127.0.0.1\",\"name\":\"pdc_test\","
            + "\"username\":\"test\",\"password\":\"test-only\"}}");

        Configuration loaded = Configuration.load(path);

        assertTrue(loaded.sync.inventory);
    }

    @Test void disabledConfigurationDoesNotRequireDatabaseSettings() throws IOException {
        Path path = tempDir.resolve("disabled.json");
        Files.writeString(path, "{\"enable\":false,\"database\":null}");
        assertFalse(Configuration.load(path).enable);
    }

    @Test void fullyDisabledItemDomainIsAcceptedWithoutSilentEnabling() {
        Configuration config = validConfiguration();
        config.sync.inventory = config.sync.armor = config.sync.offhand = config.sync.enderchest = false;
        config.normalize();
        assertFalse(config.sync.inventory);
    }

    @Test void offhandAndEnderChestCannotOutliveDisabledInventoryDomain() {
        Configuration config = validConfiguration();
        config.sync.inventory = config.sync.armor = false;
        assertTrue(assertThrows(ConfigurationException.class, config::normalize).getMessage().contains("sync.offhand"));
        config.sync.offhand = false;
        assertTrue(assertThrows(ConfigurationException.class, config::normalize).getMessage().contains("sync.enderchest"));
    }

    @Test void unsafeRecoveryRequiresExplicitAcknowledgement() {
        Configuration config = validConfiguration();
        config.recovery.mode = "last_checkpoint";
        config.recovery.acknowledgeUnsafeRecovery = false;
        assertThrows(ConfigurationException.class, config::normalize);
        config.recovery.acknowledgeUnsafeRecovery = true;
        assertDoesNotThrow(config::normalize);
    }

    @Test void unknownRecoveryModeAndInvalidBudgetsFailClosed() {
        Configuration config = validConfiguration();
        config.recovery.mode = "automatic";
        assertThrows(ConfigurationException.class, config::normalize);
        config.recovery.mode = "safe";
        config.recovery.retryIntervalSeconds = 0;
        assertThrows(ConfigurationException.class, config::normalize);
        config.recovery.retryIntervalSeconds = 10;
        config.recovery.maxWaitSeconds = 9;
        assertThrows(ConfigurationException.class, config::normalize);
    }

    @Test
    void existingValidConfigLoadsWithoutChangingItsDatabaseAndTiming() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.database.name = "pdc_existing";
        configuration.database.tableName = "players_v4";
        configuration.sync.coordinationTableName = "players_coord_v4";
        Path path = tempDir.resolve("statelink.json");
        configuration.save(path);

        Configuration loaded = Configuration.load(path);

        assertEquals("pdc_existing", loaded.database.name);
        assertEquals("players_v4", loaded.database.tableName);
        assertEquals("players_coord_v4", loaded.getCoordinationTableName());
        assertEquals(15_000, loaded.sync.leaseDurationMs);
        assertEquals(3_000, loaded.sync.leaseRenewIntervalMs);
    }

    @Test void explicitlyConfiguredLegacyShortLeaseIsPreservedButRejectedWhenRevalidationCannotFit() throws IOException {
        Configuration configuration = validConfiguration();
        configuration.sync.leaseDurationMs = 5_000;
        configuration.sync.leaseRenewIntervalMs = 1_500;
        Path path = tempDir.resolve("legacy-lease.json");
        configuration.save(path);
        String original = read(path);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> Configuration.load(path));

        assertTrue(error.getMessage().contains("sync.leaseDurationMs"));
        assertTrue(error.getMessage().contains("revalidation"));
        assertEquals(original, read(path), "unsafe legacy timing must be diagnosed, not silently rewritten");
        assertEquals(5_000, configuration.sync.leaseDurationMs);
        assertEquals(1_500, configuration.sync.leaseRenewIntervalMs);
    }

    private static Configuration validConfiguration() {
        Configuration configuration = new Configuration();
        configuration.database.host = "127.0.0.1";
        configuration.database.name = "pdc_test";
        configuration.database.username = "pdc_test";
        configuration.database.password = "test-only";
        configuration.sync.serverId = "test-server";
        return configuration;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }
}
