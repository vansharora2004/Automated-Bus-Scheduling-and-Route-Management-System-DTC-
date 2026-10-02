package com.dtc.transit.scheduling.engine.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything about one depot-day that the engine needs, loaded once.
 *
 * <p>A single consistent snapshot rather than queries during the run. Reading master data while building
 * would let a bus go into maintenance halfway through and produce a schedule that was never valid at any
 * single moment.
 *
 * <p>Maps are sorted. The engine's determinism depends on iteration order, and the seed on the run is
 * worthless if a {@code HashMap} decides which of two equally good blocks wins.
 *
 * @param depotStopId the stop that stands in for the depot gate in stop-to-stop lookups, or null when the
 *     depot has no stop of its own and only the depot travel functions apply
 */
public record DepotContext(
        long depotId,
        LocalDate serviceDate,
        Long depotStopId,
        Map<Long, StopView> stops,
        List<BusView> buses,
        TravelTimes travelTimes) {

    public DepotContext {
        stops = Map.copyOf(new TreeMap<>(stops));
        buses = buses.stream()
                .sorted(java.util.Comparator.comparingLong(BusView::id))
                .toList();
    }

    public StopView stop(long stopId) {
        return stops.get(stopId);
    }

    /** Whether a crew change is physically possible at a stop. */
    public boolean isReliefPoint(long stopId) {
        StopView stop = stops.get(stopId);
        return stop != null && stop.reliefPoint();
    }

    /** The distinct vehicle classes the depot can actually field, for the uncovered-trip reason. */
    public Collection<String> availableVehicleClasses() {
        return buses.stream().map(BusView::busType).distinct().sorted().toList();
    }
}
