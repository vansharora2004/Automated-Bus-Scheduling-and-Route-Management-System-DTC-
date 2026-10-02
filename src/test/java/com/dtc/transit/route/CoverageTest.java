package com.dtc.transit.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.route.coverage.CoverageService;
import com.dtc.transit.route.coverage.CoverageWeighting;
import com.dtc.transit.support.PostgisContainerTest;

/**
 * Service coverage by zone, and the coverage a proposed route would add.
 *
 * <p>Driven at the service layer rather than over HTTP. Coverage depends on a generated grid and a
 * refreshed materialized view, and those are set up here directly so each assertion starts from a state
 * whose expected answer is known.
 */
class CoverageTest extends PostgisContainerTest {

    /** Coarse on purpose: 250 m over the whole service area is ~108,000 cells and far too slow here. */
    private static final double CELL_SIZE_METRES = 3_000;

    @Autowired
    private CoverageService coverageService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        authenticateAsAdmin();
        jdbc.update("DELETE FROM pattern_stop");
        jdbc.update("DELETE FROM coverage_zone");
        jdbc.update("DELETE FROM grid_cell");
        jdbc.update("DELETE FROM stop");
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("measuring coverage before the grid exists fails loudly instead of reporting zero")
    void missingGridIsAnError() {
        insertZone("Ward A", 77.10, 28.55, 77.20, 28.65, 50_000L);

        // Zero cells and zero coverage produce identical numbers. Reporting "nothing is covered" when the
        // grid was simply never generated would send a planner chasing a problem that does not exist.
        assertThatThrownBy(() -> coverageService.zoneCoverage("WARD", 500, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No grid cells");
    }

    @Test
    @DisplayName("the grid tiles the service area and can be refreshed")
    void gridGeneration() {
        int cells = coverageService.generateGrid(CELL_SIZE_METRES);

        // The seeded Delhi area is roughly 94 km by 72 km, so 3 km cells give a few hundred.
        assertThat(cells).isGreaterThan(100);
        coverageService.refreshCellCoverage();

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM cell_coverage", Integer.class);
        assertThat(rows).isEqualTo(cells);
    }

    @Test
    @DisplayName("a zone with no stop anywhere near it reads as uncovered")
    void zoneWithNoStopsIsUncovered() {
        insertZone("Empty Ward", 77.10, 28.55, 77.15, 28.60, 10_000L);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        List<CoverageService.ZoneCoverage> zones = coverageService.zoneCoverage("WARD", 500, null);

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0).coveredRatio()).isZero();
    }

    @Test
    @DisplayName("a stop inside a zone covers it, and widening the catchment covers more")
    void coverageRespondsToStopsAndCatchment() {
        insertZone("Served Ward", 77.18, 28.58, 77.22, 28.62, 20_000L);
        insertStop("COV-1", 77.20, 28.60);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        double at500m = coverageService.zoneCoverage("WARD", 500, null).get(0).coveredRatio();
        double at5km = coverageService.zoneCoverage("WARD", 5_000, null).get(0).coveredRatio();

        // The radius is a query parameter because the view stores distance rather than a boolean. If it
        // stored "covered", changing the catchment would need a migration.
        assertThat(at5km).isGreaterThan(at500m);
        assertThat(at5km).isPositive();
    }

    @Test
    @DisplayName("the weighting used is reported, so area is not mistaken for population")
    void weightingIsReported() {
        insertZone("With Population", 77.18, 28.58, 77.22, 28.62, 20_000L);
        insertZone("Without Population", 77.30, 28.58, 77.34, 28.62, null);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        List<CoverageService.ZoneCoverage> zones = coverageService.zoneCoverage("WARD", 500, null);

        assertThat(zones).extracting(CoverageService.ZoneCoverage::weighting)
                .containsExactlyInAnyOrder(CoverageWeighting.POPULATION, CoverageWeighting.AREA);
    }

    @Test
    @DisplayName("zones are returned worst-served first, which is what a gap report needs")
    void worstServedZonesComeFirst() {
        insertZone("Served", 77.18, 28.58, 77.22, 28.62, 10_000L);
        insertZone("Unserved", 77.50, 28.80, 77.54, 28.84, 10_000L);
        insertStop("COV-2", 77.20, 28.60);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        List<CoverageService.ZoneCoverage> zones = coverageService.zoneCoverage("WARD", 2_000, null);

        assertThat(zones.get(0).coveredRatio()).isLessThanOrEqualTo(zones.get(1).coveredRatio());
    }

    @Test
    @DisplayName("the maxRatio filter returns only zones below a threshold")
    void maxRatioFiltersGaps() {
        insertZone("Served", 77.18, 28.58, 77.22, 28.62, 10_000L);
        insertZone("Unserved", 77.50, 28.80, 77.54, 28.84, 10_000L);
        insertStop("COV-3", 77.20, 28.60);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        List<CoverageService.ZoneCoverage> gaps = coverageService.zoneCoverage("WARD", 2_000, 0.0);

        assertThat(gaps).isNotEmpty();
        assertThat(gaps).allSatisfy(zone -> assertThat(zone.coveredRatio()).isZero());
    }

    @Test
    @DisplayName("coverage gain counts only cells that were not already reachable")
    void gainIgnoresAlreadyCoveredArea() {
        insertStop("COV-EXISTING", 77.20, 28.60);
        coverageService.generateGrid(CELL_SIZE_METRES);
        coverageService.refreshCellCoverage();

        Long sameSpot = insertStop("COV-SAME", 77.2001, 28.6001);
        Long farAway = insertStop("COV-FAR", 77.55, 28.85);

        var noGain = coverageService.coverageGain(List.of(sameSpot), 2_000);
        var realGain = coverageService.coverageGain(List.of(farAway), 2_000);

        // A new route along an already-served corridor adds nothing, however long it is. That is the whole
        // point of measuring gain rather than length.
        assertThat(noGain.newlyCoveredCells()).isZero();
        assertThat(realGain.newlyCoveredCells()).isPositive();
    }

    @Test
    @DisplayName("gain with no stops is zero rather than an error")
    void emptyGainIsZero() {
        assertThat(coverageService.coverageGain(List.of(), 500).newlyCoveredCells()).isZero();
    }

    // --- helpers ------------------------------------------------------------

    private void insertZone(String name, double minLon, double minLat, double maxLon, double maxLat, Long population) {
        jdbc.update(
                """
                INSERT INTO coverage_zone (name, zone_type, geom, population)
                VALUES (?, 'WARD', ST_Multi(ST_MakeEnvelope(?, ?, ?, ?, 4326)), ?)
                """,
                name,
                minLon,
                minLat,
                maxLon,
                maxLat,
                population);
    }

    private Long insertStop(String code, double lon, double lat) {
        jdbc.update(
                """
                INSERT INTO stop (code, name, location)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                """,
                code,
                code,
                lon,
                lat);
        return jdbc.queryForObject("SELECT id FROM stop WHERE code = ?", Long.class, code);
    }

    /** Coverage operations are administrator-gated, and method security applies to direct calls too. */
    private void authenticateAsAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "coverage-test-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
