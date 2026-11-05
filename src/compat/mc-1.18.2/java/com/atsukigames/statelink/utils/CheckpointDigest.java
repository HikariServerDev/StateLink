package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Durable final-receipt proof covers exactly the enabled domain values, not mutable objects. */
public final class CheckpointDigest {
    private static final Gson GSON = new Gson();
    /** A valid clean receipt is the existing domain-history marker; never auto-apply a previously disabled domain. */
    public static boolean reenablesDomain(String previous, Configuration.SyncConfig current) {
        var old = com.google.gson.JsonParser.parseString(previous).getAsJsonObject();
        var now = com.google.gson.JsonParser.parseString(domains(current)).getAsJsonObject();
        for (var entry : now.entrySet()) {
            String field = entry.getKey();
            boolean wasEnabled = old.has(field) ? old.get(field).getAsBoolean()
                : ((field.equals("dimension") || field.equals("rotation")) ? old.get("position").getAsBoolean() : true);
            if (entry.getValue().getAsBoolean() && !wasEnabled) return true;
        }
        return false;
    }
    private CheckpointDigest() {}
    public static String domains(Configuration.SyncConfig sync) {
        return domains(sync, false);
    }
    private static String domains(Configuration.SyncConfig sync, boolean legacy) {
        JsonObject domains = new JsonObject();
        for (String name : new String[]{"inventory", "enderchest", "armor", "offhand", "health", "food",
            "experience", "effects", "position", "gamemode", "playerProfile", "advancements", "statistics", "recipeBook"}) {
            try { domains.addProperty(name, Configuration.SyncConfig.class.getField(name).getBoolean(sync)); }
            catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
        }
        if (!legacy) {
            domains.addProperty("dimension", sync.dimensionEnabled());
            domains.addProperty("rotation", sync.rotationEnabled());
        }
        return domains.toString();
    }
    public static String digest(CompletePlayerData data, Configuration.SyncConfig sync) {
        return digest(data, sync, false, false);
    }
    /**
     * Semantic identity used only to attest an unchanged checkpoint. Vanilla elapsed-time
     * statistics are deliberately excluded: they are retained in payloads but are not dirty
     * mutations under the established dirty-only policy.
     */
    public static String verifiedStateDigest(CompletePlayerData data, Configuration.SyncConfig sync) {
        return digest(data, sync, false, true);
    }
    /** Verify old receipts under their original aggregate-position schema only. */
    public static String legacyDigest(CompletePlayerData data, Configuration.SyncConfig sync) {
        return digest(data, sync, true, false);
    }
    private static String digest(CompletePlayerData data, Configuration.SyncConfig sync, boolean legacy,
        boolean excludePassiveStatistics) {
        JsonObject all = GSON.toJsonTree(data).getAsJsonObject();
        JsonObject selected = new JsonObject();
        selected.add("uuid", all.get("uuid"));
        add(selected, all, sync.inventory, "inventoryJson", "pendingDisconnectItemsJson", "selectedItemSlot");
        add(selected, all, sync.armor, "armorJson");
        add(selected, all, sync.offhand, "offhandJson");
        add(selected, all, sync.enderchest, "enderchestJson");
        add(selected, all, sync.health, "health", "air");
        add(selected, all, sync.food, "foodLevel", "saturation", "exhaustion");
        add(selected, all, sync.experience, "experienceLevel", "experiencePoints", "experiencePointsIntoLevel");
        add(selected, all, sync.effects, "effectsJson");
        if (legacy) add(selected, all, sync.position, "dimension", "posX", "posY", "posZ", "yaw", "pitch");
        else {
            add(selected, all, sync.dimensionEnabled(), "dimension");
            add(selected, all, sync.position, "posX", "posY", "posZ");
            add(selected, all, sync.rotationEnabled(), "yaw", "pitch");
        }
        add(selected, all, sync.gamemode, "gamemode", "isFlying", "allowFlying", "isCreativeFlying");
        add(selected, all, sync.playerProfile, "displayName", "skinTexture", "skinSignature");
        add(selected, all, sync.advancements, "advancementsJson");
        if (sync.statistics) {
            if (excludePassiveStatistics) {
                var statistics = data.statisticsJson == null ? com.google.gson.JsonNull.INSTANCE
                    : PassiveStatistics.withoutPassiveCounters(data.statisticsJson);
                if (statistics == null) throw new IllegalArgumentException("Invalid statistics snapshot for attestation");
                selected.add("statisticsJson", statistics);
            } else {
                add(selected, all, true, "statisticsJson");
            }
        }
        add(selected, all, sync.recipeBook, "recipeBookJson");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((domains(sync, legacy) + "\n" + selected).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void add(JsonObject target, JsonObject source, boolean enabled, String... fields) {
        if (enabled) for (String field : fields) target.add(field, source.get(field));
    }
}
