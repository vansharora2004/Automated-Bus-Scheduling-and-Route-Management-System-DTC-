package com.dtc.transit.timetable.trip;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.CursorResponse;
import com.dtc.transit.common.time.ServiceTime;

/**
 * Trip listing.
 *
 * <p>Keyset-paginated rather than offset-paginated. Trips are the highest-volume table in the system, and a
 * deep offset makes the database walk every preceding row before discarding it.
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    /** Deliberately lower than the offset endpoints' 100: a trip row is wider and callers page further. */
    public static final int MAX_LIMIT = 500;

    public static final int DEFAULT_LIMIT = 100;

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    /**
     * @param after the previous page's {@code nextCursor}; omit for the first page
     * @param limit clamped to {@link #MAX_LIMIT} rather than rejected, matching the page-size behaviour of
     *     the offset endpoints so one list endpoint does not behave differently from the rest
     */
    @GetMapping
    public CursorResponse<TripResponse> list(
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Long timetableId,
            @RequestParam(required = false) Long patternId,
            @RequestParam(required = false) Integer fromSec,
            @RequestParam(required = false) Integer toSec,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = Math.min(limit == null || limit < 1 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        List<Trip> fetched = tripService.page(after, timetableId, patternId, fromSec, toSec, effectiveLimit);
        return CursorResponse.of(fetched, effectiveLimit, TripResponse::from, Trip::getId);
    }

    /**
     * @param departure and arrival are clock times for readability; the seconds are authoritative and may
     *     exceed 86,400 for service past midnight
     */
    public record TripResponse(
            Long id,
            Long timetableId,
            Long patternId,
            Long startStopId,
            Long endStopId,
            int startSec,
            int endSec,
            String departure,
            String arrival,
            Double distanceM,
            String requiredVehicleClass,
            boolean endsAfterMidnight) {

        static TripResponse from(Trip trip) {
            return new TripResponse(
                    trip.getId(),
                    trip.getTimetableId(),
                    trip.getPatternId(),
                    trip.getStartStopId(),
                    trip.getEndStopId(),
                    trip.getStartSec(),
                    trip.getEndSec(),
                    ServiceTime.format(trip.getStartSec()),
                    ServiceTime.format(trip.getEndSec()),
                    trip.getDistanceM(),
                    trip.getRequiredVehicleClass(),
                    trip.endsAfterMidnight());
        }
    }
}
