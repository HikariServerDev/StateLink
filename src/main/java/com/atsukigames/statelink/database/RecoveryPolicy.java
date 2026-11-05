package com.atsukigames.statelink.database;
import java.util.Set;
/** Unknown/inconsistent ownership is never automatically resolved, even with opt-in. */
public final class RecoveryPolicy {
    private RecoveryPolicy() {}
    private static final Set<String> AUTOMATIC_REASONS = Set.of(
        "FINAL_COMMIT_ACK_UNKNOWN", "LOCAL_AUTHORITY_LOST", "LEASE_EXPIRED_UNCLEAN",
        "SERVER_CRASH", "SHUTDOWN_TIMEOUT", "FINAL_SAVE_FAILED", "WORLD_MUTATION_UNCERTAIN",
        "DIRTY_DISCONNECT_WITHOUT_SAVE", "SESSION_ABANDONED");
    public static boolean allowsAutomatic(String reason) { return reason != null && AUTOMATIC_REASONS.contains(reason); }
}
