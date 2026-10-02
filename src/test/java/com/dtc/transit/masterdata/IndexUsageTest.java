package com.dtc.transit.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.PostgisContainerTest;

/**
 * Proves the filtered list queries actually use their indexes, on a realistically sized table.
 *
 * <p>A fast query on an empty table proves nothing: PostgreSQL will sequentially scan 10 rows whatever
 * indexes exist, so an index that is never used looks identical to one that is. The data is generated to
 * 100k rows first, then {@code EXPLAIN ANALYZE} is read and asserted on.
 *
 * <p>This is the Phase 3 "EXPLAIN ANALYZE shows index usage" check, executed rather than described.
 */
class IndexUsageTest extends PostgisContainerTest {

    private static final int BUS_ROWS = 100_000;
    private static final int CREW_ROWS = 100_000;
    private static final int STOP_ROWS = 100_000;

    private static boolean seeded;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    /**
     * Generates the data once per JVM.
     *
     * <p>Inserted with generate_series rather than through JPA: 300k entity persists would dominate the
     * suite runtime and tell us nothing extra about the planner.
     */
    private void seedOnce() {
        if (seeded) {
            return;
        }
        jdbc.update("DELETE FROM crew_member");
        jdbc.update("DELETE FROM bus");
        jdbc.update("DELETE FROM stop");
        jdbc.update("DELETE FROM depot WHERE code LIKE 'PERF-%'");

        jdbc.update(
                """
                INSERT INTO depot (code, name, location, parking_capacity, charging_bays)
                SELECT 'PERF-' || g, 'Perf depot ' || g,
                       ST_SetSRID(ST_MakePoint(77.0 + g * 0.01, 28.4 + g * 0.01), 4326), 150, 4
                FROM generate_series(1, 45) g
                """);

        jdbc.update(
                """
                INSERT INTO bus (registration_no, registration_no_raw, fleet_no, depot_id,
                                 bus_type, fuel_type, is_ac, capacity, ev_range_km, status)
                SELECT 'DL' || lpad(g::text, 8, '0'),
                       'DL' || lpad(g::text, 8, '0'),
                       'F-' || g,
                       d.id,
                       CASE g %% 3 WHEN 0 THEN 'STANDARD' WHEN 1 THEN 'LOW_FLOOR' ELSE 'MIDI' END,
                       CASE g %% 3 WHEN 0 THEN 'ELECTRIC' ELSE 'CNG' END,
                       g %% 2 = 0,
                       40,
                       CASE WHEN g %% 3 = 0 THEN 180 ELSE NULL END,
                       CASE g %% 10 WHEN 0 THEN 'UNDER_MAINTENANCE' ELSE 'ACTIVE' END
                FROM generate_series(1, %d) g
                JOIN depot d ON d.id = (SELECT id FROM depot WHERE code = 'PERF-' || (1 + g %% 45))
                """
                        .formatted(BUS_ROWS));

        jdbc.update(
                """
                INSERT INTO crew_member (employee_code, name, crew_role, depot_id,
                                         licence_no, licence_class, licence_expiry, status, weekly_off_dow)
                SELECT lpad(g::text, 6, '0'),
                       'Crew ' || g,
                       CASE g %% 2 WHEN 0 THEN 'DRIVER' ELSE 'CONDUCTOR' END,
                       d.id,
                       CASE WHEN g %% 2 = 0 THEN 'L-' || g ELSE NULL END,
                       CASE WHEN g %% 2 = 0 THEN 'HPMV' ELSE NULL END,
                       CASE WHEN g %% 2 = 0 THEN DATE '2027-01-01' + (g %% 900) ELSE NULL END,
                       CASE g %% 20 WHEN 0 THEN 'SUSPENDED' ELSE 'ACTIVE' END,
                       1 + g %% 7
                FROM generate_series(1, %d) g
                JOIN depot d ON d.id = (SELECT id FROM depot WHERE code = 'PERF-' || (1 + g %% 45))
                """
                        .formatted(CREW_ROWS));

        jdbc.update(
                """
                INSERT INTO stop (code, name, location, is_terminal, is_relief_point)
                SELECT 'S-' || g, 'Stop ' || g,
                       ST_SetSRID(ST_MakePoint(76.9 + (g %% 1000) * 0.0006, 28.3 + (g / 1000) * 0.0006), 4326),
                       g %% 50 = 0,
                       g %% 25 = 0
                FROM generate_series(1, %d) g
                """
                        .formatted(STOP_ROWS));

        // Without fresh statistics the planner may still choose a sequential scan on a table it has
        // never analysed, which would make this test assert the wrong thing.
        jdbc.execute("ANALYZE depot");
        jdbc.execute("ANALYZE bus");
        jdbc.execute("ANALYZE crew_member");
        jdbc.execute("ANALYZE stop");
        seeded = true;
    }

