package com.dtc.transit.timetable.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.route.pattern.RunningTimeBand;
import com.dtc.transit.route.route.DayType;
import com.dtc.transit.timetable.headway.HeadwayBand;

/**
 * Turns headway bands into actual trip departures.
 *
 * <p>This is the step that replaces a planner typing out several hundred departure times by hand. The
 * algorithm is deliberately simple: walk each band from its start, stepping by the headway, and look up
 * the running time that applies at each departure.
 *
 * <p>Pure: no Spring, no database, no clock. Given the same bands it produces the same trips, which is what
 * lets the counts be asserted against an analytical formula rather than against a previous run.
 */
@Component
public class TripGenerator {

    private static final Logger log = LoggerFactory.getLogger(TripGenerator.class);

    /** Below this, an urban bus average implies the running time was entered in the wrong unit. */
    public static final double MIN_PLAUSIBLE_SPEED_KMH = 5;

    /** Above this, a city bus is implausibly fast, which usually means a missing digit. */
    public static final double MAX_PLAUSIBLE_SPEED_KMH = 45;

    /**
     * Generates departures for one pattern.
     *
     * @param strict when true a missing running-time band fails generation; when false the nearest band is
     *     used and a warning is recorded (edge case EC-TT-07)
     */
    public Result generate(Request request) {
        List<HeadwayBand> bands = request.headwayBands().stream()
                .sorted(java.util.Comparator.comparingInt(HeadwayBand::getFromSec))
                .toList();

        if (bands.isEmpty()) {
            throw new BusinessRuleException(
                    "NO_HEADWAY_BANDS",
                    "Cannot generate trips for direction " + request.direction() + ": no headway bands defined");
        }
        requireNoOverlaps(bands);

        List<TripDraft> drafts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (HeadwayBand band : bands) {
            if (band.getHeadwaySec() > band.windowSeconds()) {
                // A 60-minute band at a 90-minute headway still deserves the one departure at its start,
                // rather than silently producing nothing (edge case EC-TT-04).
                warnings.add("Headway %d min exceeds the %d min band starting %s, so only one trip is generated"
                        .formatted(
                                band.getHeadwaySec() / 60,
                                band.windowSeconds() / 60,
                                ServiceTime.format(band.getFromSec())));
            }

            for (int departure = band.getFromSec(); departure < band.getToSec(); departure += band.getHeadwaySec()) {
                int runningSec = resolveRunningTime(request, departure, warnings);
                drafts.add(new TripDraft(
                        request.patternId(),
                        departure,
                        departure + runningSec,
                        request.distanceMetres(),
                        request.requiredVehicleClass()));
            }
        }

        checkPlausibleSpeeds(drafts, request, warnings);
        log.debug("generated {} trip(s) for pattern {}", drafts.size(), request.patternId());
        return new Result(drafts, warnings);
    }

    /**
     * The count the generator should produce, derived from the bands alone.
     *
     * <p>Exists so a test can compare the generator against arithmetic rather than against its own output,
     * which would only prove it is consistent, not correct.
     */
    public static int analyticalTripCount(List<HeadwayBand> bands) {
        return bands.stream().mapToInt(HeadwayBand::expectedTripCount).sum();
    }

