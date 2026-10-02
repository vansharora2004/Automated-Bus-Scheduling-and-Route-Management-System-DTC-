package com.dtc.transit.timetable.timetable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.route.pattern.PatternStopRepository;
import com.dtc.transit.route.pattern.RoutePattern;
import com.dtc.transit.route.pattern.RoutePatternRepository;
import com.dtc.transit.route.pattern.RunningTimeBandRepository;
import com.dtc.transit.route.route.DayType;
import com.dtc.transit.route.route.Direction;
import com.dtc.transit.route.route.RouteRepository;
import com.dtc.transit.timetable.calendar.ServiceCalendar;
import com.dtc.transit.timetable.headway.HeadwayBand;
import com.dtc.transit.timetable.headway.HeadwayBandRepository;
import com.dtc.transit.timetable.trip.Trip;
import com.dtc.transit.timetable.trip.TripGenerator;
import com.dtc.transit.timetable.trip.TripRepository;

/** Timetables, their headway bands, and trip generation. */
@Service
public class TimetableService {

    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    private final TimetableRepository timetables;
    private final HeadwayBandRepository headwayBands;
    private final RunningTimeBandRepository runningTimeBands;
    private final TripRepository trips;
    private final RoutePatternRepository patterns;
    private final PatternStopRepository patternStops;
    private final RouteRepository routes;
    private final TripGenerator tripGenerator;
    private final ServiceCalendar calendar;
    private final ApplicationEventPublisher events;

    public TimetableService(
            TimetableRepository timetables,
            HeadwayBandRepository headwayBands,
            RunningTimeBandRepository runningTimeBands,
            TripRepository trips,
            RoutePatternRepository patterns,
            PatternStopRepository patternStops,
            RouteRepository routes,
            TripGenerator tripGenerator,
            ServiceCalendar calendar,
            ApplicationEventPublisher events) {
        this.timetables = timetables;
        this.headwayBands = headwayBands;
        this.runningTimeBands = runningTimeBands;
        this.trips = trips;
        this.patterns = patterns;
        this.patternStops = patternStops;
        this.routes = routes;
        this.tripGenerator = tripGenerator;
        this.calendar = calendar;
        this.events = events;
    }

    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Timetable create(Long routeId, DayType dayType, LocalDate validFrom, LocalDate validTo) {
        if (!routes.existsById(routeId)) {
            throw NotFoundException.of("Route", routeId);
        }
        Timetable timetable = timetables.save(new Timetable(routeId, dayType, validFrom, validTo));
        events.publishEvent(AuditEvent.created("TIMETABLE", timetable.getId(), describe(timetable)));
        return timetable;
    }

