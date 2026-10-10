package com.atsukigames.statelink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Carries the local files written under the mod's former name, PlayerDataConnector, over to the
 * StateLink names. It runs on every startup before the configuration is read and does nothing
 * once no legacy file is left.
 *
 * <p>Only file names change. A file is renamed in place and never rewritten, so the migrated
 * configuration is byte-for-byte the legacy one and keeps pointing at the same database and
 * tables. The database needs no migration: its tables are named by the configuration, not after
 * the mod, and StateLink speaks the same schema and coordination protocol as PlayerDataConnector
 * of the same version.</p>
 *
 * <p>Nothing is ever deleted or overwritten. Whenever it is not certain which file carries the
 * operator's intent, startup is refused instead of guessing: a wrong guess could connect this
 * backend to a different database.</p>
 */
public final class LegacyNameMigration {
    public static final String LEGACY_MOD_ID = "playerdataconnector";
    public static final String LEGACY_CONFIG_FILE = "playerdataconnector.json";
    public static final String LEGACY_MARKER_FILE = "playerdataconnector-disabled.marker";
    public static final String CONFIG_FILE = "statelink.json";
    static final String ARCHIVE_SUFFIX = ".migrated-to-statelink";
    static final String TEMPLATE_SUFFIX = ".unused-template";
    private static final int MAX_ARCHIVE_NAMES = 1000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LegacyNameMigration() {}

    public enum Outcome {
        /** Nothing carries the legacy name; the normal case after the first StateLink startup. */
        NO_LEGACY_FILE,
        /** The legacy file was renamed to the StateLink name. */
        MOVED,
        /** As MOVED, after setting aside a StateLink configuration that was still the untouched template. */
        MOVED_OVER_UNUSED_TEMPLATE,
        /** Both names existed with identical contents; the legacy copy was set aside. */
        DUPLICATE_ARCHIVED,
        /** Both markers existed with different contents; the StateLink one stays in force. */
        SUPERSEDED_ARCHIVED
    }

    /** {@code archived} is the file that was set aside, or null. */
    public record Result(Outcome outcome, Path legacy, Path target, Path archived) {
        public boolean changedAnything() { return outcome != Outcome.NO_LEGACY_FILE; }
    }

    /** Must run before {@link Configuration#load}. Throws {@link ConfigurationException} to refuse startup. */
    public static Result migrateConfig(Path configDirectory) {
        Path legacy = configDirectory.resolve(LEGACY_CONFIG_FILE);
        Path target = configDirectory.resolve(CONFIG_FILE);
        if (!present(legacy)) return new Result(Outcome.NO_LEGACY_FILE, legacy, target, null);
        if (!Files.isRegularFile(legacy)) {
            throw new ConfigurationException("STATELINK_LEGACY_CONFIG_UNUSABLE: " + legacy + " is not a readable "
                + "regular file. Move it away, or replace it with the PlayerDataConnector configuration to migrate");
        }
        if (!present(target)) {
            move(legacy, target);
            return new Result(Outcome.MOVED, legacy, target, null);
        }
        if (!Files.isRegularFile(target)) {
            throw new ConfigurationException("STATELINK_LEGACY_CONFIG_CONFLICT: " + target + " exists but is not a "
                + "regular file, and " + legacy + " is still present. Remove one of them");
        }
        if (sameFile(legacy, target) && Files.isSymbolicLink(target)) {
            // Setting the legacy file aside would leave the StateLink name dangling.
            throw new ConfigurationException("STATELINK_LEGACY_CONFIG_CONFLICT: " + target + " is a link to " + legacy
                + ". Replace the link with the real file and remove the legacy name");
        }
        if (sameContent(legacy, target)) {
            return new Result(Outcome.DUPLICATE_ARCHIVED, legacy, target, archive(legacy, ARCHIVE_SUFFIX));
        }
        if (isUntouchedTemplate(target)) {
            // An untouched template has no connection settings and can never be an enabled
            // configuration, so it carries no decision that the legacy file could contradict.
            Path archived = archive(target, TEMPLATE_SUFFIX);
            move(legacy, target);
            return new Result(Outcome.MOVED_OVER_UNUSED_TEMPLATE, legacy, target, archived);
        }
        throw new ConfigurationException("STATELINK_LEGACY_CONFIG_CONFLICT: both " + target + " and " + legacy
            + " exist with different contents, and StateLink will not guess which one is current. Keep the correct "
            + "settings in " + CONFIG_FILE + " and delete or rename " + LEGACY_CONFIG_FILE);
    }

