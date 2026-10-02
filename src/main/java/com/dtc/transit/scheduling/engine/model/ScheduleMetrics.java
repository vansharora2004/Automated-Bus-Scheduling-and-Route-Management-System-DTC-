package com.dtc.transit.scheduling.engine.model;

/**
 * What a run produced, as numbers a scheduler can compare between runs.
 *
 * <p>Stored on the run rather than recomputed, because the inputs change: asking "how good was Tuesday's
 * schedule" next week must not be answered against next week's fleet.
 *
 * @param minFleetLowerBound the optimal peak vehicle requirement under the model, or null when not computed
 * @param deadKmRatio dead running as a share of all running; the headline efficiency figure
 * @param elapsedMillis wall-clock time the engine took, which is what the performance target is measured on
 * @param outputHash a digest of the schedule's logical content, so two runs over the same input and seed can
 *     be compared in one value instead of row by row. Covers the blocks, duties and assignments, and never
 *     the database ids or timings, which differ between runs for reasons that are not the schedule.
 */
public record ScheduleMetrics(
        int tripsTotal,
        int tripsCovered,
        int tripsUncovered,
        int blocks,
        Integer minFleetLowerBound,
        int busesAssigned,
        int blocksUnassigned,
        int duties,
        int handovers,
        double serviceKm,
        double deadKm,
        double deadKmRatio,
        int hardConflicts,
        int softConflicts,
        long elapsedMillis,
        String outputHash) {

    /** Share of trips that made it into a block. 1.0 on a date with no service, which is full coverage. */
    public double coverageRatio() {
        return tripsTotal == 0 ? 1.0 : (double) tripsCovered / tripsTotal;
    }

    /** How many buses the greedy answer costs above the optimum, or null when the bound is unknown. */
    public Integer fleetAboveLowerBound() {
        return minFleetLowerBound == null ? null : blocks - minFleetLowerBound;
    }
}
