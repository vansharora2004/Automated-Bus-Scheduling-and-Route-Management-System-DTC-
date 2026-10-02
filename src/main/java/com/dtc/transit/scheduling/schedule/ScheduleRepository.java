package com.dtc.transit.scheduling.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, JpaSpecificationExecutor<Schedule> {

    Optional<Schedule> findByRunId(java.util.UUID runId);

    @Query(
            """
            select coalesce(max(s.versionNo), 0) from Schedule s
            where s.depotId = :depotId and s.serviceDate = :serviceDate
            """)
    int highestVersionFor(@Param("depotId") Long depotId, @Param("serviceDate") LocalDate serviceDate);

    @Query(
            """
            select s from Schedule s
            where s.depotId = :depotId and s.serviceDate = :serviceDate
              and s.status = com.dtc.transit.scheduling.schedule.ScheduleStatus.PUBLISHED
            """)
    Optional<Schedule> findPublished(
            @Param("depotId") Long depotId, @Param("serviceDate") LocalDate serviceDate);

    Page<Schedule> findByDepotIdOrderByServiceDateDescVersionNoDesc(Long depotId, Pageable pageable);

    List<Schedule> findByServiceDateAndStatusIn(LocalDate serviceDate, List<ScheduleStatus> statuses);
}
