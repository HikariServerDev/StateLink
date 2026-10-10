package com.atsukigames.statelink.config;

import com.atsukigames.statelink.StateLink;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigManager {
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "statelink.json";
    private static final Path CONFIG_PATH = Paths.get(CONFIG_DIR, CONFIG_FILE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 設定ファイルを読み込む（存在しない場合はデフォルト設定を作成）
     */
    public static DatabaseConfig loadConfig() {
        try {
            // 設定ディレクトリが存在しない場合は作成
            Files.createDirectories(CONFIG_PATH.getParent());

            File configFile = CONFIG_PATH.toFile();
            
            if (!configFile.exists()) {
                // デフォルト設定を作成
                DatabaseConfig defaultConfig = createDefaultConfig();
                saveConfig(defaultConfig);
                StateLink.LOGGER.info("Default configuration created at {}", CONFIG_PATH);
                return defaultConfig;
            }

            // 設定ファイルを読み込み
            try (FileReader reader = new FileReader(configFile)) {
                DatabaseConfig config = GSON.fromJson(reader, DatabaseConfig.class);
                StateLink.LOGGER.info("Configuration loaded from {}", CONFIG_PATH);
                return config;
            }

        } catch (IOException e) {
            StateLink.LOGGER.error("Failed to load configuration: {}", e.getMessage());
            return createDefaultConfig();
        }
    }

    /**
     * 設定をファイルに保存
     */
    public static void saveConfig(DatabaseConfig config) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(config, writer);
                StateLink.LOGGER.info("Configuration saved to {}", CONFIG_PATH);
            }

        } catch (IOException e) {
            StateLink.LOGGER.error("Failed to save configuration: {}", e.getMessage());
        }
    }

    /**
     * デフォルト設定を作成
     */
    private static DatabaseConfig createDefaultConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setHost("localhost");
        config.setPort(3306);
        config.setDatabaseName("playerdatasync");
        config.setTableName("player_data");
        config.setUsername("minecraft");
        config.setPassword("password");
        
        // 同期設定
        config.setSyncInventory(true);
        config.setSyncEnderChest(true);
        config.setSyncArmor(true);
        config.setSyncOffhand(true);
        config.setSyncHealth(true);
        config.setSyncFoodLevel(true);
        config.setSyncExperience(true);
        config.setSyncEffects(true);
        
        // Legacy configuration object is no longer used by the active
        // coordinator. Keep the field only for compatibility, with no delay.
        config.setLoginSyncDelayMs(0);
        
        return config;
    }
}
