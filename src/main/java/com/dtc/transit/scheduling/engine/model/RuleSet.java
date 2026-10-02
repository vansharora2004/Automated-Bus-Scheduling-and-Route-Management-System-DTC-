package com.dtc.transit.scheduling.engine.model;

/**
 * The labour and operational rules a run is built against.
 *
 * <p>Data, not code. The whole point of Phase 7 is that changing the maximum continuous work value
 * changes the schedule without a recompile, and that two depots can differ. Bean Validation lives on the
 * adapter that binds this from JSONB, so this record stays free of framework annotations and the engine
 * stays testable without a Spring context.
 *
 * <p>Every statutory value here must be confirmed against the rules actually in force before the system
 * is used to roster real people. The defaults below are the project's documented assumptions.
 *
 * @param maxWorkPerDutyMin most driving plus other work in one duty
 * @param maxContinuousWorkMin longest stretch before a qualifying break is required
 * @param minBreakMin shortest idle period that counts as a break
 * @param maxSpreadOverMin sign-on to sign-off, including unpaid breaks
 * @param maxWeeklyWorkMin rolling seven-day ceiling, enforced in Phase 9 during crew assignment
 * @param weeklyRestDaysPer7 rest days required in any rolling seven
 * @param minRestBetweenDutiesMin rest between a sign-off and the next sign-on, enforced in Phase 9
 * @param signOnMin paid time before the first movement
 * @param signOffMin paid time after the last movement
 * @param minLayoverMin floor for the gap between two trips on one bus
 * @param minLayoverPct the same gap as a percentage of the trip just completed, whichever is larger
 * @param handoverBufferMin slack a crew change needs on top of the arrival
 * @param maxPiecesPerDuty soft ceiling on how fragmented a duty may be
 * @param maxBusChangeoversPerDuty soft ceiling on bus changes, only reachable in unlinked mode
 * @param targetWorkPerDutyMin what the duty builder aims for when choosing between legal cuts
 * @param minPaidDutyMin the minimum paid guarantee; shorter duties are paid at this length
 * @param allowOvertime policy switch; when false, {@code maxOvertimeMin} is irrelevant
 * @param maxOvertimeMin hard ceiling once overtime is permitted
 * @param midDayDepotReturnGapMin an idle gap at least this long sends the bus back to the depot
 * @param evRangeReservePct battery margin kept unused, so a block never plans to arrive empty
 * @param standbyPoolPct crew held back for same-day cover, used from Phase 9
 * @param maxBlockDurationMin longest a single bus block may run
 * @param evChargingMin how long a mid-day charge is planned for
 */
