package com.dtc.transit.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.timetable.timetable.TimetableService;
import com.dtc.transit.user.Role;

/**
 * The timetable lifecycle over HTTP: bands, generation, activation and the trip list.
 *
 * <p>Driven through the API rather than the service, because the parts that most easily break are the ones
 * between layers: the generated trip count reaching the response, the exclusion constraints surfacing as
 * conflicts instead of internal errors, and the cursor the trip list hands back actually working.
 */
class TimetableWorkflowTest extends SecurityWebTest {

    /** Morning peak, midday trough, evening peak: 12 + 6 + 12 departures per direction. */
    private static final String BANDS =
            """
            {"bands":[{"fromSec":21600,"toSec":36000,"headwaySec":1200},
                      {"fromSec":36000,"toSec":57600,"headwaySec":3600},
                      {"fromSec":57600,"toSec":72000,"headwaySec":1200}]}""";

    private static final int EXPECTED_TRIPS_PER_DIRECTION = 30;

    @Autowired
    private RecordedChanges recordedChanges;

    private String admin;
    private String planner;
    private String scheduler;
    private Long depotId;
    private Long routeId;

    @BeforeEach
    void setUp() {
        support.createUser("tt-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "tt-admin");
        support.createUser("tt-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "tt-planner");
        support.createUser("tt-scheduler", 1L, Role.SCHEDULER);
        scheduler = support.accessTokenFor(rest, "tt-scheduler");

        depotId = Long.valueOf(support.field(
                post(
                        "/api/v1/depots",
                        admin,
                        """
                        {"code":"TT-DPT","name":"Timetable depot","longitude":77.2,"latitude":28.6}"""),
                "id"));
        routeId = createRouteWithBothDirections("TT-100");
    }

    @Test
    @DisplayName("the generated trip count equals the analytical count for both directions")
    void generatedCountMatchesAnalyticalCount() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        setBands(timetableId, "DOWN");

        ResponseEntity<String> response = generateTrips(timetableId);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        // The service reports both numbers so they can be compared rather than assumed equal. The
        // analytical figure is the sum over bands of ceil(window / headway), computed from the bands alone.
        int generated = support.json(response).get("generatedCount").asInt();
        int analytical = support.json(response).get("analyticalCount").asInt();
        assertThat(generated).isEqualTo(analytical).isEqualTo(EXPECTED_TRIPS_PER_DIRECTION * 2);
    }

    @Test
    @DisplayName("a direction with no bands produces no trips rather than failing")
    void directionWithoutBandsIsSkipped() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");

        // A route whose return working is covered by a different timetable is normal, and so is a one-way
        // loop. Refusing to generate would make either impossible to express.
        ResponseEntity<String> response = generateTrips(timetableId);

        assertThat(support.json(response).get("generatedCount").asInt()).isEqualTo(EXPECTED_TRIPS_PER_DIRECTION);
    }

    @Test
    @DisplayName("regenerating replaces the previous trips instead of adding to them")
    void regenerationReplaces() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        generateTrips(timetableId);

        ResponseEntity<String> second = generateTrips(timetableId);

