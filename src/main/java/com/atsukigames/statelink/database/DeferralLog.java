package com.atsukigames.statelink.database;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate limit for "nothing changed, still deferred" messages. A row that policy keeps deferring is
 * re-evaluated by every background scan and login retry; without a bound it would be logged every
 * few seconds forever. The first deferral, any change of state, and one summary per interval are
 * logged, each carrying the number of deferrals it stands for.
 */
public final class DeferralLog {
    private record Entry(String state, long lastLoggedMillis, long suppressed) {}

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final long intervalMillis;
    private final LongSupplier clock;

    public DeferralLog(long intervalMillis) { this(intervalMillis, System::currentTimeMillis); }

    DeferralLog(long intervalMillis, LongSupplier clock) {
        this.intervalMillis = intervalMillis;
        this.clock = clock;
    }

    /** Returns 0 to stay silent, otherwise the number of deferrals (including this one) since the last message. */
    public long permit(UUID uuid, String state) {
        long now = clock.getAsLong();
        long[] result = new long[1];
        entries.compute(uuid, (ignored, previous) -> {
            if (previous == null || !previous.state().equals(state)) {
                result[0] = 1;
                return new Entry(state, now, 0);
            }
            long suppressed = previous.suppressed() + 1;
            if (now - previous.lastLoggedMillis() >= intervalMillis) {
                result[0] = suppressed;
                return new Entry(state, now, 0);
            }
            return new Entry(state, previous.lastLoggedMillis(), suppressed);
        });
        return result[0];
    }

    public void forget(UUID uuid) { entries.remove(uuid); }
}
