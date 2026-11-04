package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.Gson;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnabledCheckpointEqualityTest {
    @Test void reenableHistoryGuardCoversEveryDomainAndOldAggregateSpatialFlags() {
        var s=new Configuration.SyncConfig();
        String[] flags={"inventory","enderchest","armor","offhand","health","food","experience","effects","position",
            "dimension","rotation","gamemode","advancements","statistics","playerProfile","recipeBook"};
        for (var flag:flags) {
            var previous=com.google.gson.JsonParser.parseString(CheckpointDigest.domains(s)).getAsJsonObject();
            previous.addProperty(flag,false); assertTrue(CheckpointDigest.reenablesDomain(previous.toString(),s),flag);
        }
        assertFalse(CheckpointDigest.reenablesDomain(CheckpointDigest.domains(s),s));
        var legacy=com.google.gson.JsonParser.parseString(CheckpointDigest.domains(s)).getAsJsonObject();
        legacy.remove("dimension"); legacy.remove("rotation"); legacy.addProperty("position",false);
        assertTrue(CheckpointDigest.reenablesDomain(legacy.toString(),s));
    }
    @Test void poisonedDisabledSpatialAndGameModeDomainsDoNotAffectEqualityOrReceipt() {
        var s=new Configuration.SyncConfig(); s.position=false; s.dimension=false; s.rotation=false; s.gamemode=false;
        var a=data(""); var b=data(",\"dimension\":\"minecraft:the_nether\",\"posX\":999,\"yaw\":91,\"gamemode\":\"creative\"");
        assertTrue(EnabledCheckpointEquality.same(a,b,s)); assertEquals(CheckpointDigest.digest(a,s),CheckpointDigest.digest(b,s));
    }
    @Test void rotationOnlyProjectionIgnoresPoisonedWorldAndCoordinates() {
        var s=new Configuration.SyncConfig(); s.position=false; s.dimension=false; s.rotation=true;
        var a=data(""); var b=data(",\"dimension\":\"minecraft:the_end\",\"posX\":999");
        assertTrue(EnabledCheckpointEquality.same(a,b,s)); assertEquals(CheckpointDigest.digest(a,s),CheckpointDigest.digest(b,s));
        assertFalse(EnabledCheckpointEquality.same(a,data(",\"yaw\":42"),s));
    }
    @Test void positionAndDimensionDoNotMakeDisabledRotationAuthoritative() {
        var s=new Configuration.SyncConfig(); s.rotation=false;
        assertTrue(EnabledCheckpointEquality.same(data(""),data(",\"yaw\":42"),s));
        assertEquals(CheckpointDigest.digest(data(""),s),CheckpointDigest.digest(data(",\"yaw\":42"),s));
    }
    @Test void disabledThenReenabledDomainRequiresOperatorReconciliation() {
        var s=new Configuration.SyncConfig(); s.position=false; s.dimension=false;
        var local=data(",\"dimension\":\"minecraft:the_end\",\"posX\":123"); var old=data("");
        assertTrue(EnabledCheckpointEquality.same(local,old,s));
        s.position=true; s.dimension=true; assertFalse(EnabledCheckpointEquality.same(local,old,s));
    }
    private static final String UUID_VALUE = "d6d79990-8206-49d8-af46-ffbb490ca57c";
    private static CompletePlayerData data(String fields) {
        return new Gson().fromJson("{\"uuid\":\"" + UUID_VALUE + "\",\"inventoryJson\":\"[]\","
            + "\"effectsJson\":\"[]\",\"advancementsJson\":\"{}\",\"statisticsJson\":\"{}\"" + fields + "}", CompletePlayerData.class);
    }
    private static CompletePlayerData dataWithStatistics(String statistics) {
        var base = data("");
        return new CompletePlayerData(base.uuid, base.username, base.inventoryJson, base.enderchestJson,
            base.armorJson, base.offhandJson, base.health, base.foodLevel, base.saturation, base.exhaustion,
            base.air, base.experienceLevel, base.experiencePoints, base.experienceTotal, base.experienceProgress,
            base.effectsJson, base.dimension, base.posX, base.posY, base.posZ, base.yaw, base.pitch,
            base.gamemode, base.isFlying, base.allowFlying, base.isCreativeFlying, base.displayName,
            base.skinTexture, base.skinSignature, base.advancementsJson, statistics, base.recipeBookJson,
            base.selectedItemSlot, base.playTimeSeconds, base.loginCount,
            base.experiencePointsIntoLevel, base.pendingDisconnectItemsJson);
    }
    @Test void metadataIsNotEnabledPlayerState() {
        assertTrue(EnabledCheckpointEquality.same(data(",\"loginCount\":1"), data(",\"loginCount\":100,\"playTimeSeconds\":500"), new Configuration.SyncConfig()));
    }
    @Test void enabledHealthAndExperienceChangesAreDirty() {
        var sync = new Configuration.SyncConfig();
        assertFalse(EnabledCheckpointEquality.same(data(""), data(",\"health\":10"), sync));
        assertFalse(EnabledCheckpointEquality.same(data(""), data(",\"experiencePointsIntoLevel\":1"), sync));
    }
    @Test void disabledDomainsDoNotDirtyTheCheckpoint() {
        var sync = new Configuration.SyncConfig();
        sync.health = sync.experience = false;
        assertTrue(EnabledCheckpointEquality.same(data(""), data(",\"health\":10,\"experiencePointsIntoLevel\":1"), sync));
    }
    @Test void criterionTimestampAndStatisticValueChangesAreDirty() {
        var sync = new Configuration.SyncConfig();
        assertFalse(EnabledCheckpointEquality.same(data(""), data(",\"advancementsJson\":\"{\\\"time\\\":123}\""), sync));
        assertFalse(EnabledCheckpointEquality.same(data(""), data(",\"statisticsJson\":\"{\\\"value\\\":1}\""), sync));
    }
    @Test void enabledDomainDigestPreservesDisabledDifferences() {
        var sync = new Configuration.SyncConfig();
        sync.inventory = false;
        var a = data("");
        var b = a.withInventoryAndPendingItems("[irrelevant]", "[retained]");
        assertEquals(CheckpointDigest.digest(a, sync), CheckpointDigest.digest(b, sync));
        sync.inventory = true;
        assertNotEquals(CheckpointDigest.digest(a, sync), CheckpointDigest.digest(b, sync));
    }
    @Test void verifiedStateDigestIgnoresOnlyPassiveElapsedStatistics() {
        var sync = new Configuration.SyncConfig();
        var first = dataWithStatistics("{\"format\":1,\"stats\":{\"minecraft:custom\":{"
            + "\"minecraft:play_time\":100,\"minecraft:total_world_time\":100,\"minecraft:jump\":3}}}");
        var passiveAdvanced = dataWithStatistics("{\"format\":1,\"stats\":{\"minecraft:custom\":{"
            + "\"minecraft:play_time\":900,\"minecraft:total_world_time\":900,\"minecraft:jump\":3}}}");
        assertNotEquals(CheckpointDigest.digest(first, sync), CheckpointDigest.digest(passiveAdvanced, sync),
            "a later real save must still retain current passive counters in the payload digest");
        assertEquals(CheckpointDigest.verifiedStateDigest(first, sync),
            CheckpointDigest.verifiedStateDigest(passiveAdvanced, sync));

        var actionAdvanced = dataWithStatistics("{\"format\":1,\"stats\":{\"minecraft:custom\":{"
            + "\"minecraft:play_time\":900,\"minecraft:total_world_time\":900,\"minecraft:jump\":4}}}");
        assertNotEquals(CheckpointDigest.verifiedStateDigest(first, sync),
            CheckpointDigest.verifiedStateDigest(actionAdvanced, sync),
            "active statistics remain part of a verified-state attestation");
    }
    @Test void nullPendingAndEmptyPendingAreEquivalentButItemsAreNot() {
        var sync = new Configuration.SyncConfig();
        var a = data("");
        assertTrue(EnabledCheckpointEquality.same(a, a.withInventoryAndPendingItems("[]", "[]"), sync));
        assertFalse(EnabledCheckpointEquality.same(a, a.withInventoryAndPendingItems("[]", "[{\"count\":1}]"), sync));
    }
    @Test void malformedRepresentationNeverProvesEquality() {
        var a = data("").withInventoryAndPendingItems("not-json", null);
        assertFalse(EnabledCheckpointEquality.same(a, a, new Configuration.SyncConfig()));
    }
}
