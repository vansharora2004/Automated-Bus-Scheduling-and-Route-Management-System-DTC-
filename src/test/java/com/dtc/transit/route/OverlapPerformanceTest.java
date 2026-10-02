package com.dtc.transit.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.route.pattern.RoutePatternRepository;
import com.dtc.transit.support.GeometryFixtures;
import com.dtc.transit.support.PostgisContainerTest;

/**
 * Overlap analysis latency against a network-sized set of patterns.
 *
 * <p>The Phase 4 criterion is a p95 under 500 ms. Measuring that on three patterns would prove nothing:
 * the query only becomes interesting once there are enough patterns that the spatial prefilter has to do
 * real work, so roughly 2,000 are generated first, matching the documented network size.
 *
 * <p>Latency on a container under a loaded CI machine is noisy, so the assertion is on p95 rather than the
 * worst case, and the measured numbers are printed for the record.
 */
class OverlapPerformanceTest extends PostgisContainerTest {

    private static final int PATTERN_COUNT = 2_000;
    private static final int MEASURED_RUNS = 20;
    private static final long P95_BUDGET_MILLIS = 500;

    @Autowired
    private RoutePatternRepository patterns;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedNetwork() {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM route_pattern", Integer.class);
        if (existing != null && existing >= PATTERN_COUNT) {
            return;
        }

        jdbc.update("DELETE FROM route_overlap");
        jdbc.update("DELETE FROM pattern_stop");
        jdbc.update("DELETE FROM route_pattern");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM depot WHERE code = 'PERF-RT'");

        jdbc.update(
                """
                INSERT INTO depot (code, name, location)
                VALUES ('PERF-RT', 'Perf routes', ST_SetSRID(ST_MakePoint(77.2, 28.6), 4326))
                """);
        Long depotId = jdbc.queryForObject("SELECT id FROM depot WHERE code = 'PERF-RT'", Long.class);

        // Routes must be ACTIVE: the query deliberately ignores anything else, so seeding drafts would
        // measure a query that filters everything out.
        jdbc.update(
                """
                INSERT INTO route (route_no, name, depot_id, status)
                SELECT 'P-' || g, 'Perf route ' || g, ?, 'ACTIVE'
                FROM generate_series(1, ?) g
                """,
                depotId,
                PATTERN_COUNT);

        // A grid of short lines spread across the service area, so the prefilter has a realistic mix of
        // near and far candidates rather than everything in one place.
        jdbc.update(
                """
                INSERT INTO route_pattern (route_id, direction, geom)
                SELECT r.id,
                       'UP',
                       ST_SetSRID(ST_MakeLine(
                         ST_MakePoint(76.95 + (g % 50) * 0.012, 28.35 + (g / 50) * 0.012),
                         ST_MakePoint(76.95 + (g % 50) * 0.012, 28.35 + (g / 50) * 0.012 + 0.05)), 4326)
                FROM generate_series(1, ?) g
                JOIN route r ON r.route_no = 'P-' || g
                """,
                PATTERN_COUNT);

        jdbc.execute("ANALYZE route_pattern");
        jdbc.execute("ANALYZE route");
    }

    @Test
    @DisplayName("overlap analysis stays under the p95 budget on a network-sized dataset")
    void overlapLatencyWithinBudget() {
        Integer seeded = jdbc.queryForObject("SELECT count(*) FROM route_pattern", Integer.class);
        assertThat(seeded).as("the dataset must be large enough for the number to mean anything").isEqualTo(PATTERN_COUNT);

        String wkt = wktOfBaseLine();

        // One untimed run first: the first execution pays for plan creation and cache warming, which is
        // not what a steady-state p95 describes.
        patterns.findOverlaps(wkt, 25, 200, null, null);

        List<Long> timings = new ArrayList<>(MEASURED_RUNS);
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            patterns.findOverlaps(wkt, 25, 200, null, null);
            timings.add((System.nanoTime() - start) / 1_000_000);
        }

        timings.sort(null);
        long p50 = timings.get(timings.size() / 2);
        long p95 = timings.get((int) Math.ceil(timings.size() * 0.95) - 1);
        long worst = timings.get(timings.size() - 1);

        System.out.printf(
                "overlap analysis over %d patterns: p50=%d ms, p95=%d ms, max=%d ms%n",
                PATTERN_COUNT, p50, p95, worst);

        assertThat(p95)
                .as("p95 over %d runs was %d ms, budget %d ms", MEASURED_RUNS, p95, P95_BUDGET_MILLIS)
                .isLessThan(P95_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("the spatial prefilter uses the GiST index rather than scanning every pattern")
    void prefilterUsesSpatialIndex() {
        String plan = String.join(
                "\n",
                jdbc.queryForList(
                        """
                        EXPLAIN (ANALYZE, BUFFERS)
                        SELECT rp.id
                        FROM route_pattern rp
                        WHERE ST_DWithin(
                                rp.geom_utm,
                                ST_Transform(ST_SetSRID(ST_GeomFromText(?), 4326), 32643),
                                25)
                        """,
                        String.class,
                        wktOfBaseLine()));

        // Latency alone could be met by a fast sequential scan at this size and would then regress
        // silently as the network grows. Asserting on the plan catches that before it happens.
        assertThat(plan).contains("route_pattern_geom_utm_gix");
        assertThat(plan).doesNotContain("Seq Scan on route_pattern");
    }

    /** The base fixture line as WKT, which is what the repository query takes. */
    private String wktOfBaseLine() {
        double endLat = GeometryFixtures.BASE_LAT + 10_000 / GeometryFixtures.METRES_PER_DEGREE_LAT;
        return "LINESTRING(%s %s, %s %s)"
                .formatted(GeometryFixtures.BASE_LON, GeometryFixtures.BASE_LAT, GeometryFixtures.BASE_LON, endLat);
    }
}
