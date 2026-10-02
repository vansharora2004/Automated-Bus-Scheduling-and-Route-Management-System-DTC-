package com.dtc.transit.route.pattern;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.route.route.DayType;

public interface RunningTimeBandRepository extends JpaRepository<RunningTimeBand, RunningTimeBand.Key> {

    @Query(
            """
            select b from RunningTimeBand b
            where b.key.patternId = :patternId and b.key.dayType = :dayType
            order by b.key.fromSec
            """)
    List<RunningTimeBand> findByPatternAndDayType(
            @Param("patternId") Long patternId, @Param("dayType") DayType dayType);

    @Query("select b from RunningTimeBand b where b.key.patternId = :patternId order by b.key.dayType, b.key.fromSec")
    List<RunningTimeBand> findByPattern(@Param("patternId") Long patternId);

    @Modifying
    @Query("delete from RunningTimeBand b where b.key.patternId = :patternId")
    void deleteByPatternId(@Param("patternId") Long patternId);
}
