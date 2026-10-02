package com.dtc.transit.route.pattern;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatternStopRepository extends JpaRepository<PatternStop, PatternStop.Key> {

    @Query("select ps from PatternStop ps where ps.key.patternId = :patternId order by ps.key.seq")
    List<PatternStop> findOrderedByPattern(@Param("patternId") Long patternId);

    @Query("select ps.stopId from PatternStop ps where ps.key.patternId = :patternId order by ps.key.seq")
    List<Long> findStopIdsOrdered(@Param("patternId") Long patternId);

    @Modifying
    @Query("delete from PatternStop ps where ps.key.patternId = :patternId")
    void deleteByPatternId(@Param("patternId") Long patternId);
}
