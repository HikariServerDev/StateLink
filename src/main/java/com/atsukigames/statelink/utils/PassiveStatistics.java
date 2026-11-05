package com.atsukigames.statelink.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/**
 * The vanilla 1.18.2 custom statistics which PlayerEntity.tick advances by elapsed time alone.
 * They are saved like every other statistic, but neither schedule a dirty-only checkpoint nor
 * make an otherwise unchanged player "dirty" for a saveOnDisconnect=false clean release.
 */
public final class PassiveStatistics {
    public static final String CUSTOM_TYPE = "minecraft:custom";
    public static final Set<String> IDS = Set.of(
        "minecraft:play_time", "minecraft:total_world_time",
        "minecraft:time_since_death", "minecraft:time_since_rest");

    private PassiveStatistics() {}

    /**
     * Parsed statistics payload without the passive counters, for equality only. Returns null
     * when the payload is not a JSON object, so an invalid representation is never "equal".
     */
    public static JsonElement withoutPassiveCounters(String statisticsJson) {
        if (statisticsJson == null) return null;
        final JsonElement parsed;
        try { parsed = JsonParser.parseString(statisticsJson); }
        catch (RuntimeException invalid) { return null; }
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject().deepCopy();
        if (root.has("stats") && root.get("stats").isJsonObject()) {
            JsonObject stats = root.getAsJsonObject("stats");
            if (stats.has(CUSTOM_TYPE) && stats.get(CUSTOM_TYPE).isJsonObject()) {
                JsonObject custom = stats.getAsJsonObject(CUSTOM_TYPE);
                for (String id : IDS) custom.remove(id);
                // A type holding only passive counters is equal to that type being absent.
                if (custom.size() == 0) stats.remove(CUSTOM_TYPE);
            }
        }
        return root;
    }
}
