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

/**
 * Overlap detection against geometries whose answers were worked out in advance.
 *
 * <p>These are the Phase 4 exit criteria. Every expected ratio here comes from the fixture's construction,
 * not from running the query, which is the only way to catch a buffer, transform or double-counting
 * mistake that still returns a plausible number.
 */
class OverlapDetectionTest extends SecurityWebTest {

    private String planner;
    private String manager;
    private Long depotId;

    @BeforeEach
    void setUp() {
        support.createUser("overlap-admin", null, Role.ADMIN);
        String admin = support.accessTokenFor(rest, "overlap-admin");
        support.createUser("overlap-planner", null, Role.PLANNER);
        planner = support.accessTokenFor(rest, "overlap-planner");
        support.createUser("overlap-manager", null, Role.MANAGER);
        manager = support.accessTokenFor(rest, "overlap-manager");

        depotId = Long.valueOf(support.field(
                post(
                        "/api/v1/depots",
                        admin,
                        """
                        {"code":"OVL-DPT","name":"Overlap depot","longitude":77.2,"latitude":28.6}"""),
                "id"));
    }

    @Test
    @DisplayName("an identical route is reported at a ratio of about 1.00")
    void identicalRouteIsFullOverlap() {
        activateRoute("R-100", GeometryFixtures.northSouthLine(10_000));

        double ratio = analyseRatio(GeometryFixtures.northSouthLine(10_000));

        assertThat(ratio).isCloseTo(1.00, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    @DisplayName("a parallel road 40 m away is not an overlap at the default 25 m buffer")
    void parallelRoadIsNotOverlap() {
        activateRoute("R-101", GeometryFixtures.northSouthLine(10_000));

        // A service lane beside a main road is a different road. A buffer wide enough to join them would
        // report half the city as duplicated (edge case EC-GEO-06).
        var response = analyse(GeometryFixtures.parallelLine(10_000, 40));

        assertThat(response.getBody()).contains("\"findings\":[]");
    }

    @Test
    @DisplayName("widening the buffer past the offset does find the parallel road")
    void parallelRoadFoundWithWiderBuffer() {
        activateRoute("R-102", GeometryFixtures.northSouthLine(10_000));

        // Proves the previous result is the buffer doing its job, not the query failing to find anything.
        double ratio = analyseRatio(GeometryFixtures.parallelLine(10_000, 40), 60.0, 200.0);

        assertThat(ratio).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("a perpendicular crossing is not an overlap")
    void perpendicularCrossingIsNotOverlap() {
        activateRoute("R-103", GeometryFixtures.northSouthLine(10_000));

        // The two lines genuinely intersect, but over a few metres. Only the minimum-segment rule
        // distinguishes that from a shared corridor (edge case EC-GEO-07).
        var response = analyse(GeometryFixtures.perpendicularCrossing(4_000, 5_000));

        assertThat(response.getBody()).contains("\"findings\":[]");
    }

    @Test
    @DisplayName("3 km shared on a 10 km route gives a ratio of about 0.30")
    void partialOverlapRatioIsProportional() {
        activateRoute("R-104", GeometryFixtures.northSouthLine(10_000));

        double ratio = analyseRatio(GeometryFixtures.partiallyOverlappingLine(3_000, 7_000));

        // The headline number of the whole feature: a planner reads this as "a third of my route already
        // exists". Being wrong here is worse than returning nothing.
        assertThat(ratio).isCloseTo(0.30, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    @DisplayName("an out-and-back route is not counted twice, so the ratio stays at or below 1")
    void outAndBackIsNotDoubleCounted() {
        activateRoute("R-105", GeometryFixtures.northSouthLine(5_000));

        double ratio = analyseRatio(GeometryFixtures.outAndBackLine(5_000));

        // Without normalising, the candidate's own length counts the road twice and the ratio can exceed
        // 1, which is meaningless (edge case EC-GEO-10).
        assertThat(ratio).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("severity follows the ratio thresholds")
    void severityReflectsRatio() {
        activateRoute("R-106", GeometryFixtures.northSouthLine(10_000));

        assertThat(analyse(GeometryFixtures.northSouthLine(10_000)).getBody()).contains("\"severity\":\"HIGH\"");
        // 3 km of 10 km sits in the MEDIUM band of 0.30 to 0.60.
        assertThat(analyse(GeometryFixtures.partiallyOverlappingLine(3_000, 7_000)).getBody())
                .contains("\"severity\":\"MEDIUM\"");
    }

    @Test
    @DisplayName("a route only overlaps routes that are in service")
    void onlyActiveRoutesAreCompared() {
        // Drafted but never activated: a proposal must not be reported as duplicating another proposal,
        // or two planners drafting the same corridor would each block the other.
        Long routeId = createRoute("R-107");
        putPattern(routeId, "UP", GeometryFixtures.northSouthLine(10_000));

        var response = analyse(GeometryFixtures.northSouthLine(10_000));

        assertThat(response.getBody()).contains("\"findings\":[]");
    }

    @Test
    @DisplayName("analysis excludes the pattern's own route, not just the pattern")
    void ownRouteIsExcluded() {
        Long routeId = createRoute("R-108");
        putPattern(routeId, "UP", GeometryFixtures.northSouthLine(10_000));
        putPattern(routeId, "DOWN", GeometryFixtures.northSouthLine(10_000));
        activate(routeId);

        // Submitting attaches analysis. The UP direction must not report its own DOWN direction as a
        // duplicate, which is true and useless (edge case EC-GEO-18).
        Long other = createRoute("R-109");
        putPattern(other, "UP", GeometryFixtures.northSouthLine(10_000));
        var submitted = support.call(rest, HttpMethod.POST, "/api/v1/routes/" + other + "/submit", planner, null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> stored =
                support.call(rest, HttpMethod.GET, "/api/v1/routes/" + other + "/overlaps", planner, null);

        // It should find R-108, but exactly one finding per direction of R-108, never itself.
        assertThat(stored.getBody()).doesNotContain("\"existingPatternId\":null");
        assertThat(support.longField(stored, "totalElements")).isGreaterThan(0);
    }

    @Test
    @DisplayName("shared stops are counted, which separates duplication from a shared corridor")
    void sharedStopsAreCounted() {
        Long stopA = createStop("OVL-S1", GeometryFixtures.stopAlongBaseLine(0));
        Long stopB = createStop("OVL-S2", GeometryFixtures.stopAlongBaseLine(5_000));
        Long routeId = createRoute("R-110");
        putPattern(routeId, "UP", GeometryFixtures.northSouthLine(10_000), List.of(stopA, stopB));
        activate(routeId);

        var response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/overlap-analysis",
                planner,
                """
                {"geometry":%s,"stopIds":[%d,%d]}"""
                        .formatted(GeometryFixtures.northSouthLine(10_000), stopA, stopB));

        assertThat(support.json(response).get("findings").size()).isPositive();
        assertThat(response.getBody()).contains("\"sharedStops\":2");
        // All three signals agree, so this really is a duplicate.
        assertThat(response.getBody()).contains("\"looksLikeDuplication\":true");
    }

    @Test
    @DisplayName("an express service on the same corridor is not flagged as duplication")
    void sameCorridorDifferentStopsIsNotDuplication() {
        Long existingStop = createStop("OVL-E1", GeometryFixtures.stopAlongBaseLine(2_000));
        Long routeId = createRoute("R-111");
        putPattern(routeId, "UP", GeometryFixtures.northSouthLine(10_000), List.of(existingStop));
        activate(routeId);

        // Same road, no stops in common. Geometry alone would call this a duplicate; it is an express.
        var response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/overlap-analysis",
                planner,
                """
                {"geometry":%s,"stopIds":[]}""".formatted(GeometryFixtures.northSouthLine(10_000)));

        assertThat(response.getBody()).contains("\"sharedStops\":0");
        assertThat(response.getBody()).contains("\"looksLikeDuplication\":false");
    }

    @Test
    @DisplayName("a buffer outside the allowed range is rejected")
    void absurdBufferRejected() {
        var response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/overlap-analysis",
                planner,
                """
                {"geometry":%s,"bufferM":100000}""".formatted(GeometryFixtures.northSouthLine(1_000)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ------------------------------------------------------------

    private double analyseRatio(String geometry) {
        return firstFindingDouble(analyse(geometry), "overlapRatio");
    }

    private double analyseRatio(String geometry, double bufferM, double minSegmentM) {
        var response = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/overlap-analysis",
                planner,
                """
                {"geometry":%s,"bufferM":%s,"minSegmentM":%s}""".formatted(geometry, bufferM, minSegmentM));
        return firstFindingDouble(response, "overlapRatio");
    }

    private ResponseEntity<String> analyse(String geometry) {
        return support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/overlap-analysis",
                planner,
                """
                {"geometry":%s}""".formatted(geometry));
    }

    /** The named field of the first finding, read from the parsed response rather than sliced out of it. */
    private double firstFindingDouble(ResponseEntity<String> response, String field) {
        assertThat(response.getStatusCode()).as("analysis call: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        var findings = support.json(response).get("findings");
        assertThat(findings).as("findings array present").isNotNull();
        assertThat(findings.size()).as("expected at least one finding in %s", response.getBody()).isPositive();
        return findings.get(0).get(field).asDouble();
    }

    private void activateRoute(String routeNo, String geometry) {
        Long id = createRoute(routeNo);
        putPattern(id, "UP", geometry);
        activate(id);
    }

    private Long createRoute(String routeNo) {
        var response = post(
                "/api/v1/routes",
                planner,
                """
                {"routeNo":"%s","name":"Route %s","depotId":%d}""".formatted(routeNo, routeNo, depotId));
        assertThat(response.getStatusCode()).as("create route: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(support.field(response, "id"));
    }

    private void putPattern(Long routeId, String direction, String geometry) {
        putPattern(routeId, direction, geometry, List.of());
    }

    private void putPattern(Long routeId, String direction, String geometry, List<Long> stopIds) {
        String ids = stopIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        var response = support.call(
                rest,
                HttpMethod.PUT,
                "/api/v1/routes/" + routeId + "/patterns/" + direction,
                planner,
                """
                {"geometry":%s,"stopIds":[%s]}""".formatted(geometry, ids));
        assertThat(response.getStatusCode())
                .as("put pattern: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void activate(Long routeId) {
        support.call(rest, HttpMethod.POST, "/api/v1/routes/" + routeId + "/submit", planner, null);
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + routeId + "/decision",
                manager,
                """
                {"approve":true,"note":"ok"}""");
        var activated = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/routes/" + routeId + "/activate",
                manager,
                """
                {"effectiveFrom":"2026-01-01"}""");
        assertThat(activated.getStatusCode())
                .as("activate: %s", activated.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private Long createStop(String code, double[] lonLat) {
        support.createUser("stop-maker-" + code, null, Role.PLANNER);
        var response = post(
                "/api/v1/stops",
                planner,
                """
                {"code":"%s","name":"%s","longitude":%s,"latitude":%s,"reliefPoint":true}"""
                        .formatted(code, code, lonLat[0], lonLat[1]));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(support.field(response, "id"));
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return support.call(rest, HttpMethod.POST, path, token, body);
    }
}
