package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.scheduling.run.RunWorker;
import com.dtc.transit.seed.DatasetGenerator;
import com.dtc.transit.seed.DatasetSize;
import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * The run queue end to end: queueing, idempotency, concurrency refusal, execution and publication.
 *
 * <p>Driven over HTTP against the S dataset, because the parts most likely to break are the seams — the partial
 * unique index refusing a second run, the worker's claim, the one transaction that writes everything, and the
 * exclusion constraint that guards publication.
 *
 * <p>The background poller is off for integration tests, so {@link RunWorker#pollOnce()} is called explicitly.
 * That is also what makes these tests deterministic rather than timing-dependent.
 */
class ScheduleRunLifecycleTest extends SecurityWebTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private RunWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private String scheduler;
    private String planner;
    private Long depotId;
    private String serviceDate;

    @BeforeEach
    void loadDataset() {
        generator.generate(DatasetSize.S, true);

        // Accounts are created after the dataset, because generating it clears every depot and the accounts
        // bound to one.
        support.createUser("run-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "run-admin");
        support.createUser("run-scheduler", null, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "run-scheduler");
        support.createUser("run-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "run-planner");

        depotId = jdbc.queryForObject("SELECT id FROM depot ORDER BY id LIMIT 1", Long.class);
        // The dataset's timetables are valid from this date, and it is a Monday, so the weekday timetable applies.
        serviceDate = "2026-06-01";
    }

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        support.reset();
        generator.purge();
    }

    @Test
    @DisplayName("queueing returns 202 with the run's location")
    void queueingAcceptsAndPointsAtTheRun() {
        ResponseEntity<String> response = queue(scheduler, null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(support.field(response, "status")).isEqualTo("QUEUED");
        // 202 plus Location is how a client gets a handle it can poll without guessing a URL.
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/schedule-runs/" + support.field(response, "id"));
    }

    @Test
    @DisplayName("a second run for the same depot and date while one is active returns 409")
    void concurrentRunForSameDepotDayIsRefused() {
        assertThat(queue(scheduler, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> second = queue(scheduler, null);

        // Two concurrent runs would race to write a schedule for the same depot-day and burn minutes of CPU
        // producing an answer one of them discards.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(second, "code")).isEqualTo("RUN_ALREADY_ACTIVE");
    }

    @Test
    @DisplayName("the same idempotency key returns the original run rather than a second one")
    void idempotencyKeyReturnsTheSameRun() {
        ResponseEntity<String> first = queue(scheduler, "retry-me");
        ResponseEntity<String> retry = queue(scheduler, "retry-me");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // 200, not 202: the work was already accepted, and the client has not just created a duplicate job.
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(retry, "id")).isEqualTo(support.field(first, "id"));
        assertThat(countRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("a planner may not queue a run")
    void plannerCannotQueueARun() {
        // Planners design the network; running the depot's day is a scheduler's job.
        assertThat(queue(planner, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the worker runs the job and the schedule covers every trip")
    void workerProducesACompleteSchedule() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));

        assertThat(worker.pollOnce()).contains(runId);

        ResponseEntity<String> run = get("/api/v1/schedule-runs/" + runId, scheduler);
        assertThat(support.field(run, "status")).as("%s", run.getBody()).isEqualTo("COMPLETED");
        assertThat(support.field(run, "progress")).isEqualTo("100");

        var metrics = support.json(run).get("metrics");
        int total = metrics.get("tripsTotal").asInt();
        assertThat(total).isPositive();
        // The Phase 6 target: full coverage, or an explicit reason per uncovered trip. The dataset is built so
        // the depot has enough buses, so full coverage is the expected answer here.
        assertThat(metrics.get("tripsCovered").asInt()).isEqualTo(total);
        assertThat(metrics.get("tripsUncovered").asInt()).isZero();
        assertThat(metrics.get("blocks").asInt()).isPositive();
    }

    @Test
    @DisplayName("every trip appears in exactly one block")
    void everyTripIsInExactlyOneBlock() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = support.json(get("/api/v1/schedule-runs/" + runId, scheduler))
                .get("scheduleId")
                .asLong();

        Integer duplicated = jdbc.queryForObject(
                """
                SELECT count(*) FROM (
                  SELECT e.trip_id FROM block_event e
                  JOIN vehicle_block b ON b.id = e.block_id
                  WHERE b.schedule_id = ? AND e.trip_id IS NOT NULL
                  GROUP BY e.trip_id HAVING count(*) > 1) AS duplicates
                """,
                Integer.class,
                scheduleId);

        // A trip in two blocks means two buses dispatched for one journey, which no later stage would notice.
        assertThat(duplicated).isZero();
    }

    @Test
    @DisplayName("consecutive trips in a block satisfy layover plus dead running")
    void blocksRespectLayoverAndDeadhead() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = scheduleIdOf(runId);

        // Checked in SQL, independently of the engine that produced it. An event that starts before the previous
        // one ends describes a bus in two places at once.
        Integer overlapping = jdbc.queryForObject(
                """
                SELECT count(*) FROM (
                  SELECT e.block_id, e.start_sec,
                         lag(e.end_sec) OVER (PARTITION BY e.block_id ORDER BY e.seq) AS previous_end
                  FROM block_event e
                  JOIN vehicle_block b ON b.id = e.block_id
                  WHERE b.schedule_id = ?) AS ordered
                WHERE previous_end IS NOT NULL AND start_sec < previous_end
                """,
                Integer.class,
                scheduleId);

        assertThat(overlapping).isZero();
    }

    @Test
    @DisplayName("every block gets a bus, and no bus is in two blocks at once")
    void busAssignmentsAreSoundAndComplete() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = scheduleIdOf(runId);

        Integer unassigned = jdbc.queryForObject(
                """
                SELECT count(*) FROM vehicle_block b
                WHERE b.schedule_id = ?
                  AND NOT EXISTS (SELECT 1 FROM bus_assignment a WHERE a.block_id = b.id)
                """,
                Integer.class,
                scheduleId);
        Integer overlaps = jdbc.queryForObject(
                """
                SELECT count(*) FROM bus_assignment a
                JOIN bus_assignment other
                  ON other.bus_id = a.bus_id AND other.id <> a.id AND other.period && a.period
                JOIN vehicle_block ab ON ab.id = a.block_id AND ab.schedule_id = ?
                """,
                Integer.class,
                scheduleId);

        assertThat(unassigned).isZero();
        assertThat(overlaps).isZero();
    }

    @Test
    @DisplayName("a completed schedule validates and then publishes")
    void validateThenPublish() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = scheduleIdOf(runId);

        ResponseEntity<String> validation = post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        assertThat(validation.getStatusCode()).as("%s", validation.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.json(validation).get("blockingConflicts").asLong())
                .as("the S dataset should schedule without blocking conflicts")
                .isZero();
        assertThat(support.json(validation).get("valid").asBoolean()).isTrue();

        ResponseEntity<String> published = post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        assertThat(published.getStatusCode()).as("%s", published.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(published, "status")).isEqualTo("PUBLISHED");
        // The assignments carry the status too, because the double-booking exclusion constraint can only see
        // columns of their own table.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM bus_assignment a
                        JOIN vehicle_block b ON b.id = a.block_id
                        WHERE b.schedule_id = ? AND a.schedule_status <> 'PUBLISHED'
                        """,
                        Integer.class,
                        scheduleId))
                .isZero();
    }

    @Test
    @DisplayName("a scheduler may not publish")
    void publicationIsRestricted() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = scheduleIdOf(runId);
        post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);

        // Publication tells crews where to be, so it needs a manager's authority.
        assertThat(post("/api/v1/schedules/" + scheduleId + "/publish", scheduler).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("publishing an unvalidated schedule is refused")
    void publishingNeedsValidationFirst() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();
        Long scheduleId = scheduleIdOf(runId);

        ResponseEntity<String> response = post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("SCHEDULE_NOT_VALIDATED");
    }

    @Test
    @DisplayName("a second run after the first finishes is allowed and makes version 2")
    void laterRunCreatesANewVersion() {
        UUID first = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();

        // The slot is free once the run has finished: the partial unique index only covers queued and running.
        UUID second = UUID.fromString(support.field(queue(scheduler, null), "id"));
        worker.pollOnce();

        assertThat(jdbc.queryForObject(
                        "SELECT version_no FROM schedule WHERE run_id = ?", Integer.class, second))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version_no FROM schedule WHERE run_id = ?", Integer.class, first))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the run records the rule set and seed it used")
    void runIsReproducible() {
        ResponseEntity<String> queued = queue(scheduler, null);

        // Both are needed to reproduce a result: the rules that applied, and the seed that broke any ties.
        assertThat(support.json(queued).get("ruleSetId").asLong()).isPositive();
        assertThat(support.json(queued).get("seed").asLong()).isNotZero();
    }

    @Test
    @DisplayName("a queued run can be cancelled, freeing the depot-day")
    void cancellingFreesTheSlot() {
        UUID runId = UUID.fromString(support.field(queue(scheduler, null), "id"));

        ResponseEntity<String> cancelled = post("/api/v1/schedule-runs/" + runId + "/cancel", scheduler);

        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(cancelled, "status")).isEqualTo("FAILED");
        assertThat(queue(scheduler, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // --- helpers ------------------------------------------------------------

    private Long scheduleIdOf(UUID runId) {
        return jdbc.queryForObject("SELECT schedule_id FROM schedule_run WHERE id = ?", Long.class, runId);
    }

    private int countRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM schedule_run", Integer.class);
    }

    private ResponseEntity<String> queue(String token, String idempotencyKey) {
        String body =
                """
                {"depotId":%d,"serviceDate":"%s","mode":"LINKED"}""".formatted(depotId, serviceDate);
        if (idempotencyKey == null) {
            return support.call(rest, HttpMethod.POST, "/api/v1/schedule-runs", token, body);
        }
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange(
                "/api/v1/schedule-runs",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers),
                String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return support.call(rest, HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<String> post(String path, String token) {
        return support.call(rest, HttpMethod.POST, path, token, null);
    }
}
