package com.dtc.transit.route.coverage;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoverageRepository extends JpaRepository<CoverageZone, Long> {

    /**
     * Coverage per zone, as a share of zone area within reach of a stop.
     *
     * <p>Built on precomputed grid cells rather than on stop buffers. Unioning thousands of 500 m buffers
     * and intersecting the result with every zone is the obvious approach and far too slow to answer a
     * request; the cells turn it into an indexed join (edge case EC-PERF-07).
     *
     * <p>The radius is a query parameter because {@code cell_coverage} stores the distance to the nearest
     * stop rather than a boolean. Storing "covered" would have baked 500 m into the view.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT z.id   AS zoneId,
                           z.name AS zoneName,
                           z.zone_type AS zoneType,
                           z.population AS population,
                           COALESCE(SUM(ST_Area(ST_Intersection(c.geom_utm, z.geom_utm))), 0) AS totalArea,
                           COALESCE(SUM(ST_Area(ST_Intersection(c.geom_utm, z.geom_utm)))
                                    FILTER (WHERE cc.nearest_stop_m IS NOT NULL
                                              AND cc.nearest_stop_m <= :radiusM), 0) AS coveredArea
                    FROM coverage_zone z
                    LEFT JOIN grid_cell c ON ST_Intersects(c.geom_utm, z.geom_utm)
                    LEFT JOIN cell_coverage cc ON cc.cell_id = c.id
                    WHERE (:zoneType IS NULL OR z.zone_type = :zoneType)
                    GROUP BY z.id, z.name, z.zone_type, z.population
                    ORDER BY z.name
                    """)
    List<ZoneCoverageRow> zoneCoverage(@Param("zoneType") String zoneType, @Param("radiusM") double radiusMetres);

    /**
     * Cells that a candidate's stops would newly bring within reach.
     *
     * <p>Counts only cells not already covered, which is the whole point: a new route along a
     * well-served corridor adds nothing, however long it is.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT COUNT(*) AS newlyCoveredCells,
                           COALESCE(SUM(ST_Area(c.geom_utm)), 0) AS newlyCoveredArea
                    FROM grid_cell c
                    LEFT JOIN cell_coverage cc ON cc.cell_id = c.id
                    WHERE (cc.nearest_stop_m IS NULL OR cc.nearest_stop_m > :radiusM)
                      AND EXISTS (
                            SELECT 1 FROM stop s
                            WHERE s.id = ANY(CAST(:stopIds AS bigint[]))
                              AND ST_DWithin(s.location_utm, c.centroid_utm, :radiusM))
                    """)
    CoverageGainRow coverageGain(@Param("stopIds") Long[] stopIds, @Param("radiusM") double radiusMetres);

    /** Number of grid cells, used to tell "no coverage" apart from "no grid generated". */
    @Query(nativeQuery = true, value = "SELECT count(*) FROM grid_cell")
    long countGridCells();

    interface ZoneCoverageRow {
        Long getZoneId();

        String getZoneName();

        String getZoneType();

        Long getPopulation();

        double getTotalArea();

        double getCoveredArea();
    }

    interface CoverageGainRow {
        long getNewlyCoveredCells();

        double getNewlyCoveredArea();
    }
}
