package com.dtc.transit.masterdata.crew;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Which depot a crew member belonged to, and when.
 *
 * <p>The current depot lives on {@link CrewMember}; this is the record that makes past dates correct.
 * Scheduling or auditing a date before a transfer has to resolve the depot the person actually belonged
 * to then, and weekly hours carry across a transfer rather than resetting (edge case EC-CA-05).
 *
 * @see CrewMember#getDepot()
 */
@Entity
@Table(name = "crew_depot_history")
public class CrewDepotHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crew_depot_history_seq")
    @SequenceGenerator(
            name = "crew_depot_history_seq",
            sequenceName = "crew_depot_history_seq",
            allocationSize = 50)
    private Long id;

    @Column(name = "crew_member_id", nullable = false)
    private Long crewMemberId;

    @Column(name = "depot_id", nullable = false)
    private Long depotId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null while this is the current posting. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CrewDepotHistory() {
        // for JPA
    }

    public CrewDepotHistory(Long crewMemberId, Long depotId, LocalDate effectiveFrom) {
        this.crewMemberId = crewMemberId;
        this.depotId = depotId;
        this.effectiveFrom = effectiveFrom;
    }

    /** Closes this posting the day before the next one begins. */
    public void closeOn(LocalDate lastDay) {
        if (lastDay.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("a posting cannot end before it began");
        }
        this.effectiveTo = lastDay;
    }

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public Long getId() {
        return id;
    }

    public Long getCrewMemberId() {
        return crewMemberId;
    }

    public Long getDepotId() {
        return depotId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
