package com.atsukigames.statelink.sync;

import com.atsukigames.statelink.database.PlayerDataRepository.RecoveryResolutionStatus;

public final class RecoveryMessages {
    private RecoveryMessages() {}

    /** A new login cannot repair these results. Keep transient CAS/live-owner conflicts retryable. */
    public static boolean requiresAdministrator(RecoveryResolutionStatus status) {
        return status == RecoveryResolutionStatus.STALE_CHECKPOINT
            || status == RecoveryResolutionStatus.MISSING_CHECKPOINT
            || status == RecoveryResolutionStatus.ATTEMPTS_EXHAUSTED
            || status == RecoveryResolutionStatus.DOMAIN_STILL_REQUIRES_RECONCILIATION;
    }

    public static String refusal(String reason, boolean operatorOnly, boolean automaticPolicy,
            RecoveryResolutionStatus status) {
        if (requiresAdministrator(status)) {
            String detail = switch (status) {
                case STALE_CHECKPOINT -> "No sufficiently fresh checkpoint or matching state attestation is available.";
                case MISSING_CHECKPOINT -> "No committed player checkpoint is available.";
                case ATTEMPTS_EXHAUSTED -> "The automatic recovery attempt limit has been reached.";
                default -> "Domain reconciliation is incomplete.";
            };
            return detail + " " + refusal(reason, true, automaticPolicy);
        }
        return refusal(reason, operatorOnly, automaticPolicy);
    }

    /** Player-facing refusal: a RecoveryReason name only, never SQL, hosts or session identifiers. */
    public static String refusal(String reason, boolean operatorOnly, boolean automaticPolicy) {
        String shown = reason == null || !reason.matches("[A-Z_]{1,64}") ? "UNSPECIFIED" : reason;
        if (operatorOnly) {
            return "Synchronized player data requires administrator reconciliation: " + shown
                + ". Reconnecting will not resolve this; please contact an administrator.";
        }
        if (automaticPolicy) {
            return "Synchronized player data requires recovery: " + shown
                + ". Automatic recovery has not completed yet and may be refused; "
                + "you may retry later, and contact an administrator if this persists.";
        }
        return "Synchronized player data requires recovery: " + shown + ". Please contact an administrator.";
    }
}