    @Test
    @DisplayName("the bus list filtered by depot and status uses bus_depot_status_idx")
    void busDepotStatusUsesIndex() {
        seedOnce();
        Long depotId = jdbc.queryForObject("SELECT id FROM depot WHERE code = 'PERF-1'", Long.class);

        String plan = explain(
                """
                SELECT b.id FROM bus b
                WHERE b.depot_id = %d AND b.status = 'ACTIVE'
                ORDER BY b.fleet_no, b.id
                LIMIT 20
                """
                        .formatted(depotId));

        assertThat(plan).contains("bus_depot_status_idx");
        assertThat(plan).doesNotContain("Seq Scan on bus");
    }

    @Test
    @DisplayName("a registration lookup uses the unique index")
    void registrationLookupUsesIndex() {
        seedOnce();

        String plan = explain("SELECT id FROM bus WHERE registration_no = 'DL00050000'");

        assertThat(plan).contains("bus_registration_no_uq");
        assertThat(plan).doesNotContain("Seq Scan on bus");
    }

    @Test
    @DisplayName("the crew list filtered by depot, role and status uses its composite index")
    void crewDepotRoleStatusUsesIndex() {
        seedOnce();
        Long depotId = jdbc.queryForObject("SELECT id FROM depot WHERE code = 'PERF-2'", Long.class);

        String plan = explain(
                """
                SELECT c.id FROM crew_member c
                WHERE c.depot_id = %d AND c.crew_role = 'DRIVER' AND c.status = 'ACTIVE'
                ORDER BY c.employee_code, c.id
                LIMIT 20
                """
                        .formatted(depotId));

        assertThat(plan).contains("crew_member_depot_role_status_idx");
        assertThat(plan).doesNotContain("Seq Scan on crew_member");
    }

    @Test
    @DisplayName("the licence-expiry filter uses the partial index and skips rows with no licence")
    void licenceExpiryUsesPartialIndex() {
        seedOnce();

        // Selective, and with no LIMIT. A broad predicate with LIMIT 50 would legitimately prefer a
        // sequential scan: the planner finds fifty matching rows almost immediately and stops, so such a
        // query proves nothing about whether the index is usable at all.
        String plan = explain(
                """
                SELECT count(*) FROM crew_member c
                WHERE c.licence_expiry IS NOT NULL
                  AND c.licence_expiry BETWEEN DATE '2027-06-10' AND DATE '2027-06-20'
                """);

        // Partial, because half the rows are conductors with no licence; indexing them would waste space
        // and make the index deeper for no benefit.
        assertThat(plan).contains("crew_member_licence_expiry_idx");
        assertThat(plan).doesNotContain("Seq Scan on crew_member");
    }

    @Test
    @DisplayName("the radius filter uses the GiST index on the projected column")
    void radiusFilterUsesGistIndex() {
        seedOnce();

        String plan = explain(
                """
                SELECT s.id FROM stop s
                WHERE ST_DWithin(
                        s.location_utm,
                        ST_Transform(ST_SetSRID(ST_MakePoint(77.2, 28.6), 4326), 32643),
                        500)
                LIMIT 20
                """);

        // The transform is on the parameter, not the column. Transforming the column instead would make
        // this index unusable and turn a point lookup into a 100k-row scan (edge case EC-PERF-04).
        assertThat(plan).contains("stop_location_utm_gix");
        assertThat(plan).doesNotContain("Seq Scan on stop");
    }

    @Test
    @DisplayName("the unique code lookups use their case-insensitive indexes")
    void codeLookupsUseExpressionIndexes() {
        seedOnce();

        assertThat(explain("SELECT id FROM stop WHERE upper(code) = 'S-500'")).contains("stop_code_uq");
        assertThat(explain("SELECT id FROM crew_member WHERE upper(employee_code) = '000500'"))
                .contains("crew_member_employee_code_uq");
    }

    @Test
    @DisplayName("the seeded tables really are large, so the assertions above mean something")
    void dataIsLargeEnoughToMatter() {
        seedOnce();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bus", Integer.class)).isEqualTo(BUS_ROWS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crew_member", Integer.class)).isEqualTo(CREW_ROWS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stop", Integer.class)).isEqualTo(STOP_ROWS);
    }

    /** Runs EXPLAIN ANALYZE and returns the plan as one string. */
    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
        return String.join("\n", lines);
    }
}
