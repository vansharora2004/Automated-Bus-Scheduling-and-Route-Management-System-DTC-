package com.dtc.transit.timetable.headway;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.route.route.Direction;

public interface HeadwayBandRepository extends JpaRepository<HeadwayBand, HeadwayBand.Key> {

    @Query("select b from HeadwayBand b where b.key.timetableId = :timetableId order by b.key.direction, b.key.fromSec")
    List<HeadwayBand> findOrderedByTimetable(@Param("timetableId") Long timetableId);

    @Query(
            """
            select b from HeadwayBand b
            where b.key.timetableId = :timetableId and b.key.direction = :direction
            order by b.key.fromSec
            """)
    List<HeadwayBand> findOrderedByTimetableAndDirection(
            @Param("timetableId") Long timetableId, @Param("direction") Direction direction);

    @Modifying
    @Query("delete from HeadwayBand b where b.key.timetableId = :timetableId")
    void deleteByTimetableId(@Param("timetableId") Long timetableId);
}
