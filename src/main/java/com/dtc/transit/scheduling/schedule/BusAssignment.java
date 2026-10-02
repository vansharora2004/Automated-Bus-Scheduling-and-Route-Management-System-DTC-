package com.dtc.transit.scheduling.schedule;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Which bus runs which block, as an absolute time range.
 *
 * <p>Absolute rather than service-day seconds, because the database refuses double-booking with an exclusion
 * constraint over a {@code tstzrange} and that has to work across midnight and across service dates: a block
 * finishing at 01:30 and the next morning's block starting at 04:00 belong to different service dates but the
 * same physical bus.
 *
 * <p>{@code scheduleStatus} is denormalised from the owning schedule on purpose. The exclusion constraint can
 * only see columns of this table, and it has to apply to published rows alone.
 */
@Entity
@Table(name = "bus_assignment")
public class BusAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bus_assignment_seq")
    @SequenceGenerator(name = "bus_assignment_seq", sequenceName = "bus_assignment_seq", allocationSize = 50)
    private Long id;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "bus_id", nullable = false)
    private Long busId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "schedule_status", nullable = false)
    private String scheduleStatus;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BusAssignment() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getBlockId() {
        return blockId;
    }

    public Long getBusId() {
        return busId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
