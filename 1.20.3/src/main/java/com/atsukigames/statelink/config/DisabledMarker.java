package com.atsukigames.statelink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Local, database-free record that this backend ran with {@code enable=false}. While disabled,
 * StateLink contacts no database, so nothing in the shared provenance can show that
 * players changed locally. The marker turns re-enabling into an explicit operator decision
 * instead of a silent LOAD of older database state over those local changes.
 *
 * <p>It only exists for periods in which the mod was installed and disabled; removing the JAR
 * leaves no marker.</p>
 */
public final class DisabledMarker {
    public static final String FILE_NAME = "statelink-disabled.marker";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DisabledMarker() {}

    public record Marker(String markerId, String serverId, String disabledAt, String version) {}

    public static Path path(Path configDirectory) { return configDirectory.resolve(FILE_NAME); }

    /** Called on every disabled startup; the first disabled startup fixes the marker identity. */
    public static Marker recordDisabled(Path configDirectory, String serverId, String version) throws IOException {
        Marker existing = read(configDirectory);
        if (existing != null) return existing;
        Marker marker = new Marker(UUID.randomUUID().toString(), serverId, Instant.now().toString(), version);
        Files.createDirectories(configDirectory);
        Path temporary = configDirectory.resolve(FILE_NAME + ".tmp");
        Files.writeString(temporary, GSON.toJson(marker));
        try {
            Files.move(temporary, path(configDirectory), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path(configDirectory), StandardCopyOption.REPLACE_EXISTING);
        }
        return marker;
    }

    /** Null when no marker exists. An unreadable marker is still a marker and never acknowledged. */
    public static Marker read(Path configDirectory) throws IOException {
        Path file = path(configDirectory);
        if (Files.notExists(file)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String id = root.get("markerId").getAsString();
            UUID.fromString(id);
            return new Marker(id, text(root, "serverId"), text(root, "disabledAt"), text(root, "version"));
        } catch (RuntimeException malformed) {
            return new Marker("", null, null, null);
        }
    }

    private static String text(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : null;
    }

    /**
     * Enabled startup. Returns the acknowledged marker (already removed) or null when none existed;
     * throws when a marker exists and the configuration does not acknowledge exactly that marker.
     */
    public static Marker requireReactivationAcknowledged(Path configDirectory, String acknowledgedMarkerId)
        throws IOException {
        Marker marker = read(configDirectory);
        if (marker == null) return null;
        if (marker.markerId().isEmpty()) {
            throw new ConfigurationException("STATELINK_REACTIVATION_ACKNOWLEDGEMENT_REQUIRED: " + path(configDirectory)
                + " is unreadable. This backend ran with enable=false; review local player changes, then delete "
                + "the marker file manually to accept database authority");
        }
        if (acknowledgedMarkerId == null || !marker.markerId().equals(acknowledgedMarkerId.trim())) {
            throw new ConfigurationException("STATELINK_REACTIVATION_ACKNOWLEDGEMENT_REQUIRED: this backend ran with "
                + "enable=false since " + marker.disabledAt() + ". Player changes made locally in that period are "
                + "not in the database and will be replaced by database state on login. After reviewing them, set "
                + "migration.acknowledgeReactivationMarker=" + marker.markerId() + " to re-enable");
        }
        Files.delete(path(configDirectory));
        return marker;
    }
}
