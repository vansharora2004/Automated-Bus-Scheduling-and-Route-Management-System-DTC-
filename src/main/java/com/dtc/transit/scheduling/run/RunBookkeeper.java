package com.dtc.transit.scheduling.run;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The run queue's bookkeeping, in transactions of its own.
 *
 * <p>A separate bean on purpose. Every method here has to commit independently of the run it describes: a
 * heartbeat must become visible to the reaper while the run's own transaction is still open, and a failure must
 * survive the rollback that caused it. Spring's transaction annotations only apply through the proxy, so putting
 * these methods on the worker itself would make every one of them silently join the caller's transaction — which
 * is precisely the bug that made the Phase 2 lockout counter never increment.
 */
@Service
public class RunBookkeeper {

    private static final Logger log = LoggerFactory.getLogger(RunBookkeeper.class);

    /**
     * How long a run may go without a heartbeat before the reaper takes it.
     *
     * <p>Two minutes. Long enough that a slow stage is not mistaken for a dead worker, short enough that a
     * crashed worker does not hold a depot-day slot for an operator's whole morning.
     */
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(2);

    private final ScheduleRunRepository runs;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RunBookkeeper(ScheduleRunRepository runs, JdbcTemplate jdbc, Clock clock) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Claims the oldest queued run, committing the claim before any work starts.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes several workers safe without a distributed lock: each one
     * takes a row nobody else holds and skips past locked rows instead of queueing behind them. A plain
     * {@code SELECT} followed by an {@code UPDATE} would let two workers read the same run and both start it.
     *
     * <p>One statement, so the claim and the status change cannot be separated by a crash. Written with
     * {@code JdbcTemplate} rather than a repository method because an {@code UPDATE ... RETURNING} is not a JPA
     * concept, and Spring Data refuses a modifying query that returns anything but void or a row count.
     */
    @Transactional
    public Optional<UUID> claimNext(String worker) {
        List<UUID> claimed = jdbc.queryForList(
                """
                UPDATE schedule_run
                SET status = 'RUNNING', claimed_by = ?, heartbeat_at = now(), started_at = now()
                WHERE id = (
                  SELECT id FROM schedule_run
                  WHERE status = 'QUEUED'
                  ORDER BY created_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1)
                RETURNING id
                """,
                UUID.class,
                worker);
        return claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.get(0));
    }

    /**
     * Records progress and liveness.
     *
     * <p>{@code REQUIRES_NEW} because the point is to be visible from outside the run's transaction. Joining it
     * would make every heartbeat invisible until the run finished, by which time it is pointless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(UUID runId, String worker, int percent) {
        int updated = runs.heartbeat(runId, worker, percent);
        if (updated == 0) {
            // The run is no longer ours: the reaper gave up on it, or it was cancelled. Worth a line in the log,
            // because the work being done from here on will be thrown away.
            log.warn("heartbeat for run {} by {} matched no running row", runId, worker);
        }
    }

    /**
     * Marks a run failed, in its own transaction.
     *
     * <p>Called while the run's transaction is rolling back, so it cannot share it: the failure record would roll
     * back too and the run would sit at {@code RUNNING} until the reaper noticed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, String error) {
        try {
            runs.markFailed(runId, error);
        } catch (Exception e) {
            log.error("could not record the failure of run {}", runId, e);
        }
    }

    /**
     * Fails runs whose worker has gone quiet.
     *
     * <p>There is nothing to clean up besides the status. A run writes its schedule in one transaction, so a
     * worker killed mid-run committed nothing and left no partial rows behind.
     *
     * @return how many runs were reaped
     */
    @Transactional
    public int reapStaleRuns() {
        int reaped = runs.reapStaleRuns(clock.instant().minus(HEARTBEAT_TIMEOUT));
        if (reaped > 0) {
            log.warn("reaped {} run(s) whose worker stopped reporting", reaped);
        }
        return reaped;
    }
}
