package com.dtc.transit.reporting;

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
 * Reports, the dashboard and the audit trail.
 *
 * <p>The checks that matter here are the ones the phase is gated on: figures matching an independent query,
 * superseded versions excluded, empty depots returning nulls rather than failing, and authorization. The report
 * SQL is not re-implemented in Java to check it — each assertion compares the view against a hand-written query
 * over the base tables, which is the only comparison worth making.
 */
class ReportingTest extends SecurityWebTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private RunWorker worker;

    @Autowired
    private ReportService reportService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private String manager;
    private String scheduler;
    private String planner;
    private Long depotId;
    private Long scheduleId;

    @BeforeEach
    void publishASchedule() {
        generator.generate(DatasetSize.S, true);

        support.createUser("rep-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "rep-admin");
        support.createUser("rep-manager", null, Role.MANAGER);
        manager = support.accessTokenFor(rest, "rep-manager");
        support.createUser("rep-scheduler", null, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "rep-scheduler");
        support.createUser("rep-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "rep-planner");

        depotId = jdbc.queryForObject("SELECT id FROM depot ORDER BY id LIMIT 1", Long.class);
        scheduleId = runAndPublish();

        // The tests below call some services directly rather than over HTTP, and those carry @PreAuthorize.
        // A bare test thread has no SecurityContext, so one is installed here.
        authenticateAsAdmin();
        reportService.refreshAll();
        dashboardService.invalidate();
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static void authenticateAsAdmin() {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "reporting-test-admin",
                "n/a",
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    }

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        support.reset();
        generator.purge();
    }

    @Test
    @DisplayName("fleet utilization matches an independent query over the base tables")
    void fleetUtilizationMatchesHandWrittenQuery() {
        var expected = jdbc.queryForMap(
                """
                SELECT count(*) AS blocks, sum(b.service_km) AS service_km, sum(b.dead_km) AS dead_km
                FROM vehicle_block b
                JOIN schedule s ON s.id = b.schedule_id
                WHERE s.status = 'PUBLISHED' AND s.depot_id = ?
                """,
                depotId);

        var report = reportService.fleetUtilization(depotId, null, null, 0, 10);

        assertThat(report.content()).hasSize(1);
        var row = report.content().get(0);
        assertThat(row.blocks()).isEqualTo(((Number) expected.get("blocks")).intValue());
        assertThat(row.serviceKm()).isCloseTo(
                ((Number) expected.get("service_km")).doubleValue(),
                org.assertj.core.data.Offset.offset(0.01));
        // The ratio is derived in the view; recomputing it here is the independent check.
        double expectedRatio = ((Number) expected.get("dead_km")).doubleValue()
                / (((Number) expected.get("service_km")).doubleValue()
                        + ((Number) expected.get("dead_km")).doubleValue());
        assertThat(row.deadKmRatio()).isCloseTo(expectedRatio, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("a superseded version is excluded, so a republished day is not double counted")
    void supersededVersionsAreExcluded() {
        int blocksBefore = reportService
                .fleetUtilization(depotId, null, null, 0, 10)
                .content()
                .get(0)
                .blocks();

        // Publish a second version for the same depot-day. The first becomes SUPERSEDED and must drop out of
        // every report; counting both would double every figure for this date.
        Long second = runAndPublish();
        reportService.refreshAll();

        var report = reportService.fleetUtilization(depotId, null, null, 0, 10);

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM schedule WHERE id = ?", String.class, scheduleId))
                .isEqualTo("SUPERSEDED");
        assertThat(report.content()).as("one row per depot-day, not one per version").hasSize(1);
        assertThat(report.content().get(0).blocks())
                .as("the figure should describe the live version only")
                .isEqualTo(blocksBefore);
        assertThat(second).isNotEqualTo(scheduleId);
    }

    @Test
    @DisplayName("a depot with no published schedule returns no rows rather than failing")
    void emptyDepotReturnsNothing() {
        Long emptyDepot = jdbc.queryForObject(
                """
                INSERT INTO depot (code, name, location)
                VALUES ('REP-EMPTY', 'Empty depot', ST_SetSRID(ST_MakePoint(77.3, 28.7), 4326))
                RETURNING id
                """,
                Long.class);

        var report = reportService.fleetUtilization(emptyDepot, null, null, 0 , 10);

        // Nothing ran, so there is nothing to report. An error here would make a new depot look broken.
        assertThat(report.content()).isEmpty();
        assertThat(report.hasNext()).isFalse();
    }

    @Test
    @DisplayName("schedule KPIs expose null ratios rather than zero when nothing is paid")
    void nullRatiosRatherThanZero() {
        var kpis = reportService.scheduleKpis(depotId, null, null, 0, 10);

        assertThat(kpis.content()).isNotEmpty();
        // A zero would read as "nobody was productive", where null reads as "there is no ratio here".
        assertThat(kpis.content()).allSatisfy(row -> {
            if (row.duties() == 0) {
                assertThat(row.platformToPaidRatio()).isNull();
            } else {
                assertThat(row.platformToPaidRatio()).isNotNull();
            }
        });
    }

    @Test
    @DisplayName("crew hours report the published roster")
    void crewHoursReflectThePublishedRoster() {
        var expected = jdbc.queryForObject(
                """
                SELECT count(DISTINCT da.crew_member_id) FROM duty_assignment da
                JOIN duty d ON d.id = da.duty_id
                JOIN schedule s ON s.id = d.schedule_id
                WHERE s.status = 'PUBLISHED' AND da.status <> 'CANCELLED'
                """,
                Integer.class);

        var report = reportService.crewHours(depotId, null, null, 0, 100);

        // One row per crew member who worked, capped by the page size. The dataset rosters more than a page's
        // worth, so a full page and a cursor onward is the correct answer.
        assertThat(report.content()).hasSize(Math.min(expected, ReportService.MAX_PAGE_SIZE));
        assertThat(report.hasNext()).isEqualTo(expected > ReportService.MAX_PAGE_SIZE);
        assertThat(report.content()).allSatisfy(row -> assertThat(row.workSec()).isPositive());
    }

    @Test
    @DisplayName("a planner cannot read the reports")
    void reportsAreRestricted() {
        assertThat(get("/api/v1/reports/fleet-utilization", planner).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/reports/crew-hours", planner).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Schedule KPIs are a management view, so a scheduler is out too.
        assertThat(get("/api/v1/reports/schedule-kpis", scheduler).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/reports/schedule-kpis", manager).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the CSV export streams a header and one line per row")
    void csvExportStreams() {
        ResponseEntity<String> response = get("/api/v1/reports/fleet-utilization.csv", manager);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("fleet-utilization.csv");
        String[] lines = response.getBody().strip().split("\n");
        assertThat(lines[0]).startsWith("depotId,serviceDate,blocks");
        assertThat(lines).hasSizeGreaterThan(1);
        // Numbers use a fixed locale: a comma as a decimal separator would silently break every column.
        assertThat(lines[1]).matches("\\d+,[0-9-]+,\\d+,\\d+,\\d+,[0-9.]+,[0-9.]+,[0-9.]*,[0-9.]*");
    }

    @Test
    @DisplayName("the dashboard reports today's published work")
    void dashboardShowsToday() {
        // The dataset's schedule is for 2026-06-01, which is not today, so the snapshot for the real current
        // date is legitimately empty. What is asserted is the shape and the cache, not a figure.
        var snapshot = dashboardService.today(depotId);

        assertThat(snapshot.serviceDate()).isEqualTo(dashboardService.currentServiceDate());
        assertThat(snapshot.depotId()).isEqualTo(depotId);
        assertThat(snapshot.serviceTimeNow()).matches("\\d{2}:\\d{2}:\\d{2}");
        assertThat(snapshot.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("the dashboard serves a cached snapshot within its window")
    void dashboardIsCached() {
        var first = dashboardService.today(depotId);
        var second = dashboardService.today(depotId);

        // Same object content, including the generation timestamp: the second call did not rebuild. An
        // operations screen polling every few seconds would otherwise cost a scan per poll.
        assertThat(second.generatedAt()).isEqualTo(first.generatedAt());

        dashboardService.invalidate();
        assertThat(dashboardService.today(depotId).generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a planner cannot read the dashboard")
    void dashboardIsRestricted() {
        assertThat(get("/api/v1/dashboard/today", planner).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/dashboard/today", scheduler).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the audit log pages by cursor, newest first")
    void auditLogPagesByCursor() {
        ResponseEntity<String> firstPage = get("/api/v1/audit-logs?size=5", admin);

        assertThat(firstPage.getStatusCode()).as("%s", firstPage.getBody()).isEqualTo(HttpStatus.OK);
        var body = support.json(firstPage);
        assertThat(body.get("content")).isNotEmpty();
        assertThat(body.get("hasNext").asBoolean()).isTrue();

        String cursorAt = body.get("nextBeforeAt").asText();
        long cursorId = body.get("nextBeforeId").asLong();
        ResponseEntity<String> secondPage =
                get("/api/v1/audit-logs?size=5&beforeAt=" + cursorAt + "&beforeId=" + cursorId, admin);

        assertThat(secondPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No overlap between pages: a cursor cannot repeat a row the way a deep offset can when new records are
        // written during paging.
        var firstIds = new java.util.HashSet<Long>();
        body.get("content").forEach(entry -> firstIds.add(entry.get("id").asLong()));
        support.json(secondPage)
                .get("content")
                .forEach(entry -> assertThat(firstIds).doesNotContain(entry.get("id").asLong()));
    }

    @Test
    @DisplayName("audit payloads are redacted for a manager and visible to an administrator")
    void auditPayloadsAreMaskedByRole() {
        ResponseEntity<String> asManager = get("/api/v1/audit-logs?action=SCHEDULE_PUBLISHED&size=10", manager);
        ResponseEntity<String> asAdmin = get("/api/v1/audit-logs?action=SCHEDULE_PUBLISHED&size=10", admin);

        assertThat(asManager.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A before-and-after pair is a whole entity state, and a crew record carries a name, an employee code
        // and a licence number. A manager needs to know who changed what and why, not the personal data in it.
        ResponseEntity<String> createdAsManager = get("/api/v1/audit-logs?action=DEPOT_CREATED&size=5", manager);
        support.json(createdAsManager).get("content").forEach(entry -> {
            if (!entry.get("after").isNull()) {
                assertThat(entry.get("after").asText()).contains("redacted");
            }
        });
        ResponseEntity<String> createdAsAdmin = get("/api/v1/audit-logs?action=DEPOT_CREATED&size=5", admin);
        support.json(createdAsAdmin).get("content").forEach(entry -> {
            if (!entry.get("after").isNull()) {
                assertThat(entry.get("after").asText()).doesNotContain("redacted");
            }
        });
    }

    @Test
    @DisplayName("a scheduler cannot read the audit log at all")
    void auditLogIsRestricted() {
        assertThat(get("/api/v1/audit-logs", scheduler).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/audit-logs", planner).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("publishing writes exactly one audit record")
    void writeEndpointsAuditExactlyOnce() {
        Integer published = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'SCHEDULE_PUBLISHED' AND entity_id = ?",
                Integer.class,
                String.valueOf(scheduleId));

        // One record per write, not zero and not two. A duplicated audit row makes a trail that cannot be
        // counted, and a missing one makes a change nobody can attribute.
        assertThat(published).isEqualTo(1);
    }

    @Test
    @DisplayName("a scheduler sees crew names masked to initials")
    void crewNamesAreMaskedForSchedulers() {
        ResponseEntity<String> asScheduler = get("/api/v1/crew?size=5", scheduler);
        ResponseEntity<String> asManager = get("/api/v1/crew?size=5", manager);

        assertThat(asScheduler.getStatusCode()).isEqualTo(HttpStatus.OK);
        String schedulerName =
                support.json(asScheduler).get("content").get(0).get("name").asText();
        String managerName = support.json(asManager).get("content").get(0).get("name").asText();

        // A scheduler rosters by employee code; the full name is personal data they do not need for that.
        // The generated names are of the form "Crew 12", so the initials come out as "C. 1.".
        assertThat(schedulerName).matches("([A-Za-z0-9]\\.\\s?)+");
        assertThat(schedulerName).hasSizeLessThan(managerName.length());
        assertThat(managerName).isNotEqualTo(schedulerName);
    }

    // --- helpers ------------------------------------------------------------

    /** Runs the whole pipeline and publishes, returning the new schedule id. */
    private Long runAndPublish() {
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
        Long newScheduleId =
                jdbc.queryForObject("SELECT schedule_id FROM schedule_run WHERE id = ?", Long.class, runId);
        assertThat(newScheduleId).as("the run should have produced a schedule").isNotNull();

        // Crew shortfalls are accepted so the publication path can be exercised; they are a Phase 9 finding with
        // their own test.
        jdbc.update(
                "UPDATE conflict SET resolved = TRUE, resolved_by = 'test' WHERE schedule_id = ? AND severity = 'HARD'",
                newScheduleId);
        support.call(rest, HttpMethod.POST, "/api/v1/schedules/" + newScheduleId + "/validate", scheduler, null);
        ResponseEntity<String> published = support.call(
                rest, HttpMethod.POST, "/api/v1/schedules/" + newScheduleId + "/publish", admin, null);
        assertThat(published.getStatusCode()).as("%s", published.getBody()).isEqualTo(HttpStatus.OK);
        return newScheduleId;
    }

    private ResponseEntity<String> get(String path, String token) {
        return support.call(rest, HttpMethod.GET, path, token, null);
    }
}
