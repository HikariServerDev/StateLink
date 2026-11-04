package com.atsukigames.statelink.sync;

import org.junit.jupiter.api.Test;
import com.atsukigames.statelink.database.PlayerDataRepository.RecoveryResolutionStatus;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryMessagesTest {
    @Test void unrecoverableCheckpointDoesNotPromiseThatReconnectingWillRepairIt() {
        for (var status : new RecoveryResolutionStatus[] {RecoveryResolutionStatus.STALE_CHECKPOINT,
                RecoveryResolutionStatus.MISSING_CHECKPOINT, RecoveryResolutionStatus.ATTEMPTS_EXHAUSTED}) {
            assertTrue(RecoveryMessages.requiresAdministrator(status));
            String message = RecoveryMessages.refusal("LOCAL_AUTHORITY_LOST", false, true, status);
            assertTrue(message.contains("Reconnecting will not resolve this"));
            assertTrue(message.contains("administrator"));
        }
    }

    @Test void retryableRecoveryOutcomesStillWaitInsteadOfRefusingLoginImmediately() {
        for (var status : new RecoveryResolutionStatus[] {null, RecoveryResolutionStatus.CONFLICT,
                RecoveryResolutionStatus.WAITING, RecoveryResolutionStatus.ATTEMPT_RESERVED}) {
            assertFalse(RecoveryMessages.requiresAdministrator(status));
            assertTrue(RecoveryMessages.refusal("LEASE_EXPIRED_UNCLEAN", false, true, status).contains("may be refused"));
        }
    }
    @Test void operatorOnlyReasonNamesTheCauseAndDoesNotSuggestReconnecting() {
        String message = RecoveryMessages.refusal("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION", true, true);
        assertTrue(message.contains("DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION"));
        assertTrue(message.contains("Reconnecting will not resolve this"));
    }

    @Test void automaticPolicyDoesNotPromiseThatRetryingSucceeds() {
        String message = RecoveryMessages.refusal("LEASE_EXPIRED_UNCLEAN", false, true);
        assertTrue(message.contains("LEASE_EXPIRED_UNCLEAN"));
        assertTrue(message.contains("may be refused"));
    }

    @Test void manualPolicyNamesTheReason() {
        assertTrue(RecoveryMessages.refusal("LOCAL_AUTHORITY_LOST", false, false).contains("LOCAL_AUTHORITY_LOST"));
    }

    @Test void anythingThatIsNotAReasonNameIsNeverEchoedToThePlayer() {
        for (String unsafe : new String[] {null, "", "jdbc:mysql://db/secret", "Access denied for user 'x'", "a".repeat(80)}) {
            String message = RecoveryMessages.refusal(unsafe, false, false);
            assertTrue(message.contains("UNSPECIFIED"), message);
            assertFalse(message.contains("jdbc"));
            assertFalse(message.contains("Access denied"));
        }
    }
}
