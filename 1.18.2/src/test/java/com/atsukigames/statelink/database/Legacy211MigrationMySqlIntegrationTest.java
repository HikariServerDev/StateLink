package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.DatabaseManager.LegacyBootstrapRequest;
import com.atsukigames.statelink.database.Legacy211Bootstrap.BootstrapRefusedException;
import com.atsukigames.statelink.database.PlayerDataRepository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrades databases that the released 2.1.11 artifact created and wrote. No fixture row here
 * was ever saved by 2.2.x code before the upgrade under test.
 */
class Legacy211MigrationMySqlIntegrationTest {
    private static final String ADOPT = Legacy211Bootstrap.POLICY_ADOPT_FIRST_LOGIN_LOCAL;
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach void cleanup() throws Exception {
        DatabaseManager.bootstrapBatchHook = null;
        DatabaseManager.BOOTSTRAP_BATCH_ROWS = 200;
        for (Fixture fixture : fixtures) fixture.close();
    }

    private Fixture fixture() throws Exception { Fixture f = new Fixture(); fixtures.add(f); return f; }

    private static Configuration.SyncConfig legacyDomainsOnly() {
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        sync.playerProfile = false; sync.advancements = false; sync.statistics = false; sync.recipeBook = false;
        return sync;
    }

    @Test void releasedArtifactCreatesTheSchemaThatIsRecognizedAsLegacy() throws Exception {
        Legacy211Runtime.assertPlaceholderConstantsComeFromTheArtifact();
        Fixture f = fixture();
        f.legacyClean(1);
        f.stopLegacy();
        assertEquals(Set.copyOf(SchemaGeneration.COORDINATION_2_1_11.keySet()), f.columns(f.coord));
        assertEquals(Set.copyOf(SchemaGeneration.AUDIT_2_1_11.keySet()), f.columns(f.audit));
        assertEquals(SchemaGeneration.PLAYER_2_1_11, f.columns(f.table));
        try (Connection c = f.raw()) {
            var detection = SchemaGeneration.detect(c, f.table, f.coord, f.audit);
            assertEquals(SchemaGeneration.Generation.LEGACY_2_1_11, detection.generation(), detection.detail());
        }
    }

    @Test void unacknowledgedStartupOnALegacyDatabaseStopsBeforeAnyDdl() throws Exception {
        Fixture f = fixture();
        f.legacyClean(3);
        f.stopLegacy();
        Set<String> before = f.columns(f.coord);
        var refused = assertThrows(BootstrapRefusedException.class, () -> new DatabaseManager(f.config, f.coord));
        assertEquals("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED", refused.code());
        assertTrue(refused.getMessage().contains("3 player rows"));
        refused = assertThrows(BootstrapRefusedException.class,
            () -> f.upgrade(new Configuration.SyncConfig(), ADOPT, false));
        assertEquals("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED", refused.code());
        assertEquals(before, f.columns(f.coord), "refused startup must not alter the 2.1.11 schema");
        assertTrue(f.columns(f.migration).isEmpty());
    }

