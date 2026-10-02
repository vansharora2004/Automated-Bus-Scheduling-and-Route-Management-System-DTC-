package com.dtc.transit.scheduling.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.jpa.BaseEntity;

/**
 * One version of a depot-day's schedule.
 *
 * <p>Versions accumulate rather than overwrite. The previous published version is what crews worked to, and a
 * report about last Tuesday has to be answerable against the plan that was actually in force.
 */
@Entity
@Table(name = "schedule")
public class Schedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "schedule_seq")
    @SequenceGenerator(name = "schedule_seq", sequenceName = "schedule_seq", allocationSize = 50)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "depot_id", nullable = false)
    private Long depotId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    /**
     * Set when master data changes under a schedule that was already validated or published.
     *
     * <p>A flag rather than an automatic downgrade: a published schedule must not silently stop being published
     * because a bus broke down, but the depot has to know it needs looking at.
     */
    @Column(name = "needs_revalidation", nullable = false)
    private boolean needsRevalidation;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    protected Schedule() {
        // for JPA
    }

    public Schedule(UUID runId, Long depotId, LocalDate serviceDate, int versionNo) {
        this.runId = runId;
        this.depotId = depotId;
        this.serviceDate = serviceDate;
        this.versionNo = versionNo;
    }

    public void markValidated() {
        status.requireCanMoveTo(ScheduleStatus.VALIDATED);
        status = ScheduleStatus.VALIDATED;
        needsRevalidation = false;
    }

    public void markEdited() {
        if (status == ScheduleStatus.VALIDATED) {
            status = ScheduleStatus.DRAFT;
        }
    }

    public void publish(String actor, Instant at) {
        status.requireCanMoveTo(ScheduleStatus.PUBLISHED);
        status = ScheduleStatus.PUBLISHED;
        publishedBy = actor;
        publishedAt = at;
        needsRevalidation = false;
    }

    public void supersede() {
        status.requireCanMoveTo(ScheduleStatus.SUPERSEDED);
        status = ScheduleStatus.SUPERSEDED;
    }

    public void discard() {
        status.requireCanMoveTo(ScheduleStatus.DISCARDED);
        status = ScheduleStatus.DISCARDED;
    }

    public void flagForRevalidation() {
        needsRevalidation = true;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public Long getDepotId() {
        return depotId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public boolean isNeedsRevalidation() {
        return needsRevalidation;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }
}
