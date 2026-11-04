package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Legacy211BootstrapTest {
    private static Configuration.SyncConfig legacyOnly() {
        Configuration.SyncConfig sync = new Configuration.SyncConfig();
        sync.playerProfile = false; sync.advancements = false; sync.statistics = false; sync.recipeBook = false;
        return sync;
    }

    @Test void synchronizedDomainsBecomeAuthoritativeAndPlaceholderDomainsStayDisabled() {
        var plan = Legacy211Bootstrap.plan(legacyOnly(), "disabled");
        for (String domain : new String[] {"inventory", "enderchest", "armor", "offhand", "health", "food",
                "experience", "effects", "position", "rotation", "dimension", "gamemode"}) {
            assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, plan.states().get(domain), domain);
        }
        for (String domain : new String[] {"playerProfile", "advancements", "statistics", "recipeBook"}) {
            assertEquals(DomainProvenance.State.DISABLED, plan.states().get(domain), domain);
        }
        assertTrue(plan.placeholderAdoptions().isEmpty());
        var provenance = DomainProvenance.read(plan.manifest(), 7L, 1L, 7L);
        assertTrue(provenance.authorizesEnabledDomains(legacyOnly()));
        assertFalse(provenance.authorizesEnabledDomains(new Configuration.SyncConfig()),
            "a placeholder-only domain enabled later is still a re-enable");
    }

    @Test void enabledPlaceholderDomainIsRefusedUnlessThePolicyIsExplicit() {
        var refused = assertThrows(Legacy211Bootstrap.BootstrapRefusedException.class,
            () -> Legacy211Bootstrap.plan(new Configuration.SyncConfig(), "disabled"));
        assertEquals("LEGACY_2_1_11_NEW_DOMAIN_POLICY_REQUIRED", refused.code());
        var plan = Legacy211Bootstrap.plan(new Configuration.SyncConfig(), "adopt-first-login-local");
        assertEquals(4, plan.placeholderAdoptions().size());
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, plan.states().get("statistics"));
    }

    @Test void currentlyDisabledDomainIsNeverBootstrappedAsAuthoritative() {
        Configuration.SyncConfig sync = legacyOnly();
        sync.inventory = false; sync.armor = false; sync.offhand = false; sync.enderchest = false;
        var plan = Legacy211Bootstrap.plan(sync, "disabled");
        assertEquals(DomainProvenance.State.DISABLED, plan.states().get("inventory"));
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, plan.states().get("health"));
    }

    @Test void rotationWithoutTheLegacyAggregatePositionSwitchIsRefused() {
        Configuration.SyncConfig sync = legacyOnly();
        sync.position = false; sync.dimension = false; sync.rotation = true;
        var refused = assertThrows(Legacy211Bootstrap.BootstrapRefusedException.class,
            () -> Legacy211Bootstrap.plan(sync, "disabled"));
        assertEquals("LEGACY_2_1_11_DOMAIN_NOT_SYNCHRONIZED", refused.code());
        sync.rotation = false;
        assertEquals(DomainProvenance.State.DISABLED, Legacy211Bootstrap.plan(sync, "disabled").states().get("rotation"));
    }

    @Test void fingerprintIsStableAndChangesWithAnyDomainOrPolicy() {
        String base = Legacy211Bootstrap.plan(legacyOnly(), "disabled").fingerprint();
        assertEquals(base, Legacy211Bootstrap.plan(legacyOnly(), "disabled").fingerprint());
        assertTrue(base.matches("[0-9a-f]{64}"));
        Configuration.SyncConfig other = legacyOnly();
        other.effects = false;
        assertNotEquals(base, Legacy211Bootstrap.plan(other, "disabled").fingerprint());
        assertNotEquals(base, Legacy211Bootstrap.plan(legacyOnly(), "adopt-first-login-local").fingerprint());
        Configuration.SyncConfig timing = legacyOnly();
        timing.serverId = "another-backend"; timing.leaseDurationMs = 9_000;
        assertEquals(base, Legacy211Bootstrap.plan(timing, "disabled").fingerprint(),
            "only domain switches are part of the cluster bootstrap identity");
    }

    @Test void clusterFingerprintIsAFunctionOfDomainSwitchesAndRecordedPolicyOnly() {
        var plan = Legacy211Bootstrap.plan(legacyOnly(), "disabled");
        assertEquals(plan.fingerprint(), Legacy211Bootstrap.clusterFingerprint(legacyOnly(), "disabled"));
        assertEquals(plan.fingerprint(), Legacy211Bootstrap.clusterFingerprintOfManifest(plan.manifest(), "disabled"),
            "a backend without any bootstrap flags reproduces the recorded value from its switches");
        Configuration.SyncConfig items = legacyOnly();
        items.armor = false;
        assertNotEquals(plan.fingerprint(), Legacy211Bootstrap.clusterFingerprint(items, "disabled"));
        Configuration.SyncConfig unrelated = legacyOnly();
        unrelated.serverId = "s9"; unrelated.leaseRenewIntervalMs = 2_000; unrelated.syncOnDimensionChange = false;
        assertEquals(plan.fingerprint(), Legacy211Bootstrap.clusterFingerprint(unrelated, "disabled"));
    }

    @Test void onlyTheExact211ConstantsArePlaceholders() {
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("advancements", "{\"placeholder\":\"advancement_data_simplified\"}"));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("statistics", "{\n  \"placeholder\": \"statistics_data_simplified\"\n}"));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("recipeBook", "{\"known_recipes\":[]}"));
        assertTrue(Legacy211Bootstrap.isLegacyPlaceholder("playerProfile", "{\"name\":\"Steve\",\"uuid\":\"u\"}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("advancements", null));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("advancements", ""));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("advancements", "{}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("advancements", "{\"placeholder\":\"statistics_data_simplified\"}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("statistics", "{\"format\":1,\"stats\":{}}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("recipeBook", "{\"known_recipes\":[\"minecraft:stick\"]}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("playerProfile", "{\"format\":1,\"name\":\"a\",\"uuid\":\"b\",\"properties\":[]}"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("inventory", "[]"));
        assertFalse(Legacy211Bootstrap.isLegacyPlaceholder("statistics", "not json"));
    }

    @Test void everyCurrentDomainHasExactlyOneLegacyClassification() {
        var names = Legacy211Bootstrap.legacyDomains().stream().map(Legacy211Bootstrap.LegacyDomain::domain).toList();
        assertEquals(DomainProvenance.domains().size(), names.size());
        assertTrue(names.containsAll(DomainProvenance.domains()));
    }
}
