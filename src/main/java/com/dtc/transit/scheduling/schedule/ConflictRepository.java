package com.dtc.transit.scheduling.schedule;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.scheduling.engine.model.Severity;

public interface ConflictRepository extends JpaRepository<Conflict, Long> {

    Page<Conflict> findByScheduleIdOrderBySeverityAscIdAsc(Long scheduleId, Pageable pageable);

    Page<Conflict> findByScheduleIdAndSeverityOrderByIdAsc(Long scheduleId, Severity severity, Pageable pageable);

    List<Conflict> findByScheduleIdOrderByIdAsc(Long scheduleId);

    /** Unresolved hard conflicts, which is exactly what blocks publication. */
    @Query(
            """
            select count(c) from Conflict c
            where c.scheduleId = :scheduleId
              and c.severity = com.dtc.transit.scheduling.engine.model.Severity.HARD
              and c.resolved = false
            """)
    long countBlocking(@Param("scheduleId") Long scheduleId);

    @Modifying
    @Query("delete from Conflict c where c.scheduleId = :scheduleId")
    void deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
