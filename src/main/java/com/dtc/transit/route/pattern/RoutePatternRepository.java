package com.dtc.transit.route.pattern;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.route.route.Direction;

public interface RoutePatternRepository extends JpaRepository<RoutePattern, Long> {

    List<RoutePattern> findByRouteId(Long routeId);

    Optional<RoutePattern> findByRouteIdAndDirection(Long routeId, Direction direction);

    /**
     * Overlap between a candidate geometry and the patterns of every active route.
     *
     * <p>Native SQL, and necessarily so. The work is a buffer, an intersection, a dump into component
     * segments and a sum of lengths, all of which belong in PostGIS. Pulling thousands of patterns into
     * the JVM to do it in JTS would be orders of magnitude slower and would lose the spatial index.
     *
     * <p>Three details carry most of the correctness:
     *
     * <ul>
     *   <li>{@code ST_DWithin} runs first as an index-assisted prefilter, so the expensive intersection
     *       only touches patterns that could possibly be near.
     *   <li>The candidate is normalised with {@code ST_LineMerge(ST_UnaryUnion(...))} before its length is
     *       measured, so a route that runs out and back along one road is not counted twice and the ratio
     *       stays at or below 1 (edge case EC-GEO-10).
     *   <li>Segments shorter than {@code minSegmentM} are discarded, which is what stops a junction
     *       crossing being reported as a shared corridor (edge case EC-GEO-07).
     * </ul>
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    WITH candidate AS (
                      SELECT ST_LineMerge(ST_UnaryUnion(
                               ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 32643))) AS g
                    ),
                    candidate_length AS (
                      SELECT GREATEST(ST_Length(g), 0.001) AS len FROM candidate
                    ),
                    nearby AS (
                      SELECT rp.id, rp.route_id, rp.direction, rp.geom_utm
                      FROM route_pattern rp
                      JOIN route r ON r.id = rp.route_id
                      CROSS JOIN candidate c
                      WHERE r.status = 'ACTIVE'
                        AND rp.id <> COALESCE(:excludePatternId, -1)
                        AND rp.route_id <> COALESCE(:excludeRouteId, -1)
                        AND ST_DWithin(rp.geom_utm, c.g, :bufferM)
                    ),
                    segments AS (
                      SELECT n.id AS pattern_id, n.route_id, n.direction, d.geom AS seg
                      FROM nearby n
                      CROSS JOIN candidate c
                      CROSS JOIN LATERAL ST_Dump(
                        ST_Intersection(
                          c.g,
                          ST_Buffer(n.geom_utm, :bufferM, 'endcap=flat join=round'))) d
                      WHERE ST_GeometryType(d.geom) = 'ST_LineString'
                    )
                    SELECT s.pattern_id AS patternId,
                           s.route_id   AS routeId,
                           r.route_no   AS routeNo,
                           r.name       AS routeName,
                           s.direction  AS direction,
                           SUM(ST_Length(s.seg)) AS overlapM,
                           SUM(ST_Length(s.seg)) / (SELECT len FROM candidate_length) AS overlapRatio
                    FROM segments s
                    JOIN route r ON r.id = s.route_id
                    WHERE ST_Length(s.seg) >= :minSegmentM
                    GROUP BY s.pattern_id, s.route_id, r.route_no, r.name, s.direction
                    ORDER BY SUM(ST_Length(s.seg)) DESC
                    """)
    List<OverlapRow> findOverlaps(
            @Param("wkt") String wellKnownText,
            @Param("bufferM") double bufferMetres,
            @Param("minSegmentM") double minSegmentMetres,
            @Param("excludePatternId") Long excludePatternId,
            @Param("excludeRouteId") Long excludeRouteId);

    /**
     * Whether a candidate runs the same way as an existing pattern along their shared corridor.
     *
     * <p>Projects the candidate's start and end onto the existing line and compares the positions. If
     * both advance together the routes run the same way. Comparing raw endpoints would be wrong for a
     * loop, where start and end coincide (edge case EC-GEO-09).
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT ST_LineLocatePoint(rp.geom_utm, ST_EndPoint(c.g))
                           >= ST_LineLocatePoint(rp.geom_utm, ST_StartPoint(c.g))
                    FROM route_pattern rp
                    CROSS JOIN (SELECT ST_LineMerge(ST_UnaryUnion(
                                  ST_Transform(ST_SetSRID(ST_GeomFromText(:wkt), 4326), 32643))) AS g) c
                    WHERE rp.id = :patternId
                      AND ST_GeometryType(c.g) = 'ST_LineString'
                    """)
    Optional<Boolean> sharesDirectionWith(@Param("patternId") Long patternId, @Param("wkt") String wellKnownText);

    /**
     * How many of a pattern's stops sit within tolerance of one of the candidate's stops.
     *
     * <p>Shared stops are a stronger duplication signal than geometry alone: two routes can run the same
     * corridor while serving different stops, which is exactly what an express service does.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT count(DISTINCT ps.stop_id)
                    FROM pattern_stop ps
                    JOIN stop existing ON existing.id = ps.stop_id
                    JOIN stop proposed ON proposed.id = ANY(CAST(:candidateStopIds AS bigint[]))
                    WHERE ps.pattern_id = :patternId
                      AND ST_DWithin(existing.location_utm, proposed.location_utm, :toleranceM)
                    """)
    long countSharedStops(
            @Param("patternId") Long patternId,
            @Param("candidateStopIds") Long[] candidateStopIds,
            @Param("toleranceM") double toleranceMetres);

    /** Distance along the line, in metres, at which a stop sits. */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT ST_LineLocatePoint(rp.geom_utm, s.location_utm) * rp.length_m
                    FROM route_pattern rp, stop s
                    WHERE rp.id = :patternId AND s.id = :stopId
                    """)
    Optional<Double> distanceAlongPattern(@Param("patternId") Long patternId, @Param("stopId") Long stopId);

    /** Shortest distance in metres from a stop to the pattern line. */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT ST_Distance(rp.geom_utm, s.location_utm)
                    FROM route_pattern rp, stop s
                    WHERE rp.id = :patternId AND s.id = :stopId
                    """)
    Optional<Double> distanceFromLine(@Param("patternId") Long patternId, @Param("stopId") Long stopId);

    /** Projection of the overlap query result. */
    interface OverlapRow {
        Long getPatternId();

        Long getRouteId();

        String getRouteNo();

        String getRouteName();

        String getDirection();

        double getOverlapM();

        double getOverlapRatio();
    }
}
