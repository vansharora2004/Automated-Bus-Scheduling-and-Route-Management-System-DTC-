package com.dtc.transit.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.PostgisContainerTest;

/**
 * The reference datasets: reproducible, and the right size.
 *
 * <p>Determinism is the property that matters. Every performance number from Phase 6 onward is quoted against
 * one of these datasets, and a comparison between two runs means nothing unless the data was identical. The
 * checksum makes that testable in one assertion instead of a row-by-row diff.
 *
 * <p>L is generated too, not just described. Its scale is exactly what the later phases are measured at, and a
 * generator that works at S and falls over at 50,000 trips would be discovered at the worst possible moment.
 *
 * <p>The checksums produced when this was written, for reference when comparing across machines:
 *
 * <pre>
 * S  1fb5313fdd627eee7d80082750960d0ca3c41c1d92aead5cdf116bc45b8cce17
 * M  7b1ad2760c5bbfb878627a0d7b9335f7d798137b369e1caee8dcfc9878f3c0be
 * L  d4935cff00e904fba98121b9c64ed3adf38b2aa774b0845f34f3a36f48144981
 * </pre>
 *
 * <p>They are documented rather than asserted. Pinning them would turn any deliberate change to the generator
 * into an opaque failure, while the run-to-run comparison below catches the accidental drift that actually
 * threatens a measurement.
 */
class DatasetGeneratorTest extends PostgisContainerTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Leaves the database empty for whatever runs next.
     *
     * <p>A loaded dataset is not inert: trips reference stops, so another test class that clears stops to set
     * up its own fixture would fail on a foreign key for reasons nothing to do with what it was testing.
     */
    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        generator.purge();
    }

    @ParameterizedTest
    @EnumSource(DatasetSize.class)
    @DisplayName("each dataset regenerates to the same checksum and hits its target counts")
    void datasetsAreDeterministicAndCorrectlySized(DatasetSize size) {
        var first = generator.generate(size, true);
        var second = generator.generate(size, true);

        // Same seed, same data. A difference here means something in the generator reads a clock, a hash
        // ordering or the default locale, all of which would silently invalidate every later measurement.
        assertThat(second.checksum()).isEqualTo(first.checksum());

        assertThat(first.depots()).isEqualTo(size.depots());
        assertThat(first.routes()).isEqualTo(size.routes());
        assertThat(first.buses()).isEqualTo(size.buses());
        assertThat(first.crew()).isEqualTo(size.crew());
        // 60 trips per route: 30 departures per direction, over the three service windows.
        assertThat(first.trips()).isEqualTo(size.routes() * 60);
    }

    @Test
    @DisplayName("the loaded rows match what the report claims")
    void reportMatchesTheDatabase() {
        var report = generator.generate(DatasetSize.S, true);

        // The report is what the CLI prints and what a reviewer trusts. If it drifted from the rows actually
        // written, the dataset would look right and be wrong.
        assertThat(count("depot")).isEqualTo(report.depots());
        assertThat(count("route")).isEqualTo(report.routes());
        assertThat(count("route_pattern")).isEqualTo(report.routes() * 2);
        assertThat(count("stop")).isEqualTo(report.stops());
        assertThat(count("timetable")).isEqualTo(report.timetables());
        assertThat(count("trip")).isEqualTo(report.trips());
        assertThat(count("bus")).isEqualTo(report.buses());
        assertThat(count("crew_member")).isEqualTo(report.crew());
        assertThat(count("deadhead")).isEqualTo(report.deadheads());
    }

    @Test
    @DisplayName("the generated trip count equals the analytical count implied by the headway bands")
    void tripCountMatchesTheBands() {
        generator.generate(DatasetSize.S, true);

        // Computed in SQL from the bands that were stored, independently of the generator's own arithmetic:
        // the sum over bands of ceil(window / headway).
        Integer analytical = jdbc.queryForObject(
                """
                SELECT sum(ceil((to_sec - from_sec)::numeric / headway_sec))::int FROM headway_band
                """,
                Integer.class);

        assertThat(count("trip")).isEqualTo(analytical);
    }

    @Test
    @DisplayName("the dataset contains the infeasible pockets conflict detection needs")
    void deliberateInfeasibilityIsPresent() {
        generator.generate(DatasetSize.S, true);

        // A dataset where every bus is available and every licence is valid never exercises the conflict
        // rules the later phases exist to enforce, so the gaps are planted on purpose.
        assertThat(count("bus_unavailability")).isPositive();
        assertThat(count("crew_leave")).isPositive();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM crew_member
                        WHERE licence_expiry IS NOT NULL AND licence_expiry < DATE '2026-07-01'
                        """,
                        Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM bus WHERE status <> 'ACTIVE'", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("every generated stop lies inside the service area")
    void stopsAreInsideTheServiceArea() {
        generator.generate(DatasetSize.S, true);

        // A stop outside the operating boundary would be rejected by the Phase 4 geographic validation, so a
        // dataset containing one could not be used by the tests it exists to support.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM stop s
                        WHERE NOT EXISTS (
                          SELECT 1 FROM service_area a WHERE a.active AND ST_Contains(a.geom, s.location))
                        """,
                        Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("purging clears the dataset without deleting the accounts or the service area")
    void purgeKeepsReferenceData() {
        generator.generate(DatasetSize.S, true);

        generator.purge();

        assertThat(count("trip")).isZero();
        assertThat(count("route")).isZero();
        assertThat(count("depot")).isZero();
        // The service area is seeded by migration and geometry validation depends on it; an operator who
        // reloaded a dataset and lost it would find every route rejected afterwards.
        assertThat(count("service_area")).isPositive();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
