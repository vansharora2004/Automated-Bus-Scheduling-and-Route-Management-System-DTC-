package com.dtc.transit.route.overlap;

/**
 * How much duplication a finding represents.
 *
 * <p>Exists so a planner can triage a list rather than read raw ratios. Thresholds are policy, not fact,
 * and live in the analysis service.
 */
public enum OverlapSeverity {
    /** Most of the route runs along an existing corridor. */
    HIGH,
    /** A substantial shared stretch, worth a look. */
    MEDIUM,
    /** Incidental sharing, usually unavoidable on arterial roads. */
    LOW
}
