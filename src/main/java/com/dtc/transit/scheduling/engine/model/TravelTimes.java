package com.dtc.transit.scheduling.engine.model;

/**
 * Non-revenue travel times, as a lookup the engine can call millions of times.
 *
 * <p>An interface rather than a map, so the engine never learns where the numbers came from. The adapter
 * resolves measured values, falls back to estimates and flags them; the engine only asks how long a move
 * takes and whether the answer was measured.
 *
 * <p>Implementations must be pure functions of their arguments and safe to call from one thread. The
 * engine is single-threaded per run and relies on repeated lookups returning the same answer, because a
 * feasibility test that changed its mind would produce a block that cannot be driven.
 */
public interface TravelTimes {

    /** Travel seconds between two stops for a move departing at the given time. */
    int betweenStops(long fromStopId, long toStopId, int atSec);

    double distanceBetweenStops(long fromStopId, long toStopId);

    /**
     * Whether the value for this pair was derived rather than surveyed.
     *
     * <p>Surfaced so the schedule can carry an {@code ESTIMATED_DEADHEAD} soft conflict. A block built on
     * guesses is not wrong, but a planner should know which parts of it rest on one.
     */
    boolean isEstimated(long fromStopId, long toStopId);

    /** Depot to a stop, for a pull-out. */
    int fromDepot(long toStopId);

    /** A stop back to the depot, for a pull-in. */
    int toDepot(long fromStopId);

    double distanceFromDepot(long toStopId);

    double distanceToDepot(long fromStopId);
}
