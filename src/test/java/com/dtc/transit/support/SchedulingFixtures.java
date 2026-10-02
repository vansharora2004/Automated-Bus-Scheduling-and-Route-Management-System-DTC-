package com.dtc.transit.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.StopView;
import com.dtc.transit.scheduling.engine.model.TimeWindow;
import com.dtc.transit.scheduling.engine.model.TravelTimes;
import com.dtc.transit.scheduling.engine.model.TripView;

/**
 * Hand-built engine inputs with known answers.
 *
 * <p>Uniform travel times on purpose. A fixture with realistic, varying deadheads makes every expected value
 * a second calculation that can itself be wrong; with a flat 10 minutes between any two stops, the expected
 * block structure can be worked out on paper and the test asserts a fact rather than a reimplementation.
 */
public final class SchedulingFixtures {

    public static final LocalDate SERVICE_DATE = LocalDate.of(2026, 6, 1);

    /** Stop ids used by the fixtures. A and B are termini; C is a mid-route stop that is not a relief point. */
    public static final long STOP_A = 1L;

    public static final long STOP_B = 2L;

    public static final long STOP_C = 3L;

    /** Flat travel time between any two distinct stops, in seconds. */
    public static final int STOP_TO_STOP_SEC = 600;

    /** Flat travel time between the depot and any stop, in seconds. */
    public static final int DEPOT_SEC = 900;

    /** Flat distance between any two distinct stops, in metres. */
    public static final double STOP_TO_STOP_M = 4_000;

    public static final double DEPOT_M = 6_000;

    private SchedulingFixtures() {
        // fixtures only
    }

    /**
     * Travel times that are the same for every pair.
     *
     * @param estimatedPairs pairs reported as estimated rather than measured, as {@code fromId + ":" + toId}
     */
    public static TravelTimes uniformTravel(Set<String> estimatedPairs) {
        return new TravelTimes() {
            @Override
            public int betweenStops(long fromStopId, long toStopId, int atSec) {
                return fromStopId == toStopId ? 0 : STOP_TO_STOP_SEC;
            }

            @Override
            public double distanceBetweenStops(long fromStopId, long toStopId) {
                return fromStopId == toStopId ? 0 : STOP_TO_STOP_M;
            }

            @Override
            public boolean isEstimated(long fromStopId, long toStopId) {
                return estimatedPairs.contains(fromStopId + ":" + toStopId);
            }

            @Override
            public int fromDepot(long toStopId) {
                return DEPOT_SEC;
            }

            @Override
            public int toDepot(long fromStopId) {
                return DEPOT_SEC;
            }

            @Override
            public double distanceFromDepot(long toStopId) {
                return DEPOT_M;
            }

            @Override
            public double distanceToDepot(long fromStopId) {
                return DEPOT_M;
            }
        };
    }

    public static TravelTimes uniformTravel() {
        return uniformTravel(Set.of());
    }

    /** A trip with no vehicle-class requirement, which is the normal case. */
    public static TripView trip(long id, long fromStop, long toStop, int startSec, int endSec) {
        return new TripView(id, 10L, 20L, fromStop, toStop, startSec, endSec, STOP_TO_STOP_M * 3, null);
    }

    public static TripView tripNeeding(
            long id, long fromStop, long toStop, int startSec, int endSec, String vehicleClass) {
        return new TripView(id, 10L, 20L, fromStop, toStop, startSec, endSec, STOP_TO_STOP_M * 3, vehicleClass);
    }

    /** A trip whose distance is stated, for range and kilometre assertions. */
    public static TripView tripOfLength(
            long id, long fromStop, long toStop, int startSec, int endSec, double metres) {
        return new TripView(id, 10L, 20L, fromStop, toStop, startSec, endSec, metres, null);
    }

    public static BusView bus(long id, String busType) {
        return new BusView(id, "F-" + id, busType, "CNG", null, 42, List.of());
    }

    public static BusView electricBus(long id, int rangeKm) {
        return new BusView(id, "E-" + id, "STANDARD", "ELECTRIC", rangeKm, 42, List.of());
    }

    public static BusView busUnavailable(long id, int fromSec, int toSec) {
        return new BusView(id, "F-" + id, "STANDARD", "CNG", null, 42, List.of(new TimeWindow(fromSec, toSec)));
    }

    /** Several identical standard buses, which is what most block-building tests need. */
    public static List<BusView> standardFleet(int count) {
        List<BusView> fleet = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            fleet.add(bus(i, "STANDARD"));
        }
        return fleet;
    }

    /**
     * A depot where A and B are relief points and C is not.
     *
     * <p>The asymmetry is the point: a test that marks every stop as a relief point cannot tell a correct
     * relief finder from one that marks everything.
     */
    public static DepotContext depot(List<BusView> buses) {
        return depot(buses, uniformTravel());
    }

    public static DepotContext depot(List<BusView> buses, TravelTimes travel) {
        Map<Long, StopView> stops = new LinkedHashMap<>();
        stops.put(STOP_A, new StopView(STOP_A, true, true, true));
        stops.put(STOP_B, new StopView(STOP_B, true, true, true));
        stops.put(STOP_C, new StopView(STOP_C, false, false, false));
        return new DepotContext(7L, SERVICE_DATE, null, stops, buses, travel);
    }
}
