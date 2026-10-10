package com.atsukigames.statelink.sync;

public final class RecoveryMessages {
    private RecoveryMessages() {}

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
