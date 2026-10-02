package com.dtc.transit.route.overlap;

import com.dtc.transit.route.route.Direction;

/**
 * One overlapping pattern.
 *
 * @param overlapMetres   shared length, measured in the projected CRS
 * @param overlapRatio    shared length as a fraction of the candidate's own length, capped at 1
 * @param sharedStops     stops the two patterns have in common within tolerance
 * @param sameDirection   false for the opposite direction on the same corridor, which is normal
 * @param severity        the ratio turned into something triageable
 */
public record OverlapFinding(
        Long patternId,
        Long routeId,
        String routeNo,
        String routeName,
        Direction direction,
        double overlapMetres,
        double overlapRatio,
        int sharedStops,
        boolean sameDirection,
        OverlapSeverity severity) {

    /**
     * Whether this looks like genuine duplication rather than shared road.
     *
     * <p>Requires all three signals to agree. Geometry alone is not enough: an express service running
     * the same arterial road while serving different stops is not a duplicate, and the opposite direction
     * is the return leg.
     */
    public boolean looksLikeDuplication() {
        return sameDirection && severity != OverlapSeverity.LOW && sharedStops > 0;
    }
}