    /**
     * Carries the "this backend ran with enable=false" marker over, so that renaming the mod can
     * never silently drop the re-activation acknowledgement. Must run before {@link DisabledMarker}
     * is consulted.
     */
    public static Result migrateDisabledMarker(Path configDirectory) {
        Path legacy = configDirectory.resolve(LEGACY_MARKER_FILE);
        Path target = configDirectory.resolve(DisabledMarker.FILE_NAME);
        if (!present(legacy)) return new Result(Outcome.NO_LEGACY_FILE, legacy, target, null);
        if (!present(target)) {
            // Renamed whatever it contains: an unreadable marker is still a marker.
            move(legacy, target);
            return new Result(Outcome.MOVED, legacy, target, null);
        }
        // A StateLink marker already guards re-activation, so the guard survives either way.
        boolean duplicate = sameContent(legacy, target);
        Path archived = archive(legacy, ARCHIVE_SUFFIX);
        return new Result(duplicate ? Outcome.DUPLICATE_ARCHIVED : Outcome.SUPERSEDED_ARCHIVED, legacy, target, archived);
    }

    /** True only for a file equal to the template StateLink writes when no configuration exists. */
    static boolean isUntouchedTemplate(Path file) {
        try {
            String template = GSON.toJson(new JsonParser().parse(GSON.toJson(new Configuration())));
            return template.equals(GSON.toJson(new JsonParser().parse(Files.readString(file))));
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Symbolic links are not followed: a dangling link still occupies the name. */
    private static boolean present(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true;
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        } catch (SecurityException denied) {
            // Falls through to the refusal below.
        }
        throw new ConfigurationException("STATELINK_LEGACY_MIGRATION_FAILED: cannot determine whether " + path
            + " exists. Check the permissions of the config directory");
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException | SecurityException unknown) {
            return false;
        }
    }

    private static boolean sameContent(Path first, Path second) {
        try {
            return Files.isRegularFile(first) && Files.isRegularFile(second) && Files.mismatch(first, second) == -1L;
        } catch (IOException | SecurityException unknown) {
            return false;
        }
    }

    /** Renames {@code source} to {@code target}, which the caller has just seen to be absent. */
    private static void move(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target);
            }
        } catch (IOException | SecurityException error) {
            // The cause is not attached: configuration diagnostics never carry more than paths.
            throw new ConfigurationException("STATELINK_LEGACY_MIGRATION_FAILED: cannot rename " + source + " to "
                + target + " (" + error.getClass().getSimpleName() + "). Rename it manually, or fix the permissions "
                + "of the config directory");
        }
        if (!present(target) || present(source)) {
            throw new ConfigurationException("STATELINK_LEGACY_MIGRATION_FAILED: renaming " + source + " to " + target
                + " did not take effect. Rename it manually");
        }
    }

    /** Sets {@code file} aside under a name that is not in use; never overwrites. */
    private static Path archive(Path file, String suffix) {
        String base = file.getFileName().toString() + suffix;
        for (int attempt = 0; attempt < MAX_ARCHIVE_NAMES; attempt++) {
            Path candidate = file.resolveSibling(attempt == 0 ? base : base + "." + attempt);
            if (present(candidate)) continue;
            try {
                Files.move(file, candidate);
                return candidate;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Try the next name.
            } catch (IOException | SecurityException error) {
                throw new ConfigurationException("STATELINK_LEGACY_MIGRATION_FAILED: cannot rename " + file + " to "
                    + candidate + " (" + error.getClass().getSimpleName() + "). Remove or rename it manually");
            }
        }
        throw new ConfigurationException("STATELINK_LEGACY_MIGRATION_FAILED: no free archive name for " + file
            + ". Remove the old " + base + "* files");
    }
}
