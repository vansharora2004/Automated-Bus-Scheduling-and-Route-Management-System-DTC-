package com.dtc.transit.timetable.deadhead;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeadheadRepository extends JpaRepository<Deadhead, Long> {

    List<Deadhead> findByEstimatedTrue();

    /**
     * The value for the band covering a departure time.
     *
     * <p>Picks the latest band starting at or before the departure, which is how a banded lookup is meant to
     * work: bands define "from this time onward until the next band".
     */
    @Query(
            """
            select d from Deadhead d
            where d.fromStopId = :fromStopId
              and d.toStopId = :toStopId
              and d.fromSecBand <= :departureSec
            order by d.fromSecBand desc
            limit 1
            """)
    Optional<Deadhead> findForBand(
            @Param("fromStopId") Long fromStopId,
            @Param("toStopId") Long toStopId,
            @Param("departureSec") int departureSec);

    /**
     * Straight-line distance between two stops, in metres.
     *
     * <p>Measured in the projected CRS. Doing this in degrees would give a number that is not a distance,
     * which is the single easiest way to produce a plausible but wrong deadhead.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT ST_Distance(a.location_utm, b.location_utm)
                    FROM stop a, stop b
                    WHERE a.id = :fromStopId AND b.id = :toStopId
                    """)
    Optional<Double> straightLineDistance(
            @Param("fromStopId") Long fromStopId, @Param("toStopId") Long toStopId);
}
