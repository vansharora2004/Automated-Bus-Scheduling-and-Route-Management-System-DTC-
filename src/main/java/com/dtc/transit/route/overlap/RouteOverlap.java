package com.dtc.transit.route.overlap;

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

/**
 * A stored overlap result, attached to a proposal when it is submitted.
 *
 * <p>Cached rather than recomputed on every read, because a reviewer should see exactly the numbers the
 * planner submitted. {@code proposedVersion} records which version of the pattern was analysed, so an
 * edit afterwards makes the result detectably stale rather than quietly misleading
 * (edge case EC-GEO-22).
 */
@Entity
@Table(name = "route_overlap")
public class RouteOverlap {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_overlap_seq")
    @SequenceGenerator(name = "route_overlap_seq", sequenceName = "route_overlap_seq", allocationSize = 50)
    private Long id;

    @Column(name = "proposed_pattern_id", nullable = false)
    private Long proposedPatternId;

    @Column(name = "existing_pattern_id", nullable = false)
    private Long existingPatternId;

    @Column(name = "overlap_m", nullable = false)
    private double overlapMetres;

    @Column(name = "overlap_ratio", nullable = false)
    private double overlapRatio;

    @Column(name = "shared_stops", nullable = false)
    private int sharedStops;

    @Column(name = "same_direction", nullable = false)
    private boolean sameDirection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverlapSeverity severity;

    @Column(name = "proposed_version", nullable = false)
    private long proposedVersion;

    @Column(name = "computed_at", nullable = false, insertable = false, updatable = false)
    private Instant computedAt;

    protected RouteOverlap() {
        // for JPA
    }

    public RouteOverlap(Long proposedPatternId, OverlapFinding finding, long proposedVersion) {
        this.proposedPatternId = proposedPatternId;
        this.existingPatternId = finding.patternId();
        this.overlapMetres = finding.overlapMetres();
        this.overlapRatio = finding.overlapRatio();
        this.sharedStops = finding.sharedStops();
        this.sameDirection = finding.sameDirection();
        this.severity = finding.severity();
        this.proposedVersion = proposedVersion;
    }

    /** True when the pattern has changed since this result was computed. */
    public boolean isStaleFor(long currentPatternVersion) {
        return proposedVersion != currentPatternVersion;
    }

    public Long getId() {
        return id;
    }

    public Long getProposedPatternId() {
        return proposedPatternId;
    }

    public Long getExistingPatternId() {
        return existingPatternId;
    }

    public double getOverlapMetres() {
        return overlapMetres;
    }

    public double getOverlapRatio() {
        return overlapRatio;
    }

    public int getSharedStops() {
        return sharedStops;
    }

    public boolean isSameDirection() {
        return sameDirection;
    }

    public OverlapSeverity getSeverity() {
        return severity;
    }

    public long getProposedVersion() {
        return proposedVersion;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
