package com.atsukigames.statelink.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Non-mutating validation of one legacy PlayerData row before it is adopted as authority. It
 * mirrors the registry-independent rules of {@code CompletePlayerDataSerializer.prepare}: JSON
 * shape, slot ranges, required fields and scalar ranges of every domain that the bootstrap would
 * mark ENABLED_AUTHORITATIVE. It cannot know which item or effect identifiers a later-loaded
 * registry contains; such a row still fails closed for that player at LOAD.
 */
public final class LegacyRowPreflight {
    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+");
    private static final Set<String> GAME_MODES = Set.of("survival", "creative", "adventure", "spectator");

    private LegacyRowPreflight() {}

    /** Column values of one row, read as the repository mapper reads them. */
    public record Row(String inventory, String pending, String enderchest, String armor, String offhand, String effects,
        double health, int air, int foodLevel, float saturation, float exhaustion, int experienceLevel,
        int experiencePoints, float experienceTotal, Float experienceProgress, Long experiencePointsIntoLevel,
        String gamemode, int selectedItemSlot, String dimension, double posX, double posY, double posZ,
        float yaw, float pitch) {}

    /** Empty when the row can be adopted; otherwise the first problem per domain, without row contents. */
    public static List<String> problems(Row row, Map<String, DomainProvenance.State> states) {
        List<String> problems = new ArrayList<>();
        if (authoritative(states, "inventory")) {
            check(problems, "inventory", () -> items(required(row.inventory()), 36, true));
            check(problems, "inventory", () -> pending(row.pending()));
            if (row.selectedItemSlot() < 0 || row.selectedItemSlot() >= 9) problems.add("inventory: selected item slot range");
        }
        if (authoritative(states, "enderchest")) check(problems, "enderchest", () -> items(required(row.enderchest()), 27, false));
        if (authoritative(states, "armor")) check(problems, "armor", () -> items(required(row.armor()), 4, false));
        if (authoritative(states, "offhand")) check(problems, "offhand", () -> items(required(row.offhand()), 1, false));
        if (authoritative(states, "effects")) check(problems, "effects", () -> effects(required(row.effects())));
        if (authoritative(states, "health")) {
            if (!Double.isFinite(row.health()) || row.health() < 0.0 || row.health() > 1024.0) problems.add("health: range");
            else if (row.air() < 0 || row.air() > 1_000_000) problems.add("health: air range");
        }
        if (authoritative(states, "food")) {
            if (row.foodLevel() < 0 || row.foodLevel() > 20) problems.add("food: level range");
            else if (!Float.isFinite(row.saturation()) || row.saturation() < 0.0F || row.saturation() > row.foodLevel()) {
                problems.add("food: saturation range");
            } else if (!Float.isFinite(row.exhaustion()) || row.exhaustion() < 0.0F || row.exhaustion() > 40.0F) {
                problems.add("food: exhaustion range");
            }
        }
        if (authoritative(states, "experience")) {
            if (row.experienceLevel() < 0 || row.experiencePoints() < 0) problems.add("experience: range");
            else if (!Float.isFinite(row.experienceTotal()) || row.experienceTotal() < 0.0F) problems.add("experience: total range");
            else if (row.experiencePointsIntoLevel() != null && (row.experiencePointsIntoLevel() < 0
                    || row.experiencePointsIntoLevel() >= experienceToNextLevel(row.experienceLevel()))) {
                problems.add("experience: points into level range");
            } else if (row.experienceProgress() != null && (!Float.isFinite(row.experienceProgress())
                    || row.experienceProgress() < 0.0F || row.experienceProgress() >= 1.0F)) {
                problems.add("experience: progress range");
            }
        }
        if (authoritative(states, "gamemode") && (row.gamemode() == null || !GAME_MODES.contains(row.gamemode()))) {
            problems.add("gamemode: unknown value");
        }
        if (authoritative(states, "dimension") && (row.dimension() == null || !IDENTIFIER.matcher(row.dimension()).matches())) {
            problems.add("dimension: invalid identifier");
        }
        if (authoritative(states, "position") && !(Double.isFinite(row.posX()) && Double.isFinite(row.posY())
                && Double.isFinite(row.posZ()))) problems.add("position: non-finite coordinate");
        if (authoritative(states, "rotation") && !(Float.isFinite(row.yaw()) && Float.isFinite(row.pitch())
                && row.pitch() >= -90.0F && row.pitch() <= 90.0F)) problems.add("rotation: range");
        return problems;
    }

