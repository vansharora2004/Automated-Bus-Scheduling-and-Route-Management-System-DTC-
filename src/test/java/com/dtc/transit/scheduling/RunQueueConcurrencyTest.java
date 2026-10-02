package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.scheduling.run.RunBookkeeper;
import com.dtc.transit.scheduling.run.RunStatus;
import com.dtc.transit.scheduling.run.ScheduleRunRepository;
import com.dtc.transit.support.PostgisContainerTest;

/**
 * The queue under contention, and recovery when a worker dies.
 *
 * <p>These are the properties that cannot be checked by reading the code. Whether {@code SKIP LOCKED} really
 * stops two workers taking one run, and whether a killed worker's run is recovered rather than holding its
 * depot-day slot forever, are facts about PostgreSQL's behaviour, not about Java.
 *
 * <p>Runs are inserted directly rather than through the service, because the subject here is the queue, not the
 * endpoint: building a schedulable depot-day for each of them would make the test slower and no more convincing.
 */
class RunQueueConcurrencyTest extends PostgisContainerTest {

    @Autowired
    private RunBookkeeper bookkeeper;

    @Autowired
    private ScheduleRunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.dtc.transit.support.SecurityTestSupport support;

    private Long depotId;
    private Long ruleSetId;

    @BeforeEach
    void setUp() {
        // The shared reset rather than a hand-rolled cleanup. Deleting depots means deleting everything that
        // references them, and duplicating that foreign-key order here is how this test passed alone and failed
        // in the suite the moment another class left a route behind.
        support.reset();
        depotId = jdbc.queryForObject(
                """
                INSERT INTO depot (code, name, location)
                VALUES ('QUEUE-DPT', 'Queue depot', ST_SetSRID(ST_MakePoint(77.2, 28.6), 4326))
                RETURNING id
                """,
                Long.class);
        ruleSetId = jdbc.queryForObject(
                "SELECT id FROM rule_set WHERE depot_id IS NULL ORDER BY effective_from DESC LIMIT 1", Long.class);
    }

    @Test
    @DisplayName("two workers racing for one run: exactly one gets it")
    void skipLockedGivesOneRunToOneWorker() throws Exception {
        UUID runId = insertQueuedRun(LocalDate.of(2026, 6, 1));

        // Eight threads claiming at once. Without SKIP LOCKED they would either all read the same QUEUED row and
        // all start it, or serialise behind one lock and each pick it up in turn.
        List<Optional<UUID>> claims =
                inParallel(8, () -> bookkeeper.claimNext("worker-" + Thread.currentThread().threadId()));

        List<UUID> won = claims.stream().filter(Optional::isPresent).map(Optional::get).toList();
        assertThat(won).containsExactly(runId);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("several queued runs are shared out, never handed to two workers")
    void queuedRunsAreSharedOut() throws Exception {
        Set<UUID> queued = IntStream.range(0, 5)
                .mapToObj(i -> insertQueuedRun(LocalDate.of(2026, 6, 1).plusDays(i)))
                .collect(Collectors.toSet());

        List<Optional<UUID>> claims =
                inParallel(5, () -> bookkeeper.claimNext("worker-" + Thread.currentThread().threadId()));

        List<UUID> won = claims.stream().filter(Optional::isPresent).map(Optional::get).toList();
        // All five claimed, each exactly once. A duplicate here would mean two workers building the same schedule.
        assertThat(won).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(queued);
    }

    @Test
    @DisplayName("an empty queue hands out nothing rather than blocking")
    void emptyQueueReturnsEmpty() {
        assertThat(bookkeeper.claimNext("lonely-worker")).isEmpty();
    }

    @Test
    @DisplayName("the reaper fails a run whose worker stopped reporting")
    void reaperRecoversALostRun() {
        UUID runId = insertQueuedRun(LocalDate.of(2026, 6, 1));
        bookkeeper.claimNext("doomed-worker");

        // Backdate the heartbeat past the timeout, which is what a killed worker looks like from outside.
        jdbc.update(
                "UPDATE schedule_run SET heartbeat_at = now() - INTERVAL '5 minutes' WHERE id = ?", runId);

        assertThat(bookkeeper.reapStaleRuns()).isEqualTo(1);

        var reaped = runs.findById(runId).orElseThrow();
        assertThat(reaped.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(reaped.getError()).isEqualTo("WORKER_LOST");
    }

    @Test
    @DisplayName("a reaped run leaves no schedule behind")
    void reapedRunLeavesNoPartialSchedule() {
        UUID runId = insertQueuedRun(LocalDate.of(2026, 6, 1));
        bookkeeper.claimNext("doomed-worker");
        jdbc.update("UPDATE schedule_run SET heartbeat_at = now() - INTERVAL '5 minutes' WHERE id = ?", runId);

        bookkeeper.reapStaleRuns();

        // There is nothing to clean up, and that is the point: a run writes everything in one transaction, so a
        // worker killed mid-run committed nothing. The reaper only has to fix the run's own status.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM schedule WHERE run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    @DisplayName("the reaper leaves a healthy run alone")
    void healthyRunSurvivesTheReaper() {
        UUID runId = insertQueuedRun(LocalDate.of(2026, 6, 1));
        bookkeeper.claimNext("busy-worker");

        // A run that is merely slow must not be killed; the heartbeat is what distinguishes slow from dead.
        assertThat(bookkeeper.reapStaleRuns()).isZero();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("a heartbeat from a worker that no longer owns the run changes nothing")
    void staleWorkerCannotResurrectARun() {
        UUID runId = insertQueuedRun(LocalDate.of(2026, 6, 1));
        bookkeeper.claimNext("original-worker");
        jdbc.update("UPDATE schedule_run SET heartbeat_at = now() - INTERVAL '5 minutes' WHERE id = ?", runId);
        bookkeeper.reapStaleRuns();

        // The original worker is still running, oblivious. Its heartbeat must not take the run back, or two
        // workers could end up believing they own the same depot-day.
        bookkeeper.heartbeat(runId, "original-worker", 50);

        var run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getProgress()).isZero();
    }

    @Test
    @DisplayName("the one-active-run index refuses a second queued run for the same depot-day")
    void databaseRefusesASecondActiveRun() {
        LocalDate serviceDate = LocalDate.of(2026, 6, 1);
        insertQueuedRun(serviceDate);

        // The index is the guarantee behind the service's own check: two requests arriving in the same instant
        // both pass the application check, and one of them has to lose here.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> insertQueuedRun(serviceDate)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the slot frees up once the run finishes")
    void finishedRunFreesTheDepotDay() {
        LocalDate serviceDate = LocalDate.of(2026, 6, 1);
        UUID first = insertQueuedRun(serviceDate);
        // Through the bookkeeper, which owns the transaction. The repository's bulk update flushes the
        // persistence context and cannot be called from outside one.
        bookkeeper.fail(first, "done with it");

        // The index covers only QUEUED and RUNNING, which is what lets a depot-day be rescheduled at all.
        assertThat(insertQueuedRun(serviceDate)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------

    private UUID insertQueuedRun(LocalDate serviceDate) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO schedule_run (id, depot_id, service_date, mode, rule_set_id, seed, status)
                VALUES (?, ?, ?, 'LINKED', ?, 1, 'QUEUED')
                """,
                id,
                depotId,
                serviceDate,
                ruleSetId);
        return id;
    }

    /** Runs the same call on several threads at once and collects every result. */
    private <T> List<T> inParallel(int threads, Callable<T> work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = pool.invokeAll(IntStream.range(0, threads)
                    .mapToObj(i -> work)
                    .toList());
            List<T> results = new java.util.ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
