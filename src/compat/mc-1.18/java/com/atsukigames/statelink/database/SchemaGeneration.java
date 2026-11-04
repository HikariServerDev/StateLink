package com.atsukigames.statelink.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies an existing database before any DDL is applied. A generation is decided from the
 * tables, columns, types, keys and engines that a known release created, never from the fact
 * that provenance values happen to be NULL.
 */
public final class SchemaGeneration {
    public enum Generation {
        /** None of the StateLink tables exist. */
        FRESH,
        /** Exactly the schema PlayerDataConnector 2.1.11 leaves behind. */
        LEGACY_2_1_11,
        /** 2.2.0-2.2.4: recovery/receipt columns but no domain provenance columns. */
        PRE_PROVENANCE_2_2,
        /** 2.2.5+: domain provenance columns exist. */
        CURRENT_PROVENANCE,
        UNKNOWN_SCHEMA
    }

    public record Detection(Generation generation, String detail) {}

    /**
     * {@code defaultValue} is semantically normalized (see {@link #normalizeDefault}); {@code onUpdate}
     * is whether the column carries ON UPDATE CURRENT_TIMESTAMP.
     */
    record Column(String dataType, boolean nullable, long length, boolean unsigned, int datetimePrecision,
        String defaultValue, boolean onUpdate) {}

    private static Column col(String type, boolean nullable, long length, boolean unsigned, int precision) {
        return new Column(type, nullable, length, unsigned, precision, null, false);
    }

    private static Column col(String type, boolean nullable, long length, boolean unsigned, int precision,
        String defaultValue) {
        return new Column(type, nullable, length, unsigned, precision, normalizeDefault(defaultValue), false);
    }

    private static Column onUpdate(Column column) {
        return new Column(column.dataType(), column.nullable(), column.length(), column.unsigned(),
            column.datetimePrecision(), column.defaultValue(), true);
    }

