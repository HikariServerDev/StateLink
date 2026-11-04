package com.atsukigames.statelink.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DisabledMarkerTest {
    @Test void enabledStartupWithoutMarkerNeedsNoAcknowledgement(@TempDir Path dir) throws Exception {
        assertNull(DisabledMarker.requireReactivationAcknowledged(dir, ""));
    }

    @Test void disabledStartupKeepsOneMarkerIdentityAcrossRestarts(@TempDir Path dir) throws Exception {
        var first = DisabledMarker.recordDisabled(dir, "s1", "2.2.7");
        var second = DisabledMarker.recordDisabled(dir, "s1", "2.2.7");
        assertEquals(first.markerId(), second.markerId());
        assertEquals(first.disabledAt(), second.disabledAt());
        assertEquals("s1", second.serverId());
    }

    @Test void reEnableIsRefusedUntilExactlyThisMarkerIsAcknowledged(@TempDir Path dir) throws Exception {
        var marker = DisabledMarker.recordDisabled(dir, "s1", "2.2.7");
        for (String wrong : new String[] {null, "", "true", java.util.UUID.randomUUID().toString()}) {
            var refused = assertThrows(ConfigurationException.class,
                () -> DisabledMarker.requireReactivationAcknowledged(dir, wrong));
            assertTrue(refused.getMessage().startsWith("STATELINK_REACTIVATION_ACKNOWLEDGEMENT_REQUIRED"));
            assertTrue(Files.exists(DisabledMarker.path(dir)), "a refused start must keep the marker");
        }
        assertEquals(marker.markerId(), DisabledMarker.requireReactivationAcknowledged(dir, marker.markerId()).markerId());
        assertFalse(Files.exists(DisabledMarker.path(dir)));
        assertNull(DisabledMarker.requireReactivationAcknowledged(dir, marker.markerId()));
    }

    @Test void aStaleAcknowledgementDoesNotCoverALaterDisablePeriod(@TempDir Path dir) throws Exception {
        var first = DisabledMarker.recordDisabled(dir, "s1", "2.2.7");
        DisabledMarker.requireReactivationAcknowledged(dir, first.markerId());
        var second = DisabledMarker.recordDisabled(dir, "s1", "2.2.7");
        assertNotEquals(first.markerId(), second.markerId());
        assertThrows(ConfigurationException.class,
            () -> DisabledMarker.requireReactivationAcknowledged(dir, first.markerId()));
    }

    @Test void unreadableMarkerIsNeverAcknowledgedAutomatically(@TempDir Path dir) throws Exception {
        Files.writeString(DisabledMarker.path(dir), "not json");
        assertThrows(ConfigurationException.class, () -> DisabledMarker.requireReactivationAcknowledged(dir, ""));
        assertTrue(Files.exists(DisabledMarker.path(dir)));
    }
}
