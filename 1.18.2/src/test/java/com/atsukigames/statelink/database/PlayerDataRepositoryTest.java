package com.atsukigames.statelink.database;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataRepositoryTest {
    @Test
    void deadlockAndLockTimeoutAreRetryableWholeTransactions() {
        assertTrue(PlayerDataRepository.isRetryableTransactionError(
            new SQLException("deadlock", "40001", 1213)));
        assertTrue(PlayerDataRepository.isRetryableTransactionError(
            new SQLException("lock wait timeout", "HY000", 1205)));
        assertFalse(PlayerDataRepository.isRetryableTransactionError(
            new SQLException("bad credentials", "28000", 1045)));
    }
}