    /**
     * Replaces the headway bands for a direction.
     *
     * <p>Overlap is rejected here as well as by a database exclusion constraint. The constraint is the
     * guarantee; this check is what turns it into a message naming the two offending bands instead of a
     * constraint-violation 409.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public List<HeadwayBand> replaceBands(Long timetableId, Direction direction, List<BandSpec> specs) {
        Timetable timetable = require(timetableId);
        timetable.requireEditable();

        List<HeadwayBand> incoming = specs.stream()
                .map(spec -> new HeadwayBand(
                        timetableId, direction, spec.fromSec(), spec.toSec(), spec.headwaySec()))
                .sorted(java.util.Comparator.comparingInt(HeadwayBand::getFromSec))
                .toList();

        for (int i = 1; i < incoming.size(); i++) {
            if (incoming.get(i - 1).getToSec() > incoming.get(i).getFromSec()) {
                throw new BusinessRuleException(
                        "HEADWAY_BANDS_OVERLAP",
                        "Bands starting at %d and %d overlap for direction %s"
                                .formatted(
                                        incoming.get(i - 1).getFromSec(),
                                        incoming.get(i).getFromSec(),
                                        direction));
            }
        }

        headwayBands.findOrderedByTimetableAndDirection(timetableId, direction).forEach(headwayBands::delete);
        headwayBands.flush();
        List<HeadwayBand> saved = headwayBands.saveAll(incoming);

        events.publishEvent(AuditEvent.updated(
                "TIMETABLE",
                timetableId,
                null,
                """
                {"direction":"%s","bands":%d}""".formatted(direction, saved.size())));
        return saved;
    }

    /**
     * Generates trips from the bands, replacing whatever was there.
     *
     * <p>Regeneration is a replace rather than an append. Appending would leave the previous run's departures
     * behind, and the unique constraint on departure time would then reject the rerun, so a planner could
     * never correct a mistake.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public GenerationResult generateTrips(Long timetableId, boolean strict) {
        Timetable timetable = require(timetableId);
        timetable.requireEditable();

        List<RoutePattern> routePatterns = patterns.findByRouteId(timetable.getRouteId());
        if (routePatterns.isEmpty()) {
            throw new BusinessRuleException(
                    "ROUTE_HAS_NO_GEOMETRY",
                    "Route " + timetable.getRouteId() + " has no patterns, so there is nothing to generate along");
        }

        trips.deleteByTimetableId(timetableId);
        trips.flush();

        List<String> warnings = new ArrayList<>();
        List<Trip> generated = new ArrayList<>();
        int analyticalTotal = 0;

        for (RoutePattern pattern : routePatterns) {
            List<HeadwayBand> bands =
                    headwayBands.findOrderedByTimetableAndDirection(timetableId, pattern.getDirection());
            if (bands.isEmpty()) {
                // A direction with no bands simply does not run, which is normal for a one-way loop.
                continue;
            }
            analyticalTotal += TripGenerator.analyticalTripCount(bands);

            var request = new TripGenerator.Request(
                    pattern.getId(),
                    pattern.getDirection(),
                    timetable.getDayType(),
                    bands,
                    runningTimeBands.findByPatternAndDayType(pattern.getId(), timetable.getDayType()),
                    pattern.getLengthM(),
                    null,
                    strict);

            var result = tripGenerator.generate(request);
            warnings.addAll(result.warnings());

            List<Long> stopIds = patternStops.findStopIdsOrdered(pattern.getId());
            for (var draft : result.trips()) {
                generated.add(new Trip(
                        timetableId,
                        draft.patternId(),
                        stopIds.isEmpty() ? null : stopIds.get(0),
                        stopIds.isEmpty() ? null : stopIds.get(stopIds.size() - 1),
                        draft.startSec(),
                        draft.endSec(),
                        draft.distanceMetres(),
                        draft.requiredVehicleClass()));
            }
        }

        trips.saveAll(generated);
        log.info("generated {} trips for timetable {}", generated.size(), timetableId);

        events.publishEvent(AuditEvent.updated(
                "TIMETABLE",
                timetableId,
                null,
                """
                {"generatedTrips":%d}""".formatted(generated.size())));

        // Published schedules built on the previous trips no longer describe reality. Phase 6 onward
        // consumes this to flag them for revalidation; nothing listens yet, which is why it is an event
        // rather than a direct call into a table that does not exist.
        events.publishEvent(new TimetableChanged(timetableId, timetable.getRouteId()));

        return new GenerationResult(generated.size(), analyticalTotal, warnings);
    }

    /**
     * Brings a timetable into force.
     *
     * <p>The database refuses a second active timetable overlapping the same route, day type and dates, so an
     * ambiguous validity window cannot be created even by a caller that skips this method.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Timetable activate(Long timetableId) {
        Timetable timetable = require(timetableId);
        if (trips.countByTimetableId(timetableId) == 0) {
            throw new BusinessRuleException(
                    "TIMETABLE_HAS_NO_TRIPS", "A timetable cannot be activated before its trips are generated");
        }
        if (timetables.activeValidityOverlaps(
                timetable.getRouteId(),
                timetable.getDayType().name(),
                timetableId,
                timetable.getValidFrom(),
                timetable.getValidTo())) {
            // The exclusion constraint would refuse this anyway. Checking first is what turns a 409 with no
            // explanation into one that names the route, the day type and the dates that clash.
            throw new ConflictException(
                    "TIMETABLE_VALIDITY_OVERLAPS",
                    "Another active %s timetable for route %d already covers part of %s to %s. Retire it first."
                            .formatted(
                                    timetable.getDayType(),
                                    timetable.getRouteId(),
                                    timetable.getValidFrom(),
                                    timetable.getValidTo() == null ? "open-ended" : timetable.getValidTo()));
        }
        timetable.activate();
        timetables.save(timetable);
        events.publishEvent(AuditEvent.of("TIMETABLE_ACTIVATED", "TIMETABLE", timetableId));
        return timetable;
    }

    /**
     * Takes a timetable out of force.
     *
     * <p>Retiring rather than deleting. Past service dates still have to resolve to the timetable that was in
     * force when they ran, which a deleted row cannot do.
     */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public Timetable retire(Long timetableId) {
        Timetable timetable = require(timetableId);
        timetable.retire();
        timetables.save(timetable);
        events.publishEvent(AuditEvent.of("TIMETABLE_RETIRED", "TIMETABLE", timetableId));
        return timetable;
    }

