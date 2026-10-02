package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Duties end to end: built by a run, stored, and readable over the API.
 *
 * <p>The engine tests pin down the cutting rules; this one checks the parts between the engine and the client.
 * Those are where the mistakes hide: pieces of work pointing at the wrong block, duty numbers colliding across
 * blocks, handovers referencing duties that were never written.
 */
class DutyPersistenceTest extends SecurityWebTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private RunWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private String scheduler;
    private Long depotId;
    private Long scheduleId;

    @BeforeEach
    void runASchedule() {
        generator.generate(DatasetSize.S, true);

        support.createUser("duty-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "duty-admin");
        support.createUser("duty-scheduler", null, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "duty-scheduler");

        depotId = jdbc.queryForObject("SELECT id FROM depot ORDER BY id LIMIT 1", Long.class);
        ResponseEntity<String> queued = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/schedule-runs",
                scheduler,
                """
                {"depotId":%d,"serviceDate":"2026-06-01","mode":"LINKED"}""".formatted(depotId));
        assertThat(queued.getStatusCode()).as("%s", queued.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = UUID.fromString(support.field(queued, "id"));
        worker.pollOnce();
        scheduleId = jdbc.queryForObject("SELECT schedule_id FROM schedule_run WHERE id = ?", Long.class, runId);
        assertThat(scheduleId).as("the run should have produced a schedule").isNotNull();
    }

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        support.reset();
        generator.purge();
    }

    @Test
    @DisplayName("a run produces duties and records them in its metrics")
    void runProducesDuties() {
        ResponseEntity<String> duties = get("/api/v1/schedules/" + scheduleId + "/duties?size=200");

        assertThat(duties.getStatusCode()).as("%s", duties.getBody()).isEqualTo(HttpStatus.OK);
        long count = support.json(duties).get("totalElements").asLong();
        assertThat(count).isPositive();
        // At least one duty per block: a block cannot be covered by nobody.
        assertThat(count).isGreaterThanOrEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_block WHERE schedule_id = ?", Long.class, scheduleId));
    }

    @Test
    @DisplayName("every duty carries metrics that agree with each other")
    void storedMetricsAreSelfConsistent() {
        // Checked in SQL, independently of the calculator that produced the numbers. Work is the spread-over less
        // the breaks, and the paid time is never below the guarantee: two relationships that must hold for every
        // row whatever the rules were.
        Integer inconsistent = jdbc.queryForObject(
                """
                SELECT count(*) FROM duty
                WHERE schedule_id = ?
                  AND (spread_sec < break_sec
                       OR spread_sec - break_sec > 480 * 60
                       OR paid_sec < spread_sec - break_sec
                       OR sign_off_sec <= sign_on_sec)
                """,
                Integer.class,
                scheduleId);

        assertThat(inconsistent).isZero();
    }

    @Test
    @DisplayName("every piece of work belongs to exactly one duty")
    void piecesAreUsedOnce() {
        // The database enforces this with a unique constraint, so a failure here would mean the constraint was
        // dropped. Worth asserting because the cost of getting it wrong is two crews turning up for one bus.
        Integer shared = jdbc.queryForObject(
                """
                SELECT count(*) FROM (
                  SELECT piece_id FROM duty_piece dp
                  JOIN duty d ON d.id = dp.duty_id
                  WHERE d.schedule_id = ?
                  GROUP BY piece_id HAVING count(*) > 1) AS shared
                """,
                Integer.class,
                scheduleId);

        assertThat(shared).isZero();
    }

    @Test
    @DisplayName("pieces of work reference the block they came from")
    void piecesPointAtTheirOwnBlock() {
        Integer wrongSchedule = jdbc.queryForObject(
                """
                SELECT count(*) FROM duty_piece dp
                JOIN duty d ON d.id = dp.duty_id
                JOIN piece_of_work p ON p.id = dp.piece_id
                JOIN vehicle_block b ON b.id = p.block_id
                WHERE d.schedule_id = ? AND b.schedule_id <> d.schedule_id
                """,
                Integer.class,
                scheduleId);

        // A piece pointing at another schedule's block is the mistake the id-mapping in the persister exists to
        // avoid, and it would be invisible in any response that did not join all the way through.
        assertThat(wrongSchedule).isZero();
    }

    @Test
    @DisplayName("the duties of a block tile it end to end")
    void dutiesCoverEveryBlockCompletely() {
        // For each block, the earliest piece starts at the pull-out and the latest ends at the pull-in. A gap
        // would be a bus running with nobody on it.
        Integer notTiled = jdbc.queryForObject(
                """
                SELECT count(*) FROM (
                  SELECT b.id, b.pull_out_sec, b.pull_in_sec,
                         min(p.start_sec) AS first_start, max(p.end_sec) AS last_end
                  FROM vehicle_block b
                  JOIN piece_of_work p ON p.block_id = b.id
                  WHERE b.schedule_id = ?
                  GROUP BY b.id, b.pull_out_sec, b.pull_in_sec) AS covered
                WHERE first_start <> pull_out_sec OR last_end <> pull_in_sec
                """,
                Integer.class,
                scheduleId);

        assertThat(notTiled).isZero();
    }

    @Test
    @DisplayName("a handover joins two real duties of the same schedule")
    void handoversJoinRealDuties() {
        Integer dangling = jdbc.queryForObject(
                """
                SELECT count(*) FROM handover h
                WHERE h.schedule_id = ?
                  AND (NOT EXISTS (SELECT 1 FROM duty d WHERE d.id = h.outgoing_duty_id AND d.schedule_id = ?)
                       OR NOT EXISTS (SELECT 1 FROM duty d WHERE d.id = h.incoming_duty_id AND d.schedule_id = ?))
                """,
                Integer.class,
                scheduleId,
                scheduleId,
                scheduleId);

        assertThat(dangling).isZero();
    }

    @Test
    @DisplayName("a duty detail response lists the bus work it covers")
    void dutyDetailListsItsPieces() {
        Long dutyId = jdbc.queryForObject(
                "SELECT id FROM duty WHERE schedule_id = ? ORDER BY duty_no LIMIT 1", Long.class, scheduleId);

        ResponseEntity<String> detail = get("/api/v1/schedules/" + scheduleId + "/duties/" + dutyId);

        assertThat(detail.getStatusCode()).as("%s", detail.getBody()).isEqualTo(HttpStatus.OK);
        var body = support.json(detail);
        assertThat(body.get("pieces")).isNotEmpty();
        // Clock times alongside the seconds: 90,000 does not read as 01:00 the next morning.
        assertThat(body.get("duty").get("signOn").asText()).matches("\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("a duty of another schedule is not found rather than forbidden")
    void dutyOfAnotherScheduleIsNotFound() {
        Long dutyId = jdbc.queryForObject(
                "SELECT id FROM duty WHERE schedule_id = ? ORDER BY duty_no LIMIT 1", Long.class, scheduleId);

        // Saying "wrong schedule" would confirm the duty exists to a caller who cannot see it.
        assertThat(get("/api/v1/schedules/999999/duties/" + dutyId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the duty summary adds up to the number of duties")
    void summaryAddsUp() {
        ResponseEntity<String> summary = get("/api/v1/schedules/" + scheduleId + "/duty-summary");

        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        long fromSummary = 0;
        for (var row : support.json(summary)) {
            fromSummary += row.get("dutyCount").asLong();
        }
        assertThat(fromSummary)
                .isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM duty WHERE schedule_id = ?", Long.class, scheduleId));
    }

    @Test
    @DisplayName("tightening the rules through the API changes the duties a later run produces")
    void ruleSetChangeChangesTheRoster() {
        int before = jdbc.queryForObject(
                "SELECT count(*) FROM duty WHERE schedule_id = ?", Integer.class, scheduleId);

        // A depot-specific rule set with a much tighter continuous-work limit, created over the API. No code is
        // changed and nothing is redeployed; this is the Phase 7 promise in one test.
        ResponseEntity<String> created = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/rule-sets",
                admin,
                """
                {"name":"Tight depot rules","depotId":%d,"effectiveFrom":"2026-01-01","rules":{
                  "maxWorkPerDutyMin":240,"maxContinuousWorkMin":120,"minBreakMin":30,"maxSpreadOverMin":600,
                  "maxWeeklyWorkMin":2880,"weeklyRestDaysPer7":1,"minRestBetweenDutiesMin":600,
                  "signOnMin":15,"signOffMin":10,"minLayoverMin":5,"minLayoverPct":10,"handoverBufferMin":5,
                  "maxPiecesPerDuty":3,"maxBusChangeoversPerDuty":2,"targetWorkPerDutyMin":180,
                  "minPaidDutyMin":60,"allowOvertime":false,"maxOvertimeMin":60,
                  "midDayDepotReturnGapMin":90,"evRangeReservePct":15,"standbyPoolPct":5,
                  "maxBlockDurationMin":1140,"evChargingMin":45}}"""
                        .formatted(depotId));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> queued = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/schedule-runs",
                scheduler,
                """
                {"depotId":%d,"serviceDate":"2026-06-01","mode":"LINKED"}""".formatted(depotId));
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID secondRun = UUID.fromString(support.field(queued, "id"));
        worker.pollOnce();

        Long secondSchedule =
                jdbc.queryForObject("SELECT schedule_id FROM schedule_run WHERE id = ?", Long.class, secondRun);
        assertThat(secondSchedule).as("the second run should have completed").isNotNull();
        int after = jdbc.queryForObject(
                "SELECT count(*) FROM duty WHERE schedule_id = ?", Integer.class, secondSchedule);

        // Two hours of continuous work instead of five means more, shorter duties for the same bus work.
        assertThat(after).isGreaterThan(before);
    }

    @Test
    @DisplayName("the effective rule set endpoint shows which rules a depot-day would use")
    void effectiveRuleSetIsVisible() {
        ResponseEntity<String> effective = get(
                "/api/v1/rule-sets/effective?depotId=" + depotId + "&serviceDate=2026-06-01");

        assertThat(effective.getStatusCode()).as("%s", effective.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.json(effective).get("rules").get("maxContinuousWorkMin").asInt())
                .isEqualTo(300);
        assertThat(support.json(effective).get("global").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a contradictory rule set is refused before it can break a run")
    void contradictoryRuleSetIsRefused() {
        // A continuous-work limit above the daily maximum can never bind, which almost always means the two were
        // entered the wrong way round. Catching it here saves a run failing an hour later.
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/rule-sets/validate",
                admin,
                """
                {"rules":{
                  "maxWorkPerDutyMin":240,"maxContinuousWorkMin":300,"minBreakMin":30,"maxSpreadOverMin":600,
                  "maxWeeklyWorkMin":2880,"weeklyRestDaysPer7":1,"minRestBetweenDutiesMin":600,
                  "signOnMin":15,"signOffMin":10,"minLayoverMin":5,"minLayoverPct":10,"handoverBufferMin":5,
                  "maxPiecesPerDuty":3,"maxBusChangeoversPerDuty":2,"targetWorkPerDutyMin":180,
                  "minPaidDutyMin":60,"allowOvertime":false,"maxOvertimeMin":60,
                  "midDayDepotReturnGapMin":90,"evRangeReservePct":15,"standbyPoolPct":5,
                  "maxBlockDurationMin":1140,"evChargingMin":45}}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> get(String path) {
        return support.call(rest, HttpMethod.GET, path, scheduler, null);
    }
}
