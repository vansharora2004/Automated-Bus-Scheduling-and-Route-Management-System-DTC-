package com.dtc.transit.scheduling.run;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ScheduleRunRepository runs;
    private final ScheduleSnapshotLoader snapshotLoader;
    private final SchedulePersister persister;
    private final RuleSetResolver ruleSets;
    private final RunBookkeeper bookkeeper;
    private final RunProgressBroadcaster progress;
    private final SchedulingEngine engine = new SchedulingEngine();

    public RunExecutor(
            ScheduleRunRepository runs,
            ScheduleSnapshotLoader snapshotLoader,
            SchedulePersister persister,
            RuleSetResolver ruleSets,
            RunBookkeeper bookkeeper,
            RunProgressBroadcaster progress) {
        this.runs = runs;
        this.snapshotLoader = snapshotLoader;
        this.persister = persister;
        this.ruleSets = ruleSets;
        this.bookkeeper = bookkeeper;
        this.progress = progress;
    }

    @Transactional
    public ScheduleResult execute(ScheduleRun run, String worker) {
        var rules = ruleSets.bindById(run.getRuleSetId());
        var snapshot = snapshotLoader.load(run.getDepotId(), run.getServiceDate());

        AtomicInteger lastReported = new AtomicInteger(0);
        ScheduleResult result = engine.run(snapshot.trips(), snapshot.context(), rules, run.getMode(), true, percent -> {
            if (percent - lastReported.get() >= HEARTBEAT_EVERY_PERCENT) {
                lastReported.set(percent);
                bookkeeper.heartbeat(run.getId(), worker, percent);
                progress.progress(run.getId(), percent);
            }
        });

        Long scheduleId = persister.persist(run.getId(), run.getDepotId(), run.getServiceDate(), result);
        runs.markCompleted(run.getId(), scheduleId, RunMetricsJson.of(result.metrics()));

        log.info(
                "run {} built schedule {}: {} blocks, {}/{} trips covered, {} hard conflicts, {} ms",
                run.getId(),
                scheduleId,
                result.metrics().blocks(),
                result.metrics().tripsCovered(),
                result.metrics().tripsTotal(),
                result.metrics().hardConflicts(),
                result.metrics().elapsedMillis());
        return result;
    }
}