    /**
     * MySQL reports semantically equal defaults differently ({@code 20.0} as {@code 20},
     * {@code FALSE} as {@code 0}, optional quotes, upper/lower case functions). Compare meaning.
     */
    static String normalizeDefault(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) text = text.substring(1, text.length() - 1);
        if (text.equalsIgnoreCase("NULL")) return null;
        if (text.equalsIgnoreCase("FALSE")) return "0";
        if (text.equalsIgnoreCase("TRUE")) return "1";
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(text);
            return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notNumeric) {
            String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (lower.equals("now()") || lower.equals("current_timestamp()")) return "current_timestamp";
            return lower.startsWith("current_timestamp") ? lower : text;
        }
    }

    /** player_data exactly as created by 2.1.11 DatabaseManager.initializeTables plus its three additive columns. */
    static final Map<String, Column> PLAYER_DATA_2_1_11 = orderedMap(
        "uuid", col("varchar", false, 36, false, -1),
        "username", col("varchar", false, 16, false, -1),
        "inventory", col("longtext", true, 0, false, -1),
        "pending_disconnect_items", col("longtext", true, 0, false, -1),
        "enderchest", col("longtext", true, 0, false, -1),
        "armor", col("longtext", true, 0, false, -1),
        "offhand", col("longtext", true, 0, false, -1),
        "health", col("double", true, 0, false, -1, "20.0"),
        "food_level", col("int", true, 0, false, -1, "20"),
        "saturation", col("float", true, 0, false, -1, "5.0"),
        "exhaustion", col("float", true, 0, false, -1, "0.0"),
        "air", col("int", true, 0, false, -1, "300"),
        "experience_level", col("int", true, 0, false, -1, "0"),
        "experience_points", col("int", true, 0, false, -1, "0"),
        "experience_total", col("float", true, 0, false, -1, "0.0"),
        "experience_progress", col("float", true, 0, false, -1),
        "experience_points_into_level", col("bigint", true, 0, false, -1),
        "effects", col("longtext", true, 0, false, -1),
        "dimension", col("varchar", true, 64, false, -1, "minecraft:overworld"),
        "pos_x", col("double", true, 0, false, -1, "0.0"),
        "pos_y", col("double", true, 0, false, -1, "64.0"),
        "pos_z", col("double", true, 0, false, -1, "0.0"),
        "yaw", col("float", true, 0, false, -1, "0.0"),
        "pitch", col("float", true, 0, false, -1, "0.0"),
        "gamemode", col("varchar", true, 16, false, -1, "survival"),
        "is_flying", col("tinyint", true, 0, false, -1, "FALSE"),
        "allow_flying", col("tinyint", true, 0, false, -1, "FALSE"),
        "is_creative_flying", col("tinyint", true, 0, false, -1, "FALSE"),
        "display_name", col("varchar", true, 32, false, -1),
        "skin_texture", col("longtext", true, 0, false, -1),
        "skin_signature", col("longtext", true, 0, false, -1),
        "advancements", col("longtext", true, 0, false, -1),
        "statistics", col("longtext", true, 0, false, -1),
        "recipe_book", col("longtext", true, 0, false, -1),
        "selected_item_slot", col("int", true, 0, false, -1, "0"),
        "first_login", col("timestamp", true, 0, false, 0, "CURRENT_TIMESTAMP"),
        "last_login", onUpdate(col("timestamp", true, 0, false, 0, "CURRENT_TIMESTAMP")),
        "login_count", col("int", true, 0, false, -1, "1"),
        "play_time_seconds", col("bigint", true, 0, false, -1, "0"),
        "backup_data", col("longtext", true, 0, false, -1),
        "backup_timestamp", col("timestamp", true, 0, false, 0));

    /** Secondary indexes of player_data in 2.1.11: name -> ordered columns (all non-unique). */
    static final Map<String, List<String>> PLAYER_DATA_INDEXES_2_1_11 = Map.of(
        "idx_username", List.of("username"), "idx_last_login", List.of("last_login"),
        "idx_dimension", List.of("dimension"));

    /** Column set of player_data in 2.1.11 (identical in 2.2.x). */
    static final Set<String> PLAYER_2_1_11 = Set.of(
        "uuid", "username", "inventory", "pending_disconnect_items", "enderchest", "armor", "offhand",
        "health", "food_level", "saturation", "exhaustion", "air",
        "experience_level", "experience_points", "experience_total", "experience_progress",
        "experience_points_into_level", "effects",
        "dimension", "pos_x", "pos_y", "pos_z", "yaw", "pitch",
        "gamemode", "is_flying", "allow_flying", "is_creative_flying",
        "display_name", "skin_texture", "skin_signature", "advancements", "statistics",
        "recipe_book", "selected_item_slot",
        "first_login", "last_login", "login_count", "play_time_seconds", "backup_data", "backup_timestamp");

    /** Coordination table exactly as created/extended by 2.1.11 DatabaseManager. */
    static final Map<String, Column> COORDINATION_2_1_11 = orderedMap(
        "uuid", col("varchar", false, 36, false, -1),
        "owner_server", col("varchar", true, 64, false, -1),
        "owner_session", col("char", true, 36, false, -1),
        "fencing_token", col("bigint", false, 0, true, -1, "0"),
        "lease_until", col("datetime", true, 0, false, 6),
        "data_revision", col("bigint", false, 0, true, -1, "0"),
        "recovery_required", col("tinyint", false, 0, false, -1, "FALSE"),
        "recovery_reason", col("varchar", true, 64, false, -1),
        "recovery_required_at", col("datetime", true, 0, false, 6),
        "last_checkpoint_at", col("datetime", true, 0, false, 6),
        "updated_at", onUpdate(col("timestamp", false, 0, false, 6, "CURRENT_TIMESTAMP(6)")));

    /** Recovery audit table exactly as created by 2.1.11. */
    static final Map<String, Column> AUDIT_2_1_11 = orderedMap(
        "operation_id", col("char", false, 36, false, -1),
        "uuid", col("char", false, 36, false, -1),
        "actor", col("varchar", false, 64, false, -1),
        "resolution", col("varchar", false, 32, false, -1),
        "recovery_reason", col("varchar", true, 64, false, -1),
        "previous_owner_server", col("varchar", true, 64, false, -1),
        "previous_owner_session", col("char", true, 36, false, -1),
        "previous_fence", col("bigint", false, 0, true, -1),
        "previous_revision", col("bigint", false, 0, true, -1),
        "resolved_fence", col("bigint", false, 0, true, -1),
        "resolved_revision", col("bigint", false, 0, true, -1),
        "resolved_at", col("datetime", false, 0, false, 6, "CURRENT_TIMESTAMP(6)"));

    /** Coordination columns added by 2.2.0-2.2.4. */
    static final Set<String> PRE_PROVENANCE_COLUMNS = Set.of(
        "checkpoint_source_version", "automatic_recovery_attempts", "automatic_recovery_last_attempt_at",
        "clean_release_session", "clean_release_fence", "clean_release_revision", "clean_release_digest",
        "clean_release_domains");

    static final Set<String> PROVENANCE_COLUMNS = Set.of(
        "domain_provenance_revision", "domain_provenance_generation", "domain_provenance_manifest");

    private SchemaGeneration() {}

    public static Detection detect(Connection connection, String playerTable, String coordinationTable,
        String auditTable) throws SQLException {
        Map<String, Column> player = columns(connection, playerTable);
        Map<String, Column> coordination = columns(connection, coordinationTable);
        Map<String, Column> audit = columns(connection, auditTable);
        if (player.isEmpty() && coordination.isEmpty() && audit.isEmpty()) {
            return new Detection(Generation.FRESH, "no StateLink tables");
        }
        if (player.isEmpty() || coordination.isEmpty()) {
            return unknown("player data and coordination tables must both exist (player=" + !player.isEmpty()
                + ", coordination=" + !coordination.isEmpty() + ")");
        }
        if (!coordination.keySet().containsAll(COORDINATION_2_1_11.keySet())) {
            return unknown("coordination table lacks 2.1.11 columns "
                + difference(COORDINATION_2_1_11.keySet(), coordination.keySet()));
        }
        Set<String> extra = difference(coordination.keySet(), COORDINATION_2_1_11.keySet());
        if (extra.isEmpty()) return detectLegacy(connection, playerTable, coordinationTable, auditTable,
            player, coordination, audit);
        Set<String> unknownColumns = new java.util.TreeSet<>(extra);
        unknownColumns.removeAll(PRE_PROVENANCE_COLUMNS);
        unknownColumns.removeAll(PROVENANCE_COLUMNS);
        if (!unknownColumns.isEmpty()) return unknown("coordination table has unrecognized columns " + unknownColumns);
        if (extra.containsAll(PROVENANCE_COLUMNS)) {
            return new Detection(Generation.CURRENT_PROVENANCE, "domain provenance columns present");
        }
        return new Detection(Generation.PRE_PROVENANCE_2_2, "2.2.x coordination columns " + extra);
    }

    private static Detection detectLegacy(Connection connection, String playerTable, String coordinationTable,
        String auditTable, Map<String, Column> player, Map<String, Column> coordination, Map<String, Column> audit)
        throws SQLException {
        String playerMismatch = player.keySet().equals(PLAYER_2_1_11)
            ? firstMismatch(playerTable, PLAYER_DATA_2_1_11, player) : null;
        if (playerMismatch != null) return unknown(playerMismatch);
        if (!player.keySet().equals(PLAYER_2_1_11)) {
            return unknown("coordination table matches 2.1.11 but player data columns differ: missing="
                + difference(PLAYER_2_1_11, player.keySet()) + " unexpected=" + difference(player.keySet(), PLAYER_2_1_11));
        }
        String mismatch = firstMismatch(coordinationTable, COORDINATION_2_1_11, coordination);
        if (mismatch != null) return unknown(mismatch);
        if (audit.isEmpty()) return unknown("coordination table matches 2.1.11 but its recovery audit table is missing");
        if (!audit.keySet().equals(AUDIT_2_1_11.keySet())) {
            return unknown("coordination table matches 2.1.11 but recovery audit columns differ: missing="
                + difference(AUDIT_2_1_11.keySet(), audit.keySet()) + " unexpected="
                + difference(audit.keySet(), AUDIT_2_1_11.keySet()));
        }
        mismatch = firstMismatch(auditTable, AUDIT_2_1_11, audit);
        if (mismatch != null) return unknown(mismatch);
        Map<String, List<String>> playerIndexes = indexes(connection, playerTable);
        Map<String, List<String>> expectedPlayerIndexes = new java.util.TreeMap<>(PLAYER_DATA_INDEXES_2_1_11);
        expectedPlayerIndexes.put("primary", List.of("uuid"));
        if (!expectedPlayerIndexes.equals(playerIndexes)) {
            return unknown(playerTable + " indexes " + playerIndexes + " differ from the 2.1.11 definition "
                + expectedPlayerIndexes);
        }
        if (!Map.of("primary", List.of("uuid")).equals(indexes(connection, coordinationTable))) {
            return unknown(coordinationTable + " indexes differ from the 2.1.11 definition");
        }
        if (!Map.of("primary", List.of("operation_id"), "idx_uuid", List.of("uuid")).equals(indexes(connection, auditTable))) {
            return unknown(auditTable + " indexes differ from the 2.1.11 definition");
        }
        for (String[] key : new String[][] {{playerTable, "uuid"}, {coordinationTable, "uuid"}, {auditTable, "operation_id"}}) {
            if (!List.of(key[1]).equals(index(connection, key[0], "PRIMARY"))) {
                return unknown(key[0] + " primary key is not (" + key[1] + ")");
            }
        }
        if (!List.of("uuid").equals(index(connection, auditTable, "idx_uuid"))) {
            return unknown(auditTable + " lacks the 2.1.11 idx_uuid index");
        }
        for (String table : new String[] {playerTable, coordinationTable, auditTable}) {
            String engine = engine(connection, table);
            if (!"InnoDB".equalsIgnoreCase(engine)) return unknown(table + " engine is " + engine + ", not InnoDB");
        }
        return new Detection(Generation.LEGACY_2_1_11, "exact 2.1.11 player, coordination and recovery audit schema");
    }

    private static Detection unknown(String detail) { return new Detection(Generation.UNKNOWN_SCHEMA, detail); }

    private static String firstMismatch(String table, Map<String, Column> expected, Map<String, Column> actual) {
        for (var entry : expected.entrySet()) {
            Column want = entry.getValue(), have = actual.get(entry.getKey());
            if (have == null || !want.dataType().equals(have.dataType()) || want.nullable() != have.nullable()
                    || (want.length() > 0 && want.length() != have.length()) || want.unsigned() != have.unsigned()
                    || (want.datetimePrecision() >= 0 && want.datetimePrecision() != have.datetimePrecision())
                    || !java.util.Objects.equals(want.defaultValue(), have.defaultValue())
                    || want.onUpdate() != have.onUpdate()) {
                return table + "." + entry.getKey() + " differs from the 2.1.11 definition";
            }
        }
        return null;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new java.util.TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    static Map<String, Column> columns(Connection connection, String table) throws SQLException {
        Map<String, Column> result = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,DATETIME_PRECISION,"
                    + "COLUMN_DEFAULT,EXTRA "
                    + "FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                    + "ORDER BY ORDINAL_POSITION")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String columnType = rows.getString(3);
                    long length = rows.getLong(5);
                    if (rows.wasNull()) length = 0;
                    int precision = rows.getInt(6);
                    if (rows.wasNull()) precision = -1;
                    result.put(rows.getString(1).toLowerCase(Locale.ROOT), new Column(
                        rows.getString(2).toLowerCase(Locale.ROOT), "YES".equals(rows.getString(4)), length,
                        columnType != null && columnType.toLowerCase(Locale.ROOT).contains("unsigned"), precision,
                        normalizeDefault(rows.getString(7)), rows.getString(8) != null
                            && rows.getString(8).toLowerCase(Locale.ROOT).contains("on update current_timestamp")));
                }
            }
        }
        return result;
    }

    private static List<String> index(Connection connection, String table, String index) throws SQLException {
        List<String> result = new java.util.ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX")) {
            query.setString(1, table);
            query.setString(2, index);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** Every index of a table, lower-cased name -> ordered columns. Unique secondary indexes are reported as differing. */
    private static Map<String, List<String>> indexes(Connection connection, String table) throws SQLException {
        Map<String, List<String>> result = new java.util.TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT INDEX_NAME,COLUMN_NAME,NON_UNIQUE FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1).toLowerCase(Locale.ROOT);
                    boolean primary = name.equals("primary");
                    String column = rows.getString(2).toLowerCase(Locale.ROOT);
                    result.computeIfAbsent(name, ignored -> new java.util.ArrayList<>())
                        .add(!primary && rows.getInt(3) == 0 ? column + "(unique)" : column);
                }
            }
        }
        return result;
    }

    private static String engine(Connection connection, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static Map<String, Column> orderedMap(Object... pairs) {
        Map<String, Column> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Column) pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(result);
    }
}
