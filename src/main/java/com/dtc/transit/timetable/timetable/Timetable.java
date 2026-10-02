package com.dtc.transit.timetable.timetable;

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
import com.dtc.transit.route.route.DayType;

/**
 * The trips a route runs on one kind of day, over a validity window.
 *
 * <p>A route has one timetable per day type at a time. Resolving a service date means finding the
 * timetable whose validity covers it, which is why overlapping active windows are refused by the
 * database rather than merely discouraged.
 */
@Entity
@Table(name = "timetable")
public class Timetable extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "timetable_seq")
    @SequenceGenerator(name = "timetable_seq", sequenceName = "timetable_seq", allocationSize = 50)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false)
    private DayType dayType;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Null means open-ended: in force until something replaces it. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimetableStatus status = TimetableStatus.DRAFT;

    protected Timetable() {
        // for JPA
    }

    public Timetable(Long routeId, DayType dayType, LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new BusinessRuleException(
                    "TIMETABLE_VALIDITY_INVERTED", "A timetable cannot stop being valid before it starts");
        }
        this.routeId = routeId;
        this.dayType = dayType;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /** Whether this timetable governs a given service date. */
    public boolean coversDate(LocalDate serviceDate) {
        return !serviceDate.isBefore(validFrom) && (validTo == null || !serviceDate.isAfter(validTo));
    }

    public void activate() {
        this.status = TimetableStatus.ACTIVE;
    }

    public void retire() {
        this.status = TimetableStatus.RETIRED;
    }

    /** Bands and trips may only change while the timetable is a draft. */
    public void requireEditable() {
        if (!status.isEditable()) {
            throw new BusinessRuleException(
                    "TIMETABLE_NOT_EDITABLE",
                    "A timetable in state " + status + " cannot be changed. Create a new version instead.");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRouteId() {
        return routeId;
    }

    public DayType getDayType() {
        return dayType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public TimetableStatus getStatus() {
        return status;
    }
}
