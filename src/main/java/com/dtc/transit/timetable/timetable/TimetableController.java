package com.dtc.transit.timetable.timetable;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.route.route.DayType;
import com.dtc.transit.route.route.Direction;
import com.dtc.transit.timetable.headway.HeadwayBand;

/** Timetables, their headway bands, and trip generation. */
@RestController
@RequestMapping("/api/v1/timetables")
public class TimetableController {

    private final TimetableService timetableService;

    public TimetableController(TimetableService timetableService) {
        this.timetableService = timetableService;
    }

    @PostMapping
    public ResponseEntity<TimetableResponse> create(@Valid @RequestBody CreateTimetableRequest request) {
        Timetable timetable = timetableService.create(
                request.routeId(), request.dayType(), request.validFrom(), request.validTo());
        return ResponseEntity.created(URI.create("/api/v1/timetables/" + timetable.getId()))
                .body(TimetableResponse.from(timetable));
    }

    @GetMapping("/{id}")
    public TimetableDetailResponse get(@PathVariable Long id) {
        return new TimetableDetailResponse(
                TimetableResponse.from(timetableService.require(id)),
                timetableService.bandsOf(id).stream().map(BandResponse::from).toList());
    }

    /** The timetable in force for a route on a date, which is what a schedule run resolves. */
    @GetMapping("/active")
    public TimetableResponse active(
            @RequestParam Long routeId, @RequestParam DayType dayType, @RequestParam LocalDate serviceDate) {
        return TimetableResponse.from(timetableService.resolveFor(routeId, dayType, serviceDate));
    }

    /**
     * The timetable in force on a calendar date, with the day type the calendar resolved.
     *
     * <p>Distinct from {@code /active}, which is asked in terms of a day type. This one is asked in terms of a
     * date and answers which day type applied, so a caller cannot accidentally skip the holiday calendar.
     */
    @GetMapping("/for-date")
    public ForDateResponse forDate(
            @RequestParam Long routeId,
            @RequestParam LocalDate serviceDate,
            @RequestParam(required = false) Long depotId) {
        var resolved = timetableService.resolveForDate(routeId, serviceDate, depotId);
        return new ForDateResponse(
                TimetableResponse.from(resolved.timetable()),
                resolved.dayType().dayType(),
                resolved.dayType().overridden(),
                resolved.dayType().source(),
                resolved.dayType().note());
    }

    /** Replaces one direction's headway bands. PUT, because a partial set of bands leaves service gaps. */
    @PutMapping("/{id}/headway-bands/{direction}")
    public List<BandResponse> replaceBands(
            @PathVariable Long id,
            @PathVariable Direction direction,
            @Valid @RequestBody ReplaceBandsRequest request) {
        return timetableService
                .replaceBands(
                        id,
                        direction,
                        request.bands().stream()
                                .map(band -> new TimetableService.BandSpec(
                                        band.fromSec(), band.toSec(), band.headwaySec()))
                                .toList())
                .stream()
                .map(BandResponse::from)
                .toList();
    }

    /**
     * Generates the trips implied by the bands.
     *
     * <p>{@code strict} defaults to true. A departure that no running-time band covers has no defensible
     * arrival time, so guessing one quietly is the wrong default; a planner who knowingly wants the nearest
     * band has to ask for it.
     */
    @PostMapping("/{id}/generate-trips")
    public TimetableService.GenerationResult generateTrips(
            @PathVariable Long id, @RequestParam(defaultValue = "true") boolean strict) {
        return timetableService.generateTrips(id, strict);
    }

    @PostMapping("/{id}/activate")
    public TimetableResponse activate(@PathVariable Long id) {
        return TimetableResponse.from(timetableService.activate(id));
    }

    @PostMapping("/{id}/retire")
    public TimetableResponse retire(@PathVariable Long id) {
        return TimetableResponse.from(timetableService.retire(id));
    }

    /** @param validTo null for an open-ended timetable, in force until something replaces it */
    public record CreateTimetableRequest(
            @NotNull Long routeId, @NotNull DayType dayType, @NotNull LocalDate validFrom, LocalDate validTo) {}

    public record ReplaceBandsRequest(@NotEmpty @Valid List<HeadwayBandRequest> bands) {}

    /**
     * @param fromSec start of the window in service-day seconds, inclusive
     * @param toSec end of the window, exclusive; may exceed 86,400 for service past midnight
     * @param headwaySec gap between departures
     */
    public record HeadwayBandRequest(
            @NotNull @Min(0) Integer fromSec,
            @NotNull @Min(1) Integer toSec,
            @NotNull @Min(60) Integer headwaySec) {}

    public record TimetableResponse(
            Long id,
            Long routeId,
            DayType dayType,
            LocalDate validFrom,
            LocalDate validTo,
            TimetableStatus status,
            long version) {

        static TimetableResponse from(Timetable timetable) {
            return new TimetableResponse(
                    timetable.getId(),
                    timetable.getRouteId(),
                    timetable.getDayType(),
                    timetable.getValidFrom(),
                    timetable.getValidTo(),
                    timetable.getStatus(),
                    timetable.getVersion());
        }
    }

    public record TimetableDetailResponse(TimetableResponse timetable, List<BandResponse> headwayBands) {}

    /**
     * @param overridden true when a calendar exception decided the day type
     * @param source where the day type came from, so an unexpected timetable can be explained
     */
    public record ForDateResponse(
            TimetableResponse timetable, DayType dayType, boolean overridden, String source, String note) {}

    /** @param from and to are also rendered as clock times, because 27:00 is not obvious from 97200 */
    public record BandResponse(
            Direction direction, int fromSec, int toSec, int headwaySec, String from, String to, int trips) {

        static BandResponse from(HeadwayBand band) {
            return new BandResponse(
                    band.getDirection(),
                    band.getFromSec(),
                    band.getToSec(),
                    band.getHeadwaySec(),
                    ServiceTime.format(band.getFromSec()),
                    ServiceTime.format(band.getToSec()),
                    band.expectedTripCount());
        }
    }
}