    /**
     * The timetable governing a route on a service date.
     *
     * <p>Resolved per date rather than per request, so a date range spanning a timetable change picks the
     * right one for each day instead of applying one to all (edge case EC-TIME-06).
     */
    @Transactional(readOnly = true)
    public Timetable resolveFor(Long routeId, DayType dayType, LocalDate serviceDate) {
        Optional<Timetable> found = timetables.findActiveFor(routeId, dayType, serviceDate);
        return found.orElseThrow(() -> new BusinessRuleException(
                "NO_TIMETABLE",
                "No active %s timetable covers %s for route %d".formatted(dayType, serviceDate, routeId)));
    }

    /**
     * The timetable governing a route on a calendar date.
     *
     * <p>Resolves the day type first, through the calendar, so a holiday picks the holiday timetable rather
     * than the one the day of the week implies. Composing the two steps here rather than leaving it to each
     * caller is what stops one of them forgetting the calendar and quietly running weekday service on Diwali.
     *
     * @param depotId may be null, which asks for the network-wide calendar answer
     */
    @Transactional(readOnly = true)
    public Resolved resolveForDate(Long routeId, LocalDate serviceDate, Long depotId) {
        ServiceCalendar.Resolution dayType = calendar.resolve(serviceDate, depotId);
        Timetable timetable = resolveFor(routeId, dayType.dayType(), serviceDate);
        return new Resolved(timetable, dayType);
    }

    @Transactional(readOnly = true)
    public Timetable require(Long timetableId) {
        return timetables.findById(timetableId).orElseThrow(() -> NotFoundException.of("Timetable", timetableId));
    }

    @Transactional(readOnly = true)
    public List<HeadwayBand> bandsOf(Long timetableId) {
        require(timetableId);
        return headwayBands.findOrderedByTimetable(timetableId);
    }

    private static String describe(Timetable timetable) {
        return """
                {"routeId":%d,"dayType":"%s","validFrom":"%s","validTo":%s}"""
                .formatted(
                        timetable.getRouteId(),
                        timetable.getDayType(),
                        timetable.getValidFrom(),
                        timetable.getValidTo() == null ? "null" : "\"" + timetable.getValidTo() + "\"");
    }

    /** @param fromSec and toSec are service-day seconds; the window is half-open */
    public record BandSpec(int fromSec, int toSec, int headwaySec) {}

    /**
     * @param analyticalCount what the bands say should have been produced, so a caller can see the generator
     *     agreeing with arithmetic rather than taking its word for it
     */
    public record GenerationResult(int generatedCount, int analyticalCount, List<String> warnings) {}

    /**
     * A timetable together with the calendar decision that selected it.
     *
     * <p>The calendar part is returned, not discarded. "Why is this route running a Sunday service on a
     * Wednesday" is the first question a planner asks, and the answer has to travel with the timetable.
     */
    public record Resolved(Timetable timetable, ServiceCalendar.Resolution dayType) {}

    /** Published when a timetable's trips change. Consumed from Phase 6 to flag schedules for revalidation. */
    public record TimetableChanged(Long timetableId, Long routeId) {}
}
