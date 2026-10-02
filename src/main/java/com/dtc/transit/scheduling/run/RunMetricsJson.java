package com.dtc.transit.scheduling.run;

import com.dtc.transit.scheduling.engine.model.ScheduleMetrics;

/**
 * Serialises run metrics by hand.
 *
 * <p>Hand-written rather than through Jackson so the engine's records stay free of serialisation concerns. The
 * engine is forbidden from importing Jackson at all, and adding a mapper configuration here to bind one flat
 * record of numbers would be more machinery than the problem deserves.
 */
final class RunMetricsJson {

    private RunMetricsJson() {
        // static helper
    }

    static String of(ScheduleMetrics metrics) {
        return ("{\"tripsTotal\":%d,\"tripsCovered\":%d,\"tripsUncovered\":%d,\"blocks\":%d,"
                        + "\"minFleetLowerBound\":%s,\"busesAssigned\":%d,\"blocksUnassigned\":%d,"
                        + "\"duties\":%d,\"handovers\":%d,\"serviceKm\":%s,\"deadKm\":%s,"
                        + "\"deadKmRatio\":%s,\"hardConflicts\":%d,\"softConflicts\":%d,"
                        + "\"elapsedMillis\":%d,\"coverageRatio\":%s}")
                .formatted(
                        metrics.tripsTotal(),
                        metrics.tripsCovered(),
                        metrics.tripsUncovered(),
                        metrics.blocks(),
                        metrics.minFleetLowerBound() == null ? "null" : metrics.minFleetLowerBound(),
                        metrics.busesAssigned(),
                        metrics.blocksUnassigned(),
                        metrics.duties(),
                        metrics.handovers(),
                        metrics.serviceKm(),
                        metrics.deadKm(),
                        round(metrics.deadKmRatio()),
                        metrics.hardConflicts(),
                        metrics.softConflicts(),
                        metrics.elapsedMillis(),
                        round(metrics.coverageRatio()));
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
