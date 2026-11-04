package com.atsukigames.statelink.config;

public class DatabaseConfig {
    private String host = "127.0.0.1";
    private int port = 3306;
    private String databaseName = "playerdatasync";
    private String tableName = "player_data";
    private String username = "root";
    private String password = "password";
    private boolean sslEnabled = false;
    private int maxConnections = 10;
    private int connectionTimeout = 30000;

    // Synchronization settings based on MySQL Inventory Bridge
    private boolean syncInventory = true;
    private boolean syncEnderChest = true;
    private boolean syncHealth = true;
    private boolean syncFoodLevel = true;
    private boolean syncExperience = true;
    private boolean syncEffects = true;
    private boolean syncArmor = true;
    private boolean syncOffhand = true;
    
    // Database maintenance
    private boolean maintenanceEnabled = false;
    private int inactivityDays = 60;
    
    // Save task settings
    private boolean saveDataTaskEnabled = true;
    private int saveIntervalMinutes = 3;
    /** Legacy field retained for JSON compatibility; the active coordinator never waits on it. */
    @Deprecated
    private int loginSyncDelayMs = 0;

    // Getters and Setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isSslEnabled() { return sslEnabled; }
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }

    public boolean isSyncInventory() { return syncInventory; }
    public void setSyncInventory(boolean syncInventory) { this.syncInventory = syncInventory; }

    public boolean isSyncEnderChest() { return syncEnderChest; }
    public void setSyncEnderChest(boolean syncEnderChest) { this.syncEnderChest = syncEnderChest; }

    public boolean isSyncHealth() { return syncHealth; }
    public void setSyncHealth(boolean syncHealth) { this.syncHealth = syncHealth; }

    public boolean isSyncFoodLevel() { return syncFoodLevel; }
    public void setSyncFoodLevel(boolean syncFoodLevel) { this.syncFoodLevel = syncFoodLevel; }

    public boolean isSyncExperience() { return syncExperience; }
    public void setSyncExperience(boolean syncExperience) { this.syncExperience = syncExperience; }

    public boolean isSyncEffects() { return syncEffects; }
    public void setSyncEffects(boolean syncEffects) { this.syncEffects = syncEffects; }

    public boolean isSyncArmor() { return syncArmor; }
    public void setSyncArmor(boolean syncArmor) { this.syncArmor = syncArmor; }

    public boolean isSyncOffhand() { return syncOffhand; }
    public void setSyncOffhand(boolean syncOffhand) { this.syncOffhand = syncOffhand; }

    public boolean isMaintenanceEnabled() { return maintenanceEnabled; }
    public void setMaintenanceEnabled(boolean maintenanceEnabled) { this.maintenanceEnabled = maintenanceEnabled; }

    public int getInactivityDays() { return inactivityDays; }
    public void setInactivityDays(int inactivityDays) { this.inactivityDays = inactivityDays; }

    public boolean isSaveDataTaskEnabled() { return saveDataTaskEnabled; }
    public void setSaveDataTaskEnabled(boolean saveDataTaskEnabled) { this.saveDataTaskEnabled = saveDataTaskEnabled; }

    public int getSaveIntervalMinutes() { return saveIntervalMinutes; }
    public void setSaveIntervalMinutes(int saveIntervalMinutes) { this.saveIntervalMinutes = saveIntervalMinutes; }

    public int getLoginSyncDelayMs() { return loginSyncDelayMs; }
    public void setLoginSyncDelayMs(int loginSyncDelayMs) { this.loginSyncDelayMs = loginSyncDelayMs; }

    public String getJdbcUrl() {
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&autoReconnect=true&allowPublicKeyRetrieval=true", 
                            host, port, databaseName, sslEnabled);
    }
}
