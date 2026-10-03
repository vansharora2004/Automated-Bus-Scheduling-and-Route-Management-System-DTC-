package com.dtc.transit.route.overlap;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.route.pattern.PatternStopRepository;
import com.dtc.transit.route.pattern.RoutePattern;
import com.dtc.transit.route.pattern.RoutePatternRepository;
import com.dtc.transit.route.route.Direction;

/**
 * Detects how much a candidate route duplicates existing ones.
 *
 * <p>The SQL answers "how many metres of this route run inside a corridor around that one". That number
 * alone is not enough to judge duplication, so three signals are added in Java:
 *
 * <ul>
 *   <li><b>Shared stops</b> — two routes along one corridor serving different stops is an express
 *       service, not a duplicate.
 *   <li><b>Direction agreement</b> — the opposite direction on the same road is the normal return leg,
 *       not duplication (edge case EC-GEO-08).
 *   <li><b>Severity</b> — a ratio turned into something a planner can triage.
 * </ul>
 */
@Service
public class OverlapAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OverlapAnalysisService.class);

    /** Corridor half-width. Wide enough to match a road, narrow enough to exclude a parallel street. */
    public static final double DEFAULT_BUFFER_METRES = 25;

    /** Shortest run that counts. Below this, an intersection is a junction crossing, not a corridor. */
    public static final double DEFAULT_MIN_SEGMENT_METRES = 200;

    /** How close two stops must be to count as the same stop. */
    public static final double SHARED_STOP_TOLERANCE_METRES = 30;

    public static final double HIGH_SEVERITY_RATIO = 0.60;
    public static final double MEDIUM_SEVERITY_RATIO = 0.30;

    private final RoutePatternRepository patterns;
    private final PatternStopRepository patternStops;

    private final com.dtc.transit.common.config.SchedulingMetrics metrics;

    public OverlapAnalysisService(
            RoutePatternRepository patterns, PatternStopRepository patternStops,
            com.dtc.transit.common.config.SchedulingMetrics metrics) {
        this.metrics = metrics;
        this.patterns = patterns;
        this.patternStops = patternStops;
    }

    /**
     * Analyses a geometry that may not be stored yet.
     *
     * <p>Ad-hoc analysis matters because a planner needs the answer while still drawing, not after
     * committing a route they may have to withdraw.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','PLANNER')")
    @Transactional(readOnly = true)
    /**
     * The timed entry point.
     *
     * <p>Overlap analysis is the heaviest spatial query here and the one a planner waits on while drawing, so it
     * is the one worth a timer of its own rather than being lost in the aggregate HTTP histogram.
     */
    public List<OverlapFinding> analyse(LineString geometry, Settings settings, List<Long> candidateStopIds) {
        // Timed here rather than left to the aggregate HTTP histogram: this is the heaviest spatial query in the
        // system and the one a planner waits on while drawing, so it deserves a series of its own.
        return metrics.overlapTimer().record(() -> analyseTimed(geometry, settings, candidateStopIds));
    }

    private List<OverlapFinding> analyseTimed(
            LineString geometry, Settings settings, List<Long> candidateStopIds) {
        return analyseInternal(geometry, settings, candidateStopIds, null, null);
    }

    /**
     * Analyses a stored pattern against every other active route.
     *
     * <p>The pattern's own route is excluded, not just the pattern. Without that, the UP direction would
     * report its own DOWN direction as a near-total duplicate, which is true and useless
     * (edge case EC-GEO-18).
     */
    // No @PreAuthorize here on purpose. Every caller authorizes first, and this also runs from the
    // async recomputation listener, where there is no security context to check against.
    @Transactional(readOnly = true)
    public List<OverlapFinding> analysePattern(RoutePattern pattern, Settings settings) {
        List<Long> stopIds = patternStops.findStopIdsOrdered(pattern.getId());
        return analyseInternal(
                pattern.getGeom(),
                settings,
                stopIds,
                pattern.getId(),
                pattern.getRoute().getId());
    }

    private List<OverlapFinding> analyseInternal(
            LineString geometry,
            Settings settings,
            List<Long> candidateStopIds,
            Long excludePatternId,
            Long excludeRouteId) {

        String wkt = geometry.toText();
        var rows = patterns.findOverlaps(
                wkt, settings.bufferMetres(), settings.minSegmentMetres(), excludePatternId, excludeRouteId);

        Long[] stopIds = candidateStopIds.toArray(Long[]::new);
        List<OverlapFinding> findings = new ArrayList<>(rows.size());

        for (var row : rows) {
            // A ratio can exceed 1 only through a geometry defect; clamping keeps a nonsense number from
            // reaching a planner as if it meant something.
            double ratio = Math.min(row.getOverlapRatio(), 1.0);

            boolean sameDirection = patterns.sharesDirectionWith(row.getPatternId(), wkt)
                    .orElse(true);

            long sharedStops = stopIds.length == 0
                    ? 0
                    : patterns.countSharedStops(row.getPatternId(), stopIds, SHARED_STOP_TOLERANCE_METRES);

            findings.add(new OverlapFinding(
                    row.getPatternId(),
                    row.getRouteId(),
                    row.getRouteNo(),
                    row.getRouteName(),
                    Direction.valueOf(row.getDirection()),
                    row.getOverlapM(),
                    ratio,
                    (int) sharedStops,
                    sameDirection,
                    severityOf(ratio)));
        }

        log.debug("overlap analysis found {} overlapping pattern(s)", findings.size());
        return findings;
    }

    /** Thresholds are configurable because what counts as duplication is a policy choice, not a fact. */
    static OverlapSeverity severityOf(double ratio) {
        if (ratio >= HIGH_SEVERITY_RATIO) {
            return OverlapSeverity.HIGH;
        }
        if (ratio >= MEDIUM_SEVERITY_RATIO) {
            return OverlapSeverity.MEDIUM;
        }
        return OverlapSeverity.LOW;
    }

    /**
     * Tunable analysis parameters.
     *
     * <p>A planner investigating a borderline case needs to vary the buffer: a 25 m corridor matches one
     * road, while 60 m starts catching the service lane beside it (edge case EC-GEO-06).
     */
    public record Settings(double bufferMetres, double minSegmentMetres) {

        public static Settings defaults() {
            return new Settings(DEFAULT_BUFFER_METRES, DEFAULT_MIN_SEGMENT_METRES);
        }

        public Settings {
            if (bufferMetres <= 0 || bufferMetres > 500) {
                throw new IllegalArgumentException("bufferM must be between 1 and 500 metres");
            }
            if (minSegmentMetres < 0 || minSegmentMetres > 10_000) {
                throw new IllegalArgumentException("minSegmentM must be between 0 and 10000 metres");
            }
        }
    }
}
