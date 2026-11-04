package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.sync.PlayerSessionContext;
import com.atsukigames.statelink.sync.SyncState;
import com.atsukigames.statelink.utils.SnapshotSerializationException;
import com.atsukigames.statelink.utils.ExperienceProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicitly opt-in MySQL integration coverage.
 *
 * <p>Set STATELINK_TEST_MYSQL_DATABASE (and optionally the other STATELINK_TEST_MYSQL_*
 * variables) to a dedicated, disposable database. Production credentials are
 * intentionally never used by default.</p>
 */
@EnabledIfEnvironmentVariable(named = "STATELINK_TEST_MYSQL_DATABASE", matches = ".+")
class PlayerDataRepositoryMySqlIntegrationTest {
    @Test void attestationUsesCaptureTimeAfterDelayedDatabaseQueueing() throws Exception {
        var cfg = testDatabaseConfig();
        String coordinationTable = cfg.tableName + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(cfg, coordinationTable);
            var repository = new PlayerDataRepository(database);
            var sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            var acquired = repository.acquireOwnershipAndLoad(uuid, "attest-time", UUID.randomUUID(), 5_000);
            var snapshot = sample(uuid, "attest-time", "[capture-state]", "[]");
            assertTrue(repository.saveCompletePlayerData(snapshot, acquired.ownership(), sync, 5_000, false).committed());

            long capturedAtNanos = System.nanoTime();
            long capturedVersion = 11L;
            Thread.sleep(1_500L); // deterministic queue delay before the repository obtains DB time
            var result = repository.attestVerifiedState(snapshot, acquired.ownership(), sync,
                capturedAtNanos, capturedVersion, () -> capturedVersion);

            assertEquals(PlayerDataRepository.StateAttestationStatus.ATTESTED, result.status());
            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT TIMESTAMPDIFF(MICROSECOND, verified_state_at, CURRENT_TIMESTAMP(6)) AS age_us FROM "
                         + coordinationTable + " WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.getLong("age_us") >= 1_500_000L,
                        "verified_state_at must not be replaced with the later DB operation time");
                }
            }
            assertTrue(repository.releaseOwnership(acquired.ownership()));
        } finally {
            dropTables(database, cfg.tableName, coordinationTable);
        }
    }

    @Test void inFlightMutationAbortsAttestationBeforeVerifiedStateCommit() throws Exception {
        var cfg = testDatabaseConfig();
        String coordinationTable = cfg.tableName + "_sync_state";
        DatabaseManager database = null;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            database = new DatabaseManager(cfg, coordinationTable);
            var repository = new PlayerDataRepository(database);
            var sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            var acquired = repository.acquireOwnershipAndLoad(uuid, "attest-race", UUID.randomUUID(), 5_000);
            var snapshot = sample(uuid, "attest-race", "[captured-state]", "[]");
            assertTrue(repository.saveCompletePlayerData(snapshot, acquired.ownership(), sync, 5_000, false).committed());

            var dirty = new com.atsukigames.statelink.sync.CheckpointDirtyState();
            dirty.committed(dirty.version());
            long capturedVersion = dirty.version();
            long capturedAtNanos = System.nanoTime();
            CountDownLatch epochCheckEntered = new CountDownLatch(1);
            CountDownLatch allowEpochCheck = new CountDownLatch(1);
            AtomicBoolean firstCheck = new AtomicBoolean(true);
            Future<PlayerDataRepository.StateAttestationResult> result = worker.submit(() ->
                repository.attestVerifiedState(snapshot, acquired.ownership(), sync, capturedAtNanos,
                    capturedVersion, () -> {
                        if (firstCheck.compareAndSet(true, false)) {
                            epochCheckEntered.countDown();
                            try {
                                if (!allowEpochCheck.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test did not release attestation epoch check");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                        }
                        return dirty.version();
                    }));

            assertTrue(epochCheckEntered.await(10, TimeUnit.SECONDS), "attestation did not reach its in-flight epoch gate");
            dirty.markChanged(); // Player mutation becomes visible while the MySQL transaction is open.
            allowEpochCheck.countDown();
            assertEquals(PlayerDataRepository.StateAttestationStatus.DATA_CHANGED,
                result.get(10, TimeUnit.SECONDS).status());
            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT verified_state_at FROM " + coordinationTable + " WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertNull(rows.getTimestamp(1), "aborted in-flight attestation must leave no verified timestamp");
                }
            }
            assertTrue(dirty.isDirty(), "rejected attestation cannot commit the mutation's dirty epoch");
            assertTrue(repository.releaseOwnership(acquired.ownership()));
        } finally {
            worker.shutdownNow();
            dropTables(database, cfg.tableName, coordinationTable);
        }
    }

    @Test void reenablingDisabledDomainRequiresExplicitReconciliationBeforeAnyLoad() throws Exception {
        var cfg=testDatabaseConfig(); String coord=cfg.tableName+"_sync_state"; DatabaseManager db=null;
        try {
            db=new DatabaseManager(cfg,coord); var repo=new PlayerDataRepository(db); var id=UUID.randomUUID();
            var disabled=new Configuration.SyncConfig(); disabled.inventory=false; disabled.position=false;
            disabled.dimension=false; disabled.rotation=false; disabled.advancements=false; disabled.statistics=false;
            var owner=repo.acquireOwnershipAndLoad(id,"history-s1",UUID.randomUUID(),5000,disabled).ownership();
            assertTrue(repo.saveCompletePlayerData(sample(id,"history","[]","[]"),owner,disabled,5000,true).committed());
            for(int i=0;i<2;i++) {
                var denied=repo.acquireOwnershipAndLoad(id,"history-s2",UUID.randomUUID(),5000,new Configuration.SyncConfig());
                assertEquals(PlayerDataRepository.AcquireStatus.RECOVERY_REQUIRED,denied.status());
                assertNull(denied.ownership()); assertTrue(denied.data().isEmpty());
            }
            try(var c=db.getConnection(); var stmt=c.createStatement(); var r=stmt.executeQuery(
                    "SELECT recovery_required,recovery_reason,fencing_token,owner_session FROM "+coord+" WHERE uuid='"+id+"'")) {
                assertTrue(r.next()); assertTrue(r.getBoolean(1));
                assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION",r.getString(2));
                assertEquals(owner.fencingToken(),r.getLong(3)); assertNull(r.getString(4));
            }
        } finally { dropTables(db,cfg.tableName,coord); }
    }
    @Test void disabledSpatialFieldsArePreservedAndRotationOnlySaveIsProjected() throws Exception {
        var cfg=testDatabaseConfig(); String coord=cfg.tableName+"_sync_state"; DatabaseManager db=null;
        try {
            db=new DatabaseManager(cfg,coord); var repo=new PlayerDataRepository(db); var id=UUID.randomUUID();
            var owner=repo.acquireOwnershipAndLoad(id,"spatial-s1",UUID.randomUUID(),5000).ownership();
            var gson=new com.google.gson.Gson(); var tree=gson.toJsonTree(sample(id,"spatial","[]","[]")).getAsJsonObject();
            tree.addProperty("dimension","minecraft:the_nether"); tree.addProperty("posX",789); tree.addProperty("yaw",123);
            var old=gson.fromJson(tree,PlayerDataRepository.CompletePlayerData.class);
            assertTrue(repo.saveCompletePlayerData(old,owner,new Configuration.SyncConfig(),5000,false).committed());
            var off=new Configuration.SyncConfig(); off.position=false; off.dimension=false; off.rotation=false;
            assertTrue(repo.saveCompletePlayerData(sample(id,"spatial","[]","[]"),owner,off,5000,false).committed());
            var loaded=repo.loadCompletePlayerData(id).orElseThrow();
            assertEquals("minecraft:the_nether",loaded.dimension); assertEquals(789,loaded.posX); assertEquals(123,loaded.yaw);
            off.rotation=true;
            assertTrue(repo.saveCompletePlayerData(sample(id,"spatial","[]","[]"),owner,off,5000,true).committed());
            loaded=repo.loadCompletePlayerData(id).orElseThrow();
            assertEquals("minecraft:the_nether",loaded.dimension); assertEquals(789,loaded.posX); assertEquals(0,loaded.yaw);
        } finally { dropTables(db,cfg.tableName,coord); }
    }
    @Test
    void exactIntegerXpRepresentationSurvivesRealMysqlBoundaryRoundTrips() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-xp", UUID.randomUUID(), 5_000);

            for (int level : new int[] {0, 1, 15, 16, 29, 30, 31, 100}) {
                long cost = ExperienceProgress.experienceToNextLevel(level);
                long into = cost - 1;
                int cumulative = Math.toIntExact(ExperienceProgress.totalExperienceAtLevel(level) + into);
                float progress = (float) into / (float) cost;
                PlayerDataRepository.CompletePlayerData snapshot = sample(
                    uuid, "xp-boundary", "[]", "[]").withExperienceState(
                        level, cumulative, cumulative, progress, into);

                assertTrue(repository.saveCompletePlayerData(
                    snapshot, acquired.ownership(), sync, 5_000, false).committed());
                PlayerDataRepository.CompletePlayerData loaded =
                    repository.loadCompletePlayerData(uuid).orElseThrow();

                assertEquals(into, loaded.experiencePointsIntoLevel, "level=" + level);
                assertEquals(cumulative, loaded.experiencePoints, "cumulative XP level=" + level);
                float restored = ExperienceProgress.resolve(
                    loaded.experienceLevel, loaded.experiencePoints,
                    loaded.experiencePointsIntoLevel, loaded.experienceProgress);
                assertEquals(progress, restored, 0.0F, "progress level=" + level);
                assertEquals(level + 1, addOneXp(level, restored),
                    "one XP must cross the same boundary after JDBC roundtrip level=" + level);
            }
            assertTrue(repository.releaseOwnership(acquired.ownership()));
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void ownershipFenceFinalFlushAndDisabledColumnsUseRealMysqlTransactions() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            Configuration.SyncConfig allFields = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            UUID sessionOne = UUID.randomUUID();
            UUID sessionTwo = UUID.randomUUID();

            PlayerDataRepository.AcquireResult firstAcquire = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", sessionOne, 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, firstAcquire.status());
            assertTrue(firstAcquire.data().isEmpty());

            PlayerDataRepository.CompletePlayerData first = sample(
                uuid, "first", "[main]", "[ender-v1]");
            assertTrue(repository.saveCompletePlayerData(
                first, firstAcquire.ownership(), allFields, 5_000, false).committed());

            PlayerDataRepository.AcquireResult busy = repository.acquireOwnershipAndLoad(
                uuid, "integration-s2", sessionTwo, 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.BUSY, busy.status());

            assertTrue(repository.saveCompletePlayerData(
                first, firstAcquire.ownership(), allFields, 5_000, true).committed());

            PlayerDataRepository.AcquireResult secondAcquire = repository.acquireOwnershipAndLoad(
                uuid, "integration-s2", sessionTwo, 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, secondAcquire.status());
            assertNotNull(secondAcquire.ownership());
            assertEquals(Optional.of("[ender-v1]"), secondAcquire.data().map(data -> data.enderchestJson));

            Configuration.SyncConfig enderDisabled = new Configuration.SyncConfig();
            enderDisabled.enderchest = false;
            PlayerDataRepository.CompletePlayerData second = sample(
                uuid, "second", "[main-v2]", null);
            assertTrue(repository.saveCompletePlayerData(
                second, secondAcquire.ownership(), enderDisabled, 5_000, true).committed());

            PlayerDataRepository.AcquireResult thirdAcquire = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000, enderDisabled);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, thirdAcquire.status());
            assertEquals(Optional.of("[ender-v1]"), thirdAcquire.data().map(data -> data.enderchestJson));

            PlayerDataRepository.SaveResult stale = repository.saveCompletePlayerData(
                second, secondAcquire.ownership(), allFields, 5_000, false);
            assertEquals(PlayerDataRepository.SaveStatus.FENCED, stale.status());
            assertFalse(repository.releaseOwnership(secondAcquire.ownership()));
        } finally {
            if (database != null) {
                try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + playerTable);
                    statement.executeUpdate("DROP TABLE IF EXISTS " + coordinationTable);
                } finally {
                    database.close();
                }
            }
        }
    }

    @Test
    void activeTransientCheckpointSurvivesFinalRollbackAndExplicitRecoveryOnly() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult s1 = repository.acquireOwnershipAndLoad(
                uuid, "overlay-s1", UUID.randomUUID(), 5_000);

            String pendingDiamond = "[{\"slot\":0,\"item\":\"minecraft:diamond\",\"count\":1}]";
            PlayerDataRepository.CompletePlayerData baseline = sample(
                uuid, "overlay", "[diamond]", "[]").withInventoryAndPendingItems("[diamond]", null);
            assertTrue(repository.saveCompletePlayerData(
                baseline, s1.ownership(), sync, 5_000, false).committed());

            // Each checkpoint must replace the prior live-transient overlay. These
            // snapshots model a diamond still held by the cursor on S1.
            PlayerDataRepository.CompletePlayerData cursorCheckpoint = sample(
                uuid, "overlay", "[]", "[]").withInventoryAndPendingItems("[]", pendingDiamond);
            for (int i = 0; i < 3; i++) {
                assertTrue(repository.saveCompletePlayerData(
                    cursorCheckpoint, s1.ownership(), sync, 5_000, false).committed());
                PlayerDataRepository.CompletePlayerData loaded =
                    repository.loadCompletePlayerData(uuid).orElseThrow();
                assertEquals("[]", loaded.inventoryJson);
                assertEquals(pendingDiamond, loaded.pendingDisconnectItemsJson);
            }

            PlayerDataRepository failingRepository = new PlayerDataRepository(database, (phase, finalFlush) -> {
                if (finalFlush && phase == PlayerDataRepository.TransactionPhase.AFTER_PLAYER_DATA_WRITE) {
                    throw new SQLException("injected final failure after successful transient checkpoints");
                }
            });
            PlayerDataRepository.CompletePlayerData failedFinal = sample(
                uuid, "overlay-final", "[]", "[]").withInventoryAndPendingItems("[]", null);
            assertThrows(SQLException.class, () -> failingRepository.saveCompletePlayerData(
                failedFinal, s1.ownership(), sync, 5_000, true));
            assertEquals(pendingDiamond, repository.loadCompletePlayerData(uuid).orElseThrow()
                .pendingDisconnectItemsJson);
            assertEquals(s1.ownership().ownerSession().toString(),
                readOwnerSession(database, coordinationTable, uuid));

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }
            assertRecoveryDenied(repository, uuid, "overlay-s2");
            explicitReviewedRecovery(repository, uuid, s1.ownership().fencingToken());
            PlayerDataRepository.AcquireResult s2 = repository.acquireOwnershipAndLoad(
                uuid, "overlay-s2", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, s2.status());
            assertTrue(s2.ownership().fencingToken() > s1.ownership().fencingToken());
            PlayerDataRepository.CompletePlayerData takeoverLoad = s2.data().orElseThrow();
            assertEquals("[]", takeoverLoad.inventoryJson);
            assertEquals(pendingDiamond, takeoverLoad.pendingDisconnectItemsJson);

            // Atomic normalization changes the authoritative representation from
            // pending to inventory. A subsequent LOAD sees it once, in one place.
            PlayerDataRepository.CompletePlayerData normalized = sample(
                uuid, "overlay-s2", "[diamond]", "[]").withInventoryAndPendingItems("[diamond]", null);
            assertTrue(repository.saveCompletePlayerData(
                normalized, s2.ownership(), sync, 5_000, false).committed());
            PlayerDataRepository.CompletePlayerData normalizedLoad =
                repository.loadCompletePlayerData(uuid).orElseThrow();
            assertEquals("[diamond]", normalizedLoad.inventoryJson);
            assertEquals(null, normalizedLoad.pendingDisconnectItemsJson);
            assertTrue(repository.saveCompletePlayerData(
                normalized, s2.ownership(), sync, 5_000, true).committed());
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void repeatedTransientCheckpointsReplacePendingAndRequireReviewedRecoveryAfterFailure() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "checkpoint-s1", UUID.randomUUID(), 5_000);

            String cursorDiamond = "[{\"slot\":0,\"item\":\"minecraft:diamond\",\"count\":1}]";
            PlayerDataRepository.CompletePlayerData baseline = sample(
                uuid, "checkpoint", "[diamond]", "[]").withInventoryAndPendingItems("[diamond]", null);
            assertTrue(repository.saveCompletePlayerData(
                baseline, acquired.ownership(), sync, 5_000, false).committed());

            // This models three read-only snapshots of the same live cursor source.
            // The coordinator composes each from the immutable context base, and the
            // repository replaces the pending column on every successful checkpoint.
            PlayerDataRepository.CompletePlayerData transientCheckpoint = sample(
                uuid, "checkpoint", "[]", "[]").withInventoryAndPendingItems("[]", cursorDiamond);
            for (int attempt = 0; attempt < 3; attempt++) {
                assertTrue(repository.saveCompletePlayerData(
                    transientCheckpoint, acquired.ownership(), sync, 5_000, false).committed());
                PlayerDataRepository.CompletePlayerData stored =
                    repository.loadCompletePlayerData(uuid).orElseThrow();
                assertEquals("[]", stored.inventoryJson);
                assertEquals(cursorDiamond, stored.pendingDisconnectItemsJson);
            }

            // When the same live item moves back from cursor to PlayerInventory,
            // the next checkpoint replaces the old overlay instead of leaving a
            // stale copy in pending_disconnect_items.
            PlayerDataRepository.CompletePlayerData movedBackToInventory = sample(
                uuid, "checkpoint", "[diamond]", "[]").withInventoryAndPendingItems("[diamond]", null);
            assertTrue(repository.saveCompletePlayerData(
                movedBackToInventory, acquired.ownership(), sync, 5_000, false).committed());
            PlayerDataRepository.CompletePlayerData movedBackLoad =
                repository.loadCompletePlayerData(uuid).orElseThrow();
            assertEquals("[diamond]", movedBackLoad.inventoryJson);
            assertNull(movedBackLoad.pendingDisconnectItemsJson);

            // Put the modeled live cursor back for the following final-write
            // failure/takeover assertion; the DB write is a replacement again.
            assertTrue(repository.saveCompletePlayerData(
                transientCheckpoint, acquired.ownership(), sync, 5_000, false).committed());

            PlayerDataRepository failingRepository = new PlayerDataRepository(database, (phase, finalFlush) -> {
                if (finalFlush && phase == PlayerDataRepository.TransactionPhase.AFTER_PLAYER_DATA_WRITE) {
                    throw new SQLException("injected failure after transient checkpoint");
                }
            });
            PlayerDataRepository.CompletePlayerData uncommittedFinal = sample(
                uuid, "checkpoint-final", "[]", "[]").withInventoryAndPendingItems("[]", null);
            assertThrows(SQLException.class, () -> failingRepository.saveCompletePlayerData(
                uncommittedFinal, acquired.ownership(), sync, 5_000, true));
            assertEquals(cursorDiamond, repository.loadCompletePlayerData(uuid).orElseThrow()
                .pendingDisconnectItemsJson);
            assertEquals(acquired.ownership().ownerSession().toString(),
                readOwnerSession(database, coordinationTable, uuid));

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }
            assertRecoveryDenied(repository, uuid, "checkpoint-s2");
            explicitReviewedRecovery(repository, uuid, acquired.ownership().fencingToken());
            PlayerDataRepository.AcquireResult takeover = repository.acquireOwnershipAndLoad(
                uuid, "checkpoint-s2", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, takeover.status());
            assertTrue(takeover.ownership().fencingToken() > acquired.ownership().fencingToken());
            PlayerDataRepository.CompletePlayerData recovered = takeover.data().orElseThrow();
            assertEquals("[]", recovered.inventoryJson);
            assertEquals(cursorDiamond, recovered.pendingDisconnectItemsJson);

            // Model the normalizer's atomic inventory + pending replacement after
            // it has room to merge the item. It must not reappear on another LOAD.
            PlayerDataRepository.CompletePlayerData normalized = sample(
                uuid, "checkpoint-s2", "[diamond]", "[]").withInventoryAndPendingItems("[diamond]", null);
            assertTrue(repository.saveCompletePlayerData(
                normalized, takeover.ownership(), sync, 5_000, false).committed());
            PlayerDataRepository.CompletePlayerData normalizedLoad =
                repository.loadCompletePlayerData(uuid).orElseThrow();
            assertEquals("[diamond]", normalizedLoad.inventoryJson);
            assertEquals(null, normalizedLoad.pendingDisconnectItemsJson);
            assertTrue(repository.saveCompletePlayerData(
                normalized, takeover.ownership(), sync, 5_000, true).committed());
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void simultaneousFirstLoginAcquisitionHasExactlyOneOwner() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            UUID sessionOne = UUID.randomUUID();
            UUID sessionTwo = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);

            Future<PlayerDataRepository.AcquireResult> first = workers.submit(() -> {
                start.await();
                return repository.acquireOwnershipAndLoad(uuid, "integration-s1", sessionOne, 5_000);
            });
            Future<PlayerDataRepository.AcquireResult> second = workers.submit(() -> {
                start.await();
                return repository.acquireOwnershipAndLoad(uuid, "integration-s2", sessionTwo, 5_000);
            });
            start.countDown();

            PlayerDataRepository.AcquireResult firstResult = first.get(10, TimeUnit.SECONDS);
            PlayerDataRepository.AcquireResult secondResult = second.get(10, TimeUnit.SECONDS);
            int owners = (firstResult.status() == PlayerDataRepository.AcquireStatus.ACQUIRED ? 1 : 0)
                + (secondResult.status() == PlayerDataRepository.AcquireStatus.ACQUIRED ? 1 : 0);
            assertEquals(1, owners, "InnoDB coordination row must elect one first-login owner");

            PlayerDataRepository.Ownership winner = firstResult.ownership() != null
                ? firstResult.ownership() : secondResult.ownership();
            assertNotNull(winner);
            assertTrue(repository.releaseOwnership(winner));
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            if (database != null) {
                try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + playerTable);
                    statement.executeUpdate("DROP TABLE IF EXISTS " + coordinationTable);
                } finally {
                    database.close();
                }
            }
        }
    }

    @Test
    void expiredOwnerCannotRenewOrWriteWithTheOldFence() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, acquired.status());

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(MICROSECOND, -1, CURRENT_TIMESTAMP(6))"
                         + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }

            assertFalse(repository.renewLease(acquired.ownership(), 5_000));
            assertRecoveryDenied(repository, uuid, "integration-s2");
            explicitReviewedRecovery(repository, uuid, acquired.ownership().fencingToken());
            PlayerDataRepository.AcquireResult takeover = repository.acquireOwnershipAndLoad(
                uuid, "integration-s2", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, takeover.status());
            assertEquals(acquired.ownership().fencingToken() + 2, takeover.ownership().fencingToken());

            PlayerDataRepository.SaveResult stale = repository.saveCompletePlayerData(
                sample(uuid, "stale", "[old]", "[old-ender]"), acquired.ownership(),
                new Configuration.SyncConfig(), 5_000, false);
            assertEquals(PlayerDataRepository.SaveStatus.FENCED, stale.status());
            assertTrue(repository.loadCompletePlayerData(uuid).isEmpty());
            assertTrue(repository.releaseOwnership(takeover.ownership()));
        } finally {
            if (database != null) {
                try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + playerTable);
                    statement.executeUpdate("DROP TABLE IF EXISTS " + coordinationTable);
                } finally {
                    database.close();
                }
            }
        }
    }

    @Test
    void renewWaitingOnRowLockRejectsLeaseThatExpiresBeforeLockAcquisition() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        Connection blocker = null;
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, acquired.status());

            blocker = database.getConnection();
            blocker.setAutoCommit(false);
            lockRow(blocker, coordinationTable, uuid);
            Timestamp expiry = databaseTimestampAfterMicros(blocker, 500_000L);
            try (PreparedStatement statement = blocker.prepareStatement(
                "UPDATE " + coordinationTable + " SET lease_until = ? WHERE uuid = ?")) {
                statement.setTimestamp(1, expiry);
                statement.setString(2, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }

            CountDownLatch renewStarted = new CountDownLatch(1);
            Future<PlayerDataRepository.LeaseRenewalResult> renewal = workers.submit(() -> {
                renewStarted.countDown();
                return repository.renewLeaseWithResult(acquired.ownership(), 5_000);
            });
            assertTrue(renewStarted.await(5, TimeUnit.SECONDS));
            awaitInnoDbLockWait(database, 5, TimeUnit.SECONDS);
            awaitDatabaseTimeAtOrAfter(database, expiry, 5, TimeUnit.SECONDS);

            blocker.commit();
            blocker.close();
            blocker = null;

            PlayerDataRepository.LeaseRenewalResult result = renewal.get(5, TimeUnit.SECONDS);
            assertFalse(result.renewed(), "the old owner must not revive after waiting past lease expiry");
            assertEquals(PlayerDataRepository.NO_LEASE_ANCHOR, result.leaseAnchorNanos());
            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT fencing_token, lease_until, CURRENT_TIMESTAMP(6) AS db_now FROM "
                         + coordinationTable + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(acquired.ownership().fencingToken(), rows.getLong("fencing_token"));
                    assertEquals(expiry, rows.getTimestamp("lease_until"),
                        "failed renewal must not rewrite the lease deadline");
                    assertTrue(rows.getTimestamp("lease_until").before(rows.getTimestamp("db_now")));
                }
            }
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } finally { blocker.close(); }
            }
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void sameSessionAcquireRetryRefreshesLiveLeaseButNeverRevivesExpiredLease() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            PlayerDataRepository.AcquireResult first = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", session, 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, first.status());

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(MICROSECOND, 300000, CURRENT_TIMESTAMP(6))"
                         + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }

            long retryStartedNanos = System.nanoTime();
            PlayerDataRepository.AcquireResult retry = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", session, 5_000);
            long retryReturnedNanos = System.nanoTime();
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, retry.status());
            assertEquals(first.ownership().fencingToken(), retry.ownership().fencingToken(),
                "idempotent same-session retry must preserve its fence");
            assertTrue(retry.leaseAnchorNanos() - retryStartedNanos >= 0);
            assertTrue(retryReturnedNanos - retry.leaseAnchorNanos() >= 0);
            assertTrue(remainingLeaseMicros(database, coordinationTable, uuid) > 4_000_000L,
                "near-expiry retry must extend the DB lease instead of merely claiming a fresh local window");

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(MICROSECOND, -1, CURRENT_TIMESTAMP(6))"
                         + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }

            PlayerDataRepository.AcquireResult expiredRetry = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", session, 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.RECOVERY_REQUIRED, expiredRetry.status(),
                "an expired same-session lease must not be revived");
            assertEquals(first.ownership().fencingToken(), readFence(database, coordinationTable, uuid));

            assertRecoveryDenied(repository, uuid, "integration-s2");
            explicitReviewedRecovery(repository, uuid, first.ownership().fencingToken());
            PlayerDataRepository.AcquireResult takeover = repository.acquireOwnershipAndLoad(
                uuid, "integration-s2", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, takeover.status());
            assertEquals(first.ownership().fencingToken() + 2, takeover.ownership().fencingToken());
            assertTrue(repository.releaseOwnership(takeover.ownership()));
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void snapshotSerializationFailureLeavesPreviousNbtAndFinalOwnershipUntouched() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            String existingNbtInventory = "[{\"slot\":0,\"item\":\"minecraft:shulker_box\","
                + "\"count\":1,\"nbt\":\"{BlockEntityTag:{Items:[{Slot:0b}]}}\"}]";
            PlayerDataRepository.CompletePlayerData previous = sample(
                uuid, "nbt-test", existingNbtInventory, "[]");
            PlayerDataRepository.SaveResult previousSave = repository.saveCompletePlayerData(
                previous, acquired.ownership(), sync, 5_000, false);
            assertTrue(previousSave.committed());
            AtomicInteger finalSaveCalls = new AtomicInteger();
            java.util.function.Supplier<PlayerDataRepository.CompletePlayerData> failingCapture = () -> {
                throw new SnapshotSerializationException(
                    "injected NBT serialization failure", new IllegalStateException("injected"));
            };

            assertThrows(SnapshotSerializationException.class, () -> {
                // Snapshot capture is strictly before the repository call in the
                // coordinator. An incomplete ItemStack NBT must abort this sequence.
                PlayerDataRepository.CompletePlayerData snapshot = failingCapture.get();
                finalSaveCalls.incrementAndGet();
                assertTrue(repository.saveCompletePlayerData(
                    snapshot, acquired.ownership(), sync, 5_000, true).committed());
            });
            assertEquals(0, finalSaveCalls.get());
            assertEquals(Optional.of(existingNbtInventory), repository.loadCompletePlayerData(uuid)
                .map(data -> data.inventoryJson));
            assertEquals(previousSave.dataRevision(), readDataRevision(database, coordinationTable, uuid));
            assertEquals(acquired.ownership().ownerSession().toString(),
                readOwnerSession(database, coordinationTable, uuid));

            // Cleanup only after asserting the failure path retained ownership.
            assertTrue(repository.releaseOwnership(acquired.ownership()));
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void delayedFirstLoginCommitCallbackCannotReadyAfterUncleanExpiry() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            UUID s1Session = UUID.randomUUID();
            long leaseDurationMs = 5_000L;
            long safetyBudgetNanos = TimeUnit.MILLISECONDS.toNanos(3_500L);
            PlayerDataRepository.AcquireResult s1 = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", s1Session, leaseDurationMs);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, s1.status());

            PlayerDataRepository.CompletePlayerData initial = sample(uuid, "first-login", "[]", "[]");
            PlayerDataRepository.SaveResult initialSave = repository.saveCompletePlayerData(
                initial, s1.ownership(), new Configuration.SyncConfig(), leaseDurationMs, false);
            assertTrue(initialSave.committed());

            PlayerSessionContext s1Context = new PlayerSessionContext(uuid, null, 1L);
            s1Context.markOwnership(
                s1.ownership().fencingToken(), initialSave.dataRevision(), initialSave.leaseAnchorNanos());
            CountDownLatch callbackPaused = new CountDownLatch(1);
            CountDownLatch resumeCallback = new CountDownLatch(1);
            AtomicBoolean s1Ready = new AtomicBoolean(true);
            Thread callback = new Thread(() -> {
                callbackPaused.countDown();
                try {
                    if (!resumeCallback.await(5, TimeUnit.SECONDS)) return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                s1Ready.set(s1Context.markReadyIfSafe(
                    true, true, false, initialSave.leaseAnchorNanos() + safetyBudgetNanos + 1,
                    safetyBudgetNanos));
            }, "mysql-delayed-first-login-callback");
            callback.start();
            assertTrue(callbackPaused.await(5, TimeUnit.SECONDS));

            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + coordinationTable
                         + " SET lease_until = TIMESTAMPADD(MICROSECOND, -1, CURRENT_TIMESTAMP(6))"
                         + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                assertEquals(1, statement.executeUpdate());
            }
            assertRecoveryDenied(repository, uuid, "integration-s2");
            assertEquals(s1.ownership().fencingToken(), readFence(database, coordinationTable, uuid));

            resumeCallback.countDown();
            callback.join(5_000L);
            assertFalse(callback.isAlive());
            assertFalse(s1Ready.get());
            assertEquals(SyncState.LOADING, s1Context.state());
            assertFalse(s1Context.isAuthoritativeDataLoaded());
            assertEquals(PlayerDataRepository.SaveStatus.FENCED,
                repository.saveCompletePlayerData(
                    initial, s1.ownership(), new Configuration.SyncConfig(), leaseDurationMs, false).status());
            explicitReviewedRecovery(repository, uuid, s1.ownership().fencingToken());
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void finalFlushKeepsInnoDbLockUntilCommitAndNextOwnerLoadsLatestData() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            UUID sessionOne = UUID.randomUUID();
            UUID sessionTwo = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", sessionOne, 5_000);
            PlayerDataRepository.CompletePlayerData oldData = sample(uuid, "old", "[old]", "[old-ender]");
            assertTrue(repository.saveCompletePlayerData(
                oldData, acquired.ownership(), new Configuration.SyncConfig(), 5_000, false).committed());

            CountDownLatch beforeCommit = new CountDownLatch(1);
            CountDownLatch allowCommit = new CountDownLatch(1);
            PlayerDataRepository pausingRepository = new PlayerDataRepository(database, (phase, finalFlush) -> {
                if (finalFlush && phase == PlayerDataRepository.TransactionPhase.BEFORE_COMMIT) {
                    beforeCommit.countDown();
                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new SQLException("timed out waiting to release final transaction");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("interrupted while pausing final transaction", error);
                    }
                }
            });
            PlayerDataRepository.CompletePlayerData latest = sample(
                uuid, "latest", "[latest]", "[latest-ender]");
            Future<PlayerDataRepository.SaveResult> finalSave = workers.submit(() ->
                pausingRepository.saveCompletePlayerData(
                    latest, acquired.ownership(), new Configuration.SyncConfig(), 5_000, true));
            assertTrue(beforeCommit.await(5, TimeUnit.SECONDS), "final transaction did not reach commit gate");

            Future<PlayerDataRepository.AcquireResult> nextOwner = workers.submit(() ->
                repository.acquireOwnershipAndLoad(uuid, "integration-s2", sessionTwo, 5_000));
            assertThrows(TimeoutException.class, () -> nextOwner.get(300, TimeUnit.MILLISECONDS),
                "next owner must wait for the uncommitted final transaction");

            allowCommit.countDown();
            assertTrue(finalSave.get(5, TimeUnit.SECONDS).committed());
            PlayerDataRepository.AcquireResult loaded = nextOwner.get(5, TimeUnit.SECONDS);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, loaded.status());
            assertEquals(Optional.of("[latest]"), loaded.data().map(data -> data.inventoryJson));
            assertTrue(repository.releaseOwnership(loaded.ownership()));
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void finalFlushRollsBackPlayerDataWhenFailureOccursBeforeOwnershipRelease() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            String durablePending = "[{\"slot\":0,\"item\":\"minecraft:diamond\",\"count\":1}]";
            PlayerDataRepository.CompletePlayerData oldData = sample(
                uuid, "old", "[old]", "[old-ender]").withInventoryAndPendingItems("[old]", durablePending);
            assertTrue(repository.saveCompletePlayerData(
                oldData, acquired.ownership(), sync, 5_000, false).committed());

            PlayerDataRepository failingRepository = new PlayerDataRepository(database, (phase, finalFlush) -> {
                if (finalFlush && phase == PlayerDataRepository.TransactionPhase.AFTER_PLAYER_DATA_WRITE) {
                    throw new SQLException("injected failure before ownership release");
                }
            });
            PlayerDataRepository.CompletePlayerData newer = sample(
                uuid, "newer", "[newer]", "[newer-ender]");
            assertThrows(SQLException.class, () -> failingRepository.saveCompletePlayerData(
                newer, acquired.ownership(), sync, 5_000, true));

            assertEquals(Optional.of("[old]"), repository.loadCompletePlayerData(uuid)
                .map(data -> data.inventoryJson));
            assertEquals(Optional.of(durablePending), repository.loadCompletePlayerData(uuid)
                .map(data -> data.pendingDisconnectItemsJson));
            try (Connection connection = database.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT owner_session, fencing_token, lease_until FROM " + coordinationTable
                         + " WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(acquired.ownership().ownerSession().toString(), result.getString("owner_session"));
                    assertEquals(acquired.ownership().fencingToken(), result.getLong("fencing_token"));
                    assertNotNull(result.getTimestamp("lease_until"));
                }
            }

            PlayerDataRepository failingAfterOwnershipUpdate = new PlayerDataRepository(database,
                (phase, finalFlush) -> {
                    if (finalFlush && phase == PlayerDataRepository.TransactionPhase.AFTER_OWNERSHIP_UPDATE) {
                        throw new SQLException("injected failure after ownership release update");
                    }
                });
            assertThrows(SQLException.class, () -> failingAfterOwnershipUpdate.saveCompletePlayerData(
                newer, acquired.ownership(), sync, 5_000, true));
            assertEquals(Optional.of("[old]"), repository.loadCompletePlayerData(uuid)
                .map(data -> data.inventoryJson));
            assertTrue(repository.saveCompletePlayerData(
                oldData, acquired.ownership(), sync, 5_000, true).committed());
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void deadlockRetryRestartsTheWholeTransactionAndCommunicationFailureRollsBack() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            Configuration.SyncConfig sync = new Configuration.SyncConfig();
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository repository = new PlayerDataRepository(database);
            PlayerDataRepository.AcquireResult acquired = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            PlayerDataRepository.CompletePlayerData oldData = sample(uuid, "old", "[old]", "[old-ender]");
            assertTrue(repository.saveCompletePlayerData(
                oldData, acquired.ownership(), sync, 5_000, false).committed());

            AtomicInteger attempts = new AtomicInteger();
            PlayerDataRepository retryingRepository = new PlayerDataRepository(database, (phase, finalFlush) -> {
                if (phase == PlayerDataRepository.TransactionPhase.BEFORE_COMMIT
                        && attempts.getAndIncrement() == 0) {
                    throw new SQLException("injected deadlock", "40001", 1213);
                }
            });
            PlayerDataRepository.CompletePlayerData latest = sample(
                uuid, "latest", "[latest]", "[latest-ender]");
            assertTrue(retryingRepository.saveCompletePlayerData(
                latest, acquired.ownership(), sync, 5_000, false).committed());
            assertEquals(2, attempts.get(), "deadlock must retry the entire transaction");
            assertEquals(Optional.of("[latest]"), repository.loadCompletePlayerData(uuid)
                .map(data -> data.inventoryJson));

            PlayerDataRepository communicationFailureRepository = new PlayerDataRepository(database,
                (phase, finalFlush) -> {
                    if (phase == PlayerDataRepository.TransactionPhase.AFTER_PLAYER_DATA_WRITE) {
                        throw new SQLException("injected communication failure", "08S01", 2013);
                    }
                });
            assertThrows(SQLException.class, () -> communicationFailureRepository.saveCompletePlayerData(
                oldData, acquired.ownership(), sync, 5_000, false));
            assertEquals(Optional.of("[latest]"), repository.loadCompletePlayerData(uuid)
                .map(data -> data.inventoryJson));
            assertTrue(repository.saveCompletePlayerData(
                latest, acquired.ownership(), sync, 5_000, true).committed());
        } finally {
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void lockWaitTimeoutRollsBackAcquireTransactionBeforeRetry() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        databaseConfig.lockWaitTimeoutMs = 1_000;
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Connection blocker = null;
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID uuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult owner = repository.acquireOwnershipAndLoad(
                uuid, "integration-s1", UUID.randomUUID(), 5_000);
            blocker = database.getConnection();
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                "SELECT uuid FROM " + coordinationTable + " WHERE uuid = ? FOR UPDATE")) {
                statement.setString(1, uuid.toString());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                }
            }

            Future<PlayerDataRepository.AcquireResult> blocked = workers.submit(() ->
                repository.acquireOwnershipAndLoad(uuid, "integration-s2", UUID.randomUUID(), 5_000));
            Exception failure = assertThrows(Exception.class, () -> blocked.get(5, TimeUnit.SECONDS));
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            assertTrue(PlayerDataRepository.isRetryableTransactionError(cause),
                "real MySQL lock wait timeout must be classified as retryable");

            blocker.rollback();
            blocker.close();
            blocker = null;
            PlayerDataRepository.AcquireResult retry = repository.acquireOwnershipAndLoad(
                uuid, "integration-s2", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.BUSY, retry.status(),
                "the failed transaction must have been rolled back before the retry");
            assertTrue(repository.releaseOwnership(owner.ownership()));
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } finally { blocker.close(); }
            }
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            dropTables(database, playerTable, coordinationTable);
        }
    }

    @Test
    void realInnoDbDeadlockIsDetectedAndBothConnectionsCanBeRecovered() throws Exception {
        Configuration.DatabaseConfig databaseConfig = testDatabaseConfig();
        String playerTable = databaseConfig.tableName;
        String coordinationTable = playerTable + "_sync_state";
        DatabaseManager database = null;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            database = new DatabaseManager(databaseConfig, coordinationTable);
            PlayerDataRepository repository = new PlayerDataRepository(database);
            UUID firstUuid = UUID.randomUUID();
            UUID secondUuid = UUID.randomUUID();
            PlayerDataRepository.AcquireResult first = repository.acquireOwnershipAndLoad(
                firstUuid, "integration-seed", UUID.randomUUID(), 5_000);
            PlayerDataRepository.AcquireResult second = repository.acquireOwnershipAndLoad(
                secondUuid, "integration-seed", UUID.randomUUID(), 5_000);
            assertTrue(repository.saveCompletePlayerData(sample(firstUuid, "seed", "[]", "[]"),
                first.ownership(), new Configuration.SyncConfig(), 5_000, true).committed());
            assertTrue(repository.saveCompletePlayerData(sample(secondUuid, "seed", "[]", "[]"),
                second.ownership(), new Configuration.SyncConfig(), 5_000, true).committed());

            CountDownLatch firstLocks = new CountDownLatch(2);
            DatabaseManager databaseRef = database;
            Future<SQLException> left = workers.submit(() -> runDeadlockParticipant(
                databaseRef, coordinationTable, firstUuid, secondUuid, firstLocks));
            Future<SQLException> right = workers.submit(() -> runDeadlockParticipant(
                databaseRef, coordinationTable, secondUuid, firstUuid, firstLocks));
            SQLException leftError = left.get(8, TimeUnit.SECONDS);
            SQLException rightError = right.get(8, TimeUnit.SECONDS);
            assertTrue(leftError != null || rightError != null, "InnoDB must abort one deadlock participant");
            SQLException deadlock = leftError != null ? leftError : rightError;
            assertTrue(PlayerDataRepository.isRetryableTransactionError(deadlock));

            PlayerDataRepository.AcquireResult recovered = repository.acquireOwnershipAndLoad(
                firstUuid, "integration-recovered", UUID.randomUUID(), 5_000);
            assertEquals(PlayerDataRepository.AcquireStatus.ACQUIRED, recovered.status());
            assertTrue(repository.releaseOwnership(recovered.ownership()));
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            dropTables(database, playerTable, coordinationTable);
        }
    }

    private static SQLException runDeadlockParticipant(
        DatabaseManager database,
        String coordinationTable,
        UUID firstUuid,
        UUID secondUuid,
        CountDownLatch firstLocks
    ) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            lockRow(connection, coordinationTable, firstUuid);
            firstLocks.countDown();
            if (!firstLocks.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("deadlock participant synchronization timed out");
            }
            lockRow(connection, coordinationTable, secondUuid);
            connection.commit();
            return null;
        } catch (SQLException error) {
            return error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new SQLException("deadlock participant interrupted", error);
        }
    }

    private static void lockRow(Connection connection, String table, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid FROM " + table + " WHERE uuid = ? FOR UPDATE")) {
            statement.setString(1, uuid.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("missing coordination row " + uuid);
            }
        }
    }

    private static Timestamp databaseTimestampAfterMicros(Connection connection, long micros) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)) AS target_time")) {
            statement.setLong(1, micros);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("database timestamp query returned no row");
                return rows.getTimestamp("target_time");
            }
        }
    }

    private static void awaitInnoDbLockWait(DatabaseManager database, long timeout, TimeUnit unit)
        throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            try (Connection connection = database.getConnection();
                 Statement statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT COUNT(*) AS waiting FROM performance_schema.data_lock_waits")) {
                if (rows.next() && rows.getLong("waiting") > 0) return;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("renew transaction never appeared in performance_schema.data_lock_waits");
    }

    private static void awaitDatabaseTimeAtOrAfter(
        DatabaseManager database,
        Timestamp target,
        long timeout,
        TimeUnit unit
    ) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            do {
                try (var rows = statement.executeQuery("SELECT CURRENT_TIMESTAMP(6) AS db_now")) {
                    if (rows.next() && !rows.getTimestamp("db_now").before(target)) return;
                }
                Thread.yield();
            } while (System.nanoTime() < deadline);
        }
        throw new AssertionError("database clock did not pass the injected lease expiry");
    }

    private static long remainingLeaseMicros(DatabaseManager database, String table, UUID uuid)
        throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT TIMESTAMPDIFF(MICROSECOND, CURRENT_TIMESTAMP(6), lease_until) AS remaining_us "
                     + "FROM " + table + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing coordination row " + uuid);
                return rows.getLong("remaining_us");
            }
        }
    }

    private static long readFence(DatabaseManager database, String table, UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT fencing_token FROM " + table + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing coordination row " + uuid);
                return rows.getLong("fencing_token");
            }
        }
    }

    private static long readDataRevision(DatabaseManager database, String table, UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT data_revision FROM " + table + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing coordination row " + uuid);
                return rows.getLong("data_revision");
            }
        }
    }

    private static String readOwnerSession(DatabaseManager database, String table, UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT owner_session FROM " + table + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing coordination row " + uuid);
                return rows.getString("owner_session");
            }
        }
    }

    private static void dropTables(DatabaseManager database, String playerTable, String coordinationTable)
        throws SQLException {
        if (database == null) return;
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + playerTable);
            statement.executeUpdate("DROP TABLE IF EXISTS " + coordinationTable);
            statement.executeUpdate("DROP TABLE IF EXISTS " + database.getRecoveryAuditTableName());
        } finally {
            database.close();
        }
    }

    private static void assertRecoveryDenied(PlayerDataRepository repository, UUID uuid, String server)
        throws SQLException {
        var denied = repository.acquireOwnershipAndLoad(uuid, server, UUID.randomUUID(), 5_000);
        assertEquals(PlayerDataRepository.AcquireStatus.RECOVERY_REQUIRED, denied.status());
        assertNull(denied.ownership());
        assertTrue(denied.data().isEmpty(), "unclean checkpoint must not be LOADed automatically");
    }

    private static void explicitReviewedRecovery(PlayerDataRepository repository, UUID uuid, long fence)
        throws SQLException {
        var status = repository.recoveryStatus(uuid).orElseThrow();
        assertEquals(fence, status.fence());
        // Test operator explicitly confirms the modeled source is terminated and
        // external state reconciled. Normal acquire must never perform this step.
        assertEquals(PlayerDataRepository.RecoveryResolutionStatus.RESOLVED,
            repository.forceLastCheckpoint(uuid, fence, status.revision(), UUID.randomUUID(),
                "integration-operator", PlayerDataRepository.RecoveryAcknowledgement
                    .SOURCE_FENCED_AND_WORLD_REVIEWED).status());
    }

    private static Configuration.DatabaseConfig testDatabaseConfig() {
        Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
        config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
        config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
        config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
        config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
        config.maxConnections = 4;
        config.connectionTimeoutMs = 3_000;
        config.socketTimeoutMs = 3_000;
        config.tableName = "pdc_it_" + UUID.randomUUID().toString().replace("-", "");
        return config;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int addOneXp(int level, float progress) {
        long cost = ExperienceProgress.experienceToNextLevel(level);
        float after = progress + 1.0F / (float) cost;
        return after >= 1.0F ? level + 1 : level;
    }

    private static PlayerDataRepository.CompletePlayerData sample(
        UUID uuid,
        String username,
        String inventory,
        String enderChest
    ) {
        return new PlayerDataRepository.CompletePlayerData(
            uuid, username, inventory, enderChest, "[armor]", "[offhand]",
            20.0, 20, 5.0F, 0.0F, 300,
            0, 0, 0.0F, null, "[]",
            "minecraft:overworld", 0.0, 64.0, 0.0, 0.0F, 0.0F,
            "survival", false, false, false,
            username, null, null, "{}", "{}", "{}", 0, 0L, 1
        );
    }
}
