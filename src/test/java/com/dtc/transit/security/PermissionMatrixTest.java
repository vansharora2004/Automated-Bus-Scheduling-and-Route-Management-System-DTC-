package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * Every endpoint checked against every role, in each access context.
 *
 * <p>This exists from Phase 2, with only the handful of endpoints that exist so far, and grows in every
 * later phase. Built at the end instead, it would have to be reconstructed from finished code, which is
 * exactly when a wrong assumption becomes permanent.
 *
 * <p>{@link #everyEndpointIsClassified()} is the part that keeps it honest: it fails when an endpoint
 * is added without a row here, so coverage cannot quietly rot.
 *
 * <p>{@code @AutoConfigureObservability} is required because Spring Boot switches metrics exporters off
 * inside {@code @SpringBootTest}, which would leave {@code /actuator/prometheus} unmapped and make its
 * authorization rule look verified when nothing had been checked.
 */
@AutoConfigureObservability
class PermissionMatrixTest extends SecurityWebTest {

    // Qualified explicitly: the actuator contributes a second RequestMappingHandlerMapping bean, and
    // only the MVC one carries the application's own endpoints.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * One expected outcome per endpoint and caller.
     *
     * @param expected the status a correctly configured system returns
     */
    record Case(HttpMethod method, String path, Caller caller, HttpStatus expected, String body) {
        @Override
        public String toString() {
            return method + " " + path + " as " + caller + " -> " + expected.value();
        }
    }

    enum Caller {
        ANONYMOUS,
        ADMIN_HQ,
        MANAGER_DEPOT_1,
        PLANNER_HQ,
        SCHEDULER_DEPOT_1
    }

    /** Request bodies reused across rows, so a row reads as a permission statement, not a payload. */
    private static final String DEPOT =
            """
            {"code":"MTX-DPT","name":"Matrix depot","longitude":77.1,"latitude":28.6}""";

    private static final String STOP =
            """
            {"code":"MTX-STP","name":"Matrix stop","longitude":77.1,"latitude":28.6}""";

    private static final String BUS =
            """
            {"registrationNo":"DLMTX0001","fleetNo":"M-1","depotId":1,"busType":"STANDARD",
             "fuelType":"CNG","capacity":40}""";

    private static final String BUS_STATUS = """
            {"status":"BREAKDOWN"}""";

    private static final String CREW =
            """
            {"employeeCode":"090001","name":"Matrix Crew","crewRole":"CONDUCTOR","depotId":1}""";

    private static final String LEAVE =
            """
            {"from":"2026-05-01T04:00:00Z","to":"2026-05-01T09:00:00Z","leaveType":"CASUAL"}""";

    private static final String QUALIFICATION = """
            {"code":"EV"}""";

    private static final String TRANSFER = """
            {"depotId":1,"effectiveFrom":"2026-05-01"}""";

    private static final String ROUTE =
            """
            {"routeNo":"MTX-R1","name":"Matrix route","depotId":1}""";

    private static final String PATTERN =
            """
            {"geometry":{"type":"LineString","coordinates":[[77.20,28.60],[77.20,28.64]]}}""";

    private static final String OVERLAP_QUERY =
            """
            {"geometry":{"type":"LineString","coordinates":[[77.20,28.60],[77.20,28.64]]}}""";

    private static final String DECISION = """
            {"approve":true,"note":"ok"}""";

    private static final String ACTIVATE = """
            {"effectiveFrom":"2026-06-01"}""";

    private static final String COVERAGE_GAIN = """
            {"stopIds":[1]}""";

    private static final String RUNNING_TIMES =
            """
            {"dayType":"WEEKDAY","bands":[{"fromSec":21600,"toSec":36000,"runningSec":3600}]}""";

    private static final String TIMETABLE =
            """
            {"routeId":999999,"dayType":"WEEKDAY","validFrom":"2026-06-01"}""";

    private static final String HEADWAY_BANDS =
            """
            {"bands":[{"fromSec":21600,"toSec":36000,"headwaySec":600}]}""";

    /** A complete rule set, since the typed record refuses a partial one. */
    private static final String RULE_SET_RULES =
            """
            {"maxWorkPerDutyMin":480,"maxContinuousWorkMin":300,"minBreakMin":30,"maxSpreadOverMin":720,
             "maxWeeklyWorkMin":2880,"weeklyRestDaysPer7":1,"minRestBetweenDutiesMin":600,
             "signOnMin":15,"signOffMin":10,"minLayoverMin":5,"minLayoverPct":10,"handoverBufferMin":5,
             "maxPiecesPerDuty":3,"maxBusChangeoversPerDuty":2,"targetWorkPerDutyMin":450,
             "minPaidDutyMin":240,"allowOvertime":false,"maxOvertimeMin":60,
             "midDayDepotReturnGapMin":90,"evRangeReservePct":15,"standbyPoolPct":5,
             "maxBlockDurationMin":1140,"evChargingMin":45}""";

    private static final String CREATE_RULE_SET =
            """
            {"name":"Matrix rules","effectiveFrom":"2031-01-01","rules":%s}""".formatted(RULE_SET_RULES);

    private static final String REPLACE_RULE_SET =
            """
            {"effectiveFrom":"2032-01-01","rules":%s}""".formatted(RULE_SET_RULES);

    private static final String VALIDATE_RULE_SET = """
            {"rules":%s}""".formatted(RULE_SET_RULES);

    private static final String OVERRIDE_ASSIGNMENT =
            """
            {"crewMemberId":1,"reason":"covering a late sickness"}""";

    private static final String QUEUE_RUN =
            """
            {"depotId":999999,"serviceDate":"2026-06-01","mode":"LINKED"}""";

    private static final String RESOLVE_CONFLICT = """
            {"reason":"accepted by the duty officer"}""";

    private static final String CALENDAR_EXCEPTION =
            """
            {"serviceDate":"2026-10-02","dayTypeOverride":"SUNDAY","note":"Gandhi Jayanti"}""";

    static Stream<Case> cases() {
        String newUser =
                """
                {"username":"created-by-matrix","password":"correct-horse-battery-staple","roles":["SCHEDULER"]}""";
        return Stream.of(
                // Logout requires authentication but no particular role.
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.SCHEDULER_DEPOT_1, HttpStatus.NO_CONTENT, null),
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.ADMIN_HQ, HttpStatus.NO_CONTENT, null),

                // User administration is ADMIN only.
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                // A deliberately out-of-range id, not a low one: the id sequence keeps climbing across
                // tests, so "1" could legitimately belong to a user created moments earlier. ADMIN
                // therefore gets 404 rather than 403 - the request was allowed, the row is simply absent.
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),

                new Case(HttpMethod.POST, "/api/v1/users", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.ADMIN_HQ, HttpStatus.CREATED, newUser),

                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, "{}"),
                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, "{}"),
                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, "{}"),

                // Metrics and API docs are not public.
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.ADMIN_HQ, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/v3/api-docs", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/v3/api-docs", Caller.ADMIN_HQ, HttpStatus.OK, null),

                // ---- Phase 3 master data ----
                // Depots are readable by every authenticated role; only ADMIN may create one.
                new Case(HttpMethod.GET, "/api/v1/depots", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/depots", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/depots", Caller.PLANNER_HQ, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/depots/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(HttpMethod.POST, "/api/v1/depots", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, DEPOT),
                new Case(HttpMethod.POST, "/api/v1/depots", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, DEPOT),
                new Case(HttpMethod.POST, "/api/v1/depots", Caller.ADMIN_HQ, HttpStatus.CREATED, DEPOT),

                // Stops are network-wide: planners own them, every authenticated role may read them.
                new Case(HttpMethod.GET, "/api/v1/stops", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/stops", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/stops/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(HttpMethod.POST, "/api/v1/stops", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, STOP),
                new Case(HttpMethod.POST, "/api/v1/stops", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, STOP),
                new Case(HttpMethod.POST, "/api/v1/stops", Caller.PLANNER_HQ, HttpStatus.CREATED, STOP),

                // Buses: planners may read the fleet but never change it.
                new Case(HttpMethod.GET, "/api/v1/buses", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/buses", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/buses/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/buses/999999/unavailability",
                        Caller.ADMIN_HQ,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(HttpMethod.POST, "/api/v1/buses", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, BUS),
                new Case(HttpMethod.POST, "/api/v1/buses", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, BUS),
                new Case(
                        HttpMethod.PATCH,
                        "/api/v1/buses/999999/status",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        BUS_STATUS),
                // A scheduler may record a breakdown in their own depot, so the request is permitted and
                // only then reports the missing bus.
                new Case(
                        HttpMethod.PATCH,
                        "/api/v1/buses/999999/status",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        BUS_STATUS),
                new Case(HttpMethod.POST, "/api/v1/buses/import", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/buses/import",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),

                // Crew: planners have no business in crew records at all.
                new Case(HttpMethod.GET, "/api/v1/crew", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/crew", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/crew", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/crew/999999", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/crew/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(HttpMethod.GET, "/api/v1/crew/999999/leaves", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/crew/999999/qualifications",
                        Caller.ADMIN_HQ,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/crew/999999/depot-history",
                        Caller.ADMIN_HQ,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(HttpMethod.POST, "/api/v1/crew", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, CREW),
                new Case(HttpMethod.POST, "/api/v1/crew", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, CREW),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/crew/999999/leaves",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        LEAVE),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/crew/999999/qualifications",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        QUALIFICATION),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/crew/999999/transfer",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        TRANSFER),
                new Case(HttpMethod.POST, "/api/v1/crew/import", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),

                // The user list, deferred from Phase 2 so it could use the shared paging framework.
                new Case(HttpMethod.GET, "/api/v1/users", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/users", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/users", Caller.ADMIN_HQ, HttpStatus.OK, null),

                // ---- Phase 4 routes and coverage ----
                // Routes are readable by every authenticated role; planners own them.
                new Case(HttpMethod.GET, "/api/v1/routes", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/routes", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/routes/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                new Case(HttpMethod.POST, "/api/v1/routes", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, ROUTE),
                new Case(
                        HttpMethod.POST, "/api/v1/routes", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, ROUTE),

                // Drawing geometry is a planner's job, not a manager's.
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/routes/999999/patterns/UP",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        PATTERN),
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/routes/999999/patterns/UP",
                        Caller.PLANNER_HQ,
                        HttpStatus.NOT_FOUND,
                        PATTERN),

                // Ad-hoc analysis is the tool a planner uses while drawing; a scheduler has no use for it.
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/overlap-analysis",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        OVERLAP_QUERY),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/overlap-analysis",
                        Caller.PLANNER_HQ,
                        HttpStatus.OK,
                        OVERLAP_QUERY),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/routes/999999/overlaps",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/routes/999999/overlaps",
                        Caller.PLANNER_HQ,
                        HttpStatus.NOT_FOUND,
                        null),

                // Submitting is the planner's; deciding, activating and retiring are the manager's. This
                // split is what makes the separation-of-duties rule meaningful.
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/999999/submit",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/999999/decision",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        DECISION),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/999999/activate",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        ACTIVATE),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/routes/999999/retire",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),

                // Coverage reporting is for planning roles; the maintenance actions are administrator-only.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/coverage/zones",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/coverage/gain",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        COVERAGE_GAIN),
                new Case(HttpMethod.POST, "/api/v1/coverage/grid", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(
                        HttpMethod.POST, "/api/v1/coverage/refresh", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),

                // ---- Phase 5 timetables, trips and the calendar ----
                // Running times describe the road, so they are the planner's to set and anyone's to read.
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/routes/999999/patterns/UP/running-times",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        RUNNING_TIMES),
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/routes/999999/patterns/UP/running-times",
                        Caller.PLANNER_HQ,
                        HttpStatus.NOT_FOUND,
                        RUNNING_TIMES),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/routes/999999/patterns/UP/running-times",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),

                // Timetables are the planner's to build. A manager approves routes but does not write
                // headways, and a scheduler consumes timetables rather than authoring them.
                new Case(
                        HttpMethod.POST, "/api/v1/timetables", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, TIMETABLE),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        TIMETABLE),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        TIMETABLE),
                new Case(HttpMethod.POST, "/api/v1/timetables", Caller.PLANNER_HQ, HttpStatus.NOT_FOUND, TIMETABLE),

                // Every authenticated role may read a timetable: a scheduler cannot build blocks without it.
                new Case(
                        HttpMethod.GET, "/api/v1/timetables/999999", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/timetables/999999",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                // Called without its required parameters on purpose. The point of the row is that
                // authorization decides before binding does: anonymous is refused, while a scheduler gets
                // as far as the handler and is told the request is malformed.
                new Case(
                        HttpMethod.GET, "/api/v1/timetables/active", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/timetables/active",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),

                // Asked by date rather than by day type, so the holiday calendar cannot be skipped.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/timetables/for-date",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/timetables/for-date",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),

                new Case(
                        HttpMethod.PUT,
                        "/api/v1/timetables/999999/headway-bands/UP",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        HEADWAY_BANDS),
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/timetables/999999/headway-bands/UP",
                        Caller.PLANNER_HQ,
                        HttpStatus.NOT_FOUND,
                        HEADWAY_BANDS),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables/999999/generate-trips",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables/999999/generate-trips",
                        Caller.PLANNER_HQ,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables/999999/activate",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/timetables/999999/retire",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),

                // Trips are read-only over the API and readable by every authenticated role.
                new Case(HttpMethod.GET, "/api/v1/trips", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/trips", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),

                // The calendar is read by everyone and written by planners: a holiday changes what the
                // whole network runs, so it is not a depot-local decision.
                new Case(HttpMethod.GET, "/api/v1/calendar/day-type", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/calendar/day-type",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/calendar/exceptions",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/calendar/exceptions",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        CALENDAR_EXCEPTION),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/calendar/exceptions",
                        Caller.PLANNER_HQ,
                        HttpStatus.OK,
                        CALENDAR_EXCEPTION),

                // Deadheads are what a scheduler needs most, so reads are open to every authenticated role.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/deadheads/estimated",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/deadheads/estimated",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.OK,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/deadheads",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),

                // ---- Phase 6 scheduling ----
                // Running the depot's day belongs to schedulers and the managers above them. A planner designs
                // the network and has no business starting a build.
                new Case(
                        HttpMethod.POST, "/api/v1/schedule-runs", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED,
                        QUEUE_RUN),
                new Case(
                        HttpMethod.POST, "/api/v1/schedule-runs", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN,
                        QUEUE_RUN),
                // A depot that does not exist, so the request is allowed and only then reports the missing row.
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedule-runs",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        QUEUE_RUN),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedule-runs/00000000-0000-0000-0000-000000000000",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedule-runs/00000000-0000-0000-0000-000000000000",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedule-runs/00000000-0000-0000-0000-000000000000/events",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedule-runs/00000000-0000-0000-0000-000000000000/cancel",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(HttpMethod.GET, "/api/v1/schedule-runs", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),

                // Schedules are read by the operating roles and by nobody else.
                new Case(HttpMethod.GET, "/api/v1/schedules", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/schedules", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/schedules/999999", Caller.SCHEDULER_DEPOT_1, HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/blocks",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/bus-assignments",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/conflicts",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/validate",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/discard",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),

                // Publishing tells crews where to be, so it stops at a manager. Overriding a conflict is the
                // same kind of decision and carries the same restriction.
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/publish",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/publish",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/conflicts/999999/resolve",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        RESOLVE_CONFLICT),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/schedules/999999/conflicts/999999/resolve",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        RESOLVE_CONFLICT),

                // ---- Phase 7 rules and duties ----
                // The rules are readable by every authenticated role, because everyone needs to know what the
                // schedule was judged against. Changing them is an administrator's decision: a rule set decides
                // what is legal, so it is not a per-depot operational knob.
                new Case(HttpMethod.GET, "/api/v1/rule-sets", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/rule-sets", Caller.SCHEDULER_DEPOT_1, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/rule-sets", Caller.PLANNER_HQ, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/api/v1/rule-sets/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),
                // Called without its required parameters, so the row shows authorization deciding before binding.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/rule-sets/effective",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/rule-sets",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        CREATE_RULE_SET),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/rule-sets",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        CREATE_RULE_SET),
                new Case(
                        HttpMethod.POST, "/api/v1/rule-sets", Caller.ADMIN_HQ, HttpStatus.CREATED, CREATE_RULE_SET),
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/rule-sets/999999",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.FORBIDDEN,
                        REPLACE_RULE_SET),
                new Case(
                        HttpMethod.PUT,
                        "/api/v1/rule-sets/999999",
                        Caller.ADMIN_HQ,
                        HttpStatus.NOT_FOUND,
                        REPLACE_RULE_SET),
                // Checking a rule set is harmless and useful to anyone editing one.
                new Case(
                        HttpMethod.POST,
                        "/api/v1/rule-sets/validate",
                        Caller.PLANNER_HQ,
                        HttpStatus.OK,
                        VALIDATE_RULE_SET),
                new Case(
                        HttpMethod.POST,
                        "/api/v1/rule-sets/validate",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        VALIDATE_RULE_SET),

                // Duties and handovers follow their schedule: the operating roles, and nobody else.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/duties",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/duties",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/duties/999999",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/handovers",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/duty-summary",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/schedules/999999/duty-summary",
                        Caller.ADMIN_HQ,
                        HttpStatus.NOT_FOUND,
                        null),

                // ---- Phase 9 crew assignments ----
                // Rosters are operational: the roles that run the depot can read and change them, and a planner
                // cannot. The override additionally needs If-Match, which the flow test covers.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/duty-assignments/999999",
                        Caller.ANONYMOUS,
                        HttpStatus.UNAUTHORIZED,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/duty-assignments/999999",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        null),
                new Case(
                        HttpMethod.GET,
                        "/api/v1/duty-assignments/999999",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.NOT_FOUND,
                        null),
                // Called without its required parameter, so the row shows authorization deciding before binding.
                new Case(
                        HttpMethod.GET,
                        "/api/v1/duty-assignments",
                        Caller.MANAGER_DEPOT_1,
                        HttpStatus.BAD_REQUEST,
                        null),
                new Case(
                        HttpMethod.PATCH,
                        "/api/v1/duty-assignments/999999",
                        Caller.PLANNER_HQ,
                        HttpStatus.FORBIDDEN,
                        OVERRIDE_ASSIGNMENT),
                // A scheduler is allowed through, and is then told the override needs a version.
                new Case(
                        HttpMethod.PATCH,
                        "/api/v1/duty-assignments/999999",
                        Caller.SCHEDULER_DEPOT_1,
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        OVERRIDE_ASSIGNMENT),

                // Health must answer before any token exists, for liveness probes.
                new Case(HttpMethod.GET, "/actuator/health", Caller.ANONYMOUS, HttpStatus.OK, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matrix(Case testCase) {
        String token = tokenFor(testCase.caller());

        ResponseEntity<String> response =
                support.call(rest, testCase.method(), testCase.path(), token, testCase.body());

        assertThat(response.getStatusCode())
                .as("%s %s as %s", testCase.method(), testCase.path(), testCase.caller())
                .isEqualTo(testCase.expected());
    }

    @Test
    @DisplayName("every mapped API endpoint has at least one matrix row")
    void everyEndpointIsClassified() {
        var parser = new PathPatternParser();

        List<String> unclassified = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    var patterns = info.getPathPatternsCondition() == null
                            ? Set.<String>of()
                            : info.getPathPatternsCondition().getPatternValues();
                    var methods = info.getMethodsCondition().getMethods();
                    return patterns.stream()
                            .filter(pattern -> pattern.startsWith("/api/"))
                            .flatMap(pattern -> methods.isEmpty()
                                    ? Stream.of("GET " + pattern)
                                    : methods.stream().map(method -> method.name() + " " + pattern));
                })
                .distinct()
                // Login and refresh are exercised in depth by the authentication tests.
                .filter(key -> !key.endsWith("/api/v1/auth/login") && !key.endsWith("/api/v1/auth/refresh"))
                .filter(key -> !isCovered(parser, key))
                .sorted()
                .toList();

        assertThat(unclassified)
                .as("endpoints with no permission-matrix row; add a case for each")
                .isEmpty();
    }

    /**
     * Whether some matrix row exercises this mapping.
     *
     * <p>Matched with Spring's own {@link PathPatternParser} rather than by normalising strings. The
     * earlier string approach could not tell {@code /routes/{id}/patterns/{direction}} from a concrete
     * {@code /routes/9/patterns/UP}, because only one of the two variables looks like an id, so a
     * genuinely unclassified endpoint could slip through as covered.
     */
    private static boolean isCovered(PathPatternParser parser, String methodAndPattern) {
        int space = methodAndPattern.indexOf(' ');
        String method = methodAndPattern.substring(0, space);
        var pattern = parser.parse(methodAndPattern.substring(space + 1));

        return cases().anyMatch(testCase -> testCase.method().name().equals(method)
                && pattern.matches(PathContainer.parsePath(testCase.path())));
    }

    private String tokenFor(Caller caller) {
        return switch (caller) {
            case ANONYMOUS -> null;
            case ADMIN_HQ -> {
                support.createUser("matrix-admin", null, Role.ADMIN);
                yield support.accessTokenFor(rest, "matrix-admin");
            }
            case MANAGER_DEPOT_1 -> {
                support.createUser("matrix-manager", 1L, Role.MANAGER);
                yield support.accessTokenFor(rest, "matrix-manager");
            }
            case PLANNER_HQ -> {
                support.createUser("matrix-planner", null, Role.PLANNER);
                yield support.accessTokenFor(rest, "matrix-planner");
            }
            case SCHEDULER_DEPOT_1 -> {
                support.createUser("matrix-scheduler", 1L, Role.SCHEDULER);
                yield support.accessTokenFor(rest, "matrix-scheduler");
            }
        };
    }
}
