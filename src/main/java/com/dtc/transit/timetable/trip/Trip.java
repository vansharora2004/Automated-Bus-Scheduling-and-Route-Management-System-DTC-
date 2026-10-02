package com.dtc.transit.timetable.trip;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.time.ServiceTime;

/**
 * One revenue run of a pattern at a scheduled time.
 *
 * <p>Times are service-day seconds, which may exceed 86,400. A trip departing at 00:50 is stored as
 * 89,400 and belongs to the previous service date, which is what keeps a late-night service on the day it
 * started rather than splitting it across two (edge case EC-TIME-01).
 */
@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "trip_seq")
    @SequenceGenerator(name = "trip_seq", sequenceName = "trip_seq", allocationSize = 50)
    private Long id;

    @Column(name = "timetable_id", nullable = false)
    private Long timetableId;

    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    @Column(name = "start_stop_id")
    private Long startStopId;

    @Column(name = "end_stop_id")
    private Long endStopId;

    @Column(name = "start_sec", nullable = false)
    private int startSec;

    @Column(name = "end_sec", nullable = false)
    private int endSec;

    @Column(name = "distance_m")
    private Double distanceM;

    @Column(name = "required_vehicle_class")
    private String requiredVehicleClass;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Trip() {
        // for JPA
    }

    public Trip(
            Long timetableId,
            Long patternId,
            Long startStopId,
            Long endStopId,
            int startSec,
            int endSec,
            Double distanceM,
            String requiredVehicleClass) {
        if (endSec <= startSec) {
            throw new IllegalArgumentException("a trip must end after it starts");
        }
        this.timetableId = timetableId;
        this.patternId = patternId;
        this.startStopId = startStopId;
        this.endStopId = endStopId;
        this.startSec = startSec;
        this.endSec = endSec;
        this.distanceM = distanceM;
        this.requiredVehicleClass = requiredVehicleClass;
    }

    public int runningSeconds() {
        return endSec - startSec;
    }

    /** Average speed in km/h, used to flag implausible running times. */
    public Double averageSpeedKmh() {
        if (distanceM == null || runningSeconds() <= 0) {
            return null;
        }
        return (distanceM / 1000.0) / (runningSeconds() / 3600.0);
    }

    /** True when the trip finishes after midnight of its service date. */
    public boolean endsAfterMidnight() {
        return ServiceTime.isAfterMidnight(endSec);
    }

    public Long getId() {
        return id;
    }

    public Long getTimetableId() {
        return timetableId;
    }

    public Long getPatternId() {
        return patternId;
    }

    public Long getStartStopId() {
        return startStopId;
    }

    public Long getEndStopId() {
        return endStopId;
    }

    public int getStartSec() {
        return startSec;
    }

    public int getEndSec() {
        return endSec;
    }

    public Double getDistanceM() {
        return distanceM;
    }

    public String getRequiredVehicleClass() {
        return requiredVehicleClass;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
