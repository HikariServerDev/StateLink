package com.atsukigames.statelink.database;

import java.sql.Timestamp;

/** Pure policy used under the sync-state row lock with fresh database time. */
public final class CoordinationLifecycle {
    private CoordinationLifecycle() {}

    public enum State { CLEAN_RELEASED, OWNED, RECOVERY_REQUIRED }

    public static State classify(
        boolean recoveryRequired, String ownerServer, String ownerSession,
        Timestamp leaseUntil, Timestamp databaseNow
    ) {
        if (recoveryRequired) return State.RECOVERY_REQUIRED;
        if (ownerServer == null && ownerSession == null && leaseUntil == null) {
            return State.CLEAN_RELEASED;
        }
        if (ownerServer == null || ownerSession == null || leaseUntil == null
                || ownerServer.isBlank() || ownerSession.isBlank()
                || leaseUntil.before(databaseNow)) {
            return State.RECOVERY_REQUIRED;
        }
        return State.OWNED;
    }
}
