package com.atsukigames.statelink.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Diagnostic-only monotonic timing for one repository operation.
 *
 * <p>The trace is intentionally kept outside the correctness path.  It does
 * not change transaction boundaries, timeout values, or ownership decisions;
 * it only records the boundaries needed to distinguish executor/pool delay
 * from time spent inside MySQL.</p>
 */
public final class DatabaseOperationTrace {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseOperationTrace.class);

    private final boolean enabled;
    private final String operation;
    private final UUID uuid;
    private final String session;
    private final long fence;
    private final long revision;
    private final Supplier<DatabaseManager.PoolMetrics> poolMetrics;
    private final long startedNanos = System.nanoTime();
    private final Map<String, Long> phaseStarts = new HashMap<>();
    private final Map<String, Long> phaseDurations = new HashMap<>();

    DatabaseOperationTrace(
        boolean enabled,
        String operation,
        UUID uuid,
        String session,
        long fence,
        long revision,
        Supplier<DatabaseManager.PoolMetrics> poolMetrics
    ) {
        this.enabled = enabled;
        this.operation = operation;
        this.uuid = uuid;
        this.session = session == null ? "none" : session;
        this.fence = fence;
        this.revision = revision;
        this.poolMetrics = poolMetrics;
    }

    public void start(String phase) {
        if (enabled) phaseStarts.put(phase, System.nanoTime());
    }

    public void end(String phase) {
        if (!enabled) return;
        Long start = phaseStarts.remove(phase);
        if (start != null) {
            phaseDurations.merge(phase, System.nanoTime() - start, Long::sum);
        }
    }

    public void finish(String outcome, Throwable failure) {
        if (!enabled) return;
        long finishedNanos = System.nanoTime();
        for (Map.Entry<String, Long> entry : phaseStarts.entrySet()) {
            phaseDurations.merge(entry.getKey(), finishedNanos - entry.getValue(), Long::sum);
        }
        phaseStarts.clear();
        long totalNanos = finishedNanos - startedNanos;
        DatabaseManager.PoolMetrics pool = safePoolMetrics();
        SqlFailure sqlFailure = SqlFailure.from(failure);
        LOGGER.info(
            "STATELINK_DB_TRACE operation={} uuid={} session={} fence={} revision={} thread={} "
                + "totalMs={} connectionAcquisitionMs={} transactionMs={} selectForUpdateMs={} "
                + "playerDataReadMs={} playerDataWriteMs={} ownershipUpdateMs={} commitMs={} "
                + "poolActive={} poolIdle={} poolTotal={} poolWaiting={} outcome={} "
                + "sqlState={} vendorCode={} failureType={}",
            operation, uuid, session, fence, revision, Thread.currentThread().getName(),
            millis(totalNanos), millis(phase("connectionAcquisition")), millis(phase("transaction")),
            millis(phase("selectForUpdate")), millis(phase("playerDataRead")),
            millis(phase("playerDataWrite")), millis(phase("ownershipUpdate")),
            millis(phase("commit")), pool.active(), pool.idle(), pool.total(), pool.waiting(),
            outcome, sqlFailure.sqlState(), sqlFailure.vendorCode(), sqlFailure.failureType());
    }

    private long phase(String name) {
        return phaseDurations.getOrDefault(name, 0L);
    }

    private DatabaseManager.PoolMetrics safePoolMetrics() {
        try {
            return poolMetrics.get();
        } catch (RuntimeException ignored) {
            return DatabaseManager.PoolMetrics.unavailable();
        }
    }

    private static long millis(long nanos) {
        return nanos <= 0 ? 0L : nanos / 1_000_000L;
    }

    private record SqlFailure(String sqlState, int vendorCode, String failureType) {
        private static SqlFailure from(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof SQLException sqlException) {
                    return new SqlFailure(
                        sqlException.getSQLState() == null ? "none" : sqlException.getSQLState(),
                        sqlException.getErrorCode(),
                        sqlException.getClass().getSimpleName());
                }
                current = current.getCause();
            }
            return failure == null
                ? new SqlFailure("none", 0, "none")
                : new SqlFailure("none", 0, failure.getClass().getSimpleName());
        }
    }
}
