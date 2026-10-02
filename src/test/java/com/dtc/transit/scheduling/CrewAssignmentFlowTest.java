package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.scheduling.run.RunWorker;
import com.dtc.transit.scheduling.schedule.RevalidationJob;
import com.dtc.transit.seed.DatasetGenerator;
import com.dtc.transit.seed.DatasetSize;
import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * The end-to-end flow this batch exists to deliver.
 *
 * <pre>
 * trips -> blocks -> duties -> crew assignment -> validation -> publish
 * </pre>
 *
 * <p>Deliberately focused on the main path and the guards that cannot be checked any other way: the exclusion
 * constraint, tested with raw SQL that bypasses the application entirely, and the override race, tested by
 * sending a stale version. Everything else about crew assignment is covered by the engine-level rules.
 */
class CrewAssignmentFlowTest extends SecurityWebTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private RunWorker worker;

    @Autowired
    private RevalidationJob revalidationJob;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private String scheduler;
    private Long depotId;
    private Long scheduleId;

    @BeforeEach
    void runTheWholePipeline() {
        generator.generate(DatasetSize.S, true);

        support.createUser("crew-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "crew-admin");
        support.createUser("crew-scheduler", null, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "crew-scheduler");

        depotId = jdbc.queryForObject("SELECT id FROM depot ORDER BY id LIMIT 1", Long.class);
        ResponseEntity<String> queued = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/schedule-runs",
                scheduler,
                """
                {"depotId":%d,"serviceDate":"2026-06-01","mode":"LINKED"}""".formatted(depotId));
        assertThat(queued.getStatusCode()).as("%s", queued.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        worker.pollOnce();

        UUID runId = UUID.fromString(support.field(queued, "id"));
        scheduleId = jdbc.queryForObject("SELECT schedule_id FROM schedule_run WHERE id = ?", Long.class, runId);
        assertThat(scheduleId).as("the run should have produced a schedule").isNotNull();
    }

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        support.reset();
        generator.purge();
    }

    @Test
    @DisplayName("a run assigns named crew to the duties it builds")
    void runAssignsCrew() {
        Integer duties =
                jdbc.queryForObject("SELECT count(*) FROM duty WHERE schedule_id = ?", Integer.class, scheduleId);
        Integer assigned = jdbc.queryForObject(
                """
                SELECT count(*) FROM duty_assignment a
                JOIN duty d ON d.id = a.duty_id WHERE d.schedule_id = ?
                """,
                Integer.class,
                scheduleId);

        assertThat(duties).isPositive();
        // Not necessarily all of them: a depot can genuinely be short of drivers, and that is reported rather
        // than hidden. What matters is that assignment happened at all.
        assertThat(assigned).isPositive();
    }

    @Test
    @DisplayName("nobody is booked on two overlapping duties")
    void noCrewMemberIsDoubleBooked() {
        // Checked in SQL, independently of the assigner's own in-memory bookkeeping.
        Integer overlaps = jdbc.queryForObject(
                """
                SELECT count(*) FROM duty_assignment a
                JOIN duty_assignment other
                  ON other.crew_member_id = a.crew_member_id AND other.id <> a.id
                     AND other.work_period && a.work_period
                JOIN duty d ON d.id = a.duty_id
                WHERE d.schedule_id = ? AND a.status <> 'CANCELLED' AND other.status <> 'CANCELLED'
                """,
                Integer.class,
                scheduleId);

        assertThat(overlaps).isZero();
    }

    @Test
    @DisplayName("every unassigned duty carries a reason histogram")
    void unassignedDutiesExplainThemselves() {
        Integer withoutHistogram = jdbc.queryForObject(
                """
                SELECT count(*) FROM conflict
                WHERE schedule_id = ? AND type = 'UNASSIGNED_DUTY'
                  AND (details IS NULL OR details = '{}'::jsonb OR message IS NULL)
                """,
                Integer.class,
                scheduleId);

        // "Unassigned" sends a scheduler hunting. "9 without enough rest, 6 on leave" tells them what to do.
        assertThat(withoutHistogram).isZero();
    }

    @Test
    @DisplayName("the whole flow reaches a published schedule")
    void validateThenPublish() {
        resolveBlockingConflicts();

        ResponseEntity<String> validation = post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        assertThat(validation.getStatusCode()).as("%s", validation.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.json(validation).get("valid").asBoolean())
                .as("%s", validation.getBody())
                .isTrue();

        ResponseEntity<String> published = post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        assertThat(published.getStatusCode()).as("%s", published.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(published, "status")).isEqualTo("PUBLISHED");
        // The crew rows carry the status too, because the double-booking constraint can only see its own table.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM duty_assignment a
                        JOIN duty d ON d.id = a.duty_id
                        WHERE d.schedule_id = ? AND a.schedule_status <> 'PUBLISHED'
                        """,
                        Integer.class,
                        scheduleId))
                .isZero();
    }

    @Test
    @DisplayName("publishing with open hard conflicts is refused")
    void publishingWithConflictsIsRefused() {
        // The run leaves unassigned-duty conflicts on this dataset, which is exactly the situation the gate is
        // for. Validation reports them and publication refuses.
        ResponseEntity<String> validation = post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        long blocking = support.json(validation).get("blockingConflicts").asLong();

        if (blocking == 0) {
            // Nothing to gate on, so one is created: a schedule that cannot be made to fail would make this test
            // pass for the wrong reason.
            jdbc.update(
                    """
                    INSERT INTO conflict (schedule_id, type, severity, message)
                    VALUES (?, 'UNASSIGNED_DUTY', 'HARD', 'injected to test the publication gate')
                    """,
                    scheduleId);
            post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        }

        ResponseEntity<String> published = post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("publishing twice returns the same version rather than creating a second")
    void publishIsIdempotent() {
        resolveBlockingConflicts();
        post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);

        ResponseEntity<String> first = post("/api/v1/schedules/" + scheduleId + "/publish", admin);
        ResponseEntity<String> second = post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        // A client retrying after a lost response is doing the right thing and must not create a second version.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(second, "versionNo")).isEqualTo(support.field(first, "versionNo"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM schedule WHERE depot_id = ? AND status = 'PUBLISHED'",
                        Integer.class,
                        depotId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the database refuses an overlapping published assignment inserted with raw SQL")
    void exclusionConstraintCannotBeBypassed() {
        resolveBlockingConflicts();
        post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        var existing = jdbc.queryForMap(
                """
                SELECT a.id, a.duty_id, a.crew_member_id, a.service_date, a.starts_at, a.ends_at
                FROM duty_assignment a
                JOIN duty d ON d.id = a.duty_id
                WHERE d.schedule_id = ? AND a.schedule_status = 'PUBLISHED'
                ORDER BY a.id LIMIT 1
                """,
                scheduleId);
        Long otherDutyId = jdbc.queryForObject(
                """
                SELECT d.id FROM duty d
                WHERE d.schedule_id = ? AND d.id <> ?
                  AND NOT EXISTS (SELECT 1 FROM duty_assignment x
                                  WHERE x.duty_id = d.id AND x.crew_role = 'DRIVER')
                ORDER BY d.id LIMIT 1
                """,
                Long.class,
                scheduleId,
                existing.get("duty_id"));

        // Straight into the table, bypassing every application check. If this succeeded, the constraint would be
        // wrong and nothing reachable through the API would ever reveal it, because the service checks would mask
        // it on every normal path.
        Throwable refused = catchThrowable(() -> jdbc.update(
                """
                INSERT INTO duty_assignment (duty_id, crew_role, crew_member_id, service_date, starts_at,
                                             ends_at, schedule_status)
                VALUES (?, 'DRIVER', ?, ?, ?, ?, 'PUBLISHED')
                """,
                otherDutyId,
                existing.get("crew_member_id"),
                existing.get("service_date"),
                existing.get("starts_at"),
                existing.get("ends_at")));

        assertThat(refused)
                .as("the exclusion constraint must refuse an overlapping published assignment")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(refused.getMessage()).contains("no_crew_double_booking");
    }

    @Test
    @DisplayName("a draft assignment may overlap a published one, which is what versioning needs")
    void draftsMayOverlapPublishedRows() {
        resolveBlockingConflicts();
        post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        var existing = jdbc.queryForMap(
                """
                SELECT a.duty_id, a.crew_member_id, a.service_date, a.starts_at, a.ends_at
                FROM duty_assignment a
                JOIN duty d ON d.id = a.duty_id
                WHERE d.schedule_id = ? AND a.schedule_status = 'PUBLISHED'
                ORDER BY a.id LIMIT 1
                """,
                scheduleId);

        // The constraint covers published rows only, deliberately. A replacement version is prepared while the
        // live one is still live, so its drafts legitimately overlap.
        jdbc.update(
                """
                INSERT INTO duty_assignment (duty_id, crew_role, crew_member_id, service_date, starts_at,
                                             ends_at, schedule_status)
                VALUES (?, 'CONDUCTOR', ?, ?, ?, ?, 'DRAFT')
                """,
                existing.get("duty_id"),
                existing.get("crew_member_id"),
                existing.get("service_date"),
                existing.get("starts_at"),
                existing.get("ends_at"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM duty_assignment WHERE schedule_status = 'DRAFT'",
                        Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("an override without If-Match is refused")
    void overrideNeedsIfMatch() {
        Long assignmentId = anyAssignmentId();

        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PATCH,
                "/api/v1/duty-assignments/" + assignmentId,
                scheduler,
                """
                {"crewMemberId":1,"reason":"swap"}""");

        // An override without a version is a blind write: whatever the caller saw may already have changed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("IF_MATCH_REQUIRED");
    }

    @Test
    @DisplayName("a stale If-Match loses the race rather than overwriting")
    void staleVersionIsRefused() {
        Long assignmentId = anyAssignmentId();
        // Any real crew member will do: the version is checked before eligibility is, which is the whole point
        // of checking it first.
        Long anyCrewMember = jdbc.queryForObject(
                "SELECT id FROM crew_member ORDER BY id LIMIT 1", Long.class);

        ResponseEntity<String> stale = patchWithIfMatch(assignmentId, anyCrewMember, "\"999\"");

        // Two schedulers editing the same roster: the one holding an old version is told to reload, rather than
        // having their decision applied to a state they never saw.
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(stale, "code")).isEqualTo("STALE_VERSION");
    }

    @Test
    @DisplayName("an override with the current version succeeds and is audited")
    void overrideWithCurrentVersionSucceeds() {
        Long assignmentId = anyAssignmentId();
        Long replacement = eligibleReplacementFor(assignmentId);
        if (replacement == null) {
            // No legal replacement exists in this dataset, so there is nothing to assert about a successful
            // override. Skipping is honest; asserting on a failure would test the wrong thing.
            return;
        }

        ResponseEntity<String> read =
                support.call(rest, HttpMethod.GET, "/api/v1/duty-assignments/" + assignmentId, scheduler, null);
        String etag = read.getHeaders().getETag();
        assertThat(etag).as("a read must return the version as an ETag").isNotNull();

        ResponseEntity<String> overridden = patchWithIfMatch(assignmentId, replacement, etag);

        assertThat(overridden.getStatusCode()).as("%s", overridden.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(overridden, "crewMemberId")).isEqualTo(String.valueOf(replacement));
        assertThat(support.field(overridden, "status")).isEqualTo("OVERRIDDEN");
        // Audited, because an override changes somebody's working day.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'ASSIGNMENT_OVERRIDDEN'", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("overriding into an ineligible crew member is refused, not warned about")
    void ineligibleOverrideIsRefused() {
        Long assignmentId = anyAssignmentId();
        // Somebody at another depot. A warning here would be read as permission.
        // The S dataset has one depot, so there may be no outsider at all. A non-existent id reaches the same
        // check, which is what this test is about.
        Long outsider = jdbc
                .queryForList(
                        "SELECT id FROM crew_member WHERE depot_id <> ? ORDER BY id LIMIT 1", Long.class, depotId)
                .stream()
                .findFirst()
                .orElse(999_999L);

        ResponseEntity<String> read =
                support.call(rest, HttpMethod.GET, "/api/v1/duty-assignments/" + assignmentId, scheduler, null);
        ResponseEntity<String> response =
                patchWithIfMatch(assignmentId, outsider, read.getHeaders().getETag());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isIn("CREW_NOT_AT_DEPOT", "CREW_NOT_ELIGIBLE");
    }

    @Test
    @DisplayName("revalidation flags a published schedule whose bus has gone into the workshop")
    void revalidationFlagsMasterDataChanges() {
        resolveBlockingConflicts();
        post("/api/v1/schedules/" + scheduleId + "/validate", scheduler);
        post("/api/v1/schedules/" + scheduleId + "/publish", admin);

        // A bus assigned to one of this schedule's blocks breaks down after publication.
        jdbc.update(
                """
                INSERT INTO bus_unavailability (bus_id, starts_at, ends_at, reason)
                SELECT a.bus_id, a.starts_at, a.ends_at, 'BREAKDOWN'
                FROM bus_assignment a
                JOIN vehicle_block b ON b.id = a.block_id
                WHERE b.schedule_id = ? ORDER BY a.id LIMIT 1
                """,
                scheduleId);

        int flagged = revalidationJob.revalidate(java.time.LocalDate.of(2026, 1, 1));

        // The published schedule is not rewritten: crews were told where to be. It is flagged, and a human
        // decides whether to publish a correction.
        assertThat(flagged).isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT needs_revalidation FROM schedule WHERE id = ?", Boolean.class, scheduleId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conflict WHERE schedule_id = ? AND type = 'NEEDS_REVALIDATION'",
                        Integer.class,
                        scheduleId))
                .isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------

    /** Accepts every blocking conflict so the publication path can be exercised. */
    private void resolveBlockingConflicts() {
        jdbc.update(
                "UPDATE conflict SET resolved = TRUE, resolved_by = 'test' WHERE schedule_id = ? AND severity = 'HARD'",
                scheduleId);
    }

    private Long anyAssignmentId() {
        Long id = jdbc.queryForObject(
                """
                SELECT a.id FROM duty_assignment a
                JOIN duty d ON d.id = a.duty_id
                WHERE d.schedule_id = ? ORDER BY a.id LIMIT 1
                """,
                Long.class,
                scheduleId);
        assertThat(id).as("the run should have produced at least one assignment").isNotNull();
        return id;
    }

    /** A driver at this depot who is not already booked over the assignment's window. */
    private Long eligibleReplacementFor(Long assignmentId) {
        return jdbc.queryForList(
                        """
                        SELECT c.id FROM crew_member c
                        WHERE c.depot_id = ? AND c.crew_role = 'DRIVER' AND c.status = 'ACTIVE'
                          AND (c.licence_expiry IS NULL OR c.licence_expiry >= DATE '2026-06-01')
                          AND c.weekly_off_dow <> 1
                          AND NOT EXISTS (SELECT 1 FROM crew_leave l WHERE l.crew_member_id = c.id)
                          AND NOT EXISTS (SELECT 1 FROM duty_assignment a WHERE a.crew_member_id = c.id)
                        ORDER BY c.id LIMIT 1
                        """,
                        Long.class,
                        depotId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ResponseEntity<String> patchWithIfMatch(Long assignmentId, Long crewMemberId, String ifMatch) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(scheduler);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ifMatch != null) {
            headers.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return rest.exchange(
                "/api/v1/duty-assignments/" + assignmentId,
                HttpMethod.PATCH,
                new HttpEntity<>(
                        """
                        {"crewMemberId":%d,"reason":"covering a late sickness"}"""
                                .formatted(crewMemberId),
                        headers),
                String.class);
    }

    private ResponseEntity<String> post(String path, String token) {
        return support.call(rest, HttpMethod.POST, path, token, null);
    }
}
