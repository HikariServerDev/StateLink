package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable provenance for PlayerData domains. Ownership fencing and data provenance
 * are deliberately independent: recovery may advance a fence without changing the
 * checkpoint or the domains which produced it.
 */
public final class DomainProvenance {
    public enum State { ENABLED_AUTHORITATIVE, DISABLED, UNKNOWN }

    private static final String[] DOMAINS = {
        "inventory", "enderchest", "armor", "offhand", "health", "food", "experience", "effects",
        "position", "rotation", "dimension", "gamemode", "playerProfile", "advancements", "statistics", "recipeBook"
    };

    private final long dataRevision;
    private final long generation;
    private final Map<String, State> states;

    private DomainProvenance(long dataRevision, long generation, Map<String, State> states) {
        this.dataRevision = dataRevision;
        this.generation = generation;
        this.states = Map.copyOf(states);
    }

    public static DomainProvenance unknown(long revision) {
        Map<String, State> states = new LinkedHashMap<>();
        for (String domain : DOMAINS) states.put(domain, State.UNKNOWN);
        return new DomainProvenance(revision, 0, states);
    }

    public static DomainProvenance forConfiguration(Configuration.SyncConfig sync, long revision, long generation) {
        Map<String, State> states = new LinkedHashMap<>();
        for (String domain : DOMAINS) states.put(domain, enabled(sync, domain)
            ? State.ENABLED_AUTHORITATIVE : State.DISABLED);
        return new DomainProvenance(revision, Math.max(1, generation), states);
    }

    /** Explicit per-domain states; used only by the audited one-time legacy bootstrap. */
    public static DomainProvenance ofStates(long revision, long generation, Map<String, State> explicit) {
        Map<String, State> states = new LinkedHashMap<>();
        for (String domain : DOMAINS) {
            State state = explicit.get(domain);
            if (state == null) throw new IllegalArgumentException("missing PlayerData domain state: " + domain);
            states.put(domain, state);
        }
        if (explicit.size() != DOMAINS.length) throw new IllegalArgumentException("unknown PlayerData domain state");
        return new DomainProvenance(revision, Math.max(1, generation), states);
    }

    public static java.util.List<String> domains() { return java.util.List.of(DOMAINS); }

    /** Parse either the 2.2.5 state manifest or an older boolean clean-release manifest. */
    public static DomainProvenance read(String manifest, Long provenanceRevision, Long generation,
        long currentDataRevision) {
        if (manifest == null || provenanceRevision == null || generation == null || generation <= 0
                || provenanceRevision != currentDataRevision || currentDataRevision < 0) {
            return unknown(currentDataRevision);
        }
        try {
            JsonObject root = JsonParser.parseString(manifest).getAsJsonObject();
            JsonObject encoded = root;
            if (root.has("states")) {
                if (!root.has("format") || !root.get("format").isJsonPrimitive()
                        || !root.getAsJsonPrimitive("format").isNumber() || root.get("format").getAsInt() != 1
                        || !root.get("states").isJsonObject()) return unknown(currentDataRevision);
                encoded = root.getAsJsonObject("states");
            }
            Map<String, State> states = new LinkedHashMap<>();
            for (String domain : DOMAINS) {
                State state = State.UNKNOWN;
                if (encoded.has(domain)) {
                    JsonElement value = encoded.get(domain);
                    if (value != null && value.isJsonPrimitive()) {
                        if (value.getAsJsonPrimitive().isBoolean()) {
                            state = value.getAsBoolean() ? State.ENABLED_AUTHORITATIVE : State.DISABLED;
                        } else if (value.getAsJsonPrimitive().isString()) {
                            try { state = State.valueOf(value.getAsString()); }
                            catch (IllegalArgumentException ignored) { state = State.UNKNOWN; }
                        }
                    }
                }
                states.put(domain, state);
            }
            return new DomainProvenance(currentDataRevision, generation, states);
        } catch (RuntimeException malformed) {
            return unknown(currentDataRevision);
        }
    }

    public long dataRevision() { return dataRevision; }
    public long generation() { return generation; }
    public State state(String domain) { return states.getOrDefault(domain, State.UNKNOWN); }
    public Map<String, State> states() { return states; }

    public boolean authorizesEnabledDomains(Configuration.SyncConfig sync) {
        for (String domain : DOMAINS) {
            if (enabled(sync, domain) && state(domain) != State.ENABLED_AUTHORITATIVE) return false;
        }
        return true;
    }

    /** Record an explicit config choice to keep disabled domains out of synchronization. */
    public DomainProvenance markConfiguredDisabledDomains(Configuration.SyncConfig sync) {
        Map<String, State> next = new LinkedHashMap<>(states);
        boolean changed = false;
        for (String domain : DOMAINS) {
            if (!enabled(sync, domain) && next.get(domain) != State.DISABLED) {
                next.put(domain, State.DISABLED);
                changed = true;
            }
        }
        return changed ? new DomainProvenance(dataRevision, Math.addExact(generation, 1), next) : this;
    }

    public DomainProvenance adoptDatabaseDomain(String domain, long revision) {
        if (!states.containsKey(domain)) throw new IllegalArgumentException("unknown PlayerData domain: " + domain);
        Map<String, State> next = new LinkedHashMap<>(states);
        next.put(domain, State.ENABLED_AUTHORITATIVE);
        return new DomainProvenance(revision, Math.addExact(generation, 1), next);
    }

    public DomainProvenance atRevision(Configuration.SyncConfig sync, long revision) {
        return forConfiguration(sync, revision, Math.addExact(generation, 1));
    }

    public String encode() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonObject encoded = new JsonObject();
        for (String domain : DOMAINS) encoded.addProperty(domain, state(domain).name());
        root.add("states", encoded);
        return root.toString();
    }

    public static boolean isKnownDomain(String domain) {
        for (String candidate : DOMAINS) if (candidate.equals(domain)) return true;
        return false;
    }

    public static boolean enabled(Configuration.SyncConfig sync, String domain) {
        return switch (domain) {
            case "inventory" -> sync.inventory;
            case "enderchest" -> sync.enderchest;
            case "armor" -> sync.armor;
            case "offhand" -> sync.offhand;
            case "health" -> sync.health;
            case "food" -> sync.food;
            case "experience" -> sync.experience;
            case "effects" -> sync.effects;
            case "position" -> sync.position;
            case "rotation" -> sync.rotationEnabled();
            case "dimension" -> sync.dimensionEnabled();
            case "gamemode" -> sync.gamemode;
            case "playerProfile" -> sync.playerProfile;
            case "advancements" -> sync.advancements;
            case "statistics" -> sync.statistics;
            case "recipeBook" -> sync.recipeBook;
            default -> throw new IllegalArgumentException("unknown PlayerData domain: " + domain);
        };
    }
}
