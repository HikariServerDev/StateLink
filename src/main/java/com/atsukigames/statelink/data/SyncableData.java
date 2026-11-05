package com.atsukigames.statelink.data;

public interface SyncableData {
    /**
     * Validates if the data is in a valid state for synchronization
     * @return true if the data is valid, false otherwise
     */
    boolean isValidData();
}
