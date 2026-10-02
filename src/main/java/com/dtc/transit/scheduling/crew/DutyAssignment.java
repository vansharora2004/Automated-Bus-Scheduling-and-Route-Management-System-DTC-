package com.dtc.transit.scheduling.crew;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.jpa.BaseEntity;

/**
 * One named crew member on one duty, in one role.
 *
 * <p>The row the database guards. Its {@code work_period} is an absolute range, and an exclusion constraint
 * refuses two overlapping published rows for the same person — across dates and across depots, which is the case
 * application code reliably forgets.
 *
 * <p>{@code version} drives optimistic locking, which is what makes the manual override safe: two schedulers
 * changing the same assignment cannot both win, and the second is told to reload.
 */
@Entity
@Table(name = "duty_assignment")
public class DutyAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "duty_assignment_seq")
    @SequenceGenerator(name = "duty_assignment_seq", sequenceName = "duty_assignment_seq", allocationSize = 50)
    private Long id;

    @Column(name = "duty_id", nullable = false)
    private Long dutyId;

    @Column(name = "crew_role", nullable = false)
    private String crewRole;

    @Column(name = "crew_member_id", nullable = false)
    private Long crewMemberId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "schedule_status", nullable = false)
    private String scheduleStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

    @Column(name = "override_reason")
    private String overrideReason;

    protected DutyAssignment() {
        // for JPA
    }

    /**
     * Replaces the person on this duty.
     *
     * <p>The reason is required, not optional. An override changes somebody's working day, and a change nobody
     * explained is one nobody can review.
     */
    public void overrideCrew(Long newCrewMemberId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(
                    "OVERRIDE_NEEDS_REASON",
                    "Overriding an assignment requires a reason, which is recorded against whoever gave it");
        }
        if (status == AssignmentStatus.CANCELLED) {
            throw new BusinessRuleException(
                    "ASSIGNMENT_CANCELLED", "A cancelled assignment cannot be overridden; create a new one");
        }
        this.crewMemberId = newCrewMemberId;
        this.status = AssignmentStatus.OVERRIDDEN;
        this.overrideReason = reason;
    }

    public void cancel(String reason) {
        this.status = AssignmentStatus.CANCELLED;
        this.overrideReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getDutyId() {
        return dutyId;
    }

    public String getCrewRole() {
        return crewRole;
    }

    public Long getCrewMemberId() {
        return crewMemberId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getScheduleStatus() {
        return scheduleStatus;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public String getOverrideReason() {
        return overrideReason;
    }
}
