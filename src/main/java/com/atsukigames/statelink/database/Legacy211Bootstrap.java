package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time provenance bootstrap plan for a database written by PlayerDataConnector 2.1.11.
 *
 * <p>The domain classification below is taken from the 2.1.11 artifact
 * (SHA-256 1fc8a501...0708), not from the current configuration:
 * {@code PlayerSyncCoordinator.extractCompletePlayerData}, {@code PlayerDataRepository.upsertPlayerData}
 * and {@code CompletePlayerDataSerializer.prepare/applyPrepared}. 2.1.11 had no domain
 * provenance, so the plan is only ever applied after an explicit operator acknowledgement
 * that the legacy-compatible domain switches match the 2.1.11-era configuration.</p>
 */
public final class Legacy211Bootstrap {
    public static final String MIGRATION_ID = "legacy-2.1.11-provenance-bootstrap";
    public static final String SOURCE_SCHEMA = "2.1.11";
    public static final String POLICY_DISABLED = "disabled";
    public static final String POLICY_ADOPT_FIRST_LOGIN_LOCAL = "adopt-first-login-local";

    /** What 2.1.11 actually did with a domain. */
    public enum LegacySemantics {
        /** Saved from and applied to the live player, gated by one 2.1.11 sync switch. */
        SYNCHRONIZED,
        /** 2.1.11 wrote a constant placeholder and never applied the column. */
        PLACEHOLDER_ONLY
    }

    /** 2.2.x domain, its 2.1.11 semantics and the 2.1.11 switch which governed it. */
    public record LegacyDomain(String domain, LegacySemantics semantics, String legacySwitch, String column) {}

    private static final List<LegacyDomain> LEGACY_DOMAINS = List.of(
        new LegacyDomain("inventory", LegacySemantics.SYNCHRONIZED, "inventory", "inventory"),
        new LegacyDomain("enderchest", LegacySemantics.SYNCHRONIZED, "enderchest", "enderchest"),
        new LegacyDomain("armor", LegacySemantics.SYNCHRONIZED, "armor", "armor"),
        new LegacyDomain("offhand", LegacySemantics.SYNCHRONIZED, "offhand", "offhand"),
        new LegacyDomain("health", LegacySemantics.SYNCHRONIZED, "health", "health"),
        new LegacyDomain("food", LegacySemantics.SYNCHRONIZED, "food", "food_level"),
        new LegacyDomain("experience", LegacySemantics.SYNCHRONIZED, "experience", "experience_level"),
        new LegacyDomain("effects", LegacySemantics.SYNCHRONIZED, "effects", "effects"),
        // 2.1.11 had one aggregate switch for dimension, coordinates, yaw and pitch.
        new LegacyDomain("position", LegacySemantics.SYNCHRONIZED, "position", "pos_x"),
        new LegacyDomain("rotation", LegacySemantics.SYNCHRONIZED, "position", "yaw"),
        new LegacyDomain("dimension", LegacySemantics.SYNCHRONIZED, "position", "dimension"),
        new LegacyDomain("gamemode", LegacySemantics.SYNCHRONIZED, "gamemode", "gamemode"),
        // {"name","uuid"} identity only; never applied on LOAD.
        new LegacyDomain("playerProfile", LegacySemantics.PLACEHOLDER_ONLY, "playerProfile", "skin_texture"),
        // {"placeholder":"advancement_data_simplified"}
        new LegacyDomain("advancements", LegacySemantics.PLACEHOLDER_ONLY, "advancements", "advancements"),
        // {"placeholder":"statistics_data_simplified"}
        new LegacyDomain("statistics", LegacySemantics.PLACEHOLDER_ONLY, "statistics", "statistics"),
        // {"known_recipes":[]}
        new LegacyDomain("recipeBook", LegacySemantics.PLACEHOLDER_ONLY, "recipeBook", "recipe_book"));

    private Legacy211Bootstrap() {}

    public static List<LegacyDomain> legacyDomains() { return LEGACY_DOMAINS; }

