package com.dtc.transit.route.overlap;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dtc.transit.route.pattern.RoutePattern;
import com.dtc.transit.route.pattern.RoutePatternRepository;
import com.dtc.transit.route.route.RouteRepository;
import com.dtc.transit.route.route.RouteStatus;

/**
 * Recomputes stored overlap results when an in-service pattern changes.
 *
 * <p>Overlap is a relationship, not a property: changing one active route silently invalidates every
 * other route's analysis against it. Without this, a reviewer could approve a proposal on numbers that
 * stopped being true when a neighbouring route was redrawn (edge case EC-GEO-22 extended to the
 * network).
 *
 * <p>{@code AFTER_COMMIT} is required, not merely preferred. The analysis reads the new geometry through
 * its own query, so running before commit would compare against the old line. {@code @Async} keeps the
 * cost off the request thread, since recomputation touches every proposal near the changed route.
 */
@Component
public class OverlapRecomputeListener {

    private static final Logger log = LoggerFactory.getLogger(OverlapRecomputeListener.class);

    private final RoutePatternRepository patterns;
    private final RouteRepository routes;
    private final RouteOverlapRepository overlaps;
    private final OverlapAnalysisService analysis;

    public OverlapRecomputeListener(
            RoutePatternRepository patterns,
            RouteRepository routes,
            RouteOverlapRepository overlaps,
            OverlapAnalysisService analysis) {
        this.patterns = patterns;
        this.routes = routes;
        this.overlaps = overlaps;
        this.analysis = analysis;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActivePatternChanged(ActivePatternChanged event) {
        List<com.dtc.transit.route.route.Route> underReview = routes.findByStatus(RouteStatus.UNDER_REVIEW);
        if (underReview.isEmpty()) {
            return;
        }

        int recomputed = 0;
        for (var route : underReview) {
            for (RoutePattern pattern : patterns.findByRouteId(route.getId())) {
                overlaps.deleteByProposedPatternId(pattern.getId());
                for (OverlapFinding finding :
                        analysis.analysePattern(pattern, OverlapAnalysisService.Settings.defaults())) {
                    overlaps.save(new RouteOverlap(pattern.getId(), finding, pattern.getVersion()));
                }
                recomputed++;
            }
        }
        log.info(
                "recomputed overlaps for {} pattern(s) after active pattern {} changed",
                recomputed,
                event.patternId());
    }

    /** @param patternId the in-service pattern whose geometry changed */
    public record ActivePatternChanged(Long patternId) {}
}
