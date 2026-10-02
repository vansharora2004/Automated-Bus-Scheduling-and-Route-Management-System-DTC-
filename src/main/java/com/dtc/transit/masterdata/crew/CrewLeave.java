package com.dtc.transit.masterdata.crew;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A period of absence.
 *
 * <p>Stored as instants rather than whole days so a half-day absence excludes only the duties it
 * actually overlaps. Rounding leave to a day would needlessly free or block a whole shift
 * (edge case EC-CA-03).
 */
@Entity
@Table(name = "crew_leave")
public class CrewLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crew_leave_seq")
    @SequenceGenerator(name = "crew_leave_seq", sequenceName = "crew_leave_seq", allocationSize = 50)
    private Long id;

    @Column(name = "crew_member_id", nullable = false)
    private Long crewMemberId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false)
    private LeaveType leaveType;

    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CrewLeave() {
        // for JPA
    }

    public CrewLeave(Long crewMemberId, Instant startsAt, Instant endsAt, LeaveType leaveType, String note) {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("leave must end after it starts");
        }
        this.crewMemberId = crewMemberId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.leaveType = leaveType;
        this.note = note;
    }

    /** Half-open comparison, matching the '[)' bounds of the generated range column. */
    public boolean overlaps(Instant from, Instant to) {
        return startsAt.isBefore(to) && endsAt.isAfter(from);
    }

    public Long getId() {
        return id;
    }

    public Long getCrewMemberId() {
        return crewMemberId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
