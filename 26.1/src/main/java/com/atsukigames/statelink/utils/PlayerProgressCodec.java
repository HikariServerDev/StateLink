package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.mixin.AdvancementTrackerAccess;
import com.atsukigames.statelink.mixin.CriterionProgressAccess;
import com.atsukigames.statelink.mixin.StatHandlerAccess;
import com.atsukigames.statelink.mixin.ServerStatHandlerAccess;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;

/** 1.18.2 server-thread replacement, never grantCriterion / reward / increaseStat. */
public final class PlayerProgressCodec {
    private PlayerProgressCodec() {}

    private static void requireServerThread(ServerPlayer player) {
        if (player.level().getServer() == null || !player.level().getServer().isSameThread()) {
            throw new IllegalStateException("Progress capture/apply requires server thread");
        }
    }

    public static String advancements(ServerPlayer player) {
        requireServerThread(player);
        JsonObject progress = new JsonObject();
        var tracker = (AdvancementTrackerAccess) player.getAdvancements();
        tracker.statelink$progress().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(a -> a.id().toString())))
            .forEach(entry -> {
                JsonObject criteria = new JsonObject();
                entry.getKey().value().criteria().keySet().stream().sorted().forEach(name -> {
                    var criterion = entry.getValue().getCriterion(name);
                    if (criterion != null && criterion.getObtained() != null) {
                        criteria.addProperty(name, criterion.getObtained().toEpochMilli());
                    }
                });
                if (criteria.size() != 0) progress.add(entry.getKey().id().toString(), criteria);
            });
        return envelope("progress", progress).toString();
    }

    public static Map<AdvancementHolder, AdvancementProgress> prepareAdvancements(ServerPlayer player, String json) {
        requireServerThread(player);
        JsonObject root = readEnvelope(json, "advancements");
        if (root == null) return null;
        Map<AdvancementHolder, AdvancementProgress> result = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject("progress").entrySet()) {
            AdvancementHolder advancement = player.level().getServer().getAdvancements().get(Identifier.parse(entry.getKey()));
            if (advancement == null) throw new IllegalArgumentException("Unknown advancement identifier");
            AdvancementProgress progress = new AdvancementProgress();
            progress.update(advancement.value().requirements());
            for (var criterion : entry.getValue().getAsJsonObject().entrySet()) {
                var target = progress.getCriterion(criterion.getKey());
                if (target == null) throw new IllegalArgumentException("Unknown advancement criterion");
                long millis = criterion.getValue().getAsBigDecimal().longValueExact();
                if (millis < 0) throw new IllegalArgumentException("Invalid criterion timestamp");
                ((CriterionProgressAccess) target).statelink$obtainedDate(java.time.Instant.ofEpochMilli(millis));
            }
            result.put(advancement, progress);
        }
        return result;
    }

    public static void applyAdvancements(ServerPlayer player, Map<AdvancementHolder, AdvancementProgress> prepared) {
        if (prepared == null) return;
        requireServerThread(player);
        var tracker = player.getAdvancements();
        var access = (AdvancementTrackerAccess) tracker;
        tracker.stopListening();
        access.statelink$progress().clear();
        access.statelink$progress().putAll(prepared);
        access.statelink$visible().clear();
        access.statelink$visibilityUpdates().clear();
        access.statelink$progressUpdates().clear();
        access.statelink$displayTab(null);
        access.statelink$dirty(true);
        // Unlike reload/load, these methods do NOT execute rewardEmptyAdvancements.
        // 1.19.4 removed updateCompleted(): mark every completed advancement for display recomputation.
        for (var entry : new java.util.ArrayList<>(access.statelink$progress().entrySet())) {
            if (entry.getValue().isDone()) {
                access.statelink$progressUpdates().add(entry.getKey());
                access.statelink$onStatusUpdate(entry.getKey());
            }
        }
        access.statelink$beginTrackingAll(player.level().getServer().getAdvancements());
        tracker.flushDirty(player, false);
    }

    public static String statistics(ServerPlayer player) {
        requireServerThread(player);
        JsonObject stats = new JsonObject();
        var map = ((StatHandlerAccess) player.getStats()).statelink$stats();
        for (var entry : map.object2IntEntrySet()) {
            Stat<?> stat = entry.getKey();
            String type = BuiltInRegistries.STAT_TYPE.getKey(stat.getType()).toString();
            if (!stats.has(type)) stats.add(type, new JsonObject());
            stats.getAsJsonObject(type).addProperty(statId(stat).toString(), entry.getIntValue());
        }
        return envelope("stats", stats).toString();
    }

    private static <T> Identifier statId(Stat<T> stat) {
        return stat.getType().getRegistry().getKey(stat.getValue());
    }

    public static Map<Stat<?>, Integer> prepareStatistics(ServerPlayer player, String json) {
        requireServerThread(player);
        JsonObject root = readEnvelope(json, "statistics");
        if (root == null) return null;
        Map<Stat<?>, Integer> result = new LinkedHashMap<>();
        for (var group : root.getAsJsonObject("stats").entrySet()) {
            StatType<?> type = BuiltInRegistries.STAT_TYPE.getOptional(Identifier.parse(group.getKey()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown statistic type"));
            for (var stat : group.getValue().getAsJsonObject().entrySet()) {
                int value = stat.getValue().getAsBigDecimal().intValueExact();
                if (value < 0) throw new IllegalArgumentException("Negative statistic");
                result.put(resolveStat(type, Identifier.parse(stat.getKey())), value);
            }
        }
        return result;
    }

    private static <T> Stat<T> resolveStat(StatType<T> type, Identifier id) {
        T value = type.getRegistry().getOptional(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown statistic value"));
        return type.get(value);
    }

    public static void applyStatistics(ServerPlayer player, Map<Stat<?>, Integer> prepared) {
        if (prepared == null) return;
        requireServerThread(player);
        var handler = player.getStats();
        var values = ((StatHandlerAccess) handler).statelink$stats();
        var pending = ((ServerStatHandlerAccess) handler).statelink$pendingStats();
        pending.addAll(values.keySet()); // Send zero for removed local entries as well.
        values.clear();
        prepared.forEach((stat, count) -> values.put(stat, count.intValue()));
        handler.markAllDirty();
        handler.sendStats(player);
    }

    private static JsonObject envelope(String key, JsonObject values) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add(key, values);
        return root;
    }

    private static JsonObject readEnvelope(String json, String domain) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Missing " + domain + " payload");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        // <=2.1.11 never stored real progress. Preserve local state once, never
        // interpret a placeholder as authoritative empty progress.
        String legacy = domain.equals("advancements") ? "advancement_data_simplified" : "statistics_data_simplified";
        if (root.size() == 1 && root.has("placeholder") && legacy.equals(root.get("placeholder").getAsString())) {
            StateLink.LOGGER.warn("Legacy {} placeholder: preserving local state until first real checkpoint", domain);
            return null;
        }
        if (!root.has("format") || root.get("format").getAsBigDecimal().intValueExact() != 1) {
            throw new IllegalArgumentException("Unsupported " + domain + " payload format");
        }
        String key = domain.equals("advancements") ? "progress" : "stats";
        if (!root.has(key) || !root.get(key).isJsonObject()) throw new IllegalArgumentException("Invalid " + domain + " payload");
        return root;
    }
}
