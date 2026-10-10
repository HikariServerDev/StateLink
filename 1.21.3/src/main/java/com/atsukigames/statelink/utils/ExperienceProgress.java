package com.atsukigames.statelink.utils;

/** Vanilla 1.18.2 XP progress compatibility helpers. */
public final class ExperienceProgress {
    private ExperienceProgress() {}

    /**
     * Resolves Minecraft's float progress from its durable integer representation.
     * The old FLOAT column is only a compatibility source and is quantized back to
     * the nearest whole XP point to undo JDBC text-protocol decimal rounding.
     */
    public static float resolve(int level, int cumulativeExperience, Float storedProgress) {
        return resolve(level, cumulativeExperience, null, storedProgress);
    }

    public static float resolve(
        int level,
        int cumulativeExperience,
        Long pointsIntoLevel,
        Float legacyProgress
    ) {
        if (level < 0 || cumulativeExperience < 0) {
            throw new IllegalArgumentException("experience level and cumulative XP must be non-negative");
        }
        long nextLevelCost = experienceToNextLevel(level);
        if (pointsIntoLevel != null) {
            if (pointsIntoLevel < 0 || pointsIntoLevel >= nextLevelCost) {
                throw new IllegalArgumentException("XP points into level are outside the current level");
            }
            return (float) pointsIntoLevel / (float) nextLevelCost;
        }
        if (legacyProgress != null) {
            if (!Float.isFinite(legacyProgress) || legacyProgress < 0.0F || legacyProgress >= 1.0F) {
                throw new IllegalArgumentException("experience progress must be finite and in [0, 1)");
            }
            return (float) pointsIntoLevel(level, legacyProgress) / (float) nextLevelCost;
        }

        long levelStart = totalExperienceAtLevel(level);
        if (cumulativeExperience <= levelStart) return 0.0F;
        long intoLevel = cumulativeExperience - levelStart;
        intoLevel = Math.min(intoLevel, nextLevelCost - 1L);
        return (float) intoLevel / (float) nextLevelCost;
    }

    /** Converts the in-memory vanilla ratio to the durable whole XP earned this level. */
    public static long pointsIntoLevel(int level, float progress) {
        if (level < 0) throw new IllegalArgumentException("level must be non-negative");
        if (!Float.isFinite(progress) || progress < 0.0F || progress >= 1.0F) {
            throw new IllegalArgumentException("experience progress must be finite and in [0, 1)");
        }
        long cost = experienceToNextLevel(level);
        long points = Math.round((double) progress * (double) cost);
        return Math.max(0L, Math.min(points, cost - 1L));
    }

    /** Total vanilla XP threshold at the start of {@code level}. */
    public static long totalExperienceAtLevel(int level) {
        if (level < 0) throw new IllegalArgumentException("level must be non-negative");
        if (level <= 15) return (long) level * level + 6L * level;
        if (level <= 30) {
            long n = level - 15L;
            return 315L + n * 37L + 5L * n * (n - 1L) / 2L;
        }
        long n = level - 30L;
        try {
            return Math.addExact(1_395L,
                Math.addExact(Math.multiplyExact(n, 112L), Math.multiplyExact(9L * n, n - 1L) / 2L));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Vanilla PlayerEntity#getNextLevelExperience for Minecraft 1.18.2. */
    public static long experienceToNextLevel(int level) {
        if (level < 0) throw new IllegalArgumentException("level must be non-negative");
        if (level < 15) return 7L + 2L * level;
        if (level < 30) return 37L + 5L * (level - 15L);
        return 112L + 9L * (level - 30L);
    }
}
