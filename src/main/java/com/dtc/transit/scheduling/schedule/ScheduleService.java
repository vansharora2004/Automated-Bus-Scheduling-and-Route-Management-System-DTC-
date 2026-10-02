package com.dtc.transit.scheduling.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.scheduling.crew.DutyAssignmentRepository;
import com.dtc.transit.scheduling.engine.model.Severity;
import com.dtc.transit.security.DepotAccessEvaluator;

/**
 * The schedule lifecycle: read, validate, publish.
 *
 * <p>Publication is the only operation here that changes anything a crew sees, and it is deliberately narrow: a
 * schedule can only be published when nothing blocks it, and publishing supersedes the previous version in the
 * same transaction so there is never a moment with two live schedules for one depot-day.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository schedules;
    private final VehicleBlockRepository blocks;
    private final ConflictRepository conflicts;
    private final BusAssignmentRepository assignments;
    private final DutyAssignmentRepository crewAssignments;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ScheduleService(
            ScheduleRepository schedules,
            VehicleBlockRepository blocks,
            ConflictRepository conflicts,
            BusAssignmentRepository assignments,
            DutyAssignmentRepository crewAssignments,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events,
            Clock clock) {
        this.schedules = schedules;
        this.blocks = blocks;
        this.conflicts = conflicts;
        this.assignments = assignments;
        this.crewAssignments = crewAssignments;
        this.depotAccess = depotAccess;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Schedule require(Long scheduleId) {
        Schedule schedule =
                schedules.findById(scheduleId).orElseThrow(() -> NotFoundException.of("Schedule", scheduleId));
        depotAccess.requireAccess(schedule.getDepotId(), "Schedule", scheduleId);
        return schedule;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<Schedule> listForDepot(Long depotId, Pageable pageable) {
        depotAccess.requireAccess(depotId, "Depot", depotId);
        return schedules.findByDepotIdOrderByServiceDateDescVersionNoDesc(depotId, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<VehicleBlock> blocksOf(Long scheduleId, Pageable pageable) {
        require(scheduleId);
        return blocks.findByScheduleIdOrderByBlockNoAsc(scheduleId, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<Conflict> conflictsOf(Long scheduleId, Severity severity, Pageable pageable) {
        require(scheduleId);
        return severity == null
                ? conflicts.findByScheduleIdOrderBySeverityAscIdAsc(scheduleId, pageable)
                : conflicts.findByScheduleIdAndSeverityOrderByIdAsc(scheduleId, severity, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public List<BusAssignment> assignmentsOf(Long scheduleId) {
        require(scheduleId);
        return assignments.findBySchedule(scheduleId);
    }

    /**
     * Re-reads the stored conflicts and moves the schedule to validated when none of them block.
     *
     * <p>Validation does not rebuild the schedule. The conflicts stored by the run are evidence about the snapshot
     * it was built from, and re-deriving them now would answer a different question against different master data.
     * Phase 10 adds the revalidation job that does exactly that, deliberately and on a schedule.
     *
     * @return the result, including what is still blocking
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public Validation validate(Long scheduleId) {
        Schedule schedule = require(scheduleId);
        long blocking = conflicts.countBlocking(scheduleId);

        if (blocking > 0) {
            log.info("schedule {} still has {} blocking conflict(s)", scheduleId, blocking);
            return new Validation(schedule, false, blocking);
        }
        if (schedule.getStatus() == ScheduleStatus.DRAFT) {
            schedule.markValidated();
            schedules.save(schedule);
            events.publishEvent(AuditEvent.of("SCHEDULE_VALIDATED", "SCHEDULE", scheduleId));
        }
        return new Validation(schedule, true, 0);
    }

    /**
     * Publishes a schedule, superseding whatever was published for that depot-day.
     *
     * <p>Order matters. The old version's rows are flipped to {@code SUPERSEDED} before the new ones become
     * {@code PUBLISHED}, because the exclusion constraint on bus assignments only looks at published rows: doing
     * it the other way round would make the two versions overlap each other and abort the publish.
     *
     * <p>The constraint is still the guarantee. If anything slipped through — the previous day's late block
     * overlapping this morning's early one — it aborts here rather than putting two buses on one duty.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Schedule publish(Long scheduleId) {
        Schedule schedule = require(scheduleId);

        if (schedule.getStatus() == ScheduleStatus.PUBLISHED) {
            // Idempotent. A client retrying after a lost response is doing the right thing, and publishing twice
            // must not create a second version or report a failure for work that already succeeded.
            log.debug("schedule {} is already published; returning it unchanged", scheduleId);
            return schedule;
        }
        if (schedule.getStatus() != ScheduleStatus.VALIDATED) {
            throw new BusinessRuleException(
                    "SCHEDULE_NOT_VALIDATED",
                    "A schedule must be validated before it is published; this one is " + schedule.getStatus());
        }
        long blocking = conflicts.countBlocking(scheduleId);
        if (blocking > 0) {
            throw new BusinessRuleException(
                    "SCHEDULE_HAS_BLOCKING_CONFLICTS",
                    "%d unresolved hard conflict(s) must be resolved before publishing".formatted(blocking));
        }

        schedules
                .findPublished(schedule.getDepotId(), schedule.getServiceDate())
                .ifPresent(previous -> {
                    previous.supersede();
                    schedules.save(previous);
                    assignments.updateScheduleStatus(previous.getId(), ScheduleStatus.SUPERSEDED.name());
                    crewAssignments.updateScheduleStatus(previous.getId(), ScheduleStatus.SUPERSEDED.name());
                    log.info("superseded schedule {} v{}", previous.getId(), previous.getVersionNo());
                });
        schedules.flush();

        String actor = actor();
        schedule.publish(actor, clock.instant());
        schedules.save(schedule);
        assignments.updateScheduleStatus(scheduleId, ScheduleStatus.PUBLISHED.name());
        crewAssignments.updateScheduleStatus(scheduleId, ScheduleStatus.PUBLISHED.name());

        try {
            schedules.flush();
        } catch (DataIntegrityViolationException e) {
            // Either exclusion constraint can fire here: a bus in two published blocks, or a crew member in
            // two published duties. Both are the database refusing something the application thought was fine,
            // which is exactly why the constraints exist.
            throw new ConflictException(
                    "PUBLISH_WOULD_DOUBLE_BOOK",
                    "Publishing this schedule would double-book a bus or a crew member against another "
                            + "published schedule. Check duties and blocks that run past midnight.");
        }

        events.publishEvent(AuditEvent.of("SCHEDULE_PUBLISHED", "SCHEDULE", scheduleId));
        log.info("published schedule {} v{} by {}", scheduleId, schedule.getVersionNo(), actor);
        return schedule;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public Schedule discard(Long scheduleId) {
        Schedule schedule = require(scheduleId);
        schedule.discard();
        schedules.save(schedule);
        events.publishEvent(AuditEvent.of("SCHEDULE_DISCARDED", "SCHEDULE", scheduleId));
        return schedule;
    }

    /**
     * Marks a conflict as accepted by a human.
     *
     * <p>Resolving does not make the problem go away; it records that someone with authority looked at it and
     * decided to proceed. Publication counts unresolved hard conflicts, so this is the override, and it is audited.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Conflict resolveConflict(Long scheduleId, Long conflictId, String reason) {
        require(scheduleId);
        Conflict conflict =
                conflicts.findById(conflictId).orElseThrow(() -> NotFoundException.of("Conflict", conflictId));
        if (!conflict.getScheduleId().equals(scheduleId)) {
            throw NotFoundException.of("Conflict", conflictId);
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(
                    "RESOLUTION_NEEDS_REASON",
                    "Overriding a conflict requires a reason, which is recorded against the person who gave it");
        }
        conflict.resolve(actor());
        conflicts.save(conflict);
        events.publishEvent(new AuditEvent(
                "CONFLICT_RESOLVED", "CONFLICT", String.valueOf(conflictId), null, null, reason));
        return conflict;
    }

    /** Trip ids a schedule's blocks cover, for the coverage check the evaluation suite runs. */
    @Transactional(readOnly = true)
    public List<Long> coveredTripIds(Long scheduleId) {
        require(scheduleId);
        return blocks.coveredTripIds(scheduleId);
    }

    @Transactional(readOnly = true)
    public List<Schedule> publishedOn(LocalDate serviceDate) {
        return schedules.findByServiceDateAndStatusIn(serviceDate, List.of(ScheduleStatus.PUBLISHED));
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    /** @param blockingConflicts how many hard conflicts are still unresolved, which is why validation failed */
    public record Validation(Schedule schedule, boolean valid, long blockingConflicts) {}
}
