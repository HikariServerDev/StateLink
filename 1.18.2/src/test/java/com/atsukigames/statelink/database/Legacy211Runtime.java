package com.atsukigames.statelink.database;

import com.atsukigames.statelink.config.Configuration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Runs the database layer of the released PlayerDataConnector 2.1.11 artifact itself, in an
 * isolated child-first class loader, so legacy fixtures are rows that 2.1.11 really wrote into
 * a schema that 2.1.11 really created. Nothing here is produced by current code.
 */
final class Legacy211Runtime implements AutoCloseable {
    static final Path JAR = Path.of("../staging-results/2026-10-02/2.1.11/artifact/PlayerDataConnector-2.1.11.jar");
    static final String SHA256 = "1fc8a5017ed0c996776a988f1ac145ac49e803e182da083e77cfcd7c41830708";
    /** Constants 2.1.11 CompletePlayerDataSerializer wrote for domains it never implemented. */
    static final String ADVANCEMENTS = "{\"placeholder\":\"advancement_data_simplified\"}";
    static final String STATISTICS = "{\"placeholder\":\"statistics_data_simplified\"}";
    static final String RECIPE_BOOK = "{\"known_recipes\":[]}";
    /** One status effect in the JSON shape 2.1.11 serializeEffects wrote. */
    static final String EFFECTS = "[{\"effect\":\"minecraft:haste\",\"amplifier\":1,\"duration\":600,"
        + "\"ambient\":false,\"showParticles\":true,\"showIcon\":true}]";

    /** One item in the JSON shape 2.1.11 serializeInventory wrote, carrying a recognizable marker. */
    static String items(String marker) {
        return "[{\"slot\":0,\"item\":\"minecraft:paper\",\"count\":1,\"nbt\":\"{Count:1b,id:\\\"minecraft:paper\\\","
            + "tag:{marker:\\\"" + marker + "\\\"}}\"}]";
    }

    private static final String PACKAGE = "com.atsukigames.playerdataconnector.";

    private final URLClassLoader loader;
    private final Object database;
    private final Object repository;
    private final Object sync;

    Legacy211Runtime(Configuration.DatabaseConfig current, String coordinationTable) throws Exception {
        byte[] bytes = Files.readAllBytes(JAR);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!SHA256.equals(actual)) throw new IllegalStateException("not the released 2.1.11 artifact: " + actual);
        loader = new URLClassLoader(new URL[] {JAR.toUri().toURL()}, Legacy211Runtime.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(PACKAGE)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        Class<?> configClass = type("config.Configuration$DatabaseConfig");
        Object config = configClass.getConstructor().newInstance();
        for (String field : new String[] {"host", "port", "name", "username", "password", "tableName", "maxConnections"}) {
            configClass.getField(field).set(config, Configuration.DatabaseConfig.class.getField(field).get(current));
        }
        Class<?> databaseClass = type("database.DatabaseManager");
        database = databaseClass.getConstructor(configClass, String.class).newInstance(config, coordinationTable);
        if (database.getClass().getClassLoader() != loader) throw new IllegalStateException("legacy classes leaked");
        repository = type("database.PlayerDataRepository").getConstructor(databaseClass).newInstance(database);
        sync = type("config.Configuration$SyncConfig").getConstructor().newInstance();
    }

    /** The jar really contains the placeholder constants used above. */
    static void assertPlaceholderConstantsComeFromTheArtifact() throws Exception {
        try (var zip = new java.util.zip.ZipFile(JAR.toFile())) {
            var entry = zip.getEntry("com/atsukigames/playerdataconnector/utils/CompletePlayerDataSerializer.class");
            String constantPool = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String constant : new String[] {"advancement_data_simplified", "statistics_data_simplified", "known_recipes", "placeholder"}) {
                if (!constantPool.contains(constant)) throw new AssertionError("2.1.11 artifact lacks constant " + constant);
            }
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(PACKAGE + name, true, loader); }

    void setSync(String field, boolean value) throws Exception { sync.getClass().getField(field).setBoolean(sync, value); }

    private boolean syncFlag(String field) throws Exception { return sync.getClass().getField(field).getBoolean(sync); }

    /** 2.1.11 acquire; returns its Ownership record. */
    Object acquire(UUID uuid, String server, long leaseMs) throws Exception {
        Object result = repository.getClass().getMethod("acquireOwnershipAndLoad", UUID.class, String.class, UUID.class, long.class)
            .invoke(repository, uuid, server, UUID.randomUUID(), leaseMs);
        Object ownership = result.getClass().getMethod("ownership").invoke(result);
        if (ownership == null) throw new IllegalStateException("2.1.11 acquire refused: " + result);
        return ownership;
    }

    /** 2.1.11 checkpoint or final save of the values its coordinator would have extracted. */
    void save(Object ownership, UUID uuid, String name, String inventory, double health, int food, int xpLevel,
        boolean finalFlush) throws Exception {
        Class<?> dataClass = type("database.PlayerDataRepository$CompletePlayerData");
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : dataClass.getConstructors()) if (candidate.getParameterCount() == 35) constructor = candidate;
        if (constructor == null) throw new IllegalStateException("2.1.11 CompletePlayerData constructor not found");
        // Mirrors 2.1.11 PlayerSyncCoordinator.extractCompletePlayerData for each switch.
        Object data = constructor.newInstance(uuid, name,
            syncFlag("inventory") ? inventory : null, syncFlag("enderchest") ? items("ender-" + name) : null,
            syncFlag("armor") ? items("armor-" + name) : null, syncFlag("offhand") ? items("offhand-" + name) : null,
            health, food, 5.0f, 0.0f, 300, xpLevel, 7, 7.0f, 0.25f,
            syncFlag("effects") ? EFFECTS : null,
            syncFlag("position") ? "minecraft:the_nether" : "minecraft:overworld",
            syncFlag("position") ? 12.5 : 0.0, syncFlag("position") ? 70.0 : 64.0, syncFlag("position") ? -3.5 : 0.0,
            syncFlag("position") ? 41.0f : 0.0f, syncFlag("position") ? 12.0f : 0.0f,
            syncFlag("gamemode") ? "adventure" : "survival", false, false, false,
            name, syncFlag("playerProfile") ? "{\"name\":\"" + name + "\",\"uuid\":\"" + uuid + "\"}" : null, null,
            syncFlag("advancements") ? ADVANCEMENTS : null, syncFlag("statistics") ? STATISTICS : null,
            syncFlag("recipeBook") ? RECIPE_BOOK : null, 3, 0L, 1);
        Method save = null;
        for (Method candidate : repository.getClass().getMethods()) {
            if (candidate.getName().equals("saveCompletePlayerData") && candidate.getParameterCount() == 5) save = candidate;
        }
        Object result = save.invoke(repository, data, ownership, sync, 5_000L, finalFlush);
        if (!"COMMITTED".equals(String.valueOf(result.getClass().getMethod("status").invoke(result)))) {
            throw new IllegalStateException("2.1.11 save not committed: " + result);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void markRecoveryRequired(Object ownership, String reason) throws Exception {
        Class reasonClass = type("database.PlayerDataRepository$RecoveryReason");
        Object marked = repository.getClass().getMethod("markRecoveryRequired", ownership.getClass(), reasonClass)
            .invoke(repository, ownership, Enum.valueOf(reasonClass, reason));
        if (!Boolean.TRUE.equals(marked)) throw new IllegalStateException("2.1.11 did not mark recovery");
    }

    @Override public void close() throws Exception {
        database.getClass().getMethod("close").invoke(database);
        loader.close();
    }
}
