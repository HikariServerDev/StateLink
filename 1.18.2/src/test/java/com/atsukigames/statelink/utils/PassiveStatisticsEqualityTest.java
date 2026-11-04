package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassiveStatisticsEqualityTest {
    private static final Configuration.SyncConfig SYNC = new Configuration.SyncConfig();

    private static CompletePlayerData data(String statistics) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid", "d6d79990-8206-49d8-af46-ffbb490ca57c");
        root.addProperty("statisticsJson", statistics);
        return new Gson().fromJson(root, CompletePlayerData.class);
    }

    private static String stats(String custom, String mined) {
        return "{\"format\":1,\"stats\":{" + (custom == null ? "" : "\"minecraft:custom\":{" + custom + "}")
            + (custom != null && mined != null ? "," : "") + (mined == null ? "" : "\"minecraft:mined\":{" + mined + "}") + "}}";
    }

    @Test void elapsedTimeCountersAloneDoNotMakeAnIdlePlayerDirty() {
        var checkpoint = data(stats("\"minecraft:play_time\":100,\"minecraft:total_world_time\":100,"
            + "\"minecraft:time_since_death\":100,\"minecraft:time_since_rest\":100,\"minecraft:jump\":3", "\"minecraft:stone\":2"));
        var later = data(stats("\"minecraft:play_time\":260,\"minecraft:total_world_time\":260,"
            + "\"minecraft:time_since_death\":260,\"minecraft:time_since_rest\":260,\"minecraft:jump\":3", "\"minecraft:stone\":2"));
        assertTrue(EnabledCheckpointEquality.same(checkpoint, later, SYNC));
    }

    @Test void firstPassiveTickAfterAnEmptyCheckpointIsStillClean() {
        assertTrue(EnabledCheckpointEquality.same(data(stats(null, null)), data(stats("\"minecraft:play_time\":1", null)), SYNC));
    }

    @Test void materialStatisticsStillMakeThePlayerDirty() {
        var checkpoint = data(stats("\"minecraft:play_time\":100,\"minecraft:jump\":3", "\"minecraft:stone\":2"));
        assertFalse(EnabledCheckpointEquality.same(checkpoint,
            data(stats("\"minecraft:play_time\":100,\"minecraft:jump\":3", "\"minecraft:stone\":3")), SYNC), "mined");
        assertFalse(EnabledCheckpointEquality.same(checkpoint,
            data(stats("\"minecraft:play_time\":160,\"minecraft:jump\":4", "\"minecraft:stone\":2")), SYNC), "jump");
        assertFalse(EnabledCheckpointEquality.same(checkpoint,
            data(stats("\"minecraft:play_time\":160,\"minecraft:jump\":3,\"minecraft:sneak_time\":5", "\"minecraft:stone\":2")), SYNC),
            "sneak_time is a player action");
        assertFalse(EnabledCheckpointEquality.same(checkpoint,
            data(stats("\"minecraft:play_time\":160,\"minecraft:jump\":3", null)), SYNC), "removed type");
    }

    @Test void passiveIdentifierOutsideTheCustomTypeIsNotIgnored() {
        assertFalse(EnabledCheckpointEquality.same(data(stats(null, "\"minecraft:play_time\":1")),
            data(stats(null, "\"minecraft:play_time\":2")), SYNC));
    }

    @Test void invalidOrMissingRepresentationIsNeverCleanProof() {
        var valid = data(stats("\"minecraft:play_time\":1", null));
        assertFalse(EnabledCheckpointEquality.same(valid, data("not json"), SYNC));
        assertFalse(EnabledCheckpointEquality.same(valid, data(null), SYNC));
        assertFalse(EnabledCheckpointEquality.same(data("[]"), data("[]"), SYNC));
    }

    @Test void normalizationDoesNotChangeWhatIsSaved() {
        String original = stats("\"minecraft:play_time\":100,\"minecraft:jump\":3", null);
        PassiveStatistics.withoutPassiveCounters(original);
        assertEquals(stats("\"minecraft:play_time\":100,\"minecraft:jump\":3", null), original);
        assertTrue(PassiveStatistics.withoutPassiveCounters(original).toString().contains("minecraft:jump"));
        assertFalse(PassiveStatistics.withoutPassiveCounters(original).toString().contains("play_time"));
    }

    @Test void disabledStatisticsAreNotCompared() {
        Configuration.SyncConfig off = new Configuration.SyncConfig();
        off.statistics = false;
        assertTrue(EnabledCheckpointEquality.same(data("not json"), data(stats(null, "\"minecraft:stone\":9")), off));
    }
}