public record RuleSet(
        int maxWorkPerDutyMin,
        int maxContinuousWorkMin,
        int minBreakMin,
        int maxSpreadOverMin,
        int maxWeeklyWorkMin,
        int weeklyRestDaysPer7,
        int minRestBetweenDutiesMin,
        int signOnMin,
        int signOffMin,
        int minLayoverMin,
        int minLayoverPct,
        int handoverBufferMin,
        int maxPiecesPerDuty,
        int maxBusChangeoversPerDuty,
        int targetWorkPerDutyMin,
        int minPaidDutyMin,
        boolean allowOvertime,
        int maxOvertimeMin,
        int midDayDepotReturnGapMin,
        int evRangeReservePct,
        int standbyPoolPct,
        int maxBlockDurationMin,
        int evChargingMin) {

    /**
     * The documented defaults, for tests and for the engine's own fallback.
     *
     * <p>Production reads the rule set from the database. This exists so a unit test can state the one
     * value it cares about with {@code defaults().withMaxContinuousWorkMin(...)} rather than listing
     * twenty-three arguments it does not.
     */
    public static RuleSet defaults() {
        return new RuleSet(
                480, 300, 30, 720, 2880, 1, 600, 15, 10, 5, 10, 5, 3, 2, 450, 240, false, 60, 90, 15, 5, 1140, 45);
    }

    public RuleSet {
        requirePositive(maxWorkPerDutyMin, "maxWorkPerDutyMin");
        requirePositive(maxContinuousWorkMin, "maxContinuousWorkMin");
        requirePositive(minBreakMin, "minBreakMin");
        requirePositive(maxSpreadOverMin, "maxSpreadOverMin");
        requirePositive(maxBlockDurationMin, "maxBlockDurationMin");
        if (maxContinuousWorkMin > maxWorkPerDutyMin) {
            // A continuous limit above the daily limit can never bind, which almost always means the two
            // were entered the wrong way round.
            throw new IllegalArgumentException(
                    "maxContinuousWorkMin (" + maxContinuousWorkMin + ") exceeds maxWorkPerDutyMin ("
                            + maxWorkPerDutyMin + "), so the continuous limit could never apply");
        }
        if (maxWorkPerDutyMin > maxSpreadOverMin) {
            throw new IllegalArgumentException("maxWorkPerDutyMin exceeds maxSpreadOverMin, which is impossible: "
                    + "work is part of the spread-over");
        }
        if (evRangeReservePct < 0 || evRangeReservePct >= 100) {
            throw new IllegalArgumentException("evRangeReservePct must be in [0, 100): " + evRangeReservePct);
        }
    }

    /**
     * Minimum layover after a trip of the given length.
     *
     * <p>The percentage term matters because a 90-minute cross-city run needs more recovery than a
     * 12-minute shuttle, and a single fixed figure would be either too generous or too tight.
     */
    public int minLayoverSecAfter(int tripDurationSec) {
        return Math.max(minLayoverMin * 60, tripDurationSec * minLayoverPct / 100);
    }

    /** Usable range in metres after the battery reserve is set aside. */
    public double usableRangeMetres(int evRangeKm) {
        return evRangeKm * 1000.0 * (100 - evRangeReservePct) / 100.0;
    }

    public int maxContinuousWorkSec() {
        return maxContinuousWorkMin * 60;
    }

    public int minBreakSec() {
        return minBreakMin * 60;
    }

    public int maxWorkPerDutySec() {
        return maxWorkPerDutyMin * 60;
    }

    public int maxSpreadOverSec() {
        return maxSpreadOverMin * 60;
    }

    public int signOnSec() {
        return signOnMin * 60;
    }

    public int signOffSec() {
        return signOffMin * 60;
    }

    public int handoverBufferSec() {
        return handoverBufferMin * 60;
    }

    public int midDayDepotReturnGapSec() {
        return midDayDepotReturnGapMin * 60;
    }

    public int maxBlockDurationSec() {
        return maxBlockDurationMin * 60;
    }

    public int targetWorkPerDutySec() {
        return targetWorkPerDutyMin * 60;
    }

    public int minPaidDutySec() {
        return minPaidDutyMin * 60;
    }

    /**
     * A mutable copy, for changing one rule without restating the other twenty-two.
     *
     * <p>A builder rather than a {@code withX} method per component. Twenty-three near-identical methods
     * would be the kind of code nobody reads and everybody copies a mistake into.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {

        private int maxWorkPerDutyMin;
        private int maxContinuousWorkMin;
        private int minBreakMin;
        private int maxSpreadOverMin;
        private int maxWeeklyWorkMin;
        private int weeklyRestDaysPer7;
        private int minRestBetweenDutiesMin;
        private int signOnMin;
        private int signOffMin;
        private int minLayoverMin;
        private int minLayoverPct;
        private int handoverBufferMin;
        private int maxPiecesPerDuty;
        private int maxBusChangeoversPerDuty;
        private int targetWorkPerDutyMin;
        private int minPaidDutyMin;
        private boolean allowOvertime;
        private int maxOvertimeMin;
        private int midDayDepotReturnGapMin;
        private int evRangeReservePct;
        private int standbyPoolPct;
        private int maxBlockDurationMin;
        private int evChargingMin;

        private Builder(RuleSet source) {
            maxWorkPerDutyMin = source.maxWorkPerDutyMin;
            maxContinuousWorkMin = source.maxContinuousWorkMin;
            minBreakMin = source.minBreakMin;
            maxSpreadOverMin = source.maxSpreadOverMin;
            maxWeeklyWorkMin = source.maxWeeklyWorkMin;
            weeklyRestDaysPer7 = source.weeklyRestDaysPer7;
            minRestBetweenDutiesMin = source.minRestBetweenDutiesMin;
            signOnMin = source.signOnMin;
            signOffMin = source.signOffMin;
            minLayoverMin = source.minLayoverMin;
            minLayoverPct = source.minLayoverPct;
            handoverBufferMin = source.handoverBufferMin;
            maxPiecesPerDuty = source.maxPiecesPerDuty;
            maxBusChangeoversPerDuty = source.maxBusChangeoversPerDuty;
            targetWorkPerDutyMin = source.targetWorkPerDutyMin;
            minPaidDutyMin = source.minPaidDutyMin;
            allowOvertime = source.allowOvertime;
            maxOvertimeMin = source.maxOvertimeMin;
            midDayDepotReturnGapMin = source.midDayDepotReturnGapMin;
            evRangeReservePct = source.evRangeReservePct;
            standbyPoolPct = source.standbyPoolPct;
            maxBlockDurationMin = source.maxBlockDurationMin;
            evChargingMin = source.evChargingMin;
        }

        public Builder maxWorkPerDutyMin(int value) {
            maxWorkPerDutyMin = value;
            return this;
        }

        public Builder maxContinuousWorkMin(int value) {
            maxContinuousWorkMin = value;
            return this;
        }

        public Builder minBreakMin(int value) {
            minBreakMin = value;
            return this;
        }

        public Builder maxSpreadOverMin(int value) {
            maxSpreadOverMin = value;
            return this;
        }

        public Builder minLayoverMin(int value) {
            minLayoverMin = value;
            return this;
        }

        public Builder minLayoverPct(int value) {
            minLayoverPct = value;
            return this;
        }

        public Builder handoverBufferMin(int value) {
            handoverBufferMin = value;
            return this;
        }

        public Builder targetWorkPerDutyMin(int value) {
            targetWorkPerDutyMin = value;
            return this;
        }

        public Builder minPaidDutyMin(int value) {
            minPaidDutyMin = value;
            return this;
        }

        public Builder allowOvertime(boolean value) {
            allowOvertime = value;
            return this;
        }

        public Builder maxOvertimeMin(int value) {
            maxOvertimeMin = value;
            return this;
        }

        public Builder midDayDepotReturnGapMin(int value) {
            midDayDepotReturnGapMin = value;
            return this;
        }

        public Builder evRangeReservePct(int value) {
            evRangeReservePct = value;
            return this;
        }

        public Builder maxBlockDurationMin(int value) {
            maxBlockDurationMin = value;
            return this;
        }

        public Builder evChargingMin(int value) {
            evChargingMin = value;
            return this;
        }

        public Builder signOnMin(int value) {
            signOnMin = value;
            return this;
        }

        public Builder signOffMin(int value) {
            signOffMin = value;
            return this;
        }

        public RuleSet build() {
            return new RuleSet(
                    maxWorkPerDutyMin,
                    maxContinuousWorkMin,
                    minBreakMin,
                    maxSpreadOverMin,
                    maxWeeklyWorkMin,
                    weeklyRestDaysPer7,
                    minRestBetweenDutiesMin,
                    signOnMin,
                    signOffMin,
                    minLayoverMin,
                    minLayoverPct,
                    handoverBufferMin,
                    maxPiecesPerDuty,
                    maxBusChangeoversPerDuty,
                    targetWorkPerDutyMin,
                    minPaidDutyMin,
                    allowOvertime,
                    maxOvertimeMin,
                    midDayDepotReturnGapMin,
                    evRangeReservePct,
                    standbyPoolPct,
                    maxBlockDurationMin,
                    evChargingMin);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
