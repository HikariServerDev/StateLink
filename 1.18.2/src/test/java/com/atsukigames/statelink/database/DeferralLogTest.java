package com.atsukigames.statelink.database;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DeferralLogTest {
    @Test void aRowDeferredOnEveryScanIsLoggedOnceThenSummarizedPerInterval() {
        AtomicLong now = new AtomicLong(1_000);
        DeferralLog log = new DeferralLog(60_000, now::get);
        UUID player = UUID.randomUUID();
        assertEquals(1, log.permit(player, "STALE:1:1:30"), "the first deferral is always reported");
        long logged = 0;
        // A background scan every two seconds for ten minutes.
        long summarized = 0;
        for (int scan = 1; scan <= 300; scan++) {
            now.addAndGet(2_000);
            long count = log.permit(player, "STALE:1:1:30");
            if (count > 0) { logged++; summarized += count; }
        }
        assertEquals(10, logged, "one summary per minute instead of 300 lines");
        assertEquals(300, summarized, "no deferral is hidden: every one is counted in a summary");
    }

    @Test void aChangedStateIsReportedImmediatelyAndPlayersAreIndependent() {
        AtomicLong now = new AtomicLong();
        DeferralLog log = new DeferralLog(60_000, now::get);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(1, log.permit(a, "STALE:1:1:30"));
        assertEquals(0, log.permit(a, "STALE:1:1:30"));
        assertEquals(1, log.permit(b, "STALE:1:1:30"));
        assertEquals(1, log.permit(a, "STALE:2:1:30"), "a new fence is a new state");
        assertEquals(1, log.permit(a, "CONFLICT:DOMAIN_REENABLE_REQUIRES_RECONCILIATION:2:1"));
        log.forget(a);
        assertEquals(1, log.permit(a, "CONFLICT:DOMAIN_REENABLE_REQUIRES_RECONCILIATION:2:1"));
    }
}
