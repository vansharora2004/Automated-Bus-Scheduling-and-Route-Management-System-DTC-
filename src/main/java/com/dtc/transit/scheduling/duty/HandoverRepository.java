package com.dtc.transit.scheduling.duty;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoverRepository extends JpaRepository<Handover, Long> {

    Page<Handover> findByScheduleIdOrderByAtSecAsc(Long scheduleId, Pageable pageable);

    List<Handover> findByScheduleIdOrderByAtSecAsc(Long scheduleId);

    long countByScheduleId(Long scheduleId);
}
