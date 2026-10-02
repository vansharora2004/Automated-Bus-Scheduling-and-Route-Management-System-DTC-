package com.dtc.transit.timetable.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarExceptionRepository extends JpaRepository<CalendarException, Long> {

    /**
     * The override that applies to a date for a depot, if any.
     *
     * <p>Ordered so a depot-specific row is returned ahead of a network-wide one. Without that ordering the
     * result would depend on insertion order, and a local event could silently override the network or not.
     */
    @Query(
            """
            select e from CalendarException e
            where e.serviceDate = :serviceDate
              and (e.depotId = :depotId or e.depotId is null)
            order by case when e.depotId is null then 1 else 0 end
            limit 1
            """)
    Optional<CalendarException> findForDateAndDepot(
            @Param("serviceDate") LocalDate serviceDate, @Param("depotId") Long depotId);

    @Query("select e from CalendarException e where e.serviceDate = :serviceDate and e.depotId is null")
    Optional<CalendarException> findNetworkWide(@Param("serviceDate") LocalDate serviceDate);

    List<CalendarException> findByServiceDateBetweenOrderByServiceDateAsc(LocalDate from, LocalDate to);
}
