package com.dtc.transit.timetable.trip;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<Trip, Long> {

    long countByTimetableId(Long timetableId);

    List<Trip> findByTimetableIdOrderByStartSecAsc(Long timetableId);

    @Modifying
    @Query("delete from Trip t where t.timetableId = :timetableId")
    void deleteByTimetableId(@Param("timetableId") Long timetableId);

    /**
     * One page of trips, ordered by id, starting after a cursor.
     *
     * <p>Keyset rather than offset. Trips are the highest-volume table in the system at roughly 50,000 rows
     * per day type, and a deep offset forces the database to walk and discard every preceding row. A cursor
     * costs the same at page 1 and page 5,000, and cannot skip or repeat a row when trips are inserted
     * during paging (edge case EC-API-05).
     */
    @Query(
            """
            select t from Trip t
            where (:afterId is null or t.id > :afterId)
              and (:timetableId is null or t.timetableId = :timetableId)
              and (:patternId is null or t.patternId = :patternId)
              and (:fromSec is null or t.startSec >= :fromSec)
              and (:toSec is null or t.startSec <= :toSec)
            order by t.id
            """)
    List<Trip> findPage(
            @Param("afterId") Long afterId,
            @Param("timetableId") Long timetableId,
            @Param("patternId") Long patternId,
            @Param("fromSec") Integer fromSec,
            @Param("toSec") Integer toSec,
            org.springframework.data.domain.Pageable limit);
}
