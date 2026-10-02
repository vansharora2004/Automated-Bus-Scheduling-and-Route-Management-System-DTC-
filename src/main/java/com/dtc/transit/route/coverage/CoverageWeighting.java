package com.dtc.transit.route.coverage;

/**
 * How a coverage figure was weighted.
 *
 * <p>Reported alongside every result, because "60% covered" means something different by area than by
 * population, and a reader who cannot tell which may act on the wrong one (edge case EC-GEO-19).
 */
public enum CoverageWeighting {
    /** Weighted by resident population. Preferred where figures exist. */
    POPULATION,
    /** Weighted by land area, used when no population figure is available. */
    AREA
}
