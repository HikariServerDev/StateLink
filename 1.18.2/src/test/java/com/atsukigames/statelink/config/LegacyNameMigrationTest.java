package com.atsukigames.statelink.config;

import com.atsukigames.statelink.config.LegacyNameMigration.Outcome;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LegacyNameMigrationTest {
    /** Deliberately odd formatting: the migration must not reformat or reinterpret anything. */
    private static final String LEGACY_CONFIG = "{\r\n  \"enable\": true,\n\t\"database\": {\"host\": \"db.example\", "
        + "\"port\": 3307, \"name\": \"players\", \"username\": \"mc\", \"password\": \"p\\u00e4ss w\\\"rd\", "
        + "\"tableName\": \"player_data\"},\n  \"sync\": {\"serverId\": \"s1\", \"position\": false, "
        + "\"coordinationTableName\": \"custom_state\"},\n  \"unknownFutureKey\": [1, 2, 3]\n}\n\n";

    private static Path legacyConfig(Path dir) { return dir.resolve(LegacyNameMigration.LEGACY_CONFIG_FILE); }
    private static Path config(Path dir) { return dir.resolve(LegacyNameMigration.CONFIG_FILE); }
    private static Path legacyMarker(Path dir) { return dir.resolve(LegacyNameMigration.LEGACY_MARKER_FILE); }

    private static List<String> names(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test void theNamesAreTheOnesTheModsActuallyUse() {
        assertEquals("playerdataconnector.json", LegacyNameMigration.LEGACY_CONFIG_FILE);
        assertEquals("playerdataconnector-disabled.marker", LegacyNameMigration.LEGACY_MARKER_FILE);
        assertEquals("statelink.json", LegacyNameMigration.CONFIG_FILE);
        assertEquals("statelink-disabled.marker", DisabledMarker.FILE_NAME);
    }

    @Test void aFreshInstallIsLeftAlone(@TempDir Path dir) throws Exception {
        assertEquals(Outcome.NO_LEGACY_FILE, LegacyNameMigration.migrateConfig(dir).outcome());
        assertEquals(Outcome.NO_LEGACY_FILE, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        assertEquals(List.of(), names(dir));
    }

    @Test void anExistingStateLinkInstallIsLeftAlone(@TempDir Path dir) throws Exception {
        Files.writeString(config(dir), LEGACY_CONFIG);
        Files.writeString(DisabledMarker.path(dir), "marker");
        assertFalse(LegacyNameMigration.migrateConfig(dir).changedAnything());
        assertFalse(LegacyNameMigration.migrateDisabledMarker(dir).changedAnything());
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
        assertEquals(List.of(DisabledMarker.FILE_NAME, LegacyNameMigration.CONFIG_FILE), names(dir));
    }

    @Test void theLegacyConfigIsRenamedByteForByte(@TempDir Path dir) throws Exception {
        byte[] original = LEGACY_CONFIG.getBytes(StandardCharsets.UTF_8);
        Files.write(legacyConfig(dir), original);
        var result = LegacyNameMigration.migrateConfig(dir);
        assertEquals(Outcome.MOVED, result.outcome());
        assertNull(result.archived());
        assertArrayEquals(original, Files.readAllBytes(config(dir)));
        assertEquals(List.of(LegacyNameMigration.CONFIG_FILE), names(dir));
    }

    @Test void theMigratedConfigSelectsTheSameDatabaseAndTables(@TempDir Path dir, @TempDir Path reference)
        throws Exception {
        Path untouched = reference.resolve("legacy.json");
        Files.writeString(untouched, LEGACY_CONFIG);
        Configuration before = Configuration.load(untouched);
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        LegacyNameMigration.migrateConfig(dir);
        Configuration after = Configuration.load(config(dir));
        assertEquals("db.example", after.database.host);
        assertEquals(3307, after.database.port);
        assertEquals("players", after.database.name);
        assertEquals("päss w\"rd", after.database.password);
        assertEquals("player_data", after.database.tableName);
        assertEquals("custom_state", after.getCoordinationTableName());
        assertEquals("s1", after.sync.serverId);
        assertFalse(after.sync.position);
        var gson = new com.google.gson.Gson();
        assertEquals(gson.toJson(before), gson.toJson(after));
    }

    @Test void migrationIsIdempotent(@TempDir Path dir) throws Exception {
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        Files.writeString(legacyMarker(dir), "marker");
        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateConfig(dir).outcome());
        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        List<String> afterFirst = names(dir);
        for (int restart = 0; restart < 3; restart++) {
            assertEquals(Outcome.NO_LEGACY_FILE, LegacyNameMigration.migrateConfig(dir).outcome());
            assertEquals(Outcome.NO_LEGACY_FILE, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        }
        assertEquals(afterFirst, names(dir));
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
    }

    @Test void anIdenticalLeftoverCopyIsSetAsideNotDeleted(@TempDir Path dir) throws Exception {
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        Files.writeString(config(dir), LEGACY_CONFIG);
        var result = LegacyNameMigration.migrateConfig(dir);
        assertEquals(Outcome.DUPLICATE_ARCHIVED, result.outcome());
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
        assertEquals(LEGACY_CONFIG, Files.readString(result.archived()));
        assertEquals(List.of(LegacyNameMigration.LEGACY_CONFIG_FILE + LegacyNameMigration.ARCHIVE_SUFFIX,
            LegacyNameMigration.CONFIG_FILE), names(dir));
    }

    @Test void anUntouchedTemplateYieldsToTheLegacyConfig(@TempDir Path dir) throws Exception {
        new Configuration().save(config(dir));
        String template = Files.readString(config(dir));
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        var result = LegacyNameMigration.migrateConfig(dir);
        assertEquals(Outcome.MOVED_OVER_UNUSED_TEMPLATE, result.outcome());
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
        assertEquals(template, Files.readString(result.archived()));
        assertFalse(Files.exists(legacyConfig(dir)));
    }

    @Test void aReformattedTemplateIsStillATemplate(@TempDir Path dir) throws Exception {
        Files.writeString(config(dir), new com.google.gson.Gson().toJson(new Configuration()));
        assertTrue(LegacyNameMigration.isUntouchedTemplate(config(dir)));
    }

    @Test void twoDifferentConfiguredFilesRefuseStartupAndChangeNothing(@TempDir Path dir) throws Exception {
        String other = LEGACY_CONFIG.replace("db.example", "other.example");
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        Files.writeString(config(dir), other);
        var refused = assertThrows(ConfigurationException.class, () -> LegacyNameMigration.migrateConfig(dir));
        assertTrue(refused.getMessage().startsWith("STATELINK_LEGACY_CONFIG_CONFLICT"));
        assertFalse(refused.getMessage().contains(".example") || refused.getMessage().contains("\u00e4"),
            "the message must not quote file contents");
        assertEquals(LEGACY_CONFIG, Files.readString(legacyConfig(dir)));
        assertEquals(other, Files.readString(config(dir)));
        assertEquals(2, names(dir).size());
    }

    @Test void aDeliberatelyDisabledStateLinkConfigIsNotMistakenForATemplate(@TempDir Path dir) throws Exception {
        Configuration disabled = new Configuration();
        disabled.enable = false;
        disabled.save(config(dir));
        assertFalse(LegacyNameMigration.isUntouchedTemplate(config(dir)));
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        assertThrows(ConfigurationException.class, () -> LegacyNameMigration.migrateConfig(dir));
        assertFalse(Configuration.load(config(dir)).enable);
    }

    @Test void malformedFilesAreNeverTreatedAsTemplates(@TempDir Path dir) throws Exception {
        for (String content : new String[] {"", "{", "null", "[]", "not json"}) {
            Files.writeString(config(dir), content);
            assertFalse(LegacyNameMigration.isUntouchedTemplate(config(dir)), content);
        }
        assertFalse(LegacyNameMigration.isUntouchedTemplate(dir.resolve("missing.json")));
    }

    @Test void aLegacyNameThatIsNotAFileRefusesStartup(@TempDir Path dir) throws Exception {
        Files.createDirectory(legacyConfig(dir));
        var refused = assertThrows(ConfigurationException.class, () -> LegacyNameMigration.migrateConfig(dir));
        assertTrue(refused.getMessage().startsWith("STATELINK_LEGACY_CONFIG_UNUSABLE"));
        assertTrue(Files.isDirectory(legacyConfig(dir)));
        assertFalse(Files.exists(config(dir)));
    }

    @Test void archiveNamesNeverOverwriteEarlierArchives(@TempDir Path dir) throws Exception {
        String archive = LegacyNameMigration.LEGACY_CONFIG_FILE + LegacyNameMigration.ARCHIVE_SUFFIX;
        Files.writeString(dir.resolve(archive), "earlier-0");
        Files.writeString(dir.resolve(archive + ".1"), "earlier-1");
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        Files.writeString(config(dir), LEGACY_CONFIG);
        var result = LegacyNameMigration.migrateConfig(dir);
        assertEquals(dir.resolve(archive + ".2"), result.archived());
        assertEquals("earlier-0", Files.readString(dir.resolve(archive)));
        assertEquals("earlier-1", Files.readString(dir.resolve(archive + ".1")));
        assertEquals(LEGACY_CONFIG, Files.readString(result.archived()));
    }

    @Test void aSymlinkedLegacyConfigKeepsWorking(@TempDir Path dir, @TempDir Path shared) throws Exception {
        Path real = shared.resolve("shared.json");
        Files.writeString(real, LEGACY_CONFIG);
        try {
            Files.createSymbolicLink(legacyConfig(dir), real);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symbolic links are not available");
        }
        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateConfig(dir).outcome());
        assertTrue(Files.isSymbolicLink(config(dir)));
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
        assertEquals(LEGACY_CONFIG, Files.readString(real));
    }

    @Test void aStateLinkNameLinkedToTheLegacyFileIsNotLeftDangling(@TempDir Path dir) throws Exception {
        Files.writeString(legacyConfig(dir), LEGACY_CONFIG);
        try {
            Files.createSymbolicLink(config(dir), legacyConfig(dir));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symbolic links are not available");
        }
        assertThrows(ConfigurationException.class, () -> LegacyNameMigration.migrateConfig(dir));
        assertEquals(LEGACY_CONFIG, Files.readString(config(dir)));
        assertTrue(Files.isRegularFile(legacyConfig(dir), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test void theDisabledMarkerKeepsItsIdentityAcrossTheRename(@TempDir Path dir) throws Exception {
        var written = DisabledMarker.recordDisabled(dir, "s1", "2.2.9");
        Files.move(DisabledMarker.path(dir), legacyMarker(dir));   // exactly what PlayerDataConnector left behind
        assertNull(DisabledMarker.read(dir), "precondition: StateLink does not see the legacy name by itself");

        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        assertEquals(written.markerId(), DisabledMarker.read(dir).markerId());
        assertEquals(written.disabledAt(), DisabledMarker.read(dir).disabledAt());
        // A later disabled startup must not mint a new identity.
        assertEquals(written.markerId(), DisabledMarker.recordDisabled(dir, "s1", "2.2.9").markerId());
        // Re-enabling is still refused until exactly this marker is acknowledged.
        assertThrows(ConfigurationException.class, () -> DisabledMarker.requireReactivationAcknowledged(dir, ""));
        assertEquals(written.markerId(),
            DisabledMarker.requireReactivationAcknowledged(dir, written.markerId()).markerId());
    }

    @Test void anUnreadableLegacyMarkerIsStillAMarker(@TempDir Path dir) throws Exception {
        Files.writeString(legacyMarker(dir), "garbage");
        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        assertThrows(ConfigurationException.class,
            () -> DisabledMarker.requireReactivationAcknowledged(dir, "anything"));
    }

    @Test void anExistingStateLinkMarkerStaysInForce(@TempDir Path dir) throws Exception {
        var current = DisabledMarker.recordDisabled(dir, "s1", "2.2.9");
        Files.writeString(legacyMarker(dir), "{\"markerId\":\"" + java.util.UUID.randomUUID() + "\"}");
        var result = LegacyNameMigration.migrateDisabledMarker(dir);
        assertEquals(Outcome.SUPERSEDED_ARCHIVED, result.outcome());
        assertTrue(Files.exists(result.archived()));
        assertFalse(Files.exists(legacyMarker(dir)));
        assertEquals(current.markerId(), DisabledMarker.read(dir).markerId());
    }

    @Test void anInterruptedMigrationIsCompletedOnTheNextStartup(@TempDir Path dir) throws Exception {
        // Config already renamed, marker not yet: the state after a crash between the two steps.
        Files.writeString(config(dir), LEGACY_CONFIG);
        var written = DisabledMarker.recordDisabled(dir, "s1", "2.2.9");
        Files.move(DisabledMarker.path(dir), legacyMarker(dir));
        assertEquals(Outcome.NO_LEGACY_FILE, LegacyNameMigration.migrateConfig(dir).outcome());
        assertEquals(Outcome.MOVED, LegacyNameMigration.migrateDisabledMarker(dir).outcome());
        assertEquals(written.markerId(), DisabledMarker.read(dir).markerId());
    }
}
