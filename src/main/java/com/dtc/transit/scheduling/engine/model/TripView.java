package com.dtc.transit.scheduling.engine.model;

/**
 * One trip, as the engine sees it.
 *
 * <p>A flat record rather than the JPA entity. The engine runs tens of thousands of feasibility tests
 * per block build, and a managed entity would make each one a potential lazy load.
 *
 * @param startSec and endSec are service-day seconds and may exceed 86,400
 * @param requiredVehicleClass null when any class will do, which is the normal case
 */
public record TripView(
        long id,
        long patternId,
        long routeId,
        long startStopId,
        long endStopId,
        int startSec,
        int endSec,
        double distanceM,
        String requiredVehicleClass) {

    public TripView {
        if (endSec <= startSec) {
            throw new IllegalArgumentException("trip " + id + " ends at or before it starts");
        }
    }

    public int durationSec() {
        return endSec - startSec;
    }
}
