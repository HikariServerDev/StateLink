package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainProvenanceTest {
    @Test void ownershipFenceIsNotPartOfDataProvenance() {
        String legacyManifest = "{\"inventory\":true,\"enderchest\":true,\"armor\":true,"
            + "\"offhand\":true,\"health\":true,\"food\":true,\"experience\":true,"
            + "\"effects\":true,\"position\":true,\"rotation\":true,\"dimension\":true,"
            + "\"gamemode\":true,\"playerProfile\":true,\"advancements\":true,"
            + "\"statistics\":true,\"recipeBook\":true}";
        var provenance = DomainProvenance.read(legacyManifest, 12L, 4L, 12L);
        assertTrue(provenance.authorizesEnabledDomains(new Configuration.SyncConfig()));
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, provenance.state("inventory"));
    }

    @Test void absentMalformedOrStaleProvenanceIsUnknown() {
        var sync = new Configuration.SyncConfig();
        assertFalse(DomainProvenance.read(null, null, null, 12).authorizesEnabledDomains(sync));
        assertFalse(DomainProvenance.read("not-json", 12L, 1L, 12).authorizesEnabledDomains(sync));
        assertFalse(DomainProvenance.read("{\"inventory\":true}", 10L, 1L, 12).authorizesEnabledDomains(sync));
        assertFalse(DomainProvenance.read("{\"inventory\":true}", 12L, null, 12).authorizesEnabledDomains(sync));
        assertFalse(DomainProvenance.read("{\"format\":99,\"states\":{\"inventory\":\"ENABLED_AUTHORITATIVE\"}}",
            12L, 1L, 12).authorizesEnabledDomains(sync));
    }

    @Test void disablingIsDurableAndReenableRequiresExplicitAdoption() {
        var all = new Configuration.SyncConfig();
        var disabled = new Configuration.SyncConfig();
        disabled.inventory = false;
        var original = DomainProvenance.forConfiguration(all, 9, 2);
        var afterDisable = original.markConfiguredDisabledDomains(disabled);
        assertEquals(DomainProvenance.State.DISABLED, afterDisable.state("inventory"));
        assertEquals(DomainProvenance.State.ENABLED_AUTHORITATIVE, afterDisable.state("health"));
        assertFalse(afterDisable.authorizesEnabledDomains(all));
        var reconciled = afterDisable.adoptDatabaseDomain("inventory", 9);
        assertTrue(reconciled.authorizesEnabledDomains(all));
        assertEquals(4, reconciled.generation());
    }

    @Test void disablingUnknownDomainsDoesNotInventEnabledHistory() {
        var partial = new Configuration.SyncConfig();
        partial.inventory = false;
        var provenance = DomainProvenance.unknown(7).markConfiguredDisabledDomains(partial);
        assertEquals(DomainProvenance.State.DISABLED, provenance.state("inventory"));
        assertEquals(DomainProvenance.State.UNKNOWN, provenance.state("health"));
        assertFalse(provenance.authorizesEnabledDomains(partial));
        var allDisabled = new Configuration.SyncConfig();
        allDisabled.inventory = allDisabled.enderchest = allDisabled.armor = allDisabled.offhand = false;
        allDisabled.health = allDisabled.food = allDisabled.experience = allDisabled.effects = false;
        allDisabled.position = false;
        allDisabled.dimension = false;
        allDisabled.rotation = false;
        allDisabled.gamemode = allDisabled.playerProfile = allDisabled.advancements = false;
        allDisabled.statistics = allDisabled.recipeBook = false;
        var allOff = provenance.markConfiguredDisabledDomains(allDisabled);
        assertTrue(allOff.authorizesEnabledDomains(allDisabled));
    }

    @Test void roundTripPreservesTriStateAndGeneration() {
        var sync = new Configuration.SyncConfig();
        sync.inventory = false;
        var before = DomainProvenance.forConfiguration(sync, 25, 6);
        var after = DomainProvenance.read(before.encode(), 25L, 6L, 25);
        assertEquals(before.states(), after.states());
        assertEquals(6, after.generation());
        assertEquals(25, after.dataRevision());
    }
}
