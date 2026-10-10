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
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatType;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

/** 1.18.2 server-thread replacement, never grantCriterion / reward / increaseStat. */
public final class PlayerProgressCodec {
    private PlayerProgressCodec() {}

    private static void requireServerThread(ServerPlayerEntity player) {
        if (player.getServer() == null || !player.getServer().isOnThread()) {
            throw new IllegalStateException("Progress capture/apply requires server thread");
        }
    }

    public static String advancements(ServerPlayerEntity player) {
        requireServerThread(player);
        JsonObject progress = new JsonObject();
        var tracker = (AdvancementTrackerAccess) player.getAdvancementTracker();
        tracker.statelink$progress().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(a -> a.getId().toString())))
            .forEach(entry -> {
                JsonObject criteria = new JsonObject();
                entry.getKey().getCriteria().keySet().stream().sorted().forEach(name -> {
                    var criterion = entry.getValue().getCriterionProgress(name);
                    if (criterion != null && criterion.getObtainedDate() != null) {
                        criteria.addProperty(name, criterion.getObtainedDate().getTime());
                    }
                });
                if (criteria.size() != 0) progress.add(entry.getKey().getId().toString(), criteria);
            });
        return envelope("progress", progress).toString();
    }

    public static Map<Advancement, AdvancementProgress> prepareAdvancements(ServerPlayerEntity player, String json) {
        requireServerThread(player);
        JsonObject root = readEnvelope(json, "advancements");
        if (root == null) return null;
        Map<Advancement, AdvancementProgress> result = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject("progress").entrySet()) {
            Advancement advancement = player.getServer().getAdvancementLoader().get(new Identifier(entry.getKey()));
            if (advancement == null) throw new IllegalArgumentException("Unknown advancement identifier");
            AdvancementProgress progress = new AdvancementProgress();
            progress.init(advancement.getCriteria(), advancement.getRequirements());
            for (var criterion : entry.getValue().getAsJsonObject().entrySet()) {
                var target = progress.getCriterionProgress(criterion.getKey());
                if (target == null) throw new IllegalArgumentException("Unknown advancement criterion");
                long millis = criterion.getValue().getAsBigDecimal().longValueExact();
                if (millis < 0) throw new IllegalArgumentException("Invalid criterion timestamp");
                ((CriterionProgressAccess) target).statelink$obtainedDate(new Date(millis));
            }
            result.put(advancement, progress);
        }
        return result;
    }

    public static void applyAdvancements(ServerPlayerEntity player, Map<Advancement, AdvancementProgress> prepared) {
        if (prepared == null) return;
        requireServerThread(player);
        var tracker = player.getAdvancementTracker();
        var access = (AdvancementTrackerAccess) tracker;
        tracker.clearCriteria();
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
        access.statelink$beginTrackingAll(player.getServer().getAdvancementLoader());
        tracker.sendUpdate(player);
    }

    public static String statistics(ServerPlayerEntity player) {
        requireServerThread(player);
        JsonObject stats = new JsonObject();
        var map = ((StatHandlerAccess) player.getStatHandler()).statelink$stats();
        for (var entry : map.object2IntEntrySet()) {
            Stat<?> stat = entry.getKey();
            String type = Registries.STAT_TYPE.getId(stat.getType()).toString();
            if (!stats.has(type)) stats.add(type, new JsonObject());
            stats.getAsJsonObject(type).addProperty(statId(stat).toString(), entry.getIntValue());
        }
        return envelope("stats", stats).toString();
    }

    private static <T> Identifier statId(Stat<T> stat) {
        return stat.getType().getRegistry().getId(stat.getValue());
    }

    public static Map<Stat<?>, Integer> prepareStatistics(ServerPlayerEntity player, String json) {
        requireServerThread(player);
        JsonObject root = readEnvelope(json, "statistics");
        if (root == null) return null;
        Map<Stat<?>, Integer> result = new LinkedHashMap<>();
        for (var group : root.getAsJsonObject("stats").entrySet()) {
            StatType<?> type = Registries.STAT_TYPE.getOrEmpty(new Identifier(group.getKey()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown statistic type"));
            for (var stat : group.getValue().getAsJsonObject().entrySet()) {
                int value = stat.getValue().getAsBigDecimal().intValueExact();
                if (value < 0) throw new IllegalArgumentException("Negative statistic");
                result.put(resolveStat(type, new Identifier(stat.getKey())), value);
            }
        }
        return result;
    }

    private static <T> Stat<T> resolveStat(StatType<T> type, Identifier id) {
        T value = type.getRegistry().getOrEmpty(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown statistic value"));
        return type.getOrCreateStat(value);
    }

    public static void applyStatistics(ServerPlayerEntity player, Map<Stat<?>, Integer> prepared) {
        if (prepared == null) return;
        requireServerThread(player);
        var handler = player.getStatHandler();
        var values = ((StatHandlerAccess) handler).statelink$stats();
        var pending = ((ServerStatHandlerAccess) handler).statelink$pendingStats();
        pending.addAll(values.keySet()); // Send zero for removed local entries as well.
        values.clear();
        prepared.forEach((stat, count) -> values.put(stat, count.intValue()));
        handler.updateStatSet();
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
