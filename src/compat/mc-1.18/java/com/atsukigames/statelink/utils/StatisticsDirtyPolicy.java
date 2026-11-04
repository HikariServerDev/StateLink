package com.atsukigames.statelink.utils;

import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Dirty-only checkpoint policy for statistics. Vanilla's passive elapsed-time
 * counters advance while a player is idle, so they are retained in statistics
 * snapshots but do not by themselves schedule periodic checkpoints.
 */
public final class StatisticsDirtyPolicy {
    private static final Set<Identifier> PASSIVE_TIME_STATS = PassiveStatistics.IDS.stream()
        .map(Identifier::new).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private StatisticsDirtyPolicy() {}

    /** Returns whether a changed stat should mark the PlayerData checkpoint dirty. */
    public static boolean isDirtyRelevant(Stat<?> stat) {
        return stat == null || isDirtyRelevant(Stats.CUSTOM.equals(stat.getType()), stat.getValue());
    }

    static boolean isDirtyRelevant(boolean customStat, Object value) {
        return !customStat || !(value instanceof Identifier identifier) || !PASSIVE_TIME_STATS.contains(identifier);
    }
}