    @Test void cleanLegacyPlayerLoadsAfterOneAcknowledgedBootstrapWithoutAnyPerPlayerCommand() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(1).get(0);
        f.stopLegacy();
        long fence = f.number("SELECT fencing_token FROM " + f.coord + " WHERE uuid='" + player + "'");
        long revision = f.number("SELECT data_revision FROM " + f.coord + " WHERE uuid='" + player + "'");
        assertTrue(revision > 0);

        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        assertEquals(SchemaGeneration.Generation.LEGACY_2_1_11, db.detectedGeneration().generation());
        assertEquals(revision, f.number("SELECT domain_provenance_revision FROM " + f.coord + " WHERE uuid='" + player + "'"));
        assertEquals(1, f.number("SELECT domain_provenance_generation FROM " + f.coord + " WHERE uuid='" + player + "'"));
        assertEquals(fence, f.number("SELECT fencing_token FROM " + f.coord + " WHERE uuid='" + player + "'"),
            "the bootstrap must not use or advance the ownership fence");
        assertEquals(revision, f.number("SELECT data_revision FROM " + f.coord + " WHERE uuid='" + player + "'"));
        assertEquals(0, f.number("SELECT COUNT(*) FROM " + f.audit), "no recovery or reconciliation was issued");
        assertEquals("COMPLETED|2.1.11|1|1", f.text("SELECT CONCAT(state,'|',source_schema,'|',operator_acknowledged,'|',row_count) FROM "
            + f.migration + " WHERE migration_id='" + Legacy211Bootstrap.MIGRATION_ID + "'"));
        assertEquals(Legacy211Bootstrap.plan(sync, ADOPT).fingerprint(),
            f.text("SELECT config_fingerprint FROM " + f.migration));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.migration + " WHERE completed_at>=started_at AND target_version IS NOT NULL"));

        AcquireResult acquired = new PlayerDataRepository(db).acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, acquired.status());
        assertEquals(fence + 1, acquired.ownership().fencingToken());
        CompletePlayerData data = acquired.data().orElseThrow();
        // Values 2.1.11 really synchronized are conserved bit for bit.
        assertEquals(Legacy211Runtime.items("inventory-p0"), data.inventoryJson);
        assertEquals(Legacy211Runtime.items("ender-p0"), data.enderchestJson);
        assertEquals(Legacy211Runtime.items("armor-p0"), data.armorJson);
        assertEquals(Legacy211Runtime.items("offhand-p0"), data.offhandJson);
        assertEquals(Legacy211Runtime.EFFECTS, data.effectsJson);
        assertEquals(17.5, data.health); assertEquals(13, data.foodLevel); assertEquals(30, data.experienceLevel);
        assertEquals("adventure", data.gamemode); assertEquals("minecraft:the_nether", data.dimension);
        assertEquals(12.5, data.posX); assertEquals(41.0f, data.yaw);
        // Placeholder-only domains still hold the 2.1.11 constants, which LOAD treats as "keep local".
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("advancements", data.advancementsJson));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("statistics", data.statisticsJson));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("recipeBook", data.recipeBookJson));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("playerProfile", data.skinTexture));
    }

    @Test void emptyLegacySchemaUpgradesWithoutAcknowledgement() throws Exception {
        Fixture f = fixture();
        f.stopLegacy();
        assertEquals(0, f.number("SELECT COUNT(*) FROM " + f.table));
        DatabaseManager db = f.open(new DatabaseManager(f.config, f.coord));
        assertEquals(SchemaGeneration.Generation.LEGACY_2_1_11, db.detectedGeneration().generation());
        assertTrue(f.columns(f.coord).contains("domain_provenance_manifest"));
        assertEquals("COMPLETED|0|0", f.text("SELECT CONCAT(state,'|',operator_acknowledged,'|',row_count) FROM " + f.migration));
        UUID fresh = UUID.randomUUID();
        assertEquals(AcquireStatus.ACQUIRED, new PlayerDataRepository(db)
            .acquireOwnershipAndLoad(fresh, "s1", UUID.randomUUID(), 5_000, new Configuration.SyncConfig()).status());
    }

    @Test void legacyLocalAuthorityLostBacklogKeepsItsRecoveryStateAndCompletesAutomaticRecoveryOnce() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyBacklog("LOCAL_AUTHORITY_LOST");
        f.stopLegacy();
        String before = f.text("SELECT CONCAT_WS('|',owner_server,owner_session,fencing_token,data_revision,recovery_required,recovery_reason) FROM "
            + f.coord + " WHERE uuid='" + player + "'");
        assertTrue(before.endsWith("|1|LOCAL_AUTHORITY_LOST"), before);

        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        PlayerDataRepository repo = new PlayerDataRepository(db);
        assertEquals(before, f.text("SELECT CONCAT_WS('|',owner_server,owner_session,fencing_token,data_revision,recovery_required,recovery_reason) FROM "
            + f.coord + " WHERE uuid='" + player + "'"), "the bootstrap must not clear or rewrite the recovery backlog");
        RecoveryStatus status = repo.recoveryStatus(player).orElseThrow();
        assertEquals(status.revision(), f.number("SELECT domain_provenance_revision FROM " + f.coord + " WHERE uuid='" + player + "'"));

        // manual / safe: provenance does not stand in for a recovery decision.
        AcquireResult manual = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, manual.status());
        assertEquals("LOCAL_AUTHORITY_LOST", manual.recoveryReason());
        assertFalse(manual.requiresOperatorReconciliation());
        assertTrue(manual.data().isEmpty());
        assertNotEquals(RecoveryResolutionStatus.RESOLVED,
            repo.resolveSafeFinalReceipt(player, status.fence(), status.revision(), UUID.randomUUID(), "safe").status());
        assertEquals(1, f.number("SELECT recovery_required FROM " + f.coord + " WHERE uuid='" + player + "'"));

        // automatic with the explicit risk acknowledgement: one resolution, then a normal LOAD.
        var resolved = repo.resolveAutomaticLastCheckpoint(player, status.fence(), status.revision(), UUID.randomUUID(),
            "auto:s1", 0, 86_400, 12, 5, true);
        assertEquals(RecoveryResolutionStatus.RESOLVED, resolved.status());
        assertEquals(status.fence() + 1, resolved.fence());
        assertEquals(status.revision(), resolved.revision());
        AcquireResult acquired = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, acquired.status(), "recovery followed by acquire must not re-quarantine");
        assertEquals(Legacy211Runtime.items("inventory-backlog"), acquired.data().orElseThrow().inventoryJson);
        assertEquals(0, f.number("SELECT recovery_required FROM " + f.coord + " WHERE uuid='" + player + "'"));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.audit + " WHERE uuid='" + player
            + "' AND recovery_mode='automatic' AND source_version='2.1.11-legacy' AND recovery_reason='LOCAL_AUTHORITY_LOST'"));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.audit));
    }

    @Test void manyLegacyPlayersBootstrapInBoundedBatchesAndAllLoad() throws Exception {
        Fixture f = fixture();
        List<UUID> players = f.legacyClean(120);
        f.stopLegacy();
        DatabaseManager.BOOTSTRAP_BATCH_ROWS = 25;
        List<Integer> batches = new ArrayList<>();
        List<Integer> poolActive = new ArrayList<>();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager[] holder = new DatabaseManager[1];
        DatabaseManager.bootstrapBatchHook = batch -> batches.add(batch);
        long started = System.nanoTime();
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        holder[0] = db;
        poolActive.add(db.poolMetrics().active());
        assertEquals(5, batches.size(), "120 rows in batches of 25");
        assertEquals(120, f.number("SELECT COUNT(*) FROM " + f.coord
            + " WHERE domain_provenance_generation=1 AND domain_provenance_revision=data_revision AND domain_provenance_manifest IS NOT NULL"));
        assertEquals(120, f.number("SELECT row_count FROM " + f.migration));
        assertEquals(0, db.poolMetrics().active(), "bootstrap must return every pooled connection");
        assertTrue(elapsedMs < 30_000, "bootstrap of 120 rows took " + elapsedMs + "ms");
        PlayerDataRepository repo = new PlayerDataRepository(db);
        for (UUID player : players) {
            assertEquals(AcquireStatus.ACQUIRED,
                repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).status());
        }
        assertEquals(0, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE recovery_required"));
        System.out.println("LEGACY_BOOTSTRAP_EVIDENCE rows=120 batchRows=25 batches=" + batches.size() + " elapsedMs=" + elapsedMs
            + " poolActiveAfter=" + poolActive);
    }

    @Test void restartAfterCompletionIsIdempotentAndLaterBackendsNeedNoAcknowledgement() throws Exception {
        Fixture f = fixture();
        f.legacyClean(4);
        f.stopLegacy();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        f.upgrade(sync, ADOPT, true).close();
        String snapshot = f.text("SELECT GROUP_CONCAT(CONCAT_WS(':',uuid,domain_provenance_revision,domain_provenance_generation,fencing_token) ORDER BY uuid) FROM " + f.coord);
        String completedAt = f.text("SELECT CONCAT(completed_at,'|',row_count) FROM " + f.migration);
        f.upgrade(sync, ADOPT, true).close();
        DatabaseManager unacknowledged = f.open(new DatabaseManager(f.config, f.coord));
        assertEquals(SchemaGeneration.Generation.CURRENT_PROVENANCE, unacknowledged.detectedGeneration().generation());
        assertEquals(snapshot, f.text("SELECT GROUP_CONCAT(CONCAT_WS(':',uuid,domain_provenance_revision,domain_provenance_generation,fencing_token) ORDER BY uuid) FROM " + f.coord));
        assertEquals(completedAt, f.text("SELECT CONCAT(completed_at,'|',row_count) FROM " + f.migration));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.migration));
    }

    @Test void crashedBootstrapIsDetectedRefusedWithoutAcknowledgementAndResumedExactlyOnce() throws Exception {
        Fixture f = fixture();
        f.legacyClean(60);
        f.stopLegacy();
        DatabaseManager.BOOTSTRAP_BATCH_ROWS = 20;
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager.bootstrapBatchHook = batch -> { if (batch == 1) throw new IllegalStateException("injected crash"); };
        assertEquals("injected crash", assertThrows(IllegalStateException.class, () -> f.upgrade(sync, ADOPT, true)).getMessage());
        DatabaseManager.bootstrapBatchHook = null;
        assertEquals("STARTED", f.text("SELECT state FROM " + f.migration));
        assertEquals(20, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_manifest IS NOT NULL"));
        assertEquals(40, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_manifest IS NULL"));

        // A half-migrated database is recognized by its durable record, never served as-is.
        var refused = assertThrows(BootstrapRefusedException.class, () -> new DatabaseManager(f.config, f.coord));
        assertEquals("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED", refused.code());
        Configuration.SyncConfig other = new Configuration.SyncConfig();
        other.effects = false;
        refused = assertThrows(BootstrapRefusedException.class, () -> f.upgrade(other, ADOPT, true));
        assertEquals("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH", refused.code());
        assertEquals(40, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_manifest IS NULL"));

        f.upgrade(sync, ADOPT, true);
        assertEquals("COMPLETED|60", f.text("SELECT CONCAT(state,'|',row_count) FROM " + f.migration));
        assertEquals(60, f.number("SELECT COUNT(*) FROM " + f.coord
            + " WHERE domain_provenance_generation=1 AND domain_provenance_revision=data_revision"),
            "rows bootstrapped before the crash must not get a second generation");
    }

    @Test void concurrentlyStartingBackendsRunOneBootstrap() throws Exception {
        Fixture f = fixture();
        f.legacyClean(40);
        f.stopLegacy();
        DatabaseManager.BOOTSTRAP_BATCH_ROWS = 10;
        DatabaseManager.bootstrapBatchHook = batch -> { try { Thread.sleep(40); } catch (InterruptedException e) { throw new IllegalStateException(e); } };
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        var first = CompletableFuture.supplyAsync(() -> f.upgradeUnchecked(sync, ADOPT, true));
        var second = CompletableFuture.supplyAsync(() -> f.upgradeUnchecked(sync, ADOPT, true));
        DatabaseManager a = first.get(), b = second.get();
        long legacyLeaders = (a.detectedGeneration().generation() == SchemaGeneration.Generation.LEGACY_2_1_11 ? 1 : 0)
            + (b.detectedGeneration().generation() == SchemaGeneration.Generation.LEGACY_2_1_11 ? 1 : 0);
        assertEquals(1, legacyLeaders, "exactly one backend may observe and migrate the legacy schema");
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.migration));
        assertEquals("COMPLETED|40", f.text("SELECT CONCAT(state,'|',row_count) FROM " + f.migration));
        assertEquals(40, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_generation=1"));
    }

    @Test void backendWithADifferentBootstrapDomainConfigurationIsRejected() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(1).get(0);
        f.stopLegacy();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        f.upgrade(sync, ADOPT, true).close();
        Configuration.SyncConfig second = new Configuration.SyncConfig();
        second.gamemode = false;
        var refused = assertThrows(BootstrapRefusedException.class, () -> f.upgrade(second, ADOPT, true));
        assertEquals("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH", refused.code());
        refused = assertThrows(BootstrapRefusedException.class, () -> f.upgrade(legacyDomainsOnly(), "disabled", true));
        assertEquals("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH", refused.code());
        // A pinned fingerprint must match this backend's own configuration as well.
        refused = assertThrows(BootstrapRefusedException.class, () -> f.open(new DatabaseManager(f.config, f.coord,
            new LegacyBootstrapRequest(sync, true, "0".repeat(64), ADOPT, "s2"))));
        assertEquals("LEGACY_2_1_11_BOOTSTRAP_FINGERPRINT_MISMATCH", refused.code());
        String pinned = Legacy211Bootstrap.plan(sync, ADOPT).fingerprint();
        DatabaseManager db = f.open(new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(sync, true, pinned, ADOPT, "s2")));
        assertEquals(AcquireStatus.ACQUIRED,
            new PlayerDataRepository(db).acquireOwnershipAndLoad(player, "s2", UUID.randomUUID(), 5_000, sync).status());
    }

    @Test void currentSchemaRowWithNullProvenanceIsNeverTreatedAsLegacy() throws Exception {
        Fixture f = fixture();
        f.stopLegacy();
        f.dropAll();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager db = f.open(new DatabaseManager(f.config, f.coord));
        assertEquals(SchemaGeneration.Generation.FRESH, db.detectedGeneration().generation());
        PlayerDataRepository repo = new PlayerDataRepository(db);
        UUID player = UUID.randomUUID();
        Ownership owner = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).ownership();
        assertTrue(repo.saveCompletePlayerData(f.currentData(player, "[diamond]"), owner, sync, 5_000, true).committed());
        f.sql("UPDATE " + f.coord + " SET domain_provenance_revision=NULL,domain_provenance_generation=NULL,"
            + "domain_provenance_manifest=NULL,clean_release_domains=NULL,clean_release_revision=NULL");
        db.close();

        // Even a fully acknowledged legacy bootstrap request does nothing on a current schema.
        DatabaseManager again = f.upgrade(sync, ADOPT, true);
        assertEquals(SchemaGeneration.Generation.CURRENT_PROVENANCE, again.detectedGeneration().generation());
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_manifest IS NULL"));
        assertEquals(0, f.number("SELECT COUNT(*) FROM " + f.migration));
        AcquireResult denied = new PlayerDataRepository(again).acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, denied.status());
        assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", denied.recoveryReason());
        assertTrue(denied.requiresOperatorReconciliation());
        assertTrue(denied.data().isEmpty());
    }

    @Test void schemaThatIsNotExactly211IsUnknownAndNeverBootstrapped() throws Exception {
        String[] drifts = {
            "ALTER TABLE %coord% ADD COLUMN custom_flag INT NULL",
            "ALTER TABLE %coord% MODIFY fencing_token BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE %coord% MODIFY lease_until DATETIME NULL",
            "ALTER TABLE %audit% ADD COLUMN note VARCHAR(8) NULL",
            "DROP TABLE %audit%",
            "ALTER TABLE %audit% DROP INDEX idx_uuid",
            "ALTER TABLE %table% ADD COLUMN legacy_extra INT NULL",
            "ALTER TABLE %table% DROP COLUMN backup_data",
            "ALTER TABLE %coord% DROP COLUMN last_checkpoint_at",
            // Same column names as 2.1.11, different definition.
            "ALTER TABLE %table% MODIFY health FLOAT DEFAULT 20.0",
            "ALTER TABLE %table% MODIFY inventory TEXT",
            "ALTER TABLE %table% MODIFY username VARCHAR(32) NOT NULL",
            "ALTER TABLE %table% MODIFY username VARCHAR(16) NULL",
            "ALTER TABLE %table% MODIFY health DOUBLE DEFAULT 10.0",
            "ALTER TABLE %table% MODIFY gamemode VARCHAR(16) DEFAULT 'creative'",
            "ALTER TABLE %table% MODIFY last_login TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE %table% MODIFY experience_level INT UNSIGNED DEFAULT 0",
            "ALTER TABLE %table% DROP INDEX idx_dimension",
            "ALTER TABLE %table% DROP INDEX idx_username, ADD INDEX idx_username (username, uuid)",
            "ALTER TABLE %table% DROP INDEX idx_username, ADD UNIQUE INDEX idx_username (username)",
            "ALTER TABLE %table% ADD INDEX idx_extra (display_name)",
            "ALTER TABLE %table% DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, username)",
            "ALTER TABLE %coord% MODIFY fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 1",
            "ALTER TABLE %coord% MODIFY updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)",
            "ALTER TABLE %coord% ADD INDEX idx_owner (owner_server)",
            "ALTER TABLE %audit% MODIFY resolved_at DATETIME(6) NOT NULL",
            "ALTER TABLE %audit% ENGINE=MyISAM"
        };
        for (String drift : drifts) {
            Fixture f = fixture();
            f.legacyClean(1);
            f.stopLegacy();
            f.sql(drift.replace("%coord%", f.coord).replace("%audit%", f.audit).replace("%table%", f.table));
            Set<String> before = f.columns(f.coord);
            String rowsBefore = f.text("SELECT CONCAT_WS('|',COUNT(*),SUM(fencing_token),SUM(data_revision),MAX(updated_at)) FROM " + f.coord);
            try (Connection c = f.raw()) {
                assertEquals(SchemaGeneration.Generation.UNKNOWN_SCHEMA,
                    SchemaGeneration.detect(c, f.table, f.coord, f.audit).generation(), drift);
            }
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> f.upgrade(new Configuration.SyncConfig(), ADOPT, true), drift);
            assertEquals(rowsBefore, f.text("SELECT CONCAT_WS('|',COUNT(*),SUM(fencing_token),SUM(data_revision),MAX(updated_at)) FROM " + f.coord), drift);
            assertTrue(refused.getMessage().startsWith("UNKNOWN_SCHEMA"), drift + " -> " + refused.getMessage());
            assertEquals(before, f.columns(f.coord), drift);
            assertTrue(f.columns(f.migration).isEmpty(), drift);
        }
    }

    @Test void placeholderOnlyDomainsNeedAnExplicitClusterPolicy() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(2).get(0);
        f.stopLegacy();
        // 2.1.11 defaults leave all four enabled; silently adopting or disabling them is refused.
        var refused = assertThrows(BootstrapRefusedException.class,
            () -> f.upgrade(new Configuration.SyncConfig(), "disabled", true));
        assertEquals("LEGACY_2_1_11_NEW_DOMAIN_POLICY_REQUIRED", refused.code());
        assertTrue(f.columns(f.migration).isEmpty());

        // Explicitly disabled: provenance says DISABLED, and enabling later is a normal re-enable gate.
        DatabaseManager db = f.upgrade(legacyDomainsOnly(), "disabled", true);
        String manifest = f.text("SELECT domain_provenance_manifest FROM " + f.coord + " WHERE uuid='" + player + "'");
        var provenance = DomainProvenance.read(manifest, 1L, 1L, 1L);
        assertEquals(DomainProvenance.State.DISABLED, provenance.state("statistics"));
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, provenance.state("inventory"));
        PlayerDataRepository repo = new PlayerDataRepository(db);
        AcquireResult gated = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, new Configuration.SyncConfig());
        assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION", gated.recoveryReason());
        assertTrue(gated.data().isEmpty());
    }

    @Test void adoptingPlaceholderDomainsRequiresEveryStoredValueToBeThe211Placeholder() throws Exception {
        Fixture f = fixture();
        List<UUID> players = f.legacyClean(3);
        f.stopLegacy();
        f.sql("UPDATE " + f.table + " SET statistics='{\"format\":1,\"stats\":{}}' WHERE uuid='" + players.get(1) + "'");
        f.sql("UPDATE " + f.table + " SET recipe_book=NULL WHERE uuid='" + players.get(2) + "'");
        var refused = assertThrows(BootstrapRefusedException.class,
            () -> f.upgrade(new Configuration.SyncConfig(), ADOPT, true));
        assertEquals("LEGACY_2_1_11_NEW_DOMAIN_VALUES_NOT_PLACEHOLDER", refused.code());
        assertTrue(refused.getMessage().contains("statistics=1") && refused.getMessage().contains("recipeBook=1"), refused.getMessage());
        assertTrue(f.columns(f.migration).isEmpty(), "refusal happens before the bootstrap is recorded");
        assertFalse(f.columns(f.coord).contains("domain_provenance_manifest"));
    }

    @Test void acknowledgementContradictedByStoredValuesIsRefused() throws Exception {
        Fixture f = fixture();
        f.legacy.setSync("effects", false);
        f.legacyClean(2);
        f.stopLegacy();
        assertEquals(2, f.number("SELECT COUNT(*) FROM " + f.table + " WHERE effects IS NULL"));
        var refused = assertThrows(BootstrapRefusedException.class,
            () -> f.upgrade(new Configuration.SyncConfig(), ADOPT, true));
        assertEquals("LEGACY_2_1_11_DOMAIN_EVIDENCE_MISMATCH", refused.code());
        assertTrue(refused.getMessage().contains("sync.effects"));
        Configuration.SyncConfig matching = new Configuration.SyncConfig();
        matching.effects = false;
        f.upgrade(matching, ADOPT, true);
        assertEquals(DomainProvenance.State.DISABLED, DomainProvenance.read(
            f.text("SELECT MIN(domain_provenance_manifest) FROM " + f.coord), 1L, 1L, 1L).state("effects"));
    }

    @Test void bootstrapIsRefusedWhileAnOldWriterStillHoldsALiveLease() throws Exception {
        Fixture f = fixture();
        f.legacyClean(1);
        UUID online = UUID.randomUUID();
        Object ownership = f.legacy.acquire(online, "legacy-s2", 60_000);
        f.legacy.save(ownership, online, "online", Legacy211Runtime.items("inventory-online"), 20, 20, 0, false);
        f.stopLegacy();
        var refused = assertThrows(BootstrapRefusedException.class,
            () -> f.upgrade(new Configuration.SyncConfig(), ADOPT, true));
        assertEquals("LEGACY_2_1_11_BOOTSTRAP_LIVE_OWNER", refused.code());
        assertFalse(f.columns(f.coord).contains("domain_provenance_manifest"));
    }

    @Test void migratedRowsObeyTheNormalDisableThenEnableGateAndStaleRevisionRule() throws Exception {
        Fixture f = fixture();
        List<UUID> players = f.legacyClean(2);
        f.stopLegacy();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        PlayerDataRepository repo = new PlayerDataRepository(db);

        // disable -> local change -> enable on a migrated row.
        Configuration.SyncConfig itemsOff = new Configuration.SyncConfig();
        itemsOff.inventory = false; itemsOff.armor = false; itemsOff.offhand = false; itemsOff.enderchest = false;
        AcquireResult off = repo.acquireOwnershipAndLoad(players.get(0), "s1", UUID.randomUUID(), 5_000, itemsOff);
        assertEquals(AcquireStatus.ACQUIRED, off.status());
        assertTrue(repo.saveCompletePlayerData(f.currentData(players.get(0), "[local-only]"), off.ownership(), itemsOff, 5_000, true).committed());
        assertEquals(Legacy211Runtime.items("inventory-p0"), f.text("SELECT inventory FROM " + f.table + " WHERE uuid='" + players.get(0) + "'"));
        AcquireResult reenabled = repo.acquireOwnershipAndLoad(players.get(0), "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION", reenabled.recoveryReason());
        assertTrue(reenabled.data().isEmpty());

        // A bootstrap receipt is tied to its data revision like any other.
        f.sql("UPDATE " + f.coord + " SET data_revision=data_revision+1 WHERE uuid='" + players.get(1) + "'");
        AcquireResult stale = repo.acquireOwnershipAndLoad(players.get(1), "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", stale.recoveryReason());

        // The legacy exception is gone: restarting with the acknowledgement repairs nothing.
        db.close();
        DatabaseManager restarted = f.upgrade(sync, ADOPT, true);
        assertEquals(AcquireStatus.RECOVERY_REQUIRED,
            new PlayerDataRepository(restarted).acquireOwnershipAndLoad(players.get(1), "s1", UUID.randomUUID(), 5_000, sync).status());
    }

    @Test void preCoordinationPlayerRowGetsSyntheticDurableRevisionOne() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(1).get(0);
        f.stopLegacy();
        f.sql("DELETE FROM " + f.coord + " WHERE uuid='" + player + "'");
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        assertEquals("1|1|1|0", f.text("SELECT CONCAT_WS('|',data_revision,domain_provenance_revision,domain_provenance_generation,fencing_token) FROM "
            + f.coord + " WHERE uuid='" + player + "'"));
        db.close();
        // Restarting never advances a synthetic revision again.
        DatabaseManager again = f.upgrade(sync, ADOPT, true);
        assertEquals("1|1|1|0", f.text("SELECT CONCAT_WS('|',data_revision,domain_provenance_revision,domain_provenance_generation,fencing_token) FROM "
            + f.coord + " WHERE uuid='" + player + "'"));
        assertEquals(1, f.number("SELECT synthetic_revision_rows FROM " + f.migration));
        AcquireResult acquired = new PlayerDataRepository(again).acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, acquired.status());
        assertEquals(Legacy211Runtime.items("inventory-p0"), acquired.data().orElseThrow().inventoryJson);
    }

    @Test void productionShapedSchemaConfigurationAndRowShapesUpgrade() throws Exception {
        Fixture f = new Fixture(true);
        fixtures.add(f);
        // Production 2.1.11 configuration: aggregate position and gamemode were not synchronized.
        f.legacy.setSync("position", false);
        f.legacy.setSync("gamemode", false);
        List<UUID> clean = f.legacyClean(3);
        // A session whose backend died: still owned, lease expired, never flagged by 2.1.11.
        UUID crashed = UUID.randomUUID();
        Object ownership = f.legacy.acquire(crashed, "legacy-s1", 5_000);
        f.legacy.save(ownership, crashed, "crashed", Legacy211Runtime.items("inventory-crashed"), 20, 20, 0, false);
        f.stopLegacy();
        f.sql("UPDATE " + f.coord + " SET lease_until=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6)) WHERE uuid='" + crashed + "'");
        // Most production players have PlayerData from before the coordination table existed.
        f.sql("DELETE FROM " + f.coord + " WHERE uuid='" + clean.get(2) + "'");
        try (Connection c = f.raw()) {
            var detection = SchemaGeneration.detect(c, f.table, f.coord, f.audit);
            assertEquals(SchemaGeneration.Generation.LEGACY_2_1_11, detection.generation(), detection.detail());
        }

        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        sync.position = false; sync.dimension = false; sync.rotation = false; sync.gamemode = false;
        DatabaseManager db = f.upgrade(sync, ADOPT, true);
        var provenance = DomainProvenance.read(f.text("SELECT MIN(domain_provenance_manifest) FROM " + f.coord), 1L, 1L, 1L);
        for (String off : new String[] {"position", "dimension", "rotation", "gamemode"}) {
            assertEquals(DomainProvenance.State.DISABLED, provenance.state(off), off);
        }
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, provenance.state("inventory"));
        assertEquals(4, f.number("SELECT row_count FROM " + f.migration));
        PlayerDataRepository repo = new PlayerDataRepository(db);
        for (UUID player : clean) {
            AcquireResult acquired = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
            assertEquals(AcquireStatus.ACQUIRED, acquired.status());
            assertTrue(acquired.data().orElseThrow().inventoryJson.contains("inventory-p"));
        }
        AcquireResult unclean = repo.acquireOwnershipAndLoad(crashed, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, unclean.status());
        assertEquals("LEASE_EXPIRED_UNCLEAN", unclean.recoveryReason());
        RecoveryStatus status = repo.recoveryStatus(crashed).orElseThrow();
        assertEquals(RecoveryResolutionStatus.RESOLVED, repo.resolveAutomaticLastCheckpoint(crashed, status.fence(),
            status.revision(), UUID.randomUUID(), "auto:s1", 0, 86_400, 12, 5, true).status());
        AcquireResult recovered = repo.acquireOwnershipAndLoad(crashed, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, recovered.status());
        assertEquals(Legacy211Runtime.items("inventory-crashed"), recovered.data().orElseThrow().inventoryJson);
        // Enabling a domain 2.1.11 never synchronized here is still an explicit reconciliation.
        Configuration.SyncConfig withGamemode = new Configuration.SyncConfig();
        withGamemode.position = false; withGamemode.dimension = false; withGamemode.rotation = false;
        f.sql("UPDATE " + f.coord + " SET owner_server=NULL,owner_session=NULL,lease_until=NULL WHERE uuid='" + clean.get(0) + "'");
        assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION",
            repo.acquireOwnershipAndLoad(clean.get(0), "s1", UUID.randomUUID(), 5_000, withGamemode).recoveryReason());
    }

    @Test void semanticallyIdenticalDefaultSpellingIsStillRecognizedAsLegacy() throws Exception {
        Fixture f = fixture();
        f.legacyClean(1);
        f.stopLegacy();
        f.sql("ALTER TABLE " + f.table + " MODIFY health DOUBLE DEFAULT 20, MODIFY is_flying TINYINT(1) DEFAULT 0, "
            + "MODIFY gamemode VARCHAR(16) DEFAULT 'survival'");
        try (Connection c = f.raw()) {
            var detection = SchemaGeneration.detect(c, f.table, f.coord, f.audit);
            assertEquals(SchemaGeneration.Generation.LEGACY_2_1_11, detection.generation(), detection.detail());
        }
    }

    @Test void legacyBacklogHoursOldRecoversAutomaticallyUnderTheLegacyAgeOnlyWhileItsCheckpointIsLegacy() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyBacklog("LOCAL_AUTHORITY_LOST");
        f.stopLegacy();
        // The backend died six hours ago.
        f.sql("UPDATE " + f.coord + " SET last_checkpoint_at=TIMESTAMPADD(SECOND,-21660,CURRENT_TIMESTAMP(6)),"
            + "recovery_required_at=TIMESTAMPADD(HOUR,-6,CURRENT_TIMESTAMP(6)),lease_until=TIMESTAMPADD(HOUR,-6,CURRENT_TIMESTAMP(6))");
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        PlayerDataRepository repo = new PlayerDataRepository(f.upgrade(sync, ADOPT, true));
        RecoveryStatus status = repo.recoveryStatus(player).orElseThrow();
        int normalAge = 30, legacyAge = new Configuration.MigrationConfig().legacy211RecoveryMaxCheckpointAgeSeconds;
        // The checkpoint was already 60s old at failure (normal limit 30s), not merely offline
        // for six hours. Only the explicitly acknowledged legacy age policy can admit it.
        for (int retry = 0; retry < 25; retry++) {
            assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT, repo.resolveAutomaticLastCheckpoint(player, status.fence(),
                status.revision(), UUID.randomUUID(), "auto:s1", 0, normalAge, normalAge, 12, 5, true).status());
        }
        assertEquals(0, repo.recoveryStatus(player).orElseThrow().automaticAttempts());
        // Defaults: normal age 30s, legacy backlog age from MigrationConfig. No operator command.
        assertEquals(RecoveryResolutionStatus.RESOLVED, repo.resolveAutomaticLastCheckpoint(player, status.fence(),
            status.revision(), UUID.randomUUID(), "auto:s1", 0, normalAge, legacyAge, 12, 5, true).status());
        AcquireResult acquired = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, acquired.status());
        assertEquals(Legacy211Runtime.items("inventory-backlog"), acquired.data().orElseThrow().inventoryJson);

        // Crash before the first 2.2.x checkpoint: the durable checkpoint is still the legacy one.
        f.sql("UPDATE " + f.coord + " SET lease_until=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6)) WHERE uuid='" + player + "'");
        status = repo.recoveryStatus(player).orElseThrow();
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).status());
        assertEquals(RecoveryResolutionStatus.RESOLVED, repo.resolveAutomaticLastCheckpoint(player, status.fence(),
            status.revision(), UUID.randomUUID(), "auto:s1", 0, normalAge, legacyAge, 12, 5, true).status());

        // After one checkpoint by this release, a later crash is an ordinary crash: normal age only.
        acquired = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertTrue(repo.saveCompletePlayerData(f.currentData(player, "[]"), acquired.ownership(), sync, 5_000, false).committed());
        f.sql("UPDATE " + f.coord + " SET lease_until=TIMESTAMPADD(HOUR,-6,CURRENT_TIMESTAMP(6)),"
            + "last_checkpoint_at=TIMESTAMPADD(SECOND,-21660,CURRENT_TIMESTAMP(6)) WHERE uuid='" + player + "'");
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).status());
        status = repo.recoveryStatus(player).orElseThrow();
        assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT, repo.resolveAutomaticLastCheckpoint(player, status.fence(),
            status.revision(), UUID.randomUUID(), "auto:s1", 0, normalAge, legacyAge, 12, 5, true).status(),
            "the legacy age must not leak to a checkpoint written by 2.2.x");
        assertEquals(1, f.number("SELECT recovery_required FROM " + f.coord + " WHERE uuid='" + player + "'"));
    }

    @Test void preCoordinationPlayerCrashingBeforeItsFirstCheckpointIsAutomaticallyRecoverable() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(1).get(0);
        f.stopLegacy();
        f.sql("DELETE FROM " + f.coord + " WHERE uuid='" + player + "'");
        f.sql("UPDATE " + f.table + " SET last_login=TIMESTAMPADD(DAY,-20,CURRENT_TIMESTAMP)");
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        PlayerDataRepository repo = new PlayerDataRepository(f.upgrade(sync, ADOPT, true));
        assertEquals(1, f.number("SELECT synthetic_revision_rows FROM " + f.migration));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.coord + " c JOIN " + f.table
            + " p ON p.uuid=c.uuid WHERE c.last_checkpoint_at=p.last_login AND c.checkpoint_source_version IS NULL"));
        AcquireResult first = repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, first.status());
        assertEquals(1, first.ownership().dataRevision());
        // READY, then the backend dies before any checkpoint.
        f.sql("UPDATE " + f.coord + " SET lease_until=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6))");
        AcquireResult denied = repo.acquireOwnershipAndLoad(player, "s2", UUID.randomUUID(), 5_000, sync);
        assertEquals("LEASE_EXPIRED_UNCLEAN", denied.recoveryReason());
        RecoveryStatus status = repo.recoveryStatus(player).orElseThrow();
        assertEquals(RecoveryResolutionStatus.RESOLVED, repo.resolveAutomaticLastCheckpoint(player, status.fence(),
            status.revision(), UUID.randomUUID(), "auto:s2", 0, 30,
            new Configuration.MigrationConfig().legacy211RecoveryMaxCheckpointAgeSeconds, 12, 5, true).status(),
            "no manual force may be needed in the window before the first checkpoint");
        AcquireResult recovered = repo.acquireOwnershipAndLoad(player, "s2", UUID.randomUUID(), 5_000, sync);
        assertEquals(AcquireStatus.ACQUIRED, recovered.status());
        assertEquals(Legacy211Runtime.items("inventory-p0"), recovered.data().orElseThrow().inventoryJson);
        // The first real SAVE is revision 2 and carries its own provenance.
        assertEquals(2, repo.saveCompletePlayerData(f.currentData(player, "[]"), recovered.ownership(), sync, 5_000, false).dataRevision());
        assertEquals(2, f.number("SELECT domain_provenance_revision FROM " + f.coord));
    }

    @Test void everyBackendOfABootstrappedDatabaseMustMatchTheClusterFingerprintEvenWithoutMigrationFlags() throws Exception {
        Fixture f = fixture();
        UUID player = f.legacyClean(1).get(0);
        f.stopLegacy();
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        f.upgrade(sync, ADOPT, true).close();
        String recorded = f.text("SELECT cluster_fingerprint FROM " + f.migration);
        assertEquals(Legacy211Bootstrap.clusterFingerprint(sync, ADOPT), recorded);

        // No bootstrap flags, default policy string in its config: identical switches start normally.
        f.open(new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(new Configuration.SyncConfig(), false, "", "disabled", "s2"))).close();
        // No bootstrap flags, different switches: refused at startup, before any player can load.
        for (String field : new String[] {"effects", "gamemode", "statistics", "playerProfile"}) {
            Configuration.SyncConfig other = new Configuration.SyncConfig();
            Configuration.SyncConfig.class.getField(field).setBoolean(other, false);
            var refused = assertThrows(BootstrapRefusedException.class, () -> f.open(new DatabaseManager(f.config, f.coord,
                new LegacyBootstrapRequest(other, false, "", "disabled", "s2"))), field);
            assertEquals("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH", refused.code(), field);
        }
        Configuration.SyncConfig itemsOff = new Configuration.SyncConfig();
        itemsOff.inventory = false; itemsOff.armor = false; itemsOff.offhand = false; itemsOff.enderchest = false;
        assertEquals("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH", assertThrows(BootstrapRefusedException.class, () -> f.open(
            new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(itemsOff, false, "", "disabled", "s2")))).code());
        assertEquals(recorded, f.text("SELECT cluster_fingerprint FROM " + f.migration));
        // Unrelated settings do not affect it.
        Configuration.SyncConfig unrelated = new Configuration.SyncConfig();
        unrelated.serverId = "another"; unrelated.leaseDurationMs = 9_000; unrelated.syncOnDimensionChange = false;
        f.open(new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(unrelated, false, "", "disabled", "another"))).close();

        // A deliberate cluster-wide change needs exactly the new fingerprint; a wrong value changes nothing.
        Configuration.SyncConfig noEffects = new Configuration.SyncConfig();
        noEffects.effects = false;
        String changed = Legacy211Bootstrap.clusterFingerprint(noEffects, ADOPT);
        assertEquals("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH", assertThrows(BootstrapRefusedException.class, () -> f.open(
            new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(noEffects, false, "", "disabled", "s1", recorded, false)))).code());
        DatabaseManager moved = f.open(new DatabaseManager(f.config, f.coord,
            new LegacyBootstrapRequest(noEffects, false, "", "disabled", "s1", changed, false)));
        assertEquals(changed, f.text("SELECT cluster_fingerprint FROM " + f.migration));
        assertEquals(1, f.number("SELECT cluster_fingerprint_changed_at IS NOT NULL FROM " + f.migration));
        assertEquals(recorded, f.text("SELECT config_fingerprint FROM " + f.migration), "the bootstrap record itself is immutable");
        // The backend that still has the old switches is now the one that is refused.
        assertEquals("CLUSTER_DOMAIN_FINGERPRINT_MISMATCH", assertThrows(BootstrapRefusedException.class, () -> f.open(
            new DatabaseManager(f.config, f.coord, new LegacyBootstrapRequest(sync, false, "", "disabled", "s2")))).code());
        // Changing the cluster switches does not bypass per-player provenance: effects is recorded as disabled on acquire.
        PlayerDataRepository repo = new PlayerDataRepository(moved);
        assertEquals(AcquireStatus.ACQUIRED, repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, noEffects).status());
        f.sql("UPDATE " + f.coord + " SET owner_server=NULL,owner_session=NULL,lease_until=NULL");
        assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION",
            repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).recoveryReason());
    }

    @Test void undecodableLegacyRowStopsTheBootstrapBeforeAnyChangeUnlessExplicitlyQuarantined() throws Exception {
        Fixture f = fixture();
        List<UUID> players = f.legacyClean(3);
        // A row the released 2.1.11 repository accepted but no LOAD could decode.
        UUID broken = UUID.randomUUID();
        Object ownership = f.legacy.acquire(broken, "legacy-s1", 5_000);
        f.legacy.save(ownership, broken, "broken", "{\"not\":\"an array\"}", 20, 20, 0, true);
        f.stopLegacy();
        Set<String> before = f.columns(f.coord);
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        var refused = assertThrows(BootstrapRefusedException.class, () -> f.upgrade(sync, ADOPT, true));
        assertEquals("LEGACY_2_1_11_PREFLIGHT_FAILED", refused.code());
        assertTrue(refused.getMessage().startsWith("LEGACY_2_1_11_PREFLIGHT_FAILED: 1 of 4"), refused.getMessage());
        assertFalse(refused.getMessage().contains("an array"), "row contents must not be echoed");
        assertEquals(before, f.columns(f.coord));
        assertTrue(f.columns(f.migration).isEmpty());

        DatabaseManager db = f.open(new DatabaseManager(f.config, f.coord,
            new LegacyBootstrapRequest(sync, true, "", ADOPT, "s1", "", true)));
        assertEquals("COMPLETED|4|1", f.text("SELECT CONCAT_WS('|',state,row_count,quarantined_rows) FROM " + f.migration));
        assertEquals(3, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE domain_provenance_manifest IS NOT NULL"));
        assertEquals(1, f.number("SELECT COUNT(*) FROM " + f.coord + " WHERE uuid='" + broken + "' AND domain_provenance_manifest IS NULL"));
        PlayerDataRepository repo = new PlayerDataRepository(db);
        for (UUID player : players) {
            assertEquals(AcquireStatus.ACQUIRED, repo.acquireOwnershipAndLoad(player, "s1", UUID.randomUUID(), 5_000, sync).status());
        }
        AcquireResult isolated = repo.acquireOwnershipAndLoad(broken, "s1", UUID.randomUUID(), 5_000, sync);
        assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", isolated.recoveryReason());
        assertTrue(isolated.data().isEmpty(), "an undecodable row is never loaded or partially applied");
    }

    /** One isolated set of tables, created by the released 2.1.11 artifact. */
    private static final class Fixture {
        final Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        final String table, coord, audit, migration;
        final List<DatabaseManager> opened = new ArrayList<>();
        Legacy211Runtime legacy;

        Fixture() throws Exception { this(false); }

        /** {@code productionShaped}: tables pre-created from the read-only production DDL capture. */
        Fixture(boolean productionShaped) throws Exception {
            config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
            config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
            config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
            config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
            config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
            config.maxConnections = 4;
            config.tableName = "pdc_l211_" + UUID.randomUUID().toString().replace("-", "");
            table = config.tableName;
            coord = table + "_sync_state";
            audit = DatabaseManager.recoveryAuditTableName(coord);
            migration = DatabaseManager.migrationTableName(coord);
            if (productionShaped) {
                String ddl;
                try (var in = Legacy211MigrationMySqlIntegrationTest.class.getResourceAsStream("/production-2.1.11-schema.sql")) {
                    ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                ddl = ddl.replace("`player_data_sync_state_recovery_audit`", "`" + audit + "`")
                    .replace("`player_data_sync_state`", "`" + coord + "`").replace("`player_data`", "`" + table + "`");
                for (String statement : ddl.split(";")) {
                    String body = statement.lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
                    if (!body.isEmpty()) sql(body);
                }
            }
            legacy = new Legacy211Runtime(config, coord);
        }

        /** Players who logged in, were checkpointed and cleanly released by 2.1.11. */
        List<UUID> legacyClean(int count) throws Exception {
            List<UUID> players = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID player = UUID.randomUUID();
                Object ownership = legacy.acquire(player, "legacy-s1", 5_000);
                legacy.save(ownership, player, "p" + i, Legacy211Runtime.items("inventory-p" + i), 17.5, 13, 30, false);
                legacy.save(ownership, player, "p" + i, Legacy211Runtime.items("inventory-p" + i), 17.5, 13, 30, true);
                players.add(player);
            }
            // Deterministic order for index-based assertions.
            return players;
        }

        /** A player 2.1.11 itself moved to RECOVERY_REQUIRED, whose lease then expired. */
        UUID legacyBacklog(String reason) throws Exception {
            UUID player = UUID.randomUUID();
            Object ownership = legacy.acquire(player, "legacy-s1", 5_000);
            legacy.save(ownership, player, "backlog", Legacy211Runtime.items("inventory-backlog"), 9.0, 8, 4, false);
            legacy.markRecoveryRequired(ownership, reason);
            try (Connection c = raw(); Statement s = c.createStatement()) {
                // Time passing after the 2.1.11 backend died: only the lease deadline moves.
                s.execute("UPDATE " + coord + " SET lease_until=TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6)) WHERE uuid='" + player + "'");
            }
            return player;
        }

        void stopLegacy() throws Exception { if (legacy != null) { legacy.close(); legacy = null; } }

        DatabaseManager open(DatabaseManager db) { opened.add(db); return db; }

        DatabaseManager upgrade(Configuration.SyncConfig sync, String policy, boolean acknowledged) {
            return open(new DatabaseManager(config, coord, new LegacyBootstrapRequest(sync, acknowledged, "", policy, "s1")));
        }

        DatabaseManager upgradeUnchecked(Configuration.SyncConfig sync, String policy, boolean acknowledged) {
            DatabaseManager db = new DatabaseManager(config, coord, new LegacyBootstrapRequest(sync, acknowledged, "", policy, "s1"));
            synchronized (opened) { opened.add(db); }
            return db;
        }

        CompletePlayerData currentData(UUID player, String inventory) {
            return new CompletePlayerData(player, "current", inventory, "[]", "[]", "[]",
                20, 20, 5, 0, 300, 0, 0, 0, null, "[]", "minecraft:overworld", 0, 64, 0, 0, 0,
                "survival", false, false, false, "current", null, null, "{}", "{}", "{}", 0, 0, 1);
        }

        Connection raw() throws SQLException {
            return DriverManager.getConnection("jdbc:mysql://" + config.host + ":" + config.port + "/" + config.name
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo", config.username, config.password);
        }

        void sql(String sql) throws SQLException {
            try (Connection c = raw(); Statement s = c.createStatement()) { s.execute(sql); }
        }

        long number(String sql) throws SQLException {
            try (Connection c = raw(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }

        String text(String sql) throws SQLException {
            try (Connection c = raw(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }

        Set<String> columns(String name) throws SQLException {
            Set<String> result = new TreeSet<>();
            try (Connection c = raw(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + name + "'")) {
                while (r.next()) result.add(r.getString(1).toLowerCase(java.util.Locale.ROOT));
            }
            return result;
        }

        void dropAll() throws SQLException {
            for (String name : new String[] {migration, audit, coord, table}) sql("DROP TABLE IF EXISTS " + name);
        }

        void close() throws Exception {
            for (DatabaseManager db : opened) db.close();
            stopLegacy();
            dropAll();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
