package com.dtc.transit.scheduling.duty;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.schedule.ScheduleService;

/**
 * Duty and handover reads.
 *
 * <p>Read-only. Duties are produced by a run and changed only by producing another one, which is what keeps a
 * published roster explicable: every duty traces to a run, a rule set and a snapshot.
 */
@Service
public class DutyService {

    private final DutyRepository duties;
    private final HandoverRepository handovers;
    private final ScheduleService scheduleService;

    public DutyService(
            DutyRepository duties, HandoverRepository handovers, ScheduleService scheduleService) {
        this.duties = duties;
        this.handovers = handovers;
        this.scheduleService = scheduleService;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<Duty> ofSchedule(Long scheduleId, DutyType dutyType, Pageable pageable) {
        // Through the schedule service, so the depot-scoping check that guards a schedule also guards its duties.
        scheduleService.require(scheduleId);
        return dutyType == null
                ? duties.findByScheduleIdOrderByDutyNoAsc(scheduleId, pageable)
                : duties.findByScheduleIdAndDutyTypeOrderByDutyNoAsc(scheduleId, dutyType, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<Handover> handoversOf(Long scheduleId, Pageable pageable) {
        scheduleService.require(scheduleId);
        return handovers.findByScheduleIdOrderByAtSecAsc(scheduleId, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public DutyDetail detail(Long scheduleId, Long dutyId) {
        scheduleService.require(scheduleId);
        Duty duty = duties.findById(dutyId).orElseThrow(() -> NotFoundException.of("Duty", dutyId));
        if (!duty.getScheduleId().equals(scheduleId)) {
            // A duty of another schedule is not found here, rather than forbidden: saying "wrong schedule" would
            // confirm the duty exists to someone who cannot see it.
            throw NotFoundException.of("Duty", dutyId);
        }
        return new DutyDetail(duty, duties.piecesOf(dutyId));
    }

    /** Totals per duty type, which is the fairness picture a planner wants before publishing. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public List<DutyRepository.DutyTypeSummary> summary(Long scheduleId) {
        scheduleService.require(scheduleId);
        return duties.summaryByType(scheduleId);
    }

    public record DutyDetail(Duty duty, List<PieceOfWork> pieces) {}
}