    private static boolean authoritative(Map<String, DomainProvenance.State> states, String domain) {
        return states.get(domain) == DomainProvenance.State.ENABLED_AUTHORITATIVE;
    }

    private interface Check { void run(); }

    private static void check(List<String> problems, String domain, Check check) {
        try { check.run(); }
        catch (RuntimeException invalid) { problems.add(domain + ": " + invalid.getMessage()); }
    }

    private static String required(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("missing authoritative payload");
        return json;
    }

    private static JsonArray array(String json) {
        final JsonElement parsed;
        try { parsed = JsonParser.parseString(json); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("malformed JSON"); }
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("JSON is not an array");
        return parsed.getAsJsonArray();
    }

    private static void items(String json, int size, boolean mainInventory) {
        Set<Integer> slots = new HashSet<>();
        for (JsonElement element : array(json)) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("entry is not an object");
            JsonObject object = element.getAsJsonObject();
            int slot = integer(object, "slot");
            // Old writers stored the whole PlayerInventory in the inventory column; 36-40 are duplicates.
            if (mainInventory && slot >= size && slot < size + 5) continue;
            if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot range");
            if (!slots.add(slot)) throw new IllegalArgumentException("duplicate slot");
            item(object);
        }
    }

    private static void pending(String json) {
        if (json == null || json.isBlank()) return;
        int expected = 0;
        for (JsonElement element : array(json)) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("pending entry is not an object");
            if (integer(element.getAsJsonObject(), "slot") != expected++) throw new IllegalArgumentException("pending order");
            item(element.getAsJsonObject());
        }
    }

    private static void item(JsonObject object) {
        if (!object.has("item")) throw new IllegalArgumentException("item identifier is missing");
        if (object.has("nbt")) {
            String snbt = string(object, "nbt").trim();
            if (!snbt.startsWith("{") || !snbt.endsWith("}")) throw new IllegalArgumentException("item NBT is not a compound");
        } else {
            if (!IDENTIFIER.matcher(string(object, "item")).matches()) throw new IllegalArgumentException("invalid item identifier");
            int count = integer(object, "count");
            if (count <= 0 || count > 64) throw new IllegalArgumentException("item count range");
        }
        if (object.has("damage") && integer(object, "damage") < 0) throw new IllegalArgumentException("item damage range");
    }

    private static void effects(String json) {
        for (JsonElement element : array(json)) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("entry is not an object");
            JsonObject object = element.getAsJsonObject();
            if (!IDENTIFIER.matcher(string(object, object.has("effect") ? "effect" : "id")).matches()) {
                throw new IllegalArgumentException("invalid effect identifier");
            }
            int amplifier = integer(object, "amplifier");
            if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("amplifier range");
            if (integer(object, "duration") < 0) throw new IllegalArgumentException("duration range");
            for (String flag : new String[] {"ambient", "showParticles", "showIcon"}) bool(object, flag);
        }
    }

    // The three accessors below accept exactly what the codec's Gson accessors accept, so the
    // preflight is never stricter than a real LOAD.
    private static int integer(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("missing " + key);
        try { return object.get(key).getAsInt(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid integer " + key); }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException("missing " + key);
        try { return object.get(key).getAsString(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid string " + key); }
    }

    private static void bool(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("missing " + key);
        try { object.get(key).getAsBoolean(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid boolean " + key); }
    }

    /** Vanilla 1.18.2 PlayerEntity.getNextLevelExperience. */
    static int experienceToNextLevel(int level) {
        if (level >= 30) return 112 + (level - 30) * 9;
        return level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }
}