    /** Refusal which must stop startup instead of quarantining players one by one. */
    public static final class BootstrapRefusedException extends IllegalStateException {
        private final String code;
        public BootstrapRefusedException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }
        public String code() { return code; }
    }

    public record Plan(Map<String, DomainProvenance.State> states, List<LegacyDomain> placeholderAdoptions,
        String newDomainPolicy, String manifest, String fingerprint) {}

    /**
     * Derive the cluster-wide legacy manifest. A domain becomes ENABLED_AUTHORITATIVE only when
     * 2.1.11 really synchronized it (operator-acknowledged switch) or, for placeholder-only
     * domains, when the operator explicitly chose first-login local adoption.
     */
    public static Plan plan(Configuration.SyncConfig sync, String newDomainPolicy) {
        if (sync == null) throw new IllegalArgumentException("sync configuration is required");
        String policy = newDomainPolicy == null || newDomainPolicy.isBlank() ? POLICY_DISABLED : newDomainPolicy;
        if (!POLICY_DISABLED.equals(policy) && !POLICY_ADOPT_FIRST_LOGIN_LOCAL.equals(policy)) {
            throw new IllegalArgumentException("unknown legacy211NewDomainPolicy: " + policy);
        }
        Map<String, DomainProvenance.State> states = new LinkedHashMap<>();
        List<LegacyDomain> adoptions = new java.util.ArrayList<>();
        for (LegacyDomain legacy : LEGACY_DOMAINS) {
            boolean enabledNow = DomainProvenance.enabled(sync, legacy.domain());
            if (!enabledNow) {
                states.put(legacy.domain(), DomainProvenance.State.DISABLED);
                continue;
            }
            if (legacy.semantics() == LegacySemantics.SYNCHRONIZED) {
                if (!DomainProvenance.enabled(sync, legacy.legacySwitch())) {
                    throw new BootstrapRefusedException("LEGACY_2_1_11_DOMAIN_NOT_SYNCHRONIZED",
                        "sync." + legacy.domain() + " is enabled but 2.1.11 synchronized it only through sync."
                            + legacy.legacySwitch() + ", which is disabled; the database holds no authoritative "
                            + legacy.domain() + " value");
                }
                states.put(legacy.domain(), DomainProvenance.State.ENABLED_AUTHORITATIVE);
            } else if (POLICY_ADOPT_FIRST_LOGIN_LOCAL.equals(policy)) {
                states.put(legacy.domain(), DomainProvenance.State.ENABLED_AUTHORITATIVE);
                adoptions.add(legacy);
            } else {
                throw new BootstrapRefusedException("LEGACY_2_1_11_NEW_DOMAIN_POLICY_REQUIRED",
                    "sync." + legacy.domain() + " is enabled but 2.1.11 stored only a placeholder for it. Set sync."
                        + legacy.domain() + "=false for the bootstrap, or set migration.legacy211NewDomainPolicy="
                        + POLICY_ADOPT_FIRST_LOGIN_LOCAL + " to keep each player's local state from the first backend "
                        + "they join");
            }
        }
        String manifest = DomainProvenance.ofStates(0, 1, states).encode();
        return new Plan(Map.copyOf(states), List.copyOf(adoptions), policy, manifest, clusterFingerprint(sync, policy));
    }

    /**
     * Cluster authority fingerprint: provenance model version, the enabled state of all 16 domains
     * (which includes the item-domain dependency) and the recorded legacy new-domain decision.
     * Server identity, leases, timing and logging are deliberately excluded. Every enabled backend
     * of a bootstrapped database must produce the recorded value, not only the migrating one.
     */
    public static String clusterFingerprint(Configuration.SyncConfig sync, String policy) {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (String domain : DomainProvenance.domains()) enabled.put(domain, DomainProvenance.enabled(sync, domain));
        return clusterFingerprint(enabled, policy);
    }

    /** The fingerprint a 2.2.7 bootstrap would have under this model, from its recorded manifest. */
    public static String clusterFingerprintOfManifest(String manifest, String policy) {
        DomainProvenance provenance = DomainProvenance.read(manifest, 0L, 1L, 0L);
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (String domain : DomainProvenance.domains()) {
            DomainProvenance.State state = provenance.state(domain);
            if (state == DomainProvenance.State.UNKNOWN) throw new IllegalStateException("recorded bootstrap manifest is unreadable");
            enabled.put(domain, state == DomainProvenance.State.ENABLED_AUTHORITATIVE);
        }
        return clusterFingerprint(enabled, policy);
    }

    static String clusterFingerprint(Map<String, Boolean> enabled, String policy) {
        // Hashed into the fingerprint stored in the database: this text must never change.
        StringBuilder canonical = new StringBuilder("pdc-cluster-domain-authority-v2\nprovenanceFormat=1\n");
        for (String domain : DomainProvenance.domains()) {
            canonical.append(domain).append('=').append(enabled.get(domain) ? "ENABLED" : "DISABLED").append('\n');
        }
        canonical.append("legacy211NewDomainPolicy=").append(policy == null || policy.isBlank() ? POLICY_DISABLED : policy).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Exactly the constant 2.1.11 wrote for a placeholder-only domain. Mirrors the LOAD-side
     * recognition in PlayerProgressCodec, PlayerRecipeCodec and PlayerProfileCodec, which keep
     * local state for these values. NULL, blank or any other payload is not a placeholder.
     */
    public static boolean isLegacyPlaceholder(String domain, String value) {
        if (value == null || value.isBlank()) return false;
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) return false;
            root = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return false;
        }
        return switch (domain) {
            case "advancements" -> isStringMember(root, 1, "placeholder", "advancement_data_simplified");
            case "statistics" -> isStringMember(root, 1, "placeholder", "statistics_data_simplified");
            case "recipeBook" -> root.size() == 1 && root.has("known_recipes")
                && root.get("known_recipes").isJsonArray() && root.getAsJsonArray("known_recipes").isEmpty();
            case "playerProfile" -> root.size() == 2 && isString(root, "name") && isString(root, "uuid");
            default -> false;
        };
    }

    private static boolean isStringMember(JsonObject root, int size, String key, String expected) {
        return root.size() == size && isString(root, key) && expected.equals(root.get(key).getAsString());
    }

    private static boolean isString(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() && root.getAsJsonPrimitive(key).isString();
    }
}
