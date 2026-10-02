package com.dtc.transit.route.route;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.common.filtering.Filters;
import com.dtc.transit.common.paging.SortWhitelist;
import com.dtc.transit.masterdata.depot.Depot;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.masterdata.stop.StopRepository;
import com.dtc.transit.route.overlap.OverlapAnalysisService;
import com.dtc.transit.route.overlap.OverlapFinding;
import com.dtc.transit.route.overlap.OverlapRecomputeListener;
import com.dtc.transit.route.overlap.RouteOverlap;
import com.dtc.transit.route.overlap.RouteOverlapRepository;
import com.dtc.transit.route.pattern.GeometryValidator;
import com.dtc.transit.route.pattern.PatternStop;
import com.dtc.transit.route.pattern.PatternStopRepository;
import com.dtc.transit.route.pattern.RoutePattern;
import com.dtc.transit.route.pattern.RoutePatternRepository;
import com.dtc.transit.route.pattern.RunningTimeBand;
import com.dtc.transit.route.pattern.RunningTimeBandRepository;
import com.dtc.transit.security.DepotAccessEvaluator;
import com.dtc.transit.security.DepotScope;

/** Routes, their directional patterns, and the proposal workflow. */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    public static final Set<String> SORTABLE = Set.of("routeNo", "name", "status", "effectiveFrom", "depot.code");

    /** How far a stop may sit from the drawn line before it is flagged. */
    public static final double STOP_OFF_LINE_WARNING_METRES = 50;

    private final RouteRepository routes;
    private final RoutePatternRepository patterns;
    private final PatternStopRepository patternStops;
    private final RunningTimeBandRepository runningTimes;
    private final RouteOverlapRepository overlaps;
    private final DepotRepository depots;
    private final StopRepository stops;
    private final GeometryValidator geometryValidator;
    private final OverlapAnalysisService overlapAnalysis;
    private final SortWhitelist sortWhitelist;
    private final DepotScope depotScope;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;

    public RouteService(
            RouteRepository routes,
            RoutePatternRepository patterns,
            PatternStopRepository patternStops,
            RunningTimeBandRepository runningTimes,
            RouteOverlapRepository overlaps,
            DepotRepository depots,
            StopRepository stops,
            GeometryValidator geometryValidator,
            OverlapAnalysisService overlapAnalysis,
            SortWhitelist sortWhitelist,
            DepotScope depotScope,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events) {
        this.routes = routes;
        this.patterns = patterns;
        this.patternStops = patternStops;
        this.runningTimes = runningTimes;
        this.overlaps = overlaps;
        this.depots = depots;
        this.stops = stops;
        this.geometryValidator = geometryValidator;
        this.overlapAnalysis = overlapAnalysis;
        this.sortWhitelist = sortWhitelist;
        this.depotScope = depotScope;
        this.depotAccess = depotAccess;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<Route> search(String routeNo, Long depotId, RouteStatus status, Pageable pageable) {
        Specification<Route> spec = Specification.allOf(
                Filters.contains("routeNo", routeNo),
                Filters.eq("depot.id", depotId),
                Filters.eq("status", status),
                depotScope.restrict("depot.id"));
        return routes.findAll(spec, sortWhitelist.apply(pageable, SORTABLE));
    }

    @Transactional(readOnly = true)
    public Route get(Long id) {
        Route route = routes.findById(id).orElseThrow(() -> NotFoundException.of("Route", id));
        depotAccess.requireAccess(route.getDepot().getId(), "Route", id);
        return route;
    }

    @Transactional(readOnly = true)
    public List<RoutePattern> patternsOf(Long routeId) {
        get(routeId);
        return patterns.findByRouteId(routeId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Route create(String routeNo, String name, Long depotId) {
        if (routes.existsLiveRouteNo(routeNo)) {
            throw new ConflictException(
                    "ROUTE_NO_TAKEN", "Route number '" + routeNo + "' is already in use by a live route");
        }
        Depot depot = depots.findById(depotId).orElseThrow(() -> NotFoundException.of("Depot", depotId));
        Route route = routes.save(new Route(routeNo, name, depot));
        events.publishEvent(AuditEvent.created("ROUTE", route.getId(), describe(route)));
        return route;
    }

    /**
     * Replaces a direction's geometry and stop sequence.
     *
     * <p>Only while the route is a draft. Once it is under review a reviewer is looking at specific
     * geometry, and letting it change underneath them would make the decision meaningless.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public PatternResult replacePattern(
            Long routeId, Direction direction, LineString submitted, List<Long> stopIds, boolean simplify) {
        Route route = routes.findById(routeId).orElseThrow(() -> NotFoundException.of("Route", routeId));
        route.requireEditable();

        var validated = geometryValidator.validate(submitted, simplify);
        List<String> warnings = new ArrayList<>(validated.warnings());

        RoutePattern pattern = patterns.findByRouteIdAndDirection(routeId, direction)
                .map(existing -> {
                    existing.replaceGeometry(validated.geometry());
                    return existing;
                })
                .orElseGet(() -> new RoutePattern(route, direction, validated.geometry()));
        patterns.saveAndFlush(pattern);

        // Overlap results were computed against the previous geometry, so they no longer describe this
        // pattern. Deleting beats keeping a stale row that looks authoritative (edge case EC-GEO-22).
        overlaps.deleteByProposedPatternId(pattern.getId());

        warnings.addAll(replaceStops(pattern, stopIds));

        events.publishEvent(AuditEvent.updated(
                "ROUTE_PATTERN", pattern.getId(), null, describePattern(pattern, stopIds.size())));

        if (route.getStatus() == RouteStatus.ACTIVE) {
            // Overlap is a relationship: redrawing a route in service invalidates every proposal's
            // analysis against it. Recomputed asynchronously after commit, so the new geometry is
            // visible and the request does not wait for it.
            events.publishEvent(new OverlapRecomputeListener.ActivePatternChanged(pattern.getId()));
        }
        return new PatternResult(pattern, warnings);
    }

    /**
     * Stores the stop sequence, measuring each stop's position along the line.
     *
     * <p>Two checks that matter more than they look. A stop far from the line usually means the wrong
     * stop was picked (EC-GEO-13). Distances that do not increase with the sequence mean the stop order
     * disagrees with the drawn path, which would make every downstream running time wrong (EC-GEO-14).
     */
    private List<String> replaceStops(RoutePattern pattern, List<Long> stopIds) {
        List<String> warnings = new ArrayList<>();
        patternStops.deleteByPatternId(pattern.getId());

        Double previousDistance = null;
        int seq = 1;
        for (Long stopId : stopIds) {
            if (!stops.existsById(stopId)) {
                throw new NotFoundException("Stop " + stopId + " was not found");
            }
            double offLine = patterns.distanceFromLine(pattern.getId(), stopId).orElse(0.0);
            if (offLine > STOP_OFF_LINE_WARNING_METRES) {
                warnings.add("Stop %d is %.0f m from the route line, which suggests the wrong stop"
                        .formatted(stopId, offLine));
            }

            Double distance = patterns.distanceAlongPattern(pattern.getId(), stopId).orElse(null);
            if (distance != null && previousDistance != null && distance < previousDistance) {
                boolean loop = pattern.getDirection().isLoop();
                if (!loop) {
                    throw new BusinessRuleException(
                            "STOP_SEQUENCE_OUT_OF_ORDER",
                            ("Stop %d at position %d sits %.0f m along the line, before the previous stop at"
                                            + " %.0f m. The stop order does not match the drawn path.")
                                    .formatted(stopId, seq, distance, previousDistance));
                }
                // A loop legitimately wraps past its own start once.
                warnings.add("Stop order wraps at position " + seq + ", which is expected on a loop");
            }
            patternStops.save(new PatternStop(pattern.getId(), seq, stopId, distance));
            previousDistance = distance;
            seq++;
        }
        return warnings;
    }

    /**
     * Submits a proposal for review, attaching the overlap analysis.
     *
     * <p>The analysis is stored with the pattern version it was computed against, so a later edit makes it
     * detectably stale instead of silently wrong.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Route submitForReview(Long routeId) {
        Route route = routes.findById(routeId).orElseThrow(() -> NotFoundException.of("Route", routeId));
        List<RoutePattern> routePatterns = patterns.findByRouteId(routeId);
        if (routePatterns.isEmpty()) {
            throw new BusinessRuleException(
                    "ROUTE_HAS_NO_GEOMETRY", "A route cannot be submitted before at least one pattern is drawn");
        }

        for (RoutePattern pattern : routePatterns) {
            overlaps.deleteByProposedPatternId(pattern.getId());
            List<OverlapFinding> findings =
                    overlapAnalysis.analysePattern(pattern, OverlapAnalysisService.Settings.defaults());
            for (OverlapFinding finding : findings) {
                overlaps.save(new RouteOverlap(pattern.getId(), finding, pattern.getVersion()));
            }
            log.debug("attached {} overlap finding(s) to pattern {}", findings.size(), pattern.getId());
        }

        route.transitionTo(RouteStatus.UNDER_REVIEW, currentActor(), null);
        routes.save(route);
        events.publishEvent(AuditEvent.of("ROUTE_SUBMITTED", "ROUTE", routeId));
        return route;
    }

    /**
     * Approves or rejects a proposal.
     *
     * <p>Separation of duties: the decision must come from someone other than the submitter, so a planner
     * who also holds MANAGER cannot wave their own route through (edge case EC-SEC-15).
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Route decide(Long routeId, boolean approve, String note) {
        Route route = routes.findById(routeId).orElseThrow(() -> NotFoundException.of("Route", routeId));
        String actor = currentActor();

        if (route.getSubmittedBy() != null && route.getSubmittedBy().equals(actor)) {
            throw new BusinessRuleException(
                    "SELF_APPROVAL_FORBIDDEN",
                    "A route must be decided by someone other than the person who submitted it");
        }
        if (!approve && (note == null || note.isBlank())) {
            // A rejection without a reason cannot be acted on, so the route would simply stall.
            throw new BusinessRuleException("DECISION_NOTE_REQUIRED", "A rejection must include a reason");
        }

        route.transitionTo(approve ? RouteStatus.APPROVED : RouteStatus.REJECTED, actor, note);
        routes.save(route);
        events.publishEvent(new AuditEvent(
                approve ? "ROUTE_APPROVED" : "ROUTE_REJECTED",
                "ROUTE",
                String.valueOf(routeId),
                null,
                describe(route),
                note));
        return route;
    }

    /** Brings an approved route into service, from which point it counts in overlap analysis. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Route activate(Long routeId, LocalDate effectiveFrom) {
        Route route = routes.findById(routeId).orElseThrow(() -> NotFoundException.of("Route", routeId));
        route.setEffectiveFrom(effectiveFrom);
        route.transitionTo(RouteStatus.ACTIVE, currentActor(), null);
        routes.save(route);
        events.publishEvent(AuditEvent.of("ROUTE_ACTIVATED", "ROUTE", routeId));
        return route;
    }

    /** Withdraws a route, freeing its number for reuse. */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Route retire(Long routeId) {
        Route route = routes.findById(routeId).orElseThrow(() -> NotFoundException.of("Route", routeId));
        route.transitionTo(RouteStatus.RETIRED, currentActor(), null);
        routes.save(route);
        events.publishEvent(AuditEvent.of("ROUTE_RETIRED", "ROUTE", routeId));
        return route;
    }

    /** Stored overlap findings for a route, with staleness marked against the current pattern version. */
    /**
     * Replaces the running times for one direction and day type.
     *
     * <p>Running times belong to the pattern rather than the timetable. The same stretch of road takes the
     * same time to drive whichever timetable is in force, and duplicating the numbers per timetable would let
     * two timetables of the same route disagree about how long the route takes.
     *
     * <p>Replace rather than merge, for the same reason as the stop sequence: a half-updated set of bands
     * would leave gaps at the boundaries where the old and new bands do not line up.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public List<RunningTimeBand> replaceRunningTimes(
            Long routeId, Direction direction, DayType dayType, List<RunningTimeSpec> specs) {
        Route route = get(routeId);
        if (!route.getStatus().isEditable()) {
            throw new BusinessRuleException(
                    "ROUTE_NOT_EDITABLE",
                    "Running times cannot be changed while the route is " + route.getStatus());
        }
        RoutePattern pattern = patterns.findByRouteIdAndDirection(routeId, direction)
                .orElseThrow(() -> new BusinessRuleException(
                        "PATTERN_MISSING",
                        "Route " + routeId + " has no " + direction + " pattern to attach running times to"));

        List<RunningTimeBand> incoming = specs.stream()
                .map(spec -> new RunningTimeBand(
                        pattern.getId(), dayType, spec.fromSec(), spec.toSec(), spec.runningSec()))
                .sorted(java.util.Comparator.comparingInt(RunningTimeBand::getFromSec))
                .toList();

        for (int i = 1; i < incoming.size(); i++) {
            if (incoming.get(i - 1).getToSec() > incoming.get(i).getFromSec()) {
                throw new BusinessRuleException(
                        "RUNNING_TIME_BANDS_OVERLAP",
                        "Running time bands starting at %d and %d overlap"
                                .formatted(incoming.get(i - 1).getFromSec(), incoming.get(i).getFromSec()));
            }
        }

        runningTimes.findByPatternAndDayType(pattern.getId(), dayType).forEach(runningTimes::delete);
        runningTimes.flush();
        List<RunningTimeBand> saved = runningTimes.saveAll(incoming);

        events.publishEvent(AuditEvent.updated(
                "ROUTE",
                routeId,
                null,
                """
                {"direction":"%s","dayType":"%s","runningTimeBands":%d}"""
                        .formatted(direction, dayType, saved.size())));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RunningTimeBand> runningTimesOf(Long routeId, Direction direction) {
        get(routeId);
        RoutePattern pattern = patterns.findByRouteIdAndDirection(routeId, direction)
                .orElseThrow(() -> NotFoundException.of("Pattern", direction));
        return runningTimes.findByPattern(pattern.getId());
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','PLANNER')")
    @Transactional(readOnly = true)
    public Page<RouteOverlap> storedOverlaps(Long routeId, Pageable pageable) {
        get(routeId);
        List<Long> patternIds =
                patterns.findByRouteId(routeId).stream().map(RoutePattern::getId).toList();
        if (patternIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return overlaps.findByPatternIds(patternIds, pageable);
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    private static String describe(Route route) {
        return """
                {"routeNo":"%s","name":"%s","status":"%s","depotId":%d}"""
                .formatted(
                        route.getRouteNo(), route.getName(), route.getStatus(), route.getDepot().getId());
    }

    private static String describePattern(RoutePattern pattern, int stopCount) {
        return """
                {"direction":"%s","lengthM":%s,"stops":%d}"""
                .formatted(pattern.getDirection(), pattern.getLengthM(), stopCount);
    }

    /** @param warnings non-fatal observations a planner should see, such as a stop far off the line */
    public record PatternResult(RoutePattern pattern, List<String> warnings) {}

    /** @param fromSec and toSec are service-day seconds; the window is half-open */
    public record RunningTimeSpec(int fromSec, int toSec, int runningSec) {}
}