        // Appending would hit the unique constraint on (timetable, pattern, departure), so a planner who
        // made a mistake in the bands could never correct it.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.json(second).get("generatedCount").asInt()).isEqualTo(EXPECTED_TRIPS_PER_DIRECTION);
        assertThat(tripIdsOf(timetableId)).hasSize(EXPECTED_TRIPS_PER_DIRECTION);
    }

    @Test
    @DisplayName("EC-TT-03: overlapping headway bands are refused")
    void overlappingBandsRejected() {
        Long timetableId = createTimetable();

        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/timetables/" + timetableId + "/headway-bands/UP",
                planner,
                """
                {"bands":[{"fromSec":21600,"toSec":36000,"headwaySec":1200},
                          {"fromSec":32400,"toSec":43200,"headwaySec":1800}]}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("HEADWAY_BANDS_OVERLAP");
    }

    @Test
    @DisplayName("a timetable cannot be activated before its trips exist")
    void activationRequiresTrips() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");

        ResponseEntity<String> response = post("/api/v1/timetables/" + timetableId + "/activate", planner, null);

        // An active timetable with no trips would resolve for a service date and then produce no service,
        // which is worse than having no timetable at all.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("TIMETABLE_HAS_NO_TRIPS");
    }

    @Test
    @DisplayName("EC-TIME-06: two active timetables may not cover the same route, day type and dates")
    void overlappingValidityRefused() {
        Long first = createTimetable();
        setBands(first, "UP");
        generateTrips(first);
        assertThat(post("/api/v1/timetables/" + first + "/activate", planner, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Long second = createTimetable();
        setBands(second, "UP");
        generateTrips(second);
        ResponseEntity<String> response = post("/api/v1/timetables/" + second + "/activate", planner, null);

        // Reported as a conflict the caller can act on, naming the clash. The exclusion constraint behind
        // it would otherwise surface as an unexplained failure.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(response, "code")).isEqualTo("TIMETABLE_VALIDITY_OVERLAPS");
    }

    @Test
    @DisplayName("an active timetable's bands and trips are frozen")
    void activeTimetableIsFrozen() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        generateTrips(timetableId);
        post("/api/v1/timetables/" + timetableId + "/activate", planner, null);

        ResponseEntity<String> bands = support.call(
                rest, HttpMethod.PUT, "/api/v1/timetables/" + timetableId + "/headway-bands/UP", planner, BANDS);
        ResponseEntity<String> regenerate = generateTrips(timetableId);

        // Changing a live timetable underneath the schedules built from it is how a published duty roster
        // stops matching the service it was built for. A new version is the only safe route.
        assertThat(bands.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(bands, "code")).isEqualTo("TIMETABLE_NOT_EDITABLE");
        assertThat(regenerate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a retired timetable frees the validity window for its replacement")
    void retirementFreesTheWindow() {
        Long first = createTimetable();
        setBands(first, "UP");
        generateTrips(first);
        post("/api/v1/timetables/" + first + "/activate", planner, null);
        post("/api/v1/timetables/" + first + "/retire", planner, null);

        Long second = createTimetable();
        setBands(second, "UP");
        generateTrips(second);

        // The exclusion constraint is conditional on status, which is what makes replacing a timetable
        // possible at all; a constraint over every row would deadlock the network permanently.
        assertThat(post("/api/v1/timetables/" + second + "/activate", planner, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("EC-API-05: the cursor-paged trip list neither repeats nor skips a trip")
    void tripListPagesByCursor() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        setBands(timetableId, "DOWN");
        generateTrips(timetableId);

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/trips?timetableId=" + timetableId + "&limit=7"
                    + (cursor == null ? "" : "&after=" + cursor);
            ResponseEntity<String> page = support.call(rest, HttpMethod.GET, path, scheduler, null);
            assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);

            var body = support.json(page);
            body.get("content").forEach(trip -> seen.add(trip.get("id").asLong()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asLong();
            pages++;
        } while (cursor != null && pages < 100);

        assertThat(seen).hasSize(EXPECTED_TRIPS_PER_DIRECTION * 2).doesNotHaveDuplicates();
        // Several pages, not one: a single page would have proved nothing about the cursor.
        assertThat(pages).isGreaterThan(1);
    }

    @Test
    @DisplayName("an oversized trip page limit is clamped rather than rejected")
    void tripLimitIsClamped() {
        ResponseEntity<String> response =
                support.call(rest, HttpMethod.GET, "/api/v1/trips?limit=5000", scheduler, null);

        // Matches the Phase 3 behaviour for offset pages. One list endpoint rejecting what another clamps
        // would be a trap for a client that pages both.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.json(response).get("size").asInt()).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("the active timetable for a service date is resolved per date")
    void activeTimetableResolution() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        generateTrips(timetableId);
        post("/api/v1/timetables/" + timetableId + "/activate", planner, null);

        String path = "/api/v1/timetables/active?routeId=" + routeId + "&dayType=WEEKDAY&serviceDate=";
        ResponseEntity<String> inside = support.call(rest, HttpMethod.GET, path + "2026-07-01", scheduler, null);
        ResponseEntity<String> before = support.call(rest, HttpMethod.GET, path + "2026-01-01", scheduler, null);

        assertThat(inside.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.longField(inside, "id")).isEqualTo(timetableId);
        // Before the validity window there is no timetable, and saying so is better than returning the
        // nearest one and letting a schedule be built from dates it was never approved for.
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(before, "code")).isEqualTo("NO_TIMETABLE");
    }

    @Test
    @DisplayName("regenerating trips publishes the change event later phases revalidate from")
    void generationPublishesTheChangeEvent() {
        Long timetableId = createTimetable();
        setBands(timetableId, "UP");
        recordedChanges.clear();

        generateTrips(timetableId);

        // Nothing consumes this yet: the schedule table arrives in Phase 6. The event is asserted now
        // because it is the seam those phases attach to, and a seam that was never published would be
        // discovered only once something depended on it.
        assertThat(recordedChanges.timetableIds()).containsExactly(timetableId);
    }

    // --- helpers ------------------------------------------------------------

    private Long createTimetable() {
        ResponseEntity<String> response = post(
                "/api/v1/timetables",
                planner,
                """
                {"routeId":%d,"dayType":"WEEKDAY","validFrom":"2026-06-01"}""".formatted(routeId));
        assertThat(response.getStatusCode()).as("create: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(support.field(response, "id"));
    }

    private void setBands(Long timetableId, String direction) {
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/timetables/" + timetableId + "/headway-bands/" + direction,
                planner,
                BANDS);
        assertThat(response.getStatusCode()).as("bands: %s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> generateTrips(Long timetableId) {
        return post("/api/v1/timetables/" + timetableId + "/generate-trips", planner, null);
    }

    private List<Long> tripIdsOf(Long timetableId) {
        ResponseEntity<String> response = support.call(
                rest, HttpMethod.GET, "/api/v1/trips?timetableId=" + timetableId + "&limit=500", scheduler, null);
        List<Long> ids = new ArrayList<>();
        support.json(response).get("content").forEach(trip -> ids.add(trip.get("id").asLong()));
        return ids;
    }

    /**
     * A route with both directions drawn and running times set, which is the minimum a timetable needs.
     *
     * <p>Running times are set for both directions and cover the whole service window, so generation runs in
     * strict mode. A fixture that only half-covered the window would make every test here depend on the
     * fallback behaviour instead of the rule it is checking.
     */
    private Long createRouteWithBothDirections(String routeNo) {
        ResponseEntity<String> created = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"%s","name":"Timetable route","depotId":%d}""".formatted(routeNo, depotId));
        assertThat(created.getStatusCode()).as("route: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        Long id = Long.valueOf(support.field(created, "id"));

        // A 12 km north-south line inside the Delhi service area, so the derived running time gives a
        // plausible average speed and generation produces no warnings.
        String up =
                """
                {"type":"LineString","coordinates":[[77.20,28.55],[77.20,28.61],[77.20,28.66]]}""";
        String down =
                """
                {"type":"LineString","coordinates":[[77.20,28.66],[77.20,28.61],[77.20,28.55]]}""";
        putPattern(id, "UP", up);
        putPattern(id, "DOWN", down);
        setRunningTimes(id, "UP");
        setRunningTimes(id, "DOWN");
        return id;
    }

    private void putPattern(Long id, String direction, String geometry) {
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + id + "/patterns/" + direction,
                planner,
                """
                {"geometry":%s}""".formatted(geometry));
        assertThat(response.getStatusCode()).as("pattern: %s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void setRunningTimes(Long id, String direction) {
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + id + "/patterns/" + direction + "/running-times",
                planner,
                """
                {"dayType":"WEEKDAY","bands":[{"fromSec":0,"toSec":129600,"runningSec":2700}]}""");
        assertThat(response.getStatusCode())
                .as("running times: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return support.call(rest, HttpMethod.POST, path, token, body);
    }

    @TestConfiguration
    static class EventRecordingConfiguration {

        @Bean
        RecordedChanges recordedChanges() {
            return new RecordedChanges();
        }
    }

    /**
     * Collects the timetable-change events the context publishes.
     *
     * <p>A listener bean rather than {@code @RecordApplicationEvents}: the event is published on the server's
     * request thread, and the recording support is scoped to the test's own thread.
     */
    static class RecordedChanges {

        private final java.util.List<Long> timetableIds =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @EventListener
        void on(TimetableService.TimetableChanged event) {
            timetableIds.add(event.timetableId());
        }

        void clear() {
            timetableIds.clear();
        }

        java.util.List<Long> timetableIds() {
            return java.util.List.copyOf(timetableIds);
        }
    }
}
