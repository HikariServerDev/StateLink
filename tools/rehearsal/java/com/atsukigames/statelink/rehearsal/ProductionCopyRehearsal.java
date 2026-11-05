package com.atsukigames.statelink.rehearsal;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.DatabaseManager;
import com.atsukigames.statelink.database.DatabaseManager.LegacyBootstrapRequest;
import com.atsukigames.statelink.database.PlayerDataRepository;
import com.atsukigames.statelink.database.PlayerDataRepository.AcquireStatus;
import com.atsukigames.statelink.database.SchemaGeneration;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Rehearsal on an ISOLATED COPY of the production StateLink tables. Never point this at
 * a live database: it performs the real acknowledged bootstrap and acquires ownership of every row.
 * Output is aggregate only; no player names, UUIDs or item contents are written.
 */
public final class ProductionCopyRehearsal {
    private static Connection raw(Configuration.DatabaseConfig c) throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + c.host + ":" + c.port + "/" + c.name
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo", c.username, c.password);
    }

    private static String text(Configuration.DatabaseConfig c, String sql) throws Exception {
        try (Connection k = raw(c); Statement s = k.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    private static String checksums(Configuration.DatabaseConfig c) throws Exception {
        StringBuilder all = new StringBuilder();
        try (Connection k = raw(c); Statement s = k.createStatement();
             ResultSet r = s.executeQuery("CHECKSUM TABLE player_data, player_data_sync_state, player_data_sync_state_recovery_audit")) {
            while (r.next()) all.append(r.getString(1)).append('=').append(r.getString(2)).append(';');
        }
        return all.toString() + text(c, "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME,':',COLUMN_NAME) ORDER BY TABLE_NAME,ORDINAL_POSITION) "
            + "FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()").hashCode();
    }

    public static void main(String[] args) throws Exception {
        Configuration.DatabaseConfig config = new Configuration.DatabaseConfig();
        config.host = "127.0.0.1";
        config.port = Integer.parseInt(System.getenv("STATELINK_REHEARSAL_PORT"));
        config.name = System.getenv("STATELINK_REHEARSAL_DATABASE");
        config.username = System.getenv("STATELINK_REHEARSAL_USERNAME");
        config.password = System.getenv("STATELINK_REHEARSAL_PASSWORD");
        config.maxConnections = 4;
        if (config.port == 3306 || !config.name.startsWith("pdc228_prodcopy")) throw new IllegalStateException("refusing a non-rehearsal database");
        String coord = "player_data_sync_state";
        Map<String, Object> report = new LinkedHashMap<>();

        // Production 2.1.11 domain switches.
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        sync.position = false; sync.dimension = false; sync.rotation = false; sync.gamemode = false;
        String policy = "adopt-first-login-local";

        try (Connection k = raw(config)) {
            var detection = SchemaGeneration.detect(k, "player_data", coord, coord + "_recovery_audit");
            report.put("schemaGeneration", detection.generation() + " (" + detection.detail() + ")");
            if (detection.generation() != SchemaGeneration.Generation.LEGACY_2_1_11) throw new IllegalStateException(detection.detail());
        }
        report.put("rows", Map.of("playerData", text(config, "SELECT COUNT(*) FROM player_data"),
            "coordination", text(config, "SELECT COUNT(*) FROM " + coord),
            "withoutCoordination", text(config, "SELECT COUNT(*) FROM player_data p LEFT JOIN " + coord + " c ON c.uuid=p.uuid WHERE c.uuid IS NULL")));

        // ---- structural decode validation of every adopted column (the product's own preflight rules).
        // The registry-backed codec cannot be bootstrapped outside a server; the full LOAD of every
        // row is exercised separately by logging each player into a real 2.2.8 server on a second copy.
        var states = com.atsukigames.statelink.database.Legacy211Bootstrap.plan(sync, policy).states();
        Map<String, Integer> shapes = new TreeMap<>(), failures = new TreeMap<>(), lastWriteYearMonth = new TreeMap<>();
        int decoded = 0, failedRows = 0, itemEntries = 0;
        try (Connection k = raw(config); Statement s = k.createStatement(); ResultSet r = s.executeQuery(
                "SELECT p.inventory,p.pending_disconnect_items,p.enderchest,p.armor,p.offhand,p.effects,"
                    + "DATE_FORMAT(p.last_login,'%Y-%m'),c.uuid IS NULL,p.experience_points_into_level IS NULL,"
                    + "p.experience_progress IS NULL,p.health,p.air,p.food_level,p.saturation,p.exhaustion,p.experience_level,"
                    + "p.experience_points,p.experience_total,p.experience_progress,p.experience_points_into_level,p.gamemode,"
                    + "p.selected_item_slot,p.dimension,p.pos_x,p.pos_y,p.pos_z,p.yaw,p.pitch FROM player_data p LEFT JOIN "
                    + coord + " c ON c.uuid=p.uuid")) {
            while (r.next()) {
                float progress = r.getFloat(19); Float progressValue = r.wasNull() ? null : progress;
                long into = r.getLong(20); Long intoValue = r.wasNull() ? null : into;
                var problems = com.atsukigames.statelink.database.LegacyRowPreflight.problems(
                    new com.atsukigames.statelink.database.LegacyRowPreflight.Row(r.getString(1), r.getString(2),
                        r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getDouble(11), r.getInt(12),
                        r.getInt(13), r.getFloat(14), r.getFloat(15), r.getInt(16), r.getInt(17), r.getFloat(18),
                        progressValue, intoValue, r.getString(21), r.getInt(22), r.getString(23), r.getDouble(24),
                        r.getDouble(25), r.getDouble(26), r.getFloat(27), r.getFloat(28)), states);
                for (String problem : problems) failures.merge(problem, 1, Integer::sum);
                if (problems.isEmpty()) decoded++; else failedRows++;
                String inv = r.getString(1);
                boolean wholeInventory = false, anyNbt = false;
                for (int column : new int[] {1, 3, 4, 5}) itemEntries += JsonParser.parseString(r.getString(column)).getAsJsonArray().size();
                for (var element : JsonParser.parseString(inv).getAsJsonArray()) {
                    var object = element.getAsJsonObject();
                    if (object.get("slot").getAsInt() >= 36) wholeInventory = true;
                    if (object.has("nbt")) anyNbt = true;
                }
                String group = r.getBoolean(8) ? "withoutCoordination" : "withCoordination";
                shapes.merge(group + ".total", 1, Integer::sum);
                if (wholeInventory) shapes.merge(group + ".inventoryHasSlots36to40(pre-split writer)", 1, Integer::sum);
                if (anyNbt) shapes.merge(group + ".itemsCarrySnbt", 1, Integer::sum);
                if (r.getString(2) == null) shapes.merge(group + ".pendingNull", 1, Integer::sum);
                if (r.getBoolean(9)) shapes.merge(group + ".xpPointsIntoLevelNull(pre-exact-XP writer)", 1, Integer::sum);
                if (r.getBoolean(10)) shapes.merge(group + ".xpProgressNull", 1, Integer::sum);
                lastWriteYearMonth.merge(group + "." + r.getString(7), 1, Integer::sum);
            }
        }
        report.put("structuralPreflight", Map.of("rowsValid", decoded, "rowsFailed", failedRows, "failuresByReason", failures,
            "itemEntries", itemEntries));
        report.put("rowShapes", shapes);
        report.put("lastWriteByMonth", lastWriteYearMonth);

        // ---- no acknowledgement: refused, nothing modified ----
        String before = checksums(config);
        try {
            new DatabaseManager(config, coord, new LegacyBootstrapRequest(sync, false, "", "disabled", "rehearsal")).close();
            throw new IllegalStateException("unacknowledged start was not refused");
        } catch (RuntimeException refused) {
            if (!String.valueOf(refused.getMessage()).startsWith("LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED")) throw refused;
        }
        try {
            new DatabaseManager(config, coord, new LegacyBootstrapRequest(sync, true, "", "disabled", "rehearsal")).close();
            throw new IllegalStateException("missing new-domain policy was not refused");
        } catch (RuntimeException refused) {
            if (!String.valueOf(refused.getMessage()).startsWith("LEGACY_2_1_11_NEW_DOMAIN_POLICY_REQUIRED")) throw refused;
        }
        report.put("refusalsLeftDatabaseUntouched", before.equals(checksums(config)));
        if (!before.equals(checksums(config))) throw new IllegalStateException("refused startup modified the copy");
        String legacyCoordination = text(config, "SELECT GROUP_CONCAT(CONCAT_WS(':',uuid,fencing_token,data_revision,IFNULL(owner_session,''),"
            + "recovery_required) ORDER BY uuid) FROM " + coord);
        String payloadDigest = text(config, "SELECT SHA2(GROUP_CONCAT(SHA2(CONCAT_WS('|',uuid,inventory,enderchest,armor,offhand,effects,health,"
            + "food_level,experience_level,experience_points),256) ORDER BY uuid SEPARATOR ''),256) FROM player_data");

        // ---- acknowledged bootstrap (includes the product's own preflight) ----
        long started = System.nanoTime();
        DatabaseManager db = new DatabaseManager(config, coord, new LegacyBootstrapRequest(sync, true, "", policy, "rehearsal"));
        long bootstrapMs = (System.nanoTime() - started) / 1_000_000L;
        try {
            Map<String, Object> bootstrap = new LinkedHashMap<>();
            bootstrap.put("elapsedMs", bootstrapMs);
            bootstrap.put("record", text(config, "SELECT CONCAT_WS('|',state,source_schema,target_version,operator_acknowledged,row_count,"
                + "synthetic_revision_rows,quarantined_rows,new_domain_policy) FROM " + db.getMigrationTableName()));
            bootstrap.put("rowsWithProvenanceAtOwnRevision", text(config, "SELECT COUNT(*) FROM player_data p JOIN " + coord
                + " c ON c.uuid=p.uuid WHERE c.domain_provenance_revision=c.data_revision AND c.domain_provenance_generation=1"));
            bootstrap.put("syntheticRevisionOneFenceZero", text(config, "SELECT COUNT(*) FROM " + coord
                + " WHERE data_revision=1 AND fencing_token=0 AND checkpoint_source_version IS NULL AND last_checkpoint_at IS NOT NULL AND owner_session IS NULL"));
            bootstrap.put("payloadUnchanged", payloadDigest.equals(text(config, "SELECT SHA2(GROUP_CONCAT(SHA2(CONCAT_WS('|',uuid,inventory,enderchest,"
                + "armor,offhand,effects,health,food_level,experience_level,experience_points),256) ORDER BY uuid SEPARATOR ''),256) FROM player_data")));
            String after = text(config, "SELECT GROUP_CONCAT(CONCAT_WS(':',uuid,fencing_token,data_revision,IFNULL(owner_session,''),recovery_required) "
                + "ORDER BY uuid) FROM " + coord + " WHERE NOT (data_revision=1 AND fencing_token=0 AND checkpoint_source_version IS NULL "
                + "AND uuid NOT IN ('" + String.join("','", legacyCoordination.isEmpty() ? new String[0]
                    : java.util.Arrays.stream(legacyCoordination.split(",")).map(v -> v.split(":")[0]).toArray(String[]::new)) + "'))");
            bootstrap.put("existingCoordinationRowsUnchanged", legacyCoordination.equals(after));
            report.put("bootstrap", bootstrap);

            // ---- every player: provenance gate, recovery policy, LOAD ----
            PlayerDataRepository repo = new PlayerDataRepository(db);
            int legacyAge = new Configuration.MigrationConfig().legacy211RecoveryMaxCheckpointAgeSeconds;
            Map<String, Integer> outcomes = new TreeMap<>();
            java.util.List<String> uuids = new java.util.ArrayList<>();
            try (Connection k = raw(config); Statement s = k.createStatement(); ResultSet r = s.executeQuery("SELECT uuid FROM player_data")) {
                while (r.next()) uuids.add(r.getString(1));
            }
            for (String id : uuids) {
                UUID uuid = UUID.fromString(id);
                var result = repo.acquireOwnershipAndLoad(uuid, "rehearsal", UUID.randomUUID(), 60_000, sync);
                String outcome = result.status().name();
                if (result.status() == AcquireStatus.RECOVERY_REQUIRED) {
                    outcome += "(" + result.recoveryReason() + ")";
                    var status = repo.recoveryStatus(uuid).orElseThrow();
                    // Defaults only: normal age 30 seconds, legacy backlog age from MigrationConfig, no operator command.
                    var resolved = repo.resolveAutomaticLastCheckpoint(uuid, status.fence(), status.revision(), UUID.randomUUID(),
                        "auto:rehearsal", 0, 30, legacyAge, 12, 5, true);
                    if (resolved.status() == PlayerDataRepository.RecoveryResolutionStatus.CONFLICT) {
                        Thread.sleep(50);
                        resolved = repo.resolveAutomaticLastCheckpoint(uuid, status.fence(), status.revision(), UUID.randomUUID(),
                            "auto:rehearsal", 0, 30, legacyAge, 12, 5, true);
                    }
                    outcome += "->automatic:" + resolved.status();
                    result = repo.acquireOwnershipAndLoad(uuid, "rehearsal", UUID.randomUUID(), 60_000, sync);
                    outcome += "->" + result.status();
                }
                if (result.status() == AcquireStatus.ACQUIRED && result.data().isEmpty()) outcome += "(no data)";
                outcomes.merge(outcome, 1, Integer::sum);
            }
            report.put("loginGateForEveryPlayer", outcomes);
            report.put("recoveryAuditAfter", text(config, "SELECT GROUP_CONCAT(CONCAT_WS('|',resolution,recovery_reason,recovery_mode,source_version) "
                + "SEPARATOR ';') FROM " + coord + "_recovery_audit WHERE recovery_mode='automatic'"));
        } finally {
            db.close();
        }
        Path out = Path.of(System.getenv("STATELINK_REHEARSAL_REPORT"));
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(Files.readString(out));
    }
}
