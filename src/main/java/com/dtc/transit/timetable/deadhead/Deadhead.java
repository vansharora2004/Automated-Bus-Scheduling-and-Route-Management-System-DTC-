package com.dtc.transit.timetable.deadhead;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Non-revenue travel time between two points, for one time band.
 *
 * <p>Deadhead is what makes a block feasible or not: a bus can only take the next trip if it can physically
 * reach the departure stop in time. Getting these numbers wrong produces schedules that look correct and
 * cannot be driven.
 */
@Entity
@Table(name = "deadhead")
public class Deadhead {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "deadhead_seq")
    @SequenceGenerator(name = "deadhead_seq", sequenceName = "deadhead_seq", allocationSize = 50)
    private Long id;

    @Column(name = "from_stop_id", nullable = false)
    private Long fromStopId;

    @Column(name = "to_stop_id", nullable = false)
    private Long toStopId;

    /** Start of the time band this value applies to, in service-day seconds. */
    @Column(name = "from_sec_band", nullable = false)
    private int fromSecBand;

    @Column(name = "travel_sec", nullable = false)
    private int travelSec;

    @Column(name = "distance_m")
    private Double distanceM;

    /**
     * True when this was derived rather than measured.
     *
     * <p>Kept explicit so a planner can tell a guess from a surveyed value, and so the scheduler can raise a
     * soft conflict against blocks that depend on one (edge case EC-TT-10).
     */
    @Column(nullable = false)
    private boolean estimated;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Deadhead() {
        // for JPA
    }

    public Deadhead(
            Long fromStopId, Long toStopId, int fromSecBand, int travelSec, Double distanceM, boolean estimated) {
        if (travelSec <= 0) {
            throw new IllegalArgumentException("deadhead travel time must be positive");
        }
        this.fromStopId = fromStopId;
        this.toStopId = toStopId;
        this.fromSecBand = fromSecBand;
        this.travelSec = travelSec;
        this.distanceM = distanceM;
        this.estimated = estimated;
    }

    public Long getId() {
        return id;
    }

    public Long getFromStopId() {
        return fromStopId;
    }

    public Long getToStopId() {
        return toStopId;
    }

    public int getFromSecBand() {
        return fromSecBand;
    }

    public int getTravelSec() {
        return travelSec;
    }

    public Double getDistanceM() {
        return distanceM;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
