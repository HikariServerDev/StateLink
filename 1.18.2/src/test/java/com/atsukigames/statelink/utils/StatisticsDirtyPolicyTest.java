package com.atsukigames.statelink.utils;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsDirtyPolicyTest {
    @Test
    void excludesOnlyVanillaPassiveElapsedTimeCounters() {
        assertFalse(passive("play_time"));
        assertFalse(passive("total_world_time"));
        assertFalse(passive("time_since_death"));
        assertFalse(passive("time_since_rest"));
    }

    @Test
    void activeCustomStatisticsStillMarkTheCheckpointDirty() {
        assertTrue(StatisticsDirtyPolicy.isDirtyRelevant(true, new Identifier("minecraft", "jump")));
    }

    @Test
    void nonCustomStatisticsAreNotExcludedByIdentifierValue() {
        assertTrue(StatisticsDirtyPolicy.isDirtyRelevant(false, new Identifier("minecraft", "play_time")));
    }

    @Test
    void nonIdentifierOrUnrecognizedCustomValuesRemainDirtyRelevant() {
        assertTrue(StatisticsDirtyPolicy.isDirtyRelevant(true, "play_time"));
        assertTrue(StatisticsDirtyPolicy.isDirtyRelevant(true, new Identifier("minecraft", "unknown_time_counter")));
    }

    private static boolean passive(String path) {
        return StatisticsDirtyPolicy.isDirtyRelevant(true, new Identifier("minecraft", path));
    }
}
