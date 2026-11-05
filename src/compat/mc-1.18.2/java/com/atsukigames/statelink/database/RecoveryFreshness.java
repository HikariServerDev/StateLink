package com.atsukigames.statelink.database;

import java.sql.Timestamp;

/** Freshness at failure is distinct from downtime. Never grants ownership or verifies provenance. */
public final class RecoveryFreshness {
    private RecoveryFreshness() {}

    /**
     * Use the earliest durable uncertainty boundary, not the time a late scanner noticed the row.
     * All timestamps come from the locked coordination row and the database clock. Missing/future
     * boundaries cannot manufacture historical evidence; retain the existing current-time check.
     */
    public static Timestamp failureReference(Timestamp now, Timestamp requiredAt, Timestamp leaseUntil) {
        Timestamp reference = now;
        if (requiredAt != null && requiredAt.before(reference)) reference = requiredAt;
        if (leaseUntil != null && leaseUntil.before(reference)) reference = leaseUntil;
        return reference;
    }

    public static boolean fresh(Timestamp evidenceAt, Timestamp reference, int maxAgeSeconds) {
        if (evidenceAt == null || reference == null || maxAgeSeconds <= 0) return false;
        long age = reference.getTime() - evidenceAt.getTime();
        return age >= 0 && age <= maxAgeSeconds * 1000L;
    }

    /** Preserve fresh-now eligibility, or accept evidence that was fresh at the recorded failure. */
    public static boolean eligible(Timestamp evidenceAt, Timestamp now, Timestamp failure, int maxAgeSeconds) {
        return fresh(evidenceAt, now, maxAgeSeconds) || fresh(evidenceAt, failure, maxAgeSeconds);
    }
}