    /**
     * Running time at a departure.
     *
     * <p>Looked up by departure time, not by arrival. A trip leaving at the end of the peak keeps the peak
     * running time for its whole journey, because it is sitting in peak traffic (edge case EC-TT-11).
     */
    private int resolveRunningTime(Request request, int departureSec, List<String> warnings) {
        Optional<RunningTimeBand> exact = request.runningTimeBands().stream()
                .filter(band -> band.getDayType() == request.dayType())
                .filter(band -> band.covers(departureSec))
                .findFirst();

        if (exact.isPresent()) {
            return exact.get().getRunningSec();
        }

        if (request.strict()) {
            throw new BusinessRuleException(
                    "NO_RUNNING_TIME_BAND",
                    "No running time defined for pattern " + request.patternId() + " at "
                            + ServiceTime.format(departureSec) + " on a " + request.dayType()
                            + ". Add a band covering that time, or generate with strict=false.");
        }

        RunningTimeBand nearest = request.runningTimeBands().stream()
                .filter(band -> band.getDayType() == request.dayType())
                .min(java.util.Comparator.comparingInt(band -> distanceTo(band, departureSec)))
                .orElseThrow(() -> new BusinessRuleException(
                        "NO_RUNNING_TIME_BAND",
                        "Pattern " + request.patternId() + " has no running times at all for a "
                                + request.dayType()));

        String warning = "Used the nearest running-time band for %s, which is outside any defined band"
                .formatted(ServiceTime.format(departureSec));
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
        return nearest.getRunningSec();
    }

    private static int distanceTo(RunningTimeBand band, int departureSec) {
        if (departureSec < band.getFromSec()) {
            return band.getFromSec() - departureSec;
        }
        if (departureSec >= band.getToSec()) {
            return departureSec - band.getToSec();
        }
        return 0;
    }

    private void requireNoOverlaps(List<HeadwayBand> bands) {
        for (int i = 1; i < bands.size(); i++) {
            HeadwayBand previous = bands.get(i - 1);
            HeadwayBand current = bands.get(i);
            if (previous.getToSec() > current.getFromSec()) {
                // Overlapping bands would generate two departures at the same minute, so two buses would be
                // dispatched for one trip (edge case EC-TT-03).
                throw new BusinessRuleException(
                        "HEADWAY_BANDS_OVERLAP",
                        "Bands %s-%s and %s-%s overlap for direction %s"
                                .formatted(
                                        ServiceTime.format(previous.getFromSec()),
                                        ServiceTime.format(previous.getToSec()),
                                        ServiceTime.format(current.getFromSec()),
                                        ServiceTime.format(current.getToSec()),
                                        current.getDirection()));
            }
        }
    }

    /**
     * Flags running times that imply an impossible speed.
     *
     * <p>A warning rather than a rejection. The distance comes from the drawn geometry and may itself be
     * approximate, so refusing the timetable would block a planner over a data-quality issue they may not
     * be able to fix today (edge case EC-TT-02).
     */
    private void checkPlausibleSpeeds(List<TripDraft> drafts, Request request, List<String> warnings) {
        if (request.distanceMetres() == null || request.distanceMetres() <= 0 || drafts.isEmpty()) {
            return;
        }
        for (TripDraft draft : drafts) {
            double hours = (draft.endSec() - draft.startSec()) / 3600.0;
            double speed = (request.distanceMetres() / 1000.0) / hours;
            if (speed < MIN_PLAUSIBLE_SPEED_KMH || speed > MAX_PLAUSIBLE_SPEED_KMH) {
                warnings.add("Implausible average speed of %.1f km/h for the trip at %s; expected %.0f-%.0f km/h"
                        .formatted(
                                speed,
                                ServiceTime.format(draft.startSec()),
                                MIN_PLAUSIBLE_SPEED_KMH,
                                MAX_PLAUSIBLE_SPEED_KMH));
                // One warning per pattern is enough; repeating it per trip would bury everything else.
                return;
            }
        }
    }

    /**
     * Everything needed to generate one direction's trips.
     *
     * @param strict whether a departure outside every running-time band is an error
     */
    public record Request(
            Long patternId,
            com.dtc.transit.route.route.Direction direction,
            DayType dayType,
            List<HeadwayBand> headwayBands,
            List<RunningTimeBand> runningTimeBands,
            Double distanceMetres,
            String requiredVehicleClass,
            boolean strict) {}

    /** @param startSec and endSec are service-day seconds and may exceed 86,400 */
    public record TripDraft(
            Long patternId, int startSec, int endSec, Double distanceMetres, String requiredVehicleClass) {}

    public record Result(List<TripDraft> trips, List<String> warnings) {}
}
