package com.dtc.transit.route.overlap;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteOverlapRepository extends JpaRepository<RouteOverlap, Long> {

    @Query("select o from RouteOverlap o where o.proposedPatternId in :patternIds")
    Page<RouteOverlap> findByPatternIds(@Param("patternIds") List<Long> patternIds, Pageable pageable);

    List<RouteOverlap> findByProposedPatternId(Long proposedPatternId);

    @Modifying
    @Query("delete from RouteOverlap o where o.proposedPatternId = :patternId")
    void deleteByProposedPatternId(@Param("patternId") Long patternId);
}
