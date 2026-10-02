package com.dtc.transit.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * Day-type resolution, and the effect an override has on which timetable runs.
 *
 * <p>The important case is the last one: a holiday has to change the timetable a date resolves to, not merely
 * the label the calendar reports. An override that changed the label and left the service running weekday
 * frequencies would be worse than no calendar at all.
 */
class ServiceCalendarTest extends SecurityWebTest {

    /** A Wednesday, so the day of the week and the override disagree and the precedence is observable. */
    private static final String WEDNESDAY = "2026-10-07";

    private String admin;
    private String planner;
    private String scheduler;
    private Long depotId;
    private Long routeId;

    @BeforeEach
    void setUp() {
        support.createUser("cal-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "cal-admin");
        support.createUser("cal-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "cal-planner");
        support.createUser("cal-scheduler", 1L, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "cal-scheduler");

        depotId = Long.valueOf(support.field(
                post(
                        "/api/v1/depots",
                        admin,
                        """
                        {"code":"CAL-DPT","name":"Calendar depot","longitude":77.2,"latitude":28.6}"""),
                "id"));
        routeId = createRoute();
    }

    @Test
    @DisplayName("without an override the day type comes from the day of the week")
    void dayOfWeekIsTheDefault() {
        var response = dayType(WEDNESDAY, null);

        assertThat(support.field(response, "dayType")).isEqualTo("WEEKDAY");
        assertThat(support.field(response, "overridden")).isEqualTo("false");
        // The source is part of the answer, so an unexpected day type can be explained rather than guessed at.
        assertThat(support.field(response, "source")).isEqualTo("day of week");
    }

    @Test
    @DisplayName("a network-wide override replaces the day of the week")
    void networkWideOverride() {
        addException(WEDNESDAY, null, "HOLIDAY", "Diwali");

        var response = dayType(WEDNESDAY, null);

        assertThat(support.field(response, "dayType")).isEqualTo("HOLIDAY");
        assertThat(support.field(response, "overridden")).isEqualTo("true");
        assertThat(support.field(response, "note")).isEqualTo("Diwali");
    }

    @Test
    @DisplayName("a depot override wins over a network-wide one for that depot")
    void depotOverrideBeatsNetworkWide() {
        addException(WEDNESDAY, null, "HOLIDAY", "Network holiday");
        addException(WEDNESDAY, depotId, "SUNDAY", "Local event");

        // Precedence is fixed rather than left to insertion order: a local decision is the more specific of
        // the two, so it applies, and other depots keep the network answer.
        assertThat(support.field(dayType(WEDNESDAY, depotId), "dayType")).isEqualTo("SUNDAY");
        assertThat(support.field(dayType(WEDNESDAY, null), "dayType")).isEqualTo("HOLIDAY");
    }

    @Test
    @DisplayName("a second network-wide override for the same date is refused")
    void duplicateNetworkWideOverrideRefused() {
        addException(WEDNESDAY, null, "HOLIDAY", "First");

        ResponseEntity<String> second = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/calendar/exceptions",
                planner,
                """
                {"serviceDate":"%s","dayTypeOverride":"SUNDAY","note":"Second"}""".formatted(WEDNESDAY));

        // Two network-wide answers for one date would make the resolution ambiguous, so a partial unique
        // index refuses it. The point of the row is that the refusal reaches the caller as a conflict.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a holiday override changes the timetable the date resolves to")
    void overrideChangesTheTimetablePicked() {
        Long weekday = activateTimetable("WEEKDAY");
        Long holiday = activateTimetable("HOLIDAY");

        Long beforeOverride = timetableFor(WEDNESDAY);
        addException(WEDNESDAY, null, "HOLIDAY", "Diwali");
        Long afterOverride = timetableFor(WEDNESDAY);

        assertThat(beforeOverride).isEqualTo(weekday);
        // This is the check the phase gates on: the override has to reach as far as the trips that run.
        assertThat(afterOverride).isEqualTo(holiday);
    }

    // --- helpers ------------------------------------------------------------

    private ResponseEntity<String> dayType(String serviceDate, Long depot) {
        String path = "/api/v1/calendar/day-type?serviceDate=" + serviceDate
                + (depot == null ? "" : "&depotId=" + depot);
        ResponseEntity<String> response = support.call(rest, HttpMethod.GET, path, scheduler, null);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private void addException(String serviceDate, Long depot, String dayType, String note) {
        String body = depot == null
                ? """
                {"serviceDate":"%s","dayTypeOverride":"%s","note":"%s"}""".formatted(serviceDate, dayType, note)
                : """
                {"serviceDate":"%s","depotId":%d,"dayTypeOverride":"%s","note":"%s"}"""
                        .formatted(serviceDate, depot, dayType, note);
        ResponseEntity<String> response = post("/api/v1/calendar/exceptions", planner, body);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private Long timetableFor(String serviceDate) {
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.GET,
                "/api/v1/timetables/for-date?routeId=" + routeId + "&serviceDate=" + serviceDate,
                scheduler,
                null);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return support.json(response).get("timetable").get("id").asLong();
    }

    /** A timetable for one day type, with bands, trips and activation, which is the minimum that resolves. */
    private Long activateTimetable(String dayType) {
        ResponseEntity<String> created = post(
                "/api/v1/timetables",
                planner,
                """
                {"routeId":%d,"dayType":"%s","validFrom":"2026-06-01"}""".formatted(routeId, dayType));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        Long id = Long.valueOf(support.field(created, "id"));

        support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + routeId + "/patterns/UP/running-times",
                planner,
                """
                {"dayType":"%s","bands":[{"fromSec":0,"toSec":129600,"runningSec":2700}]}""".formatted(dayType));
        ResponseEntity<String> bands = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/timetables/" + id + "/headway-bands/UP",
                planner,
                """
                {"bands":[{"fromSec":21600,"toSec":36000,"headwaySec":1800}]}""");
        assertThat(bands.getStatusCode()).as("%s", bands.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> generated = post("/api/v1/timetables/" + id + "/generate-trips", planner, null);
        assertThat(generated.getStatusCode()).as("%s", generated.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> activated = post("/api/v1/timetables/" + id + "/activate", planner, null);
        assertThat(activated.getStatusCode()).as("%s", activated.getBody()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private Long createRoute() {
        ResponseEntity<String> created = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"CAL-1","name":"Calendar route","depotId":%d}""".formatted(depotId));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        Long id = Long.valueOf(support.field(created, "id"));
        support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + id + "/patterns/UP",
                planner,
                """
                {"geometry":{"type":"LineString","coordinates":[[77.20,28.55],[77.20,28.61],[77.20,28.66]]}}""");
        return id;
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return support.call(rest, HttpMethod.POST, path, token, body);
    }
}
