package com.dtc.transit.scheduling.duty;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * One crew taking a bus over from another.
 *
 * <p>Its own row rather than something inferred from two adjacent duties: a handover is an operational event
 * with a place and a time that someone has to physically be at, and the list a depot works from in the morning
 * is a list of these.
 */
@Entity
@Table(name = "handover")
public class Handover {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "handover_seq")
    @SequenceGenerator(name = "handover_seq", sequenceName = "handover_seq", allocationSize = 50)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    /** Null means the depot. */
    @Column(name = "relief_stop_id")
    private Long reliefStopId;

    @Column(name = "at_sec", nullable = false)
    private int atSec;

    @Column(name = "outgoing_duty_id", nullable = false)
    private Long outgoingDutyId;

    @Column(name = "incoming_duty_id", nullable = false)
    private Long incomingDutyId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Handover() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public Long getBlockId() {
        return blockId;
    }

    public Long getReliefStopId() {
        return reliefStopId;
    }

    public int getAtSec() {
        return atSec;
    }

    public Long getOutgoingDutyId() {
        return outgoingDutyId;
    }

    public Long getIncomingDutyId() {
        return incomingDutyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
