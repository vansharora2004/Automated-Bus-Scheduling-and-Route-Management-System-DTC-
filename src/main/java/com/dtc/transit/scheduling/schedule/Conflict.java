package com.dtc.transit.scheduling.schedule;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dtc.transit.scheduling.engine.model.Severity;

/**
 * Something wrong with a schedule version.
 *
 * <p>Stored rather than recomputed on read. A conflict is evidence about one version built from one snapshot;
 * recomputing it against today's master data would answer a different question and quietly change history.
 */
@Entity
@Table(name = "conflict")
public class Conflict {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "conflict_seq")
    @SequenceGenerator(name = "conflict_seq", sequenceName = "conflict_seq", allocationSize = 50)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entity_refs")
    private String entityRefs;

    @Column(nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Conflict() {
        // for JPA
    }

    public void resolve(String actor) {
        this.resolved = true;
        this.resolvedBy = actor;
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public String getType() {
        return type;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getEntityRefs() {
        return entityRefs;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
