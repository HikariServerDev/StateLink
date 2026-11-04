package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyRowPreflightTest {
    private static final String ITEM = "[{\"slot\":0,\"item\":\"minecraft:diamond\",\"count\":3,\"nbt\":\"{Count:3b,id:\\\"minecraft:diamond\\\"}\"}]";
    private static final String EFFECT = "[{\"effect\":\"minecraft:haste\",\"amplifier\":1,\"duration\":600,\"ambient\":false,\"showParticles\":true,\"showIcon\":true}]";
    private static final Map<String, DomainProvenance.State> ALL =
        Legacy211Bootstrap.plan(new Configuration.SyncConfig(), Legacy211Bootstrap.POLICY_ADOPT_FIRST_LOGIN_LOCAL).states();

    private static LegacyRowPreflight.Row row(String inventory, String pending, String ender, String armor, String offhand,
        String effects, double health, int food, float saturation, int level, Long intoLevel, int slot, String gamemode) {
        return new LegacyRowPreflight.Row(inventory, pending, ender, armor, offhand, effects, health, 300, food, saturation,
            0f, level, 0, 0f, null, intoLevel, gamemode, slot, "minecraft:overworld", 0, 64, 0, 0f, 0f);
    }

    private static LegacyRowPreflight.Row valid() {
        return row(ITEM, null, "[]", "[]", "[]", EFFECT, 20, 20, 5f, 30, 5L, 0, "survival");
    }

    private static List<String> problems(LegacyRowPreflight.Row row) { return LegacyRowPreflight.problems(row, ALL); }

    @Test void rowsInThe211ShapeAreAccepted() {
        assertEquals(List.of(), problems(valid()));
        // Old writers stored armor/offhand duplicates in slots 36-40 of the inventory column.
        String whole = "[{\"slot\":0,\"item\":\"minecraft:stone\",\"count\":64},{\"slot\":36,\"item\":\"minecraft:iron_boots\",\"count\":1},"
            + "{\"slot\":40,\"item\":\"minecraft:shield\",\"count\":1}]";
        assertEquals(List.of(), problems(row(whole, "[]", "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 8, "survival")));
        // Values the codec's lenient Gson accessors accept are accepted here as well.
        assertEquals(List.of(), problems(row("[{\"slot\":\"1\",\"item\":\"minecraft:stone\",\"count\":2.0}]", null, "[]", "[]", "[]",
            "[{\"id\":\"minecraft:speed\",\"amplifier\":0,\"duration\":1,\"ambient\":\"false\",\"showParticles\":true,\"showIcon\":true}]",
            20, 20, 5f, 0, null, 0, "survival")));
    }

    @Test void undecodablePayloadsAreReportedPerDomainWithoutContents() {
        assertEquals(List.of("inventory: missing authoritative payload"), problems(row(null, null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("inventory: malformed JSON"), problems(row("{not json", null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("enderchest: JSON is not an array"), problems(row(ITEM, null, "{}", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("armor: slot range"), problems(row(ITEM, null, "[]", "[{\"slot\":4,\"item\":\"minecraft:stone\",\"count\":1}]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("inventory: duplicate slot"), problems(row("[{\"slot\":1,\"item\":\"minecraft:stone\",\"count\":1},{\"slot\":1,\"item\":\"minecraft:stone\",\"count\":1}]", null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("offhand: item identifier is missing"), problems(row(ITEM, null, "[]", "[]", "[{\"slot\":0,\"count\":1}]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("inventory: item count range"), problems(row("[{\"slot\":0,\"item\":\"minecraft:stone\",\"count\":0}]", null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("inventory: pending order"), problems(row(ITEM, "[{\"slot\":1,\"item\":\"minecraft:stone\",\"count\":1}]", "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("effects: missing ambient"), problems(row(ITEM, null, "[]", "[]", "[]", "[{\"effect\":\"minecraft:haste\",\"amplifier\":1,\"duration\":1}]", 20, 20, 5f, 0, null, 0, "survival")));
        assertTrue(problems(row("[{\"slot\":0,\"item\":\"secret-item-name!!\",\"count\":1}]", null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "survival"))
            .stream().noneMatch(problem -> problem.contains("secret-item-name")));
    }

    @Test void scalarRangesMatchTheLoadRules() {
        assertEquals(List.of("health: range"), problems(row(ITEM, null, "[]", "[]", "[]", "[]", Double.NaN, 20, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("food: saturation range"), problems(row(ITEM, null, "[]", "[]", "[]", "[]", 20, 3, 5f, 0, null, 0, "survival")));
        assertEquals(List.of("experience: points into level range"), problems(row(ITEM, null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, 7L, 0, "survival")));
        assertEquals(List.of("inventory: selected item slot range"), problems(row(ITEM, null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 9, "survival")));
        assertEquals(List.of("gamemode: unknown value"), problems(row(ITEM, null, "[]", "[]", "[]", "[]", 20, 20, 5f, 0, null, 0, "hardcore")));
    }

    @Test void domainsThatAreNotAdoptedAreNotValidated() {
        Configuration.SyncConfig production = new Configuration.SyncConfig();
        production.position = false; production.dimension = false; production.rotation = false; production.gamemode = false;
        production.effects = false;
        var states = Legacy211Bootstrap.plan(production, Legacy211Bootstrap.POLICY_ADOPT_FIRST_LOGIN_LOCAL).states();
        assertEquals(List.of(), LegacyRowPreflight.problems(
            row(ITEM, null, "[]", "[]", "[]", "garbage", 20, 20, 5f, 0, null, 0, "hardcore"), states));
    }

    @Test void semanticallyEqualSchemaDefaultsNormalizeToTheSameValue() {
        assertEquals(SchemaGeneration.normalizeDefault("20.0"), SchemaGeneration.normalizeDefault("'20'"));
        assertEquals(SchemaGeneration.normalizeDefault("FALSE"), SchemaGeneration.normalizeDefault("0"));
        assertEquals(SchemaGeneration.normalizeDefault("0.0"), SchemaGeneration.normalizeDefault("0"));
        assertEquals(SchemaGeneration.normalizeDefault("CURRENT_TIMESTAMP"), SchemaGeneration.normalizeDefault("current_timestamp"));
        assertNotEquals(SchemaGeneration.normalizeDefault("CURRENT_TIMESTAMP"), SchemaGeneration.normalizeDefault("CURRENT_TIMESTAMP(6)"));
        assertNotEquals(SchemaGeneration.normalizeDefault("20"), SchemaGeneration.normalizeDefault("10"));
        assertNotEquals(SchemaGeneration.normalizeDefault("survival"), SchemaGeneration.normalizeDefault("creative"));
        assertNull(SchemaGeneration.normalizeDefault("NULL"));
        assertNull(SchemaGeneration.normalizeDefault(null));
    }
}
