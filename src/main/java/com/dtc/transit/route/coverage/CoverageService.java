package com.dtc.transit.route.coverage;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service coverage by zone, and the coverage a proposed route would add.
 *
 * <p>Coverage is computed from precomputed grid cells rather than from stop buffers. The direct approach,
 * unioning a 500 m buffer around every stop and intersecting it with each zone, is correct and far too
 * slow to answer a request; the cells turn it into an indexed join (edge case EC-PERF-07).
 */
@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    /** Walking catchment of a stop. */
    public static final double DEFAULT_CATCHMENT_METRES = 500;

    private final CoverageRepository coverage;
    private final JdbcTemplate jdbc;

    public CoverageService(CoverageRepository coverage, JdbcTemplate jdbc) {
        this.coverage = coverage;
        this.jdbc = jdbc;
    }

    /**
     * Coverage per zone, lowest first so the worst-served areas lead.
     *
     * @param maxRatio optional ceiling, for listing only zones below a threshold
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','PLANNER')")
    @Transactional(readOnly = true)
    public List<ZoneCoverage> zoneCoverage(String zoneType, double catchmentMetres, Double maxRatio) {
        if (coverage.countGridCells() == 0) {
            // Zero cells and zero coverage look identical in the numbers, and acting on "nothing is
            // covered" when the grid was simply never generated would be a costly mistake.
            throw new com.dtc.transit.common.error.BusinessRuleException(
                    "GRID_NOT_GENERATED",
                    "No grid cells exist, so coverage cannot be measured. Generate the grid first.");
        }

        return coverage.zoneCoverage(zoneType, catchmentMetres).stream()
                .map(row -> ZoneCoverage.from(row, catchmentMetres))
                .filter(zone -> maxRatio == null || zone.coveredRatio() <= maxRatio)
                .sorted(java.util.Comparator.comparingDouble(ZoneCoverage::coveredRatio))
                .toList();
    }

    /** What a candidate's stops would newly bring within walking distance. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','PLANNER')")
    @Transactional(readOnly = true)
    public CoverageGain coverageGain(List<Long> stopIds, double catchmentMetres) {
        if (stopIds.isEmpty()) {
            return new CoverageGain(0, 0, catchmentMetres);
        }
        var row = coverage.coverageGain(stopIds.toArray(Long[]::new), catchmentMetres);
        return new CoverageGain(row.getNewlyCoveredCells(), row.getNewlyCoveredArea(), catchmentMetres);
    }

    /**
     * Rebuilds the grid over the active service area.
     *
     * <p>Only an administrator, and only on request: it discards and rebuilds every cell, which also
     * invalidates the coverage view until it is refreshed.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int generateGrid(double cellSizeMetres) {
        Integer cells = jdbc.queryForObject("SELECT generate_grid_cells(?)", Integer.class, cellSizeMetres);
        log.info("generated {} grid cells at {} m", cells, cellSizeMetres);
        return cells == null ? 0 : cells;
    }

    /**
     * Recomputes each cell's distance to the nearest stop.
     *
     * <p>{@code NOT_SUPPORTED} suspends any surrounding transaction, because
     * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} cannot run inside one. Concurrency is the point:
     * a plain refresh takes an exclusive lock and blocks every coverage read while stops are being
     * edited (edge case EC-GEO-20).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshCellCoverage() {
        try {
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY cell_coverage");
        } catch (RuntimeException e) {
            // CONCURRENTLY needs the view to have been populated at least once. Fall back rather than
            // leave coverage stale, and say so.
            log.warn("Concurrent refresh failed ({}), falling back to a blocking refresh", e.getMessage());
            jdbc.execute("REFRESH MATERIALIZED VIEW cell_coverage");
        }
    }

    /**
     * Coverage of one zone.
     *
     * @param weighting which basis was used, so a reader cannot mistake area for population
     */
    public record ZoneCoverage(
            Long zoneId,
            String zoneName,
            String zoneType,
            Long population,
            double coveredRatio,
            double coveredAreaSqM,
            double totalAreaSqM,
            CoverageWeighting weighting,
            double catchmentMetres) {

        static ZoneCoverage from(CoverageRepository.ZoneCoverageRow row, double catchmentMetres) {
            // A zone with no area would divide by zero; report it as uncovered rather than NaN
            // (edge case EC-REP-04).
            double ratio = row.getTotalArea() <= 0 ? 0 : row.getCoveredArea() / row.getTotalArea();
            return new ZoneCoverage(
                    row.getZoneId(),
                    row.getZoneName(),
                    row.getZoneType(),
                    row.getPopulation(),
                    ratio,
                    row.getCoveredArea(),
                    row.getTotalArea(),
                    row.getPopulation() == null ? CoverageWeighting.AREA : CoverageWeighting.POPULATION,
                    catchmentMetres);
        }
    }

    /** @param newlyCoveredCells cells brought within reach that were not before */
    public record CoverageGain(long newlyCoveredCells, double newlyCoveredAreaSqM, double catchmentMetres) {}
}
