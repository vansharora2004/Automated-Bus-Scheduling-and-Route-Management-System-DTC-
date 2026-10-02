package com.dtc.transit.scheduling.run;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.scheduling.crew.CrewAssignmentService;
import com.dtc.transit.scheduling.engine.SchedulingEngine;
import com.dtc.transit.scheduling.engine.model.ScheduleResult;
import com.dtc.transit.scheduling.rules.RuleSetResolver;
import com.dtc.transit.scheduling.schedule.SchedulePersister;
import com.dtc.transit.scheduling.schedule.ScheduleSnapshotLoader;

/**
 * Executes one claimed run: load, schedule, persist, complete.
 *
 * <p>All of it in a single transaction. That is the guarantee behind "killing a worker mid-run leaves no partial
 * schedule": the blocks, events, assignments, conflicts and the run's own completion either all commit or none
 * of them do. Marking the run completed in a second transaction would open a window where a schedule exists and
 * the run that made it still says {@code RUNNING}.
 *
 * <p>Heartbeats are the exception, and have to be: they go through {@link RunBookkeeper} in their own transactions
 * precisely because they must be visible from outside this one.
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** Report progress every five percent. More often is noise; less often risks looking stalled. */
    public static final int HEARTBEAT_EVERY_PERCENT = 5;

    /**
     * Wall-clock ceiling for the unlinked local search, per depot-day.
     *
     * <p>30 seconds, which is the figure the plan budgets. Only unlinked runs spend it; linked mode has no search.
     */
    public static final long SEARCH_BUDGET_MILLIS = 30_000;

    private final ScheduleRunRepository runs;
    private final ScheduleSnapshotLoader snapshotLoader;
    private final SchedulePersister persister;
    private final RuleSetResolver ruleSets;
    private final RunBookkeeper bookkeeper;
    private final RunProgressBroadcaster progress;
    private final CrewAssignmentService crewAssignment;
    private final SchedulingEngine engine = new SchedulingEngine();

    public RunExecutor(
            ScheduleRunRepository runs,
            ScheduleSnapshotLoader snapshotLoader,
            SchedulePersister persister,
            RuleSetResolver ruleSets,
            RunBookkeeper bookkeeper,
            RunProgressBroadcaster progress,
            CrewAssignmentService crewAssignment) {
        this.runs = runs;
        this.snapshotLoader = snapshotLoader;
        this.persister = persister;
        this.ruleSets = ruleSets;
        this.bookkeeper = bookkeeper;
        this.progress = progress;
        this.crewAssignment = crewAssignment;
    }

    @Transactional
    public ScheduleResult execute(ScheduleRun run, String worker) {
        var rules = ruleSets.bindById(run.getRuleSetId());
        var snapshot = snapshotLoader.load(run.getDepotId(), run.getServiceDate());

        AtomicInteger lastReported = new AtomicInteger(0);
        ScheduleResult result = engine.run(
                snapshot.trips(),
                snapshot.context(),
                rules,
                run.getMode(),
                run.getSeed(),
                SEARCH_BUDGET_MILLIS,
                true,
                percent -> {
            if (percent - lastReported.get() >= HEARTBEAT_EVERY_PERCENT) {
                lastReported.set(percent);
                bookkeeper.heartbeat(run.getId(), worker, percent);
                progress.progress(run.getId(), percent);
            }
        });

        Long scheduleId = persister.persist(run.getId(), run.getDepotId(), run.getServiceDate(), result);

        // Stage 4. In the same transaction as everything else, so a schedule never exists with a half-written
        // roster: either the whole depot-day is there or none of it is.
        bookkeeper.heartbeat(run.getId(), worker, 95);
        var crew = crewAssignment.assign(scheduleId, run.getDepotId(), run.getServiceDate(), rules);

        runs.markCompleted(run.getId(), scheduleId, RunMetricsJson.of(result.metrics()));

        log.info(
                "run {} built schedule {}: {} blocks, {}/{} trips covered, {} duties, {} crew assigned, "
                        + "{} unassigned, {} hard conflicts, {} ms",
                run.getId(),
                scheduleId,
                result.metrics().blocks(),
                result.metrics().tripsCovered(),
                result.metrics().tripsTotal(),
                result.metrics().duties(),
                crew.assignedCount(),
                crew.unassigned().size(),
                result.metrics().hardConflicts(),
                result.metrics().elapsedMillis());
        return result;
    }
}
