package com.dtc.transit.scheduling.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dtc.transit.scheduling.engine.model.SchedulingMode;

/**
 * One unit of scheduling work.
 *
 * <p>The id is a UUID rather than a sequence value, because it is handed to clients as a job handle and a
 * sequential handle would let one scheduler enumerate another's runs.
 *
 * <p>No optimistic-lock version. Claiming is done with {@code SELECT ... FOR UPDATE SKIP LOCKED}, which is a
 * row lock rather than a compare-and-set: two workers racing for the same run must have one of them see no row
 * at all, not fail on a stale version and retry the same contended row.
 */
@Entity
@Table(name = "schedule_run")
public class ScheduleRun {

    @Id
    private UUID id;

    @Column(name = "depot_id", nullable = false)
    private Long depotId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchedulingMode mode;

    @Column(name = "rule_set_id", nullable = false)
    private Long ruleSetId;

    /** Stored so a run can be reproduced exactly, together with the engine's stable iteration order. */
    @Column(nullable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.QUEUED;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(nullable = false)
    private int progress;

    @JdbcTypeCode(SqlTypes.JSON)
    private String metrics;

    private String error;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "schedule_id")
    private Long scheduleId;

    protected ScheduleRun() {
        // for JPA
    }

    public ScheduleRun(
            UUID id,
            Long depotId,
            LocalDate serviceDate,
            SchedulingMode mode,
            Long ruleSetId,
            long seed,
            String idempotencyKey,
            String createdBy) {
        this.id = id;
        this.depotId = depotId;
        this.serviceDate = serviceDate;
        this.mode = mode;
        this.ruleSetId = ruleSetId;
        this.seed = seed;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public Long getDepotId() {
        return depotId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public SchedulingMode getMode() {
        return mode;
    }

    public Long getRuleSetId() {
        return ruleSetId;
    }

    public long getSeed() {
        return seed;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public int getProgress() {
        return progress;
    }

    public String getMetrics() {
        return metrics;
    }

    public String getError() {
        return error;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getScheduleId() {
        return scheduleId;
    }
}
