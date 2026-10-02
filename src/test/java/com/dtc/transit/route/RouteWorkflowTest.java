package com.dtc.transit.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.GeometryFixtures;
import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/** Geometry validation and the route proposal lifecycle. */
class RouteWorkflowTest extends SecurityWebTest {

    private String admin;
    private String planner;
    private String manager;
    private Long depotId;

    @BeforeEach
    void setUp() {
        support.createUser("wf-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "wf-admin");
        support.createUser("wf-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "wf-planner");
        support.createUser("wf-manager", null, Role.MANAGER);
        manager = support.accessTokenFor(rest, "wf-manager");
        depotId = Long.valueOf(support.field(
                post(
                        "/api/v1/depots",
                        admin,
                        """
                        {"code":"WF-DPT","name":"Workflow depot","longitude":77.2,"latitude":28.6}"""),
                "id"));
    }

    // --- geometry validation ------------------------------------------------

    @Test
    @DisplayName("EC-GEO-01: a lon/lat swap is finally rejected, by the service area")
    void swappedCoordinatesRejected() {
        Long routeId = createRoute("W-200");

        ResponseEntity<String> response = putPattern(routeId, GeometryFixtures.swappedCoordinates());

        // This is what Phase 3 provably could not do: latitude 77 is valid, so only containment in the
        // operating boundary reveals the swap.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("OUTSIDE_SERVICE_AREA");
        // The message tells the planner what to do, not merely that something is wrong.
        assertThat(support.field(response, "detail")).contains("longitude, latitude");
    }

    @Test
    @DisplayName("EC-GEO-16: geometry outside the service area is rejected")
    void outsideServiceAreaRejected() {
        Long routeId = createRoute("W-201");

        ResponseEntity<String> response = putPattern(routeId, GeometryFixtures.outsideServiceArea());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("OUTSIDE_SERVICE_AREA");
    }

    @Test
    @DisplayName("EC-GEO-03: a line that collapses to one point is rejected")
    void degenerateLineRejected() {
        Long routeId = createRoute("W-202");

        ResponseEntity<String> response = putPattern(routeId, GeometryFixtures.degenerateLine());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("GEOMETRY_TOO_FEW_POINTS");
    }

    @Test
    @DisplayName("EC-GEO-02: geometry declaring its own CRS is rejected rather than reprojected on a guess")
    void declaredCrsRejected() {
        Long routeId = createRoute("W-203");

        ResponseEntity<String> response = putPattern(
                routeId,
                """
                {"type":"LineString","crs":{"type":"name","properties":{"name":"EPSG:3857"}},
                 "coordinates":[[77.2,28.6],[77.2,28.61]]}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("GEOMETRY_CRS_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("a Polygon where a LineString belongs is rejected")
    void wrongGeometryTypeRejected() {
        Long routeId = createRoute("W-204");

        ResponseEntity<String> response = putPattern(
                routeId,
                """
                {"type":"Polygon","coordinates":[[[77.2,28.6],[77.21,28.6],[77.21,28.61],[77.2,28.6]]]}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("GEOMETRY_WRONG_TYPE");
    }

    @Test
    @DisplayName("EC-GEO-11: a noisy trace is simplified on request, and the response says so")
    void noisyTraceSimplified() {
        Long routeId = createRoute("W-205");

        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + routeId + "/patterns/UP",
                planner,
                """
                {"geometry":%s,"simplify":true}""".formatted(GeometryFixtures.noisyLine(400)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Simplified from 400");
    }

    @Test
    @DisplayName("a length is computed in metres, not degrees")
    void lengthIsInMetres() {
        Long routeId = createRoute("W-206");

        ResponseEntity<String> response = putPattern(routeId, GeometryFixtures.northSouthLine(5_000));

        // Computing in degrees would give roughly 0.045, which is the classic silent spatial bug.
        assertThat(support.json(response).get("lengthM").asDouble()).isCloseTo(5_000, org.assertj.core.data.Offset.offset(60.0));
    }

    // --- stop sequence ------------------------------------------------------

    @Test
    @DisplayName("EC-GEO-14: stops out of order along the line are rejected")
    void stopSequenceOutOfOrderRejected() {
        Long far = createStop("WF-S-FAR", GeometryFixtures.stopAlongBaseLine(4_000));
        Long near = createStop("WF-S-NEAR", GeometryFixtures.stopAlongBaseLine(1_000));
        Long routeId = createRoute("W-207");

        // Listed far-then-near, which contradicts the drawn path and would make every running time wrong.
        ResponseEntity<String> response = putPattern(
                routeId, GeometryFixtures.northSouthLine(5_000), List.of(far, near));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("STOP_SEQUENCE_OUT_OF_ORDER");
    }

    @Test
    @DisplayName("EC-GEO-13: a stop far from the line is a warning, not a rejection")
    void stopFarFromLineWarns() {
        Long off = createStop("WF-S-OFF", new double[] {GeometryFixtures.BASE_LON + 0.01, GeometryFixtures.BASE_LAT});
        Long routeId = createRoute("W-208");

        ResponseEntity<String> response =
                putPattern(routeId, GeometryFixtures.northSouthLine(5_000), List.of(off));

        // A planner may legitimately attach a stop slightly off a simplified line, so this informs rather
        // than blocks.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("from the route line");
    }

    // --- proposal lifecycle -------------------------------------------------

    @Test
    @DisplayName("the lifecycle runs propose, review, approve, activate, retire")
    void happyPathLifecycle() {
        Long routeId = createRoute("W-210");
        putPattern(routeId, GeometryFixtures.northSouthLine(5_000));

        assertThat(statusAfter(HttpMethod.POST, routeId, "/submit", planner, null)).isEqualTo("UNDER_REVIEW");
        assertThat(statusAfter(
                        HttpMethod.POST,
                        routeId,
                        "/decision",
                        manager,
                        """
                        {"approve":true,"note":"looks good"}"""))
                .isEqualTo("APPROVED");
        assertThat(statusAfter(
                        HttpMethod.POST,
                        routeId,
                        "/activate",
                        manager,
                        """
                        {"effectiveFrom":"2026-04-01"}"""))
                .isEqualTo("ACTIVE");
        assertThat(statusAfter(HttpMethod.POST, routeId, "/retire", manager, null)).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName("an illegal transition is refused with the allowed options")
    void illegalTransitionRefused() {
        Long routeId = createRoute("W-211");
        putPattern(routeId, GeometryFixtures.northSouthLine(5_000));

        // Straight from PROPOSED to ACTIVE would put a route into service with nobody approving it.
        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + routeId + "/activate",
                manager,
                """
                {"effectiveFrom":"2026-04-01"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("ILLEGAL_ROUTE_TRANSITION");
        assertThat(support.field(response, "detail")).contains("UNDER_REVIEW");
    }

    @Test
    @DisplayName("a route with no geometry cannot be submitted")
    void submitWithoutGeometryRefused() {
        Long routeId = createRoute("W-212");

        ResponseEntity<String> response =
                support.call(rest, HttpMethod.POST, "/api/v1/routes/" + routeId + "/submit", planner, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("ROUTE_HAS_NO_GEOMETRY");
    }

    @Test
    @DisplayName("EC-SEC-15: the submitter cannot decide their own route")
    void selfApprovalRefused() {
        // A user holding both roles, which is the configuration that makes this possible.
        support.createUser("wf-both", null, Role.PLANNER, Role.MANAGER);
        String both = support.accessTokenFor(rest, "wf-both");

        Long routeId = Long.valueOf(support.field(
                support.call(
                        rest,
                        HttpMethod.POST,
                        "/api/v1/routes",
                        both,
                        """
                        {"routeNo":"W-213","name":"Self","depotId":%d}""".formatted(depotId)),
                "id"));
        support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + routeId + "/patterns/UP",
                both,
                """
                {"geometry":%s}""".formatted(GeometryFixtures.northSouthLine(5_000)));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + routeId + "/submit", both, null);

        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + routeId + "/decision",
                both,
                """
                {"approve":true,"note":"mine"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    @DisplayName("a rejection must carry a reason")
    void rejectionNeedsReason() {
        Long routeId = createRoute("W-214");
        putPattern(routeId, GeometryFixtures.northSouthLine(5_000));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + routeId + "/submit", planner, null);

        ResponseEntity<String> response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + routeId + "/decision",
                manager,
                """
                {"approve":false}""");

        // A rejection nobody can act on leaves the proposal stalled forever.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("DECISION_NOTE_REQUIRED");
    }

    @Test
    @DisplayName("geometry is frozen while a route is under review")
    void geometryFrozenUnderReview() {
        Long routeId = createRoute("W-215");
        putPattern(routeId, GeometryFixtures.northSouthLine(5_000));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + routeId + "/submit", planner, null);

        ResponseEntity<String> response = putPattern(routeId, GeometryFixtures.northSouthLine(8_000));

        // A reviewer is deciding on specific geometry; letting it change underneath them would make the
        // decision meaningless.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("ROUTE_NOT_EDITABLE");
    }

    @Test
    @DisplayName("EC-GEO-21: a retired route number can be reused")
    void retiredRouteNumberIsReusable() {
        Long first = createRoute("W-216");
        putPattern(first, GeometryFixtures.northSouthLine(5_000));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + first + "/submit", planner, null);
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + first + "/decision",
                manager,
                """
                {"approve":true,"note":"ok"}""");
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + first + "/activate",
                manager,
                """
                {"effectiveFrom":"2026-01-01"}""");
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + first + "/retire", manager, null);

        ResponseEntity<String> reused = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"W-216","name":"Replacement","depotId":%d}""".formatted(depotId));

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a live route number cannot be taken twice")
    void liveRouteNumberIsUnique() {
        createRoute("W-217");

        ResponseEntity<String> duplicate = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"w-217","name":"Clash","depotId":%d}""".formatted(depotId));

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(duplicate, "code")).isEqualTo("ROUTE_NO_TAKEN");
    }

    @Test
    @DisplayName("submitting attaches overlap analysis to the proposal")
    void submitAttachesAnalysis() {
        Long existing = createRoute("W-218");
        putPattern(existing, GeometryFixtures.northSouthLine(10_000));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + existing + "/submit", planner, null);
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + existing + "/decision",
                manager,
                """
                {"approve":true,"note":"ok"}""");
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + existing + "/activate",
                manager,
                """
                {"effectiveFrom":"2026-01-01"}""");

        Long duplicate = createRoute("W-219");
        putPattern(duplicate, GeometryFixtures.northSouthLine(10_000));
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + duplicate + "/submit", planner, null);

        ResponseEntity<String> stored =
                support.call(rest, HttpMethod.GET, "/api/v1/routes/" + duplicate + "/overlaps", manager, null);

        // A reviewer must see the numbers the planner submitted, not a fresh computation that may differ.
        assertThat(support.longField(stored, "totalElements")).isEqualTo(1);
        assertThat(stored.getBody()).contains("\"severity\":\"HIGH\"");
    }

    @Test
    @DisplayName("the route detail response carries geometry as GeoJSON")
    void detailReturnsGeoJson() {
        Long routeId = createRoute("W-220");
        putPattern(routeId, GeometryFixtures.northSouthLine(5_000));

        ResponseEntity<String> response =
                support.call(rest, HttpMethod.GET, "/api/v1/routes/" + routeId, planner, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("LineString").contains("coordinates");
        // No crs member: GeoJSON fixes the CRS, so emitting one invites a client to reproject.
        assertThat(response.getBody()).doesNotContain("\"crs\"");
    }

    // --- helpers ------------------------------------------------------------

    private String statusAfter(HttpMethod method, Long routeId, String path, String token, String body) {
        ResponseEntity<String> response =
                support.call(rest, method, "/api/v1/routes/" + routeId + path, token, body);
        assertThat(response.getStatusCode()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(HttpStatus.OK);
        return support.field(response, "status");
    }

    private Long createRoute(String routeNo) {
        ResponseEntity<String> response = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"%s","name":"Route %s","depotId":%d}""".formatted(routeNo, routeNo, depotId));
        assertThat(response.getStatusCode()).as("create: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(support.field(response, "id"));
    }

    private ResponseEntity<String> putPattern(Long routeId, String geometry) {
        return putPattern(routeId, geometry, List.of());
    }

    private ResponseEntity<String> putPattern(Long routeId, String geometry, List<Long> stopIds) {
        String ids = stopIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + routeId + "/patterns/UP",
                planner,
                """
                {"geometry":%s,"stopIds":[%s]}""".formatted(geometry, ids));
    }

    private Long createStop(String code, double[] lonLat) {
        ResponseEntity<String> response = post(
                "/api/v1/stops",
                planner,
                """
                {"code":"%s","name":"%s","longitude":%s,"latitude":%s}"""
                        .formatted(code, code, lonLat[0], lonLat[1]));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(support.field(response, "id"));
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return support.call(rest, HttpMethod.POST, path, token, body);
    }
}
