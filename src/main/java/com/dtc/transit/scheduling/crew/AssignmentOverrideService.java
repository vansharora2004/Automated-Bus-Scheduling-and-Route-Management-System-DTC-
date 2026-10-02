package com.dtc.transit.scheduling.crew;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.scheduling.engine.assignment.EligibilityFilter;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.rules.RuleSetResolver;

/**
 * Manual replacement of a crew member on a duty.
 *
 * <p>Guarded three ways, because this is the one place a human writes directly into a schedule. The caller must
 * supply the version they are editing, so two schedulers cannot silently overwrite each other; the replacement is
 * re-checked against the same eligibility rules the assigner used, so an override cannot quietly create an
 * illegal roster; and the database refuses the write outright if the new person is already published elsewhere in
 * that window.
 *
 * <p>Overriding into an ineligible crew member is refused rather than warned about. A warning here would be read
 * as permission, and the result would be someone rostered during their leave.
 */
@Service
public class AssignmentOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentOverrideService.class);

    private final DutyAssignmentRepository assignments;
    private final CrewSnapshotLoader crewLoader;
    private final RuleSetResolver ruleSets;
    private final AppTimeProperties timeProperties;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public AssignmentOverrideService(
            DutyAssignmentRepository assignments,
            CrewSnapshotLoader crewLoader,
            RuleSetResolver ruleSets,
            AppTimeProperties timeProperties,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events) {
        this.assignments = assignments;
        this.crewLoader = crewLoader;
        this.ruleSets = ruleSets;
        this.timeProperties = timeProperties;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public DutyAssignment require(Long id) {
        return assignments.findById(id).orElseThrow(() -> NotFoundException.of("DutyAssignment", id));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<DutyAssignment> forSchedule(Long scheduleId, Pageable pageable) {
        return assignments.findBySchedule(scheduleId, pageable);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<DutyAssignment> forCrewMember(Long crewMemberId, Pageable pageable) {
        return assignments.findByCrewMemberIdOrderByStartsAtDesc(crewMemberId, pageable);
    }

    /**
     * Replaces the crew member on an assignment.
     *
     * @param expectedVersion from the caller's {@code If-Match} header. A mismatch means somebody else changed
     *     the row first, and the caller is told to reload rather than having their decision silently applied to a
     *     state they never saw.
     * @throws ConflictException when the version is stale, or the database refuses the write
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public DutyAssignment override(Long assignmentId, Long newCrewMemberId, String reason, long expectedVersion) {
        DutyAssignment assignment = require(assignmentId);

        if (assignment.getVersion() != expectedVersion) {
            throw new ConflictException(
                    "STALE_VERSION",
                    "This assignment is at version %d, not %d. Reload it and try again."
                            .formatted(assignment.getVersion(), expectedVersion));
        }

        Long previousCrewMemberId = assignment.getCrewMemberId();
        requireEligible(assignment, newCrewMemberId);
        assignment.overrideCrew(newCrewMemberId, reason);

        try {
            assignments.saveAndFlush(assignment);
        } catch (OptimisticLockingFailureException e) {
            // The version check above closes the common race; this closes the one where two requests pass it in
            // the same instant.
            throw new ConflictException(
                    "STALE_VERSION", "Another change to this assignment was saved first. Reload and try again.");
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "CREW_DOUBLE_BOOKED",
                    "That crew member already holds a published duty overlapping this one.");
        }

        events.publishEvent(new AuditEvent(
                "ASSIGNMENT_OVERRIDDEN",
                "DUTY_ASSIGNMENT",
                String.valueOf(assignmentId),
                """
                {"crewMemberId":%d}""".formatted(previousCrewMemberId),
                """
                {"crewMemberId":%d}""".formatted(newCrewMemberId),
                reason));
        log.info(
                "assignment {} overridden from crew {} to {}", assignmentId, previousCrewMemberId, newCrewMemberId);
        return assignment;
    }

    /**
     * Re-checks the replacement against the rules the assigner used.
     *
     * <p>The same {@link EligibilityFilter}, not a reduced version of it. An override path with its own weaker
     * checks is how an illegal roster gets in through the side door.
     */
    private void requireEligible(DutyAssignment assignment, Long newCrewMemberId) {
        Long depotId = jdbc.queryForObject(
                """
                SELECT s.depot_id FROM schedule s
                JOIN duty d ON d.schedule_id = s.id
                WHERE d.id = ?
                """,
                Long.class,
                assignment.getDutyId());
        LocalDate serviceDate = assignment.getServiceDate();
        RuleSet rules = ruleSets.resolve(depotId, serviceDate).rules();

        var crew = crewLoader.load(depotId, serviceDate);
        var replacement = crew.pool().members().stream()
                .filter(member -> member.id() == newCrewMemberId)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "CREW_NOT_AT_DEPOT",
                        "Crew member %d is not available at depot %d on %s"
                                .formatted(newCrewMemberId, depotId, serviceDate)));

        DutyPlan duty = dutyPlanOf(assignment);
        var filtered = new EligibilityFilter(timeProperties.zone())
                .filter(
                        List.of(replacement),
                        duty,
                        assignment.getCrewRole(),
                        depotId,
                        serviceDate,
                        Set.of(),
                        crew.history(),
                        rules);

        if (filtered.isEmpty()) {
            throw new BusinessRuleException(
                    "CREW_NOT_ELIGIBLE",
                    "Crew member %d cannot take duty %d: %s"
                            .formatted(newCrewMemberId, assignment.getDutyId(), filtered.describe()));
        }
    }

    /** The duty as the eligibility rules need to see it: its times, its type and nothing else. */
    private DutyPlan dutyPlanOf(DutyAssignment assignment) {
        return jdbc.queryForObject(
                """
                SELECT duty_no, mode, duty_type, sign_on_sec, sign_off_sec, platform_sec, paid_sec,
                       break_sec, spread_sec, overtime_sec
                FROM duty WHERE id = ?
                """,
                (rs, row) -> new DutyPlan(
                        rs.getInt("duty_no"),
                        SchedulingMode.valueOf(rs.getString("mode")),
                        DutyType.valueOf(rs.getString("duty_type")),
                        rs.getInt("sign_on_sec"),
                        rs.getInt("sign_off_sec"),
                        rs.getInt("platform_sec"),
                        rs.getInt("paid_sec"),
                        rs.getInt("break_sec"),
                        rs.getInt("spread_sec"),
                        rs.getInt("overtime_sec"),
                        List.of(new PieceOfWorkPlan(
                                0, 0, 0, rs.getInt("sign_on_sec"), rs.getInt("sign_off_sec"), null, null)),
                        List.of()),
                assignment.getDutyId());
    }
}
