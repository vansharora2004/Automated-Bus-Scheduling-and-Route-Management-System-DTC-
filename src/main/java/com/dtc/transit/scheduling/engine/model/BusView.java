package com.dtc.transit.scheduling.engine.model;

import java.util.List;

/**
 * One physical bus, as the engine sees it.
 *
 * @param evRangeKm null for anything that is not electric; required for an electric bus, which the
 *     database enforces at entry so the engine never has to guess
 * @param unavailability maintenance and breakdown windows, already converted to service-day seconds
 */
public record BusView(
        long id,
        String fleetNo,
        String busType,
        String fuelType,
        Integer evRangeKm,
        int capacity,
        List<TimeWindow> unavailability) {

    public BusView {
        unavailability = unavailability == null ? List.of() : List.copyOf(unavailability);
    }

    public boolean isElectric() {
        return "ELECTRIC".equals(fuelType);
    }

    /** Whether this bus is free for the whole of a window. */
    public boolean isAvailableFor(TimeWindow window) {
        return unavailability.stream().noneMatch(window::overlaps);
    }
}
