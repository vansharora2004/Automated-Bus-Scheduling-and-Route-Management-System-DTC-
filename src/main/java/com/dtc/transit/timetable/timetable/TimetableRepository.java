package com.dtc.transit.timetable.timetable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.route.route.DayType;

public interface TimetableRepository extends JpaRepository<Timetable, Long> {

    List<Timetable> findByRouteIdOrderByValidFromDesc(Long routeId);

    /**
     * The active timetable covering a service date.
     *
     * <p>An open-ended timetable has a null {@code validTo} and runs until something replaces it, so the upper
     * bound is only compared when it is set. The database refuses two active timetables whose windows overlap
     * for the same route and day type, so at most one row can match.
     */
    @Query(
            """
            select t from Timetable t
            where t.routeId = :routeId
              and t.dayType = :dayType
              and t.status = com.dtc.transit.timetable.timetable.TimetableStatus.ACTIVE
              and t.validFrom <= :serviceDate
              and (t.validTo is null or t.validTo >= :serviceDate)
            """)
    Optional<Timetable> findActiveFor(
            @Param("routeId") Long routeId,
            @Param("dayType") DayType dayType,
            @Param("serviceDate") LocalDate serviceDate);

    @Query(
            """
            select t from Timetable t
            where t.routeId = :routeId
              and t.status = com.dtc.transit.timetable.timetable.TimetableStatus.ACTIVE
            order by t.dayType
            """)
    List<Timetable> findActiveByRoute(@Param("routeId") Long routeId);

    /**
     * Whether an active timetable already covers part of this validity window.
     *
     * <p>Native, and deliberately written with the same {@code daterange} and {@code &&} the exclusion
     * constraint uses. A JPQL approximation would have to special-case the open-ended upper bound, and the
     * two could then disagree: the check would pass and the constraint would still refuse the write.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT EXISTS (
                      SELECT 1 FROM timetable
                      WHERE route_id = :routeId
                        AND day_type = :dayType
                        AND status = 'ACTIVE'
                        AND id <> :excludeId
                        AND validity && daterange(CAST(:validFrom AS date), CAST(:validTo AS date), '[]'))
                    """)
    boolean activeValidityOverlaps(
            @Param("routeId") Long routeId,
            @Param("dayType") String dayType,
            @Param("excludeId") Long excludeId,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo);
}
