package com.atsukigames.statelink.database;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.data.PlayerData;

import java.util.Optional;
import java.util.UUID;

public class PlayerDataDAO {
    private final String tableName;

    public PlayerDataDAO(String tableName) {
        this.tableName = tableName;
    }

    public Optional<PlayerData> getPlayerData(UUID playerUuid) {
        StateLink.LOGGER.info("Getting player data for {} (temporary implementation)", playerUuid);
        return Optional.empty(); // 一時的な実装
    }

    public void savePlayerData(PlayerData data) {
        StateLink.LOGGER.info("Saving player data for {} (temporary implementation)", data.getUsername());
        // 一時的な実装：実際の保存は後で追加
    }

    public void deletePlayerData(UUID playerUuid) {
        StateLink.LOGGER.info("Deleting player data for {} (temporary implementation)", playerUuid);
        // 一時的な実装
    }

    public boolean playerExists(UUID playerUuid) {
        StateLink.LOGGER.info("Checking if player exists: {} (temporary implementation)", playerUuid);
        return false; // 一時的な実装
    }
}
