package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.JsonParser;
import java.util.Objects;

/** Conservative semantic comparison; never infers equality from elapsed time or dirty callbacks. */
public final class EnabledCheckpointEquality {
    private EnabledCheckpointEquality() {}

    public static boolean same(CompletePlayerData a, CompletePlayerData b, Configuration.SyncConfig s) {
        if (a == null || b == null || !Objects.equals(a.uuid, b.uuid)) return false;
        if (s.inventory && (!json(a.inventoryJson, b.inventoryJson)
                || !pending(a.pendingDisconnectItemsJson, b.pendingDisconnectItemsJson)
                || a.selectedItemSlot != b.selectedItemSlot)) return false;
        if (s.armor && !json(a.armorJson, b.armorJson)) return false;
        if (s.offhand && !json(a.offhandJson, b.offhandJson)) return false;
        if (s.enderchest && !json(a.enderchestJson, b.enderchestJson)) return false;
        if (s.health && (Double.compare(a.health, b.health) != 0 || a.air != b.air)) return false;
        if (s.food && (a.foodLevel != b.foodLevel || Float.compare(a.saturation, b.saturation) != 0
                || Float.compare(a.exhaustion, b.exhaustion) != 0)) return false;
        if (s.experience && (a.experienceLevel != b.experienceLevel || a.experiencePoints != b.experiencePoints
                || !Objects.equals(a.experiencePointsIntoLevel, b.experiencePointsIntoLevel))) return false;
        if (s.effects && !json(a.effectsJson, b.effectsJson)) return false;
        if (s.dimensionEnabled() && !Objects.equals(a.dimension, b.dimension)) return false;
        if (s.position && (Double.compare(a.posX, b.posX) != 0 || Double.compare(a.posY, b.posY) != 0
                || Double.compare(a.posZ, b.posZ) != 0)) return false;
        if (s.rotationEnabled() && (Float.compare(a.yaw, b.yaw) != 0 || Float.compare(a.pitch, b.pitch) != 0)) return false;
        if (s.gamemode && (!Objects.equals(a.gamemode, b.gamemode) || a.isFlying != b.isFlying
                || a.allowFlying != b.allowFlying || a.isCreativeFlying != b.isCreativeFlying)) return false;
        if (s.playerProfile && (!Objects.equals(a.displayName, b.displayName)
                || !json(a.skinTexture, b.skinTexture) || !Objects.equals(a.skinSignature, b.skinSignature))) return false;
        if (s.advancements && !json(a.advancementsJson, b.advancementsJson)) return false;
        if (s.statistics && !statistics(a.statisticsJson, b.statisticsJson)) return false;
        if (s.recipeBook && !json(a.recipeBookJson, b.recipeBookJson)) return false;
        return true;
    }

    /**
     * Elapsed-time counters tick while a player merely stays connected. Like the dirty-only
     * checkpoint policy, they are not a player change; every other statistic still is.
     */
    private static boolean statistics(String a, String b) {
        if (a == null || b == null) return a == b;
        var left = PassiveStatistics.withoutPassiveCounters(a);
        var right = PassiveStatistics.withoutPassiveCounters(b);
        return left != null && right != null && left.equals(right);
    }

    private static boolean pending(String a, String b) {
        return json(a == null || a.isBlank() ? "[]" : a, b == null || b.isBlank() ? "[]" : b);
    }

    private static boolean json(String a, String b) {
        if (a == null || b == null) return a == b;
        try {
            var left = JsonParser.parseString(a);
            var right = JsonParser.parseString(b);
            return (left.isJsonObject() || left.isJsonArray()) && (right.isJsonObject() || right.isJsonArray())
                && left.equals(right);
        } catch (RuntimeException invalid) {
            return false; // Invalid representation is never a clean-release proof.
        }
    }
}
