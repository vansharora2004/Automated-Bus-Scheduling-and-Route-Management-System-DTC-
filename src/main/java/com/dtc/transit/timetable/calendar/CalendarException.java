package com.dtc.transit.timetable.calendar;

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

import com.dtc.transit.route.route.DayType;

/**
 * Overrides the day type for one date.
 *
 * <p>Holidays cannot be derived from a date, so they only ever arrive here. A festival running a Sunday
 * timetable on a Wednesday is a calendar fact, not a calculation (edge case EC-TIME-05).
 *
 * <p>A null depot means the whole network. A depot-specific row beats a network-wide one for that depot,
 * which is what lets one depot observe a local event without affecting the rest of the city.
 */
@Entity
@Table(name = "calendar_exception")
public class CalendarException {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "calendar_exception_seq")
    @SequenceGenerator(
            name = "calendar_exception_seq",
            sequenceName = "calendar_exception_seq",
            allocationSize = 50)
    private Long id;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "depot_id")
    private Long depotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type_override", nullable = false)
    private DayType dayTypeOverride;

    private String note;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CalendarException() {
        // for JPA
    }

    public CalendarException(LocalDate serviceDate, Long depotId, DayType dayTypeOverride, String note) {
        this.serviceDate = serviceDate;
        this.depotId = depotId;
        this.dayTypeOverride = dayTypeOverride;
        this.note = note;
    }

    /** True when this applies to the whole network rather than one depot. */
    public boolean isNetworkWide() {
        return depotId == null;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public Long getDepotId() {
        return depotId;
    }

    public DayType getDayTypeOverride() {
        return dayTypeOverride;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
