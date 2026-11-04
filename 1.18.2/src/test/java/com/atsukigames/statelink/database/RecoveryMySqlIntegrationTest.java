package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.atsukigames.statelink.database.PlayerDataRepository.*;

/** Real InnoDB transitions. The operator acknowledgement is NEVER a login retry. */
@EnabledIfEnvironmentVariable(named="STATELINK_TEST_MYSQL_DATABASE", matches=".+")
class RecoveryMySqlIntegrationTest {
    @Test void baseline224FenceMismatchReproductionIsBlockedAndRequiresAuditedDomainAdoption() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            Configuration.SyncConfig disabled = new Configuration.SyncConfig();
            disabled.inventory = disabled.armor = disabled.offhand = disabled.enderchest = false;
            var offOwner = f.repo.acquireOwnershipAndLoad(f.uuid, "disabled-backend", UUID.randomUUID(), 5_000, disabled).ownership();
            assertNotNull(offOwner);
            assertTrue(f.repo.saveCompletePlayerData(f.data("[locally-consumed]"), offOwner,
                disabled, 5_000, true).committed());

            // A fence-only explicit recovery preserves the DB checkpoint/revision while
            // invalidating the old clean-release fence. This is the 2.2.4 bypass shape.
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                + "recovery_required_at=CURRENT_TIMESTAMP(6) WHERE uuid='" + f.uuid + "'");
            var beforeRecovery = f.status();
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                f.force(UUID.randomUUID(), beforeRecovery.fence(), beforeRecovery.revision()).status());

            long receiptFence;
            long receiptRevision;
            String receiptDomains;
            try (Connection c = f.db.getConnection(); PreparedStatement q = c.prepareStatement("SELECT clean_release_fence,"
                    + "clean_release_revision,clean_release_domains FROM " + f.coord + " WHERE uuid=?")) {
                q.setString(1, f.uuid.toString());
                try (var row = q.executeQuery()) {
                    assertTrue(row.next()); receiptFence = row.getLong(1); receiptRevision = row.getLong(2);
                    receiptDomains = row.getString(3);
                }
            }
            var recovered = f.status();
            assertTrue(com.atsukigames.statelink.utils.CheckpointDigest.reenablesDomain(
                receiptDomains, new Configuration.SyncConfig()));
            // Pinned 2.2.4 predicate required both fence and data revision to match.
            // Here its re-enable clause is skipped solely because recovery advanced the
            // ownership fence, even though the old domain data/revision still exists.
            boolean old224GuardWouldFire = receiptDomains != null
                && receiptFence == recovered.fence() && receiptRevision == recovered.revision()
                && com.atsukigames.statelink.utils.CheckpointDigest.reenablesDomain(
                    receiptDomains, new Configuration.SyncConfig());
            assertFalse(old224GuardWouldFire, "baseline sequence must reproduce the old guard bypass");
            assertEquals(receiptRevision, recovered.revision());
            assertNotEquals(receiptFence, recovered.fence());

            var denied = f.acquire();
            denied(denied);
            assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION", f.status().reason());
            assertTrue(denied.data().isEmpty(), "old DB diamond must not be exposed before reconciliation");

            UUID operation = UUID.randomUUID();
            long lastExpectedFence = -1;
            long lastExpectedRevision = -1;
            String[] disabledItemDomains = {"inventory", "armor", "offhand", "enderchest"};
            PlayerDataRepository.RecoveryResolutionResult result = null;
            for (String domain : disabledItemDomains) {
                var status = f.status();
                lastExpectedFence = status.fence();
                lastExpectedRevision = status.revision();
                result = f.repo.adoptDatabaseDomain(f.uuid, domain, status.fence(), status.revision(),
                    operation, "integration-operator", new Configuration.SyncConfig());
                if (!domain.equals(disabledItemDomains[disabledItemDomains.length - 1])) {
                    assertEquals(RecoveryResolutionStatus.DOMAIN_STILL_REQUIRES_RECONCILIATION, result.status());
                    operation = UUID.randomUUID();
                }
            }
            assertNotNull(result);
            assertEquals(RecoveryResolutionStatus.RESOLVED, result.status());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,
                f.repo.adoptDatabaseDomain(f.uuid, "enderchest", lastExpectedFence, lastExpectedRevision,
                    operation, "integration-operator", new Configuration.SyncConfig()).status());
            assertEquals(4, f.count("SELECT COUNT(*) FROM " + f.audit + " WHERE resolution='DOMAIN_ADOPT_DB'"));
            assertEquals(4, f.count("SELECT COUNT(*) FROM " + f.audit
                + " WHERE resolution='DOMAIN_ADOPT_DB' AND domain_provenance_before IS NOT NULL"
                + " AND domain_provenance_after IS NOT NULL AND recovery_mode LIKE 'domain-db:%'"));
            var acquired = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, acquired.status());
            assertEquals("[diamond]", acquired.data().orElseThrow().inventoryJson,
                "DB adoption is allowed only after the operator explicitly reconciles all re-enabled item domains");
        }
    }

    @Test void missingAndStaleDomainProvenanceFailClosedWithoutLoadingPlayerData() throws Exception {
        for (boolean staleRevision : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
                String mutation = staleRevision
                    ? "domain_provenance_revision=data_revision-1"
                    : "domain_provenance_revision=NULL,domain_provenance_generation=NULL,domain_provenance_manifest=NULL";
                f.sql("UPDATE " + f.coord + " SET " + mutation + " WHERE uuid='" + f.uuid + "'");
                var result = f.acquire();
                denied(result);
                assertTrue(result.data().isEmpty());
                assertEquals("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", f.status().reason());
            }
        }
    }

    @Test void legacyReceiptBackfillUsesDataRevisionNotOwnershipFence() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            f.sql("UPDATE " + f.coord + " SET clean_release_fence=0,domain_provenance_revision=NULL,"
                + "domain_provenance_generation=NULL,domain_provenance_manifest=NULL WHERE uuid='" + f.uuid + "'");
            DatabaseManager second = new DatabaseManager(f.config, f.coord);
            try {
                try (Connection c = second.getConnection(); PreparedStatement q = c.prepareStatement("SELECT data_revision,"
                        + "domain_provenance_revision,domain_provenance_generation,domain_provenance_manifest FROM "
                        + f.coord + " WHERE uuid=?")) {
                    q.setString(1, f.uuid.toString());
                    try (var row = q.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(row.getLong(1), row.getLong(2));
                        assertEquals(1, row.getLong(3));
                        var provenance = DomainProvenance.read(row.getString(4), row.getLong(2), row.getLong(3), row.getLong(1));
                        assertTrue(provenance.authorizesEnabledDomains(f.sync));
                    }
                }
            } finally { second.close(); }
        }
    }

    @Test void fenceOnlyRecoveryDoesNotInvalidateValidCurrentRevisionProvenance() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                + "recovery_required_at=CURRENT_TIMESTAMP(6) WHERE uuid='" + f.uuid + "'");
            var status = f.status();
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                f.force(UUID.randomUUID(), status.fence(), status.revision()).status());
            var acquired = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, acquired.status());
            assertEquals("[diamond]", acquired.data().orElseThrow().inventoryJson);
        }
    }

    @Test void automaticCheckpointRecoveryCannotClearDisabledDomainProvenance() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            Configuration.SyncConfig disabled = new Configuration.SyncConfig();
            disabled.inventory = disabled.armor = disabled.offhand = disabled.enderchest = false;
            var offOwner = f.repo.acquireOwnershipAndLoad(f.uuid, "disabled-backend", UUID.randomUUID(), 5_000, disabled).ownership();
            assertTrue(f.repo.saveCompletePlayerData(f.data("[local-state]"), offOwner, disabled, 5_000, true).committed());
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                + "recovery_required_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(6)) WHERE uuid='" + f.uuid + "'");
            var status = f.status();
            assertEquals(RecoveryResolutionStatus.RESOLVED, f.repo.resolveAutomaticLastCheckpoint(f.uuid,
                status.fence(), status.revision(), UUID.randomUUID(), "automatic-test", 0, 30, 5, 1, true).status());
            denied(f.acquire());
            assertEquals("DOMAIN_REENABLE_REQUIRES_RECONCILIATION", f.status().reason());
            assertEquals("[diamond]", f.repo.loadCompletePlayerData(f.uuid).orElseThrow().inventoryJson,
                "recovery must preserve the checkpoint but must not grant it to gameplay");
        }
    }

    @Test void concurrentAcquiresCannotTurnUnknownProvenanceIntoAnOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            f.sql("UPDATE " + f.coord + " SET domain_provenance_revision=NULL,domain_provenance_generation=NULL,"
                + "domain_provenance_manifest=NULL WHERE uuid='" + f.uuid + "'");
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<AcquireResult>> attempts = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    final int index = i;
                    attempts.add(workers.submit(() -> f.repo.acquireOwnershipAndLoad(f.uuid,
                        "unknown-" + index, UUID.randomUUID(), 5_000)));
                }
                for (Future<AcquireResult> attempt : attempts) denied(attempt.get(10, TimeUnit.SECONDS));
                assertNull(f.status().ownerSession());
                assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.coord + " WHERE uuid='" + f.uuid
                    + "' AND recovery_required=TRUE AND recovery_reason='DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION'"));
            } finally { workers.shutdownNow(); }
        }
    }

    @Test void concurrentDomainAdoptionWithSameOperationIdIsTransactionalAndIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(f.data("[diamond]"), f.owner, f.sync, 5_000, true).committed());
            Configuration.SyncConfig disabled = new Configuration.SyncConfig();
            disabled.inventory = disabled.armor = disabled.offhand = disabled.enderchest = false;
            var offOwner = f.repo.acquireOwnershipAndLoad(f.uuid, "disabled-backend", UUID.randomUUID(), 5_000, disabled).ownership();
            assertTrue(f.repo.saveCompletePlayerData(f.data("[local-state]"), offOwner, disabled, 5_000, true).committed());
            denied(f.acquire());
            var status = f.status(); UUID operation = UUID.randomUUID();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> f.repo.adoptDatabaseDomain(f.uuid, "inventory", status.fence(),
                    status.revision(), operation, "integration-operator", new Configuration.SyncConfig()));
                var second = workers.submit(() -> f.repo.adoptDatabaseDomain(f.uuid, "inventory", status.fence(),
                    status.revision(), operation, "integration-operator", new Configuration.SyncConfig()));
                Set<RecoveryResolutionStatus> outcomes = new HashSet<>();
                outcomes.add(first.get(10, TimeUnit.SECONDS).status());
                outcomes.add(second.get(10, TimeUnit.SECONDS).status());
                assertTrue(outcomes.contains(RecoveryResolutionStatus.DOMAIN_STILL_REQUIRES_RECONCILIATION));
                assertTrue(outcomes.contains(RecoveryResolutionStatus.ALREADY_RESOLVED));
                assertEquals(status.fence() + 1, f.status().fence());
                assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit + " WHERE operation_id='" + operation + "'"));
                denied(f.acquire());
            } finally { workers.shutdownNow(); }
        }
    }

    @Test void automaticLegacyLocalAuthorityLostPreservesAuditAndUsesFreshFence() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire();
            f.sql("UPDATE "+f.coord+" SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                +"recovery_required_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(6)),checkpoint_source_version=NULL");
            UUID operation=UUID.randomUUID();
            var result=f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,operation,"automatic",0,30,12,5,true);
            assertEquals(RecoveryResolutionStatus.RESOLVED,result.status()); assertEquals(2,result.fence());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,operation,"automatic",0,30,12,5,true).status());
            assertEquals(1,f.count("SELECT COUNT(*) FROM "+f.audit+" WHERE recovery_mode='automatic' AND source_version='2.1.11-legacy'"
                +" AND previous_owner_server='source' AND previous_fence=1 AND previous_revision=1 AND checkpoint_at IS NOT NULL AND unsafe_acknowledged=TRUE"));
            assertEquals(SaveStatus.FENCED,f.repo.saveCompletePlayerData(f.data("[stale]"),f.owner,f.sync,5000,false).status());
            var acquired=f.acquire(); assertEquals(AcquireStatus.ACQUIRED,acquired.status()); assertEquals(3,acquired.ownership().fencingToken());
        }
    }

    @Test void automaticNeverRecoversFlaggedUnexpiredOwnerOrUnknownReason() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint();
            f.sql("UPDATE "+f.coord+" SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',recovery_required_at=CURRENT_TIMESTAMP(6)");
            assertEquals(RecoveryResolutionStatus.CONFLICT,f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,true).status());
            assertEquals(1,f.status().fence()); assertEquals(0,f.status().automaticAttempts());
            f.expire(); f.sql("UPDATE "+f.coord+" SET recovery_reason='UNKNOWN_EXTERNAL_CONDITION'");
            assertEquals(RecoveryResolutionStatus.CONFLICT,f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,true).status());
            assertEquals(0,f.count("SELECT COUNT(*) FROM "+f.audit));
        }
    }

    @Test void automaticDelayCountsFromLeaseExpiryNotEarlierRecoveryFlag() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire();
            f.sql("UPDATE "+f.coord+" SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',recovery_required_at=TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(6))");
            assertEquals(RecoveryResolutionStatus.CONFLICT,f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",5,true).status());
            f.sql("UPDATE "+f.coord+" SET lease_until=TIMESTAMPADD(SECOND,-6,CURRENT_TIMESTAMP(6))");
            assertEquals(RecoveryResolutionStatus.RESOLVED,f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",5,true).status());
        }
    }

    @Test void freshCheckpointAtAuthorityLossSurvivesADayOfDowntime() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                + "lease_until=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),"
                + "recovery_required_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),"
                + "last_checkpoint_at=TIMESTAMPADD(SECOND,-86410,CURRENT_TIMESTAMP(6))");
            UUID operation = UUID.randomUUID();
            var resolved = f.repo.resolveAutomaticLastCheckpoint(f.uuid, 1, 1, operation, "auto", 0, 30, 12, 5, true);
            assertEquals(RecoveryResolutionStatus.RESOLVED, resolved.status());
            assertEquals(2, resolved.fence());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid, 1, 1, operation, "auto", 0, 30, 12, 5, true).status());
            assertEquals(SaveStatus.FENCED, f.repo.saveCompletePlayerData(f.data("[old-owner]"), f.owner, f.sync, 5000, false).status());
            assertEquals("[diamond]", f.acquire().data().orElseThrow().inventoryJson);
            assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit));
        }
    }

    @Test void checkpointStaleBeforeFailureIsNotMadeFreshByRestartingOrScanning() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                + "lease_until=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),recovery_required_at=CURRENT_TIMESTAMP(6),"
                + "last_checkpoint_at=TIMESTAMPADD(SECOND,-86460,CURRENT_TIMESTAMP(6))");
            assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid, 1, 1, UUID.randomUUID(), "auto", 0, 30, 12, 5, true).status());
            assertEquals(0, f.status().automaticAttempts());
            assertEquals(1, f.status().fence());
            assertEquals(0, f.count("SELECT COUNT(*) FROM " + f.audit));
            denied(f.acquire());
        }
    }

    @Test void matchingAttestationAtFailureSurvivesDowntimeButWrongFenceDoesNot() throws Exception {
        for (boolean wrongFence : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                var snapshot = f.data("[]");
                assertTrue(f.repo.saveCompletePlayerData(snapshot, f.owner, f.sync, 5000, false).committed());
                assertEquals(StateAttestationStatus.ATTESTED,
                    f.repo.attestVerifiedState(snapshot, f.owner, f.sync, System.nanoTime(), 1L, () -> 1L).status());
                f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',"
                    + "lease_until=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),"
                    + "recovery_required_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),"
                    + "last_checkpoint_at=TIMESTAMPADD(DAY,-2,CURRENT_TIMESTAMP(6)),"
                    + "verified_state_at=TIMESTAMPADD(SECOND,-86410,CURRENT_TIMESTAMP(6))"
                    + (wrongFence ? ",verified_state_fence=verified_state_fence+1" : ""));
                var result = f.repo.resolveAutomaticLastCheckpoint(f.uuid, 1, 1, UUID.randomUUID(), "auto", 0, 30, 12, 5, true);
                assertEquals(wrongFence ? RecoveryResolutionStatus.STALE_CHECKPOINT : RecoveryResolutionStatus.RESOLVED,
                    result.status());
                if (wrongFence) { denied(f.acquire()); assertEquals(0, f.status().automaticAttempts()); }
            }
        }
    }

    @Test void abandonedFirstLoginReportsMissingCheckpointWithoutFabricatingData() throws Exception {
        try (Fixture f = new Fixture()) {
            f.expire(); denied(f.acquire());
            assertEquals(RecoveryResolutionStatus.MISSING_CHECKPOINT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid, 1, 0, UUID.randomUUID(), "auto", 0, 30, 12, 5, true).status());
            assertEquals(0, f.status().automaticAttempts());
            assertEquals(0, f.status().revision());
            assertEquals(0, f.count("SELECT COUNT(*) FROM " + f.config.tableName));
            denied(f.acquire());
        }
    }

    @Test void staleCheckpointDeferralNeverSpendsTheAttemptBudget() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            f.sql("UPDATE "+f.coord+" SET last_checkpoint_at=TIMESTAMPADD(SECOND,-40,CURRENT_TIMESTAMP(6))");
            // Background scans and login retries far beyond the budget of 12: all are policy deferrals.
            for (int retry = 0; retry < 25; retry++) {
                assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT,
                    f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,12,5,true).status());
            }
            assertEquals(0,f.status().automaticAttempts());
            assertEquals(0,f.count("SELECT COUNT(*) FROM "+f.coord+" WHERE automatic_recovery_last_attempt_at IS NOT NULL"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM "+f.audit)); assertEquals(1,f.status().fence()); denied(f.acquire());
            // Still eligible once the operator accepts an older checkpoint.
            var reopened=new PlayerDataRepository(f.db);
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                reopened.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,3600,12,5,true).status());
            assertEquals(AcquireStatus.ACQUIRED,f.acquire().status());
        }
    }

    @Test void delayRetryIntervalAndLiveOwnerDeferralsDoNotSpendTheAttemptBudget() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint();
            // live owner
            assertEquals(RecoveryResolutionStatus.CONFLICT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,12,5,true).status());
            f.expire(); denied(f.acquire());
            // configured delay not elapsed
            assertEquals(RecoveryResolutionStatus.CONFLICT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",3600,30,12,5,true).status());
            assertEquals(0,f.status().automaticAttempts());
        }
    }

    @Test void legacyAgePolicyNeverAppliesToACheckpointWrittenByTheCurrentRelease() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            f.sql("UPDATE "+f.coord+" SET last_checkpoint_at=TIMESTAMPADD(HOUR,-6,CURRENT_TIMESTAMP(6))");
            // No legacy bootstrap record and a 2.2.x producer version: a very long legacy age changes nothing.
            assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,31_536_000,12,5,true).status());
            // Even a NULL producer version is not enough without a completed acknowledged bootstrap.
            f.sql("UPDATE "+f.coord+" SET checkpoint_source_version=NULL");
            assertEquals(RecoveryResolutionStatus.STALE_CHECKPOINT,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,31_536_000,12,5,true).status());
            assertEquals(0,f.status().automaticAttempts()); assertEquals(1,f.status().fence());
        }
    }

    @Test void attemptBudgetSpentByRealFailuresIsEnforcedAndOnlyResetByAnAuditedOperatorAction() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            f.sql("CREATE TRIGGER reject_auto_audit BEFORE INSERT ON "+f.audit+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected recovery failure'");
            assertThrows(SQLException.class,()->f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,1,5,true));
            f.sql("DROP TRIGGER reject_auto_audit");
            assertEquals(1,f.status().automaticAttempts());
            assertEquals(RecoveryResolutionStatus.ATTEMPTS_EXHAUSTED,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,1,5,true).status());
            UUID reset=UUID.randomUUID();
            assertEquals(RecoveryResolutionStatus.CONFLICT,f.repo.resetAutomaticRecoveryAttempts(f.uuid,9,1,reset,"operator").status());
            assertEquals(RecoveryResolutionStatus.RESOLVED,f.repo.resetAutomaticRecoveryAttempts(f.uuid,1,1,reset,"operator").status());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,f.repo.resetAutomaticRecoveryAttempts(f.uuid,1,1,reset,"operator").status());
            assertEquals(0,f.status().automaticAttempts());
            assertEquals(1,f.count("SELECT COUNT(*) FROM "+f.audit+" WHERE resolution='RESET_ATTEMPT_BUDGET' AND previous_fence=1 AND resolved_fence=1 AND recovery_mode='attempts:1'"));
            // The reset resolves nothing by itself.
            assertEquals(1,f.status().fence()); denied(f.acquire());
            f.sql("UPDATE "+f.coord+" SET automatic_recovery_last_attempt_at=NULL");
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,30,1,5,true).status());
        }
    }

    @Test void sqlRecoveryFailureConsumesDurableAttemptButRollsBackResolutionAndAudit() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            f.sql("CREATE TRIGGER reject_auto_audit BEFORE INSERT ON "+f.audit+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected recovery failure'");
            var op=UUID.randomUUID();
            assertThrows(SQLException.class,()->f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,op,"auto",0,30,1,5,true));
            assertEquals(1,f.status().automaticAttempts()); assertEquals(1,f.status().fence()); denied(f.acquire());
            assertEquals(0,f.count("SELECT COUNT(*) FROM "+f.audit)); f.sql("DROP TRIGGER reject_auto_audit");
            assertEquals(RecoveryResolutionStatus.ATTEMPTS_EXHAUSTED,
                f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,op,"auto",0,30,1,5,true).status());
        }
    }

    @Test void multipleLegacyPlayersRecoverWithoutOperatorsAndWithoutDoubleResolution() throws Exception {
        try(Fixture f=new Fixture()) {
            List<UUID> players=new ArrayList<>(); var gson=new com.google.gson.Gson();
            for(int i=0;i<5;i++) {
                UUID player=i==0 ? f.uuid : UUID.randomUUID(); players.add(player);
                var owner=i==0 ? f.owner : f.repo.acquireOwnershipAndLoad(player,"source",UUID.randomUUID(),5000).ownership();
                var json=gson.toJsonTree(f.data("[diamond]")).getAsJsonObject(); json.addProperty("uuid",player.toString());
                assertTrue(f.repo.saveCompletePlayerData(gson.fromJson(json,CompletePlayerData.class),owner,f.sync,5000,false).committed());
            }
            f.sql("UPDATE "+f.coord+" SET recovery_required=TRUE,recovery_reason='LOCAL_AUTHORITY_LOST',recovery_required_at=CURRENT_TIMESTAMP(6),"
                +"checkpoint_source_version=NULL,lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
            for(UUID player:players) {
                assertEquals(RecoveryResolutionStatus.RESOLVED,f.repo.resolveAutomaticLastCheckpoint(player,1,1,UUID.randomUUID(),"auto",0,true).status());
                assertEquals(AcquireStatus.ACQUIRED,f.repo.acquireOwnershipAndLoad(player,"destination",UUID.randomUUID(),5000).status());
            }
            assertEquals(5,f.count("SELECT COUNT(*) FROM "+f.audit));
        }
    }

    @Test void manualAndAutomaticRaceCannotResolveTwoOwnersOrBypassFenceCas() throws Exception {
        try(Fixture f=new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire()); var workers=Executors.newFixedThreadPool(2);
            try {
                var automatic=workers.submit(()->f.repo.resolveAutomaticLastCheckpoint(f.uuid,1,1,UUID.randomUUID(),"auto",0,true));
                var manual=workers.submit(()->f.force(UUID.randomUUID(),1,1));
                var a=automatic.get(10,TimeUnit.SECONDS); var m=manual.get(10,TimeUnit.SECONDS);
                assertTrue(a.status()==RecoveryResolutionStatus.RESOLVED || m.status()==RecoveryResolutionStatus.RESOLVED);
                assertEquals(1,f.count("SELECT COUNT(*) FROM "+f.audit)); assertEquals(2,f.status().fence());
                var acquired=f.acquire(); assertEquals(AcquireStatus.ACQUIRED,acquired.status()); assertEquals(3,acquired.ownership().fencingToken());
            } finally { workers.shutdownNow(); }
        }
    }
    @Test void progressAndStatisticsPassRealJdbcMapperWithoutTimestampOrIntegerLoss() throws Exception {
        try (Fixture f = new Fixture()) {
            var gson = new com.google.gson.Gson();
            var tree = gson.toJsonTree(f.data("[]")).getAsJsonObject();
            String progress = "{\"format\":1,\"progress\":{\"test:multi\":{\"a\":1791040000123,\"b\":1791040000987}}}";
            String stats = "{\"format\":1,\"stats\":{\"minecraft:custom\":{\"minecraft:jump\":2147483647,\"minecraft:deaths\":0}}}";
            tree.addProperty("advancementsJson", progress);
            tree.addProperty("statisticsJson", stats);
            var snapshot = gson.fromJson(tree, CompletePlayerData.class);
            f.repo.saveCompletePlayerData(snapshot, f.owner, f.sync, 5_000, true);
            var loaded = f.acquire().data().orElseThrow();
            assertEquals(progress, loaded.advancementsJson);
            assertEquals(stats, loaded.statisticsJson);
        }
    }

    @Test void disabledProgressDomainsPreserveExistingDurablePayload() throws Exception {
        try (Fixture f = new Fixture()) {
            f.repo.saveCompletePlayerData(f.data("[]"), f.owner, f.sync, 5_000, false);
            f.sync.advancements = f.sync.statistics = false;
            var gson = new com.google.gson.Gson();
            var tree = gson.toJsonTree(f.data("[]")).getAsJsonObject();
            tree.addProperty("advancementsJson", "[must-not-write]");
            tree.addProperty("statisticsJson", "[must-not-write]");
            f.repo.saveCompletePlayerData(gson.fromJson(tree, CompletePlayerData.class), f.owner, f.sync, 5_000, false);
            var loaded = f.repo.loadCompletePlayerData(f.uuid).orElseThrow();
            assertEquals("{}", loaded.advancementsJson);
            assertEquals("{}", loaded.statisticsJson);
        }
    }

    @Test void twoUnsafeResolversAndLoginSerializeWithSingleResolutionAndOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            UUID operation = UUID.randomUUID();
            ExecutorService workers = Executors.newFixedThreadPool(3);
            try {
                var first = workers.submit(() -> f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "race", 0, true));
                var second = workers.submit(() -> f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "race", 0, true));
                var login = workers.submit(f::acquire);
                Set<RecoveryResolutionStatus> results = Set.of(first.get(10, TimeUnit.SECONDS).status(), second.get(10, TimeUnit.SECONDS).status());
                assertTrue(results.contains(RecoveryResolutionStatus.RESOLVED));
                assertTrue(results.contains(RecoveryResolutionStatus.ALREADY_RESOLVED));
                var acquired = login.get(10, TimeUnit.SECONDS);
                assertTrue(acquired.status() == AcquireStatus.ACQUIRED || acquired.status() == AcquireStatus.RECOVERY_REQUIRED);
                assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit));
                assertTrue(f.status().fence() >= 2);
            } finally { workers.shutdownNow(); }
        }
    }

    @Test void safeResolverRejectsReasonOnlyAndExpiredProofWaitBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            f.sql("UPDATE " + f.coord + " SET owner_server=NULL,owner_session=NULL,lease_until=NULL,"
                + "recovery_required=TRUE,recovery_reason='FINAL_COMMIT_ACK_UNKNOWN',recovery_required_at=CURRENT_TIMESTAMP(6)");
            assertEquals(RecoveryResolutionStatus.CONFLICT,
                f.repo.resolveSafeFinalReceipt(f.uuid, 1, 1, UUID.randomUUID(), "safe").status());
            denied(f.acquire());
        }
        try (Fixture f = new Fixture()) {
            f.repo.saveCompletePlayerData(f.data("[]"), f.owner, f.sync, 5_000, true);
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='FINAL_COMMIT_ACK_UNKNOWN',"
                + "recovery_required_at=TIMESTAMPADD(SECOND,-121,CURRENT_TIMESTAMP(6))");
            assertEquals(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED,
                f.repo.resolveSafeFinalReceipt(f.uuid, 1, 1, UUID.randomUUID(), "safe", 120).status());
            denied(f.acquire());
        }
    }

    @Test void noSaveCleanReleaseDoesNotWritePayloadOrIncrementRevision() throws Exception {
        try (Fixture f = new Fixture()) {
            var snapshot = f.data("[]");
            assertTrue(f.repo.saveCompletePlayerData(snapshot, f.owner, f.sync, 5_000, false).committed());
            f.sql("CREATE TRIGGER reject_data_write BEFORE UPDATE ON " + f.config.tableName
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='must not write'");
            assertEquals(NoSaveReleaseStatus.CLEAN_RELEASED,
                f.repo.releaseWithoutDataSave(snapshot, f.owner, f.sync, false));
            assertEquals(1, f.status().revision());
            assertEquals(AcquireStatus.ACQUIRED, f.acquire().status());
            f.sql("DROP TRIGGER reject_data_write");
        }
    }

    private static CompletePlayerData withStatistics(CompletePlayerData base, String statistics) {
        var gson = new com.google.gson.Gson();
        var json = gson.toJsonTree(base).getAsJsonObject();
        json.addProperty("statisticsJson", statistics);
        return gson.fromJson(json, CompletePlayerData.class);
    }

    private static String statistics(int playTime, int jumps, int minedStone) {
        return "{\"format\":1,\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + playTime
            + ",\"minecraft:total_world_time\":" + playTime + ",\"minecraft:time_since_death\":" + playTime
            + ",\"minecraft:time_since_rest\":" + playTime + ",\"minecraft:jump\":" + jumps
            + "},\"minecraft:mined\":{\"minecraft:stone\":" + minedStone + "}}}";
    }

    @Test void noSaveIdleDisconnectWithOnlyPassiveTimeStatisticsIsACleanReleaseWithoutDataWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.sync.statistics, "statistics is enabled by default");
            var checkpoint = withStatistics(f.data("[diamond]"), statistics(100, 3, 2));
            assertTrue(f.repo.saveCompletePlayerData(checkpoint, f.owner, f.sync, 5_000, false).committed());
            f.sql("CREATE TRIGGER reject_data_write BEFORE UPDATE ON " + f.config.tableName
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='must not write'");
            // Several seconds of standing still: only the four elapsed-time counters moved.
            var idle = withStatistics(f.data("[diamond]"), statistics(260, 3, 2));
            assertEquals(NoSaveReleaseStatus.CLEAN_RELEASED, f.repo.releaseWithoutDataSave(idle, f.owner, f.sync, false));
            f.sql("DROP TRIGGER reject_data_write");
            assertEquals(1, f.status().revision());
            assertEquals(CoordinationLifecycle.State.CLEAN_RELEASED, f.status().state());
            var acquired = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, acquired.status());
            assertEquals(checkpoint.statisticsJson, acquired.data().orElseThrow().statisticsJson,
                "the durable checkpoint is untouched; passive counters are written by the next real SAVE");
        }
    }

    @Test void noSaveDisconnectAfterAMaterialStatisticChangeRequiresRecovery() throws Exception {
        for (String changed : new String[] {statistics(260, 3, 3), statistics(260, 4, 2)}) {
            try (Fixture f = new Fixture()) {
                var checkpoint = withStatistics(f.data("[diamond]"), statistics(100, 3, 2));
                assertTrue(f.repo.saveCompletePlayerData(checkpoint, f.owner, f.sync, 5_000, false).committed());
                assertEquals(NoSaveReleaseStatus.RECOVERY_REQUIRED,
                    f.repo.releaseWithoutDataSave(withStatistics(f.data("[diamond]"), changed), f.owner, f.sync, false));
                assertEquals("DIRTY_DISCONNECT_WITHOUT_SAVE", f.status().reason());
                denied(f.acquire());
                assertEquals(1, f.status().revision());
            }
        }
    }

    @Test void finalSaveStillWritesTheCurrentPassiveTimeStatistics() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.repo.saveCompletePlayerData(withStatistics(f.data("[diamond]"), statistics(100, 3, 2)),
                f.owner, f.sync, 5_000, false).committed());
            var latest = withStatistics(f.data("[diamond]"), statistics(999, 3, 2));
            assertTrue(f.repo.saveCompletePlayerData(latest, f.owner, f.sync, 5_000, true).committed());
            assertEquals(latest.statisticsJson, f.acquire().data().orElseThrow().statisticsJson);
        }
    }

    @Test void noSaveDirtyAndTransientNeverCleanRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            var snapshot = f.data("[]");
            f.repo.saveCompletePlayerData(snapshot, f.owner, f.sync, 5_000, false);
            assertEquals(NoSaveReleaseStatus.RECOVERY_REQUIRED,
                f.repo.releaseWithoutDataSave(snapshot, f.owner, f.sync, true));
            denied(f.acquire());
            assertEquals("DIRTY_DISCONNECT_WITHOUT_SAVE", f.status().reason());
        }
        try (Fixture f = new Fixture()) {
            f.repo.saveCompletePlayerData(f.data("[]"), f.owner, f.sync, 5_000, false);
            assertEquals(NoSaveReleaseStatus.RECOVERY_REQUIRED,
                f.repo.releaseWithoutDataSave(f.data("[{\"changed\":true}]"), f.owner, f.sync, false));
            denied(f.acquire());
        }
    }

    @Test void disabledItemDomainPreservesAllExistingItemAndEscrowColumns() throws Exception {
        try (Fixture f = new Fixture()) {
            var original = f.data("[]").withInventoryAndPendingItems("[]", "[{\"marker\":1}]");
            f.repo.saveCompletePlayerData(original, f.owner, f.sync, 5_000, false);
            f.sync.inventory = f.sync.armor = f.sync.offhand = f.sync.enderchest = false;
            f.repo.saveCompletePlayerData(f.data("[do-not-write]").withInventoryAndPendingItems(null, null),
                f.owner, f.sync, 5_000, false);
            var stored = f.repo.loadCompletePlayerData(f.uuid).orElseThrow();
            assertEquals("[]", stored.inventoryJson);
            assertEquals("[{\"marker\":1}]", stored.pendingDisconnectItemsJson);
            assertEquals("[]", stored.armorJson);
            assertEquals("[]", stored.offhandJson);
            assertEquals("[]", stored.enderchestJson);
        }
    }

    @Test void ambiguousFinalAckIsProvenFromTheCleanReceiptWithoutSecondWriteOrRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            var snapshot = f.data("[]");
            var faulty = new PlayerDataRepository(f.db, (phase, finalFlush) -> {
                if (phase == TransactionPhase.AFTER_COMMIT && finalFlush) throw new SQLException("ACK lost", "08006");
            });
            var result = faulty.saveCompletePlayerData(snapshot, f.owner, f.sync, 15_000, true);
            assertTrue(result.committed());
            assertEquals(1, result.dataRevision());
            var destination = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, destination.status());
            var retried = f.repo.saveCompletePlayerData(snapshot, f.owner, f.sync, 5_000, true);
            assertTrue(retried.committed());
            assertEquals(1, retried.dataRevision());
            assertEquals(destination.ownership().ownerSession().toString(), f.status().ownerSession());
            assertEquals(SaveStatus.FENCED, f.repo.saveCompletePlayerData(f.data("[changed]"), f.owner, f.sync, 5_000, true).status());
        }
    }

    @Test void ambiguousRenewalAckIsRevalidatedOnReservedConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            var faulty = new PlayerDataRepository(f.db, (phase, finalFlush) -> {
                if (phase == TransactionPhase.AFTER_RENEWAL_COMMIT) {
                    throw new SQLException("renewal ACK lost after commit", "08S01");
                }
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            var result = faulty.renewLeaseWithResult(f.owner, 15_000, deadline);

            assertTrue(result.renewed());
            assertNotEquals(PlayerDataRepository.NO_LEASE_ANCHOR, result.leaseAnchorNanos());
            assertEquals(1, faulty.renewalCommitUnknownCount());
            assertEquals(f.owner.ownerSession().toString(), f.status().ownerSession());
            assertEquals(f.owner.fencingToken(), f.status().fence());
        }
    }

    @Test void safeResolverRequiresMatchingReceiptAndAuditIsIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.repo.saveCompletePlayerData(f.data("[]"), f.owner, f.sync, 5_000, true);
            f.sql("UPDATE " + f.coord + " SET recovery_required=TRUE,recovery_reason='FINAL_COMMIT_ACK_UNKNOWN',"
                + "recovery_required_at=CURRENT_TIMESTAMP(6) WHERE uuid='" + f.uuid + "'");
            UUID operation = UUID.randomUUID();
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                f.repo.resolveSafeFinalReceipt(f.uuid, 1, 1, operation, "safe-test").status());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,
                f.repo.resolveSafeFinalReceipt(f.uuid, 1, 1, operation, "safe-test").status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit + " WHERE unsafe_acknowledged=FALSE"));
        }
        try (Fixture f = new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            assertEquals(RecoveryResolutionStatus.NOT_RECOVERY_REQUIRED,
                f.repo.resolveSafeFinalReceipt(f.uuid, 1, 1, UUID.randomUUID(), "safe-test").status());
            denied(f.acquire());
        }
    }

    @Test void unsafeResolverHonorsDelayAcknowledgementAndAuditedCas() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            UUID operation = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "unsafe", 0, false));
            assertEquals(RecoveryResolutionStatus.CONFLICT,
                f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "unsafe", 60, true).status());
            assertEquals(RecoveryResolutionStatus.RESOLVED,
                f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "unsafe", 0, true).status());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED,
                f.repo.resolveUnsafeLastCheckpoint(f.uuid, 1, 1, operation, "unsafe", 0, true).status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit + " WHERE unsafe_acknowledged=TRUE"));
        }
    }

    @Test void concurrentDestinationsPersistRecoveryWithoutReadingPlayerDataOrAdvancingFence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            f.expire();
            // Destroy the readable payload table: a denied acquire must not even
            // attempt a PlayerData SELECT, otherwise this would throw SQL failure.
            f.sql("DROP TABLE " + f.config.tableName);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<AcquireResult>> attempts = new ArrayList<>();
                for (int i=0;i<3;i++) attempts.add(pool.submit(() ->
                    f.repo.acquireOwnershipAndLoad(f.uuid, "destination", UUID.randomUUID(), 5_000)));
                for (var attempt : attempts) denied(attempt.get(5, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
            var status = f.status();
            assertEquals(f.owner.fencingToken(), status.fence());
            assertEquals(1, status.revision());
            assertEquals(f.owner.ownerSession().toString(), status.ownerSession());
            assertEquals("LEASE_EXPIRED_UNCLEAN", status.reason());
            assertNotNull(status.requiredAt());
            assertNotNull(status.lastCheckpointAt());
            assertFalse(f.repo.renewLease(f.owner, 5_000));
        }
    }
    @Test void onlyAtomicFinalCommitProducesCleanReleaseAndImmediateAcquire() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            assertEquals(AcquireStatus.BUSY, f.acquire().status());
            assertTrue(f.repo.saveCompletePlayerData(f.data("[latest]"), f.owner, f.sync, 5_000, true).committed());
            assertEquals(CoordinationLifecycle.State.CLEAN_RELEASED, f.status().state());
            var next = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, next.status());
            assertEquals("[latest]", next.data().orElseThrow().inventoryJson);
            assertEquals(f.owner.fencingToken()+1, next.ownership().fencingToken());
        }
    }
    @Test void finalSqlFailureDoesNotReleaseOrApplyOldCheckpointAfterExpiry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            f.sql("CREATE TRIGGER reject_final BEFORE UPDATE ON " + f.config.tableName
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated final fault'");
            SQLException failure = assertThrows(SQLException.class, () ->
                f.repo.saveCompletePlayerData(f.data("[changed]"), f.owner, f.sync, 5_000, true));
            assertEquals("45000", failure.getSQLState());
            assertEquals(1644, failure.getErrorCode());
            assertEquals("[diamond]", f.repo.loadCompletePlayerData(f.uuid).orElseThrow().inventoryJson);
            assertEquals(f.owner.ownerSession().toString(), f.status().ownerSession());
            f.sql("DROP TRIGGER reject_final");
            f.expire();
            denied(f.acquire());
            assertEquals(1, f.status().revision());
        }
    }
    @Test void bareAbandonmentWhileLeaseLiveIsNotCleanRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            assertTrue(f.repo.releaseOwnership(f.owner));
            denied(f.acquire());
            assertEquals("SESSION_ABANDONED", f.status().reason());
            assertEquals(f.owner.ownerSession().toString(), f.status().ownerSession());
            assertFalse(f.repo.renewLease(f.owner, 5_000));
            assertEquals(SaveStatus.FENCED, f.repo.saveCompletePlayerData(
                f.data("[bad]"), f.owner, f.sync, 5_000, true).status());
        }
    }
    @Test void explicitResolutionIsAuditedIdempotentAndCasGuarded() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            UUID operation = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> f.repo.forceLastCheckpoint(
                f.uuid, 1, 1, operation, "operator", null));
            assertEquals(RecoveryResolutionStatus.CONFLICT,
                f.force(operation, 9, 1).status());
            var resolved = f.force(operation, 1, 1);
            assertEquals(RecoveryResolutionStatus.RESOLVED, resolved.status());
            assertEquals(2, resolved.fence());
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED, f.force(operation, 1, 1).status());
            assertEquals(RecoveryResolutionStatus.CONFLICT, f.force(UUID.randomUUID(), 1, 1).status());
            assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit));
            var next = f.acquire();
            assertEquals(AcquireStatus.ACQUIRED, next.status());
            assertEquals(3, next.ownership().fencingToken());
            // Retry of a lost resolution ACK must not change the NEW owner.
            assertEquals(RecoveryResolutionStatus.ALREADY_RESOLVED, f.force(operation, 1, 1).status());
            assertEquals(next.ownership().ownerSession().toString(), f.status().ownerSession());
            assertEquals(SaveStatus.FENCED, f.repo.saveCompletePlayerData(
                f.data("[old]"), f.owner, f.sync, 5_000, false).status());
        }
    }
    @Test void verifiedSourceResolutionUpdatesSnapshotRevisionFenceAndAuditAtomically() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint(); f.expire(); denied(f.acquire());
            f.sql("CREATE TRIGGER reject_audit BEFORE INSERT ON " + f.audit
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit rollback'");
            UUID operation = UUID.randomUUID();
            assertThrows(SQLException.class, () -> f.repo.resolveRecoveryFromSource(f.data("[reconciled]"),
                f.sync, 1, 1, operation, "operator", RecoveryAcknowledgement.SOURCE_FENCED_AND_WORLD_REVIEWED));
            assertEquals("[diamond]", f.repo.loadCompletePlayerData(f.uuid).orElseThrow().inventoryJson);
            assertEquals(1, f.status().fence());
            denied(f.acquire());
            f.sql("DROP TRIGGER reject_audit");
            var result = f.repo.resolveRecoveryFromSource(f.data("[reconciled]"), f.sync, 1, 1,
                operation, "operator", RecoveryAcknowledgement.SOURCE_FENCED_AND_WORLD_REVIEWED);
            assertEquals(RecoveryResolutionStatus.RESOLVED, result.status());
            assertEquals(2, result.revision());
            assertEquals("[reconciled]", f.acquire().data().orElseThrow().inventoryJson);
            assertEquals(1, f.count("SELECT COUNT(*) FROM " + f.audit));
        }
    }
    @Test void pauseAndCrashAfterWorldExternalizationNeverRestorePlayerAutomatically() throws Exception {
        try (Fixture f = new Fixture()) {
            f.checkpoint();
            // At this point the source may spawn/transfer its diamond, then pause
            // or die. The DB has no proof that the World has/has not persisted it.
            boolean worldHasDiamond = true;
            f.expire();
            var destination = f.acquire();
            denied(destination);
            assertTrue(worldHasDiamond);
            assertTrue(destination.data().isEmpty());
            // Same source session resuming cannot resurrect its expired authority.
            denied(f.repo.acquireOwnershipAndLoad(f.uuid, f.owner.ownerServer(), f.owner.ownerSession(), 5_000));
        }
    }

    private static void denied(AcquireResult result) {
        assertEquals(AcquireStatus.RECOVERY_REQUIRED, result.status());
        assertNull(result.ownership()); assertTrue(result.data().isEmpty());
    }
    private static class Fixture implements AutoCloseable {
        final Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        final Configuration.SyncConfig sync = new Configuration.SyncConfig();
        final UUID uuid = UUID.randomUUID();
        final DatabaseManager db;
        final PlayerDataRepository repo;
        final Ownership owner;
        final String coord, audit;
        Fixture() throws Exception {
            config.host = env("STATELINK_TEST_MYSQL_HOST", "127.0.0.1");
            config.port = Integer.parseInt(env("STATELINK_TEST_MYSQL_PORT", "3306"));
            config.name = System.getenv("STATELINK_TEST_MYSQL_DATABASE");
            config.username = env("STATELINK_TEST_MYSQL_USERNAME", "root");
            config.password = env("STATELINK_TEST_MYSQL_PASSWORD", "");
            config.maxConnections = 4;
            config.tableName = "pdc_rec_" + UUID.randomUUID().toString().replace("-", "");
            coord = config.tableName + "_sync_state"; audit = DatabaseManager.recoveryAuditTableName(coord);
            db = new DatabaseManager(config, coord); repo = new PlayerDataRepository(db);
            owner = repo.acquireOwnershipAndLoad(uuid, "source", UUID.randomUUID(), 5_000).ownership();
        }
        void checkpoint() throws SQLException {
            assertTrue(repo.saveCompletePlayerData(data("[diamond]"), owner, sync, 5_000, false).committed());
        }
        void expire() throws SQLException {
            sql("UPDATE " + coord + " SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE uuid='" + uuid + "'");
        }
        AcquireResult acquire() throws SQLException { return repo.acquireOwnershipAndLoad(uuid, "destination", UUID.randomUUID(), 5_000); }
        RecoveryStatus status() throws SQLException { return repo.recoveryStatus(uuid).orElseThrow(); }
        RecoveryResolutionResult force(UUID operation, long fence, long revision) throws SQLException {
            return repo.forceLastCheckpoint(uuid, fence, revision, operation, "integration-operator",
                RecoveryAcknowledgement.SOURCE_FENCED_AND_WORLD_REVIEWED);
        }
        CompletePlayerData data(String inventory) {
            return new CompletePlayerData(uuid, "recovery", inventory, "[]", "[]", "[]",
                20, 20, 5, 0, 300, 0, 0, 0, null, "[]", "minecraft:overworld", 0,64,0,0,0,
                "survival", false,false,false,"recovery",null,null,"{}","{}","{}",0,0,1);
        }
        void sql(String sql) throws SQLException {
            try (Connection c=db.getConnection(); Statement s=c.createStatement()) { s.execute(sql); }
        }
        long count(String sql) throws SQLException {
            try (Connection c=db.getConnection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) {
                assertTrue(r.next()); return r.getLong(1);
            }
        }
        public void close() throws SQLException {
            try { sql("DROP TABLE IF EXISTS " + audit); sql("DROP TABLE IF EXISTS " + coord); sql("DROP TABLE IF EXISTS " + config.tableName); }
            finally { db.close(); }
        }
    }
    private static String env(String name, String fallback) {
        String value=System.getenv(name); return value==null || value.isBlank() ? fallback : value;
    }
}
