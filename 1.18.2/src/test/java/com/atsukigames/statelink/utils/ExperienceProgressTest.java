package com.atsukigames.statelink.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceProgressTest {
    @Test
    void vanilla1182LevelThresholdsMatchThePiecewisePlayerEntityFormula() {
        assertEquals(0L, ExperienceProgress.totalExperienceAtLevel(0));
        assertEquals(7L, ExperienceProgress.totalExperienceAtLevel(1));
        assertEquals(160L, ExperienceProgress.totalExperienceAtLevel(10));
        assertEquals(315L, ExperienceProgress.totalExperienceAtLevel(15));
        assertEquals(352L, ExperienceProgress.totalExperienceAtLevel(16));
        assertEquals(1_395L, ExperienceProgress.totalExperienceAtLevel(30));
        assertEquals(1_507L, ExperienceProgress.totalExperienceAtLevel(31));
        assertEquals(112L, ExperienceProgress.experienceToNextLevel(30));
        assertEquals(121L, ExperienceProgress.experienceToNextLevel(31));
    }

    @Test
    void exactNewRowsRoundTripLevelProgressAndCumulativeExperienceAtBoundaries() {
        for (int level : List.of(0, 1, 10, 15, 16, 29, 30, 31, 100, 5_000)) {
            long levelStart = ExperienceProgress.totalExperienceAtLevel(level);
            int nextCost = Math.toIntExact(ExperienceProgress.experienceToNextLevel(level));
            for (int partialPoints : List.of(0, Math.max(1, nextCost / 3), nextCost - 1)) {
                float progress = (float) partialPoints / (float) nextCost;
                int cumulative = Math.toIntExact(levelStart + partialPoints);
                long pointsIntoLevel = ExperienceProgress.pointsIntoLevel(level, progress);

                float restoredProgress = ExperienceProgress.resolve(
                    level, cumulative, pointsIntoLevel, progress);
                assertEquals(progress, restoredProgress, 0.0F, "level=" + level + " points=" + partialPoints);
                assertEquals(partialPoints, pointsIntoLevel,
                    "durable integer XP point count at level=" + level);
                assertEquals(cumulative, levelStart + partialPoints);

                XpState before = new XpState(level, progress, cumulative);
                XpState restored = new XpState(level, restoredProgress, cumulative);
                for (int earned = 1; earned <= 3; earned++) {
                    assertEquals(addVanillaPoints(before, earned), addVanillaPoints(restored, earned),
                        "XP award timing differs at level=" + level + " partial=" + partialPoints);
                }
            }
        }
    }

    @Test
    void mysqlRoundedLegacyFloatStillResolvesToTheExactLevelBoundaryPoint() {
        for (int level : List.of(16, 30, 100)) {
            long cost = ExperienceProgress.experienceToNextLevel(level);
            long expectedInto = cost - 1L;
            float legacyJdbcValue = switch (level) {
                case 16 -> 0.976189971F;
                case 30 -> 0.991070986F;
                default -> 0.998651981F;
            };

            float restored = ExperienceProgress.resolve(level, 0, null, legacyJdbcValue);
            assertEquals(expectedInto, ExperienceProgress.pointsIntoLevel(level, restored));
            assertEquals(level + 1, addVanillaPoints(new XpState(level, restored, 0), 1).level);
        }
    }

    @Test
    void deterministicRandomIntegerXpPointsPreserveAwardTiming() {
        Random random = new Random(0x504443215L);
        for (int sample = 0; sample < 2_000; sample++) {
            int level = random.nextInt(10_001);
            long cost = ExperienceProgress.experienceToNextLevel(level);
            long into = Math.floorMod(random.nextLong(), cost);
            float progress = (float) into / (float) cost;
            int total = Math.toIntExact(Math.min(
                Integer.MAX_VALUE,
                ExperienceProgress.totalExperienceAtLevel(level) + into));

            long durablePoints = ExperienceProgress.pointsIntoLevel(level, progress);
            float restored = ExperienceProgress.resolve(level, total, durablePoints, null);
            XpState before = new XpState(level, progress, total);
            XpState after = new XpState(level, restored, total);
            for (int earned = 1; earned <= 3; earned++) {
                assertEquals(addVanillaPoints(before, earned), addVanillaPoints(after, earned),
                    "sample=" + sample + " level=" + level + " points=" + into);
            }
        }
    }

    @Test
    void legacyRowsKeepCumulativeColumnMeaningAndDeriveProgressDeterministically() {
        int level = 30;
        int cumulative = Math.toIntExact(ExperienceProgress.totalExperienceAtLevel(level) + 56L);
        float derived = ExperienceProgress.resolve(level, cumulative, null);

        assertEquals(0.5F, derived, 0.000001F);
        assertEquals(1_451, cumulative);
        // The legacy `experience_points` value remains cumulative XP; the new
        // progress is reconstructed in memory and is not written back as that column.
    }

    @Test
    void invalidStoredProgressIsRejectedInsteadOfSilentlyClamped() {
        assertThrows(IllegalArgumentException.class, () -> ExperienceProgress.resolve(10, 200, -0.1F));
        assertThrows(IllegalArgumentException.class, () -> ExperienceProgress.resolve(10, 200, 1.0F));
        assertThrows(IllegalArgumentException.class, () -> ExperienceProgress.resolve(10, 200, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> ExperienceProgress.resolve(10, 200, 100L, null));
        assertTrue(ExperienceProgress.resolve(10, 200, null) >= 0.0F);
    }

    private static XpState addVanillaPoints(XpState start, int amount) {
        int level = start.level;
        int total = start.totalExperience + amount;
        float progress = start.progress + (float) amount / ExperienceProgress.experienceToNextLevel(level);
        while (progress >= 1.0F) {
            progress -= 1.0F;
            level++;
            progress *= (float) ExperienceProgress.experienceToNextLevel(level - 1)
                / (float) ExperienceProgress.experienceToNextLevel(level);
        }
        return new XpState(level, progress, total);
    }

    private record XpState(int level, float progress, int totalExperience) {}
}
