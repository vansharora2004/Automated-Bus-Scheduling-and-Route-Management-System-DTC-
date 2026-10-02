package com.dtc.transit.scheduling.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import com.dtc.transit.scheduling.engine.assignment.BusAssigner;
import com.dtc.transit.scheduling.engine.assignment.EvenKilometreBusAssigner;
import com.dtc.transit.scheduling.engine.constraint.VehicleScheduleValidator;
import com.dtc.transit.scheduling.engine.duty.LinkedDutyBuilder;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.HandoverPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.ScheduleMetrics;
import com.dtc.transit.scheduling.engine.model.ScheduleResult;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.engine.model.Severity;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.relief.ReliefOpportunityFinder;
import com.dtc.transit.scheduling.engine.vehicle.BlockBuilder;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;
import com.dtc.transit.scheduling.engine.vehicle.MinFleetMatchingBlockBuilder;

/**
 * Runs the pipeline for one depot-day.
 *
 * <p>Plain Java: no Spring, no JPA, no clock, no logging framework. That is what lets the whole scheduling
 * algorithm be unit-tested in milliseconds against hand-built fixtures, and it is checked mechanically by an
 * architecture test rather than left to good intentions.
 *
 * <p>Single-threaded per run. Depot-days are processed concurrently by the worker above; splitting one depot's
 * build across threads would buy little and cost the deterministic ordering the whole design relies on.
 *
 * <p>Stage 3a cuts blocks into duties in linked mode, where a crew stays with one bus. Unlinked duty building
 * and named crew arrive in Phases 8 and 9; the result already carries their shapes, so the persister and the API
 * do not change when they do.
 */
public class SchedulingEngine {

    private final BlockBuilder blockBuilder;
    private final BusAssigner busAssigner;
    private final ReliefOpportunityFinder reliefFinder;
    private final VehicleScheduleValidator validator;
    private final MinFleetMatchingBlockBuilder lowerBoundBuilder;
    private final LinkedDutyBuilder linkedDutyBuilder;

    /** The default pipeline: greedy blocks, even-kilometre assignment. */
    public SchedulingEngine() {
        this(new GreedyBestFitBlockBuilder(), new EvenKilometreBusAssigner());
    }

    public SchedulingEngine(BlockBuilder blockBuilder, BusAssigner busAssigner) {
        this.blockBuilder = blockBuilder;
        this.busAssigner = busAssigner;
        this.reliefFinder = new ReliefOpportunityFinder();
        this.validator = new VehicleScheduleValidator();
        this.lowerBoundBuilder = new MinFleetMatchingBlockBuilder();
        this.linkedDutyBuilder = new LinkedDutyBuilder();
    }

    public ScheduleResult run(List<TripView> trips, DepotContext context, RuleSet rules) {
        return run(trips, context, rules, SchedulingMode.LINKED, true, progress -> {});
    }

    /**
     * Runs every stage.
     *
     * @param computeLowerBound whether to compute the optimal fleet size for comparison. Worth it on a normal
     *     run and worth skipping in a tight loop, since it is a second matching over the same trips.
     * @param onProgress called with a percentage, so the worker can report progress without the engine knowing
     *     what a run or a database is
     */
    public ScheduleResult run(
            List<TripView> trips,
            DepotContext context,
            RuleSet rules,
            SchedulingMode mode,
            boolean computeLowerBound,
            IntConsumer onProgress) {

        long startedAt = System.nanoTime();

        onProgress.accept(10);
        VehicleSchedule blocks = blockBuilder.build(trips, context, rules);

        onProgress.accept(45);
        Integer lowerBound = blocks.minFleetLowerBound();
        if (computeLowerBound && lowerBound == null) {
            lowerBound = lowerBoundBuilder.minimumFleet(trips, context, rules);
        }
        blocks = blocks.withMinFleetLowerBound(lowerBound);

        onProgress.accept(60);
        blocks = reliefFinder.mark(blocks, context, rules);

        onProgress.accept(70);
        // Stage 3a. Unlinked mode falls through to linked for now: Phase 8 adds the piece cutter and the
        // unlinked builder, and until it does, producing linked duties is better than producing none.
        LinkedDutyBuilder.Result dutyResult = linkedDutyBuilder.build(blocks, context, rules);
        List<DutyPlan> duties = dutyResult.duties();
        List<HandoverPlan> handovers = dutyResult.handovers();

        onProgress.accept(80);
        BusAssigner.Result assignment = busAssigner.assign(blocks, context, rules);

        onProgress.accept(90);
        List<EngineConflict> conflicts = new ArrayList<>(blocks.conflicts());
        conflicts.addAll(assignment.conflicts());
        conflicts.addAll(dutyResult.conflicts());
        // Soft findings recorded against individual duties are lifted to the schedule's conflict list, so a
        // planner sees them without having to open every duty.
        duties.forEach(duty -> conflicts.addAll(duty.conflicts()));
        // The independent re-check runs last and from scratch. Its findings are merged rather than replacing
        // the builder's, because the two look at different things: the builder knows why it gave up on a trip,
        // the validator knows whether the result holds together.
        conflicts.addAll(validator.validate(blocks, assignment.assignments(), trips, context, rules));
        if (lowerBound != null && blocks.busesRequired() > lowerBound) {
            conflicts.add(EngineConflict.soft(
                    ConflictTypes.FLEET_ABOVE_LOWER_BOUND,
                    "This schedule uses %d buses; %d is the optimum under the model"
                            .formatted(blocks.busesRequired(), lowerBound),
                    List.of()));
        }
        List<EngineConflict> deduplicated = deduplicate(conflicts);

        onProgress.accept(100);
        ScheduleMetrics metrics = metricsFor(
                trips,
                blocks,
                assignment,
                duties,
                handovers,
                deduplicated,
                (System.nanoTime() - startedAt) / 1_000_000);

        return new ScheduleResult(
                blocks, assignment.assignments(), duties, handovers, deduplicated, metrics);
    }

    /**
     * Collapses conflicts the builder and the validator both found.
     *
     * <p>Both passes legitimately report the same uncovered trip, and showing a scheduler the same problem
     * twice makes them doubt the list. Keyed on type, severity and message, so two genuinely different
     * problems of the same type survive.
     */
    private static List<EngineConflict> deduplicate(List<EngineConflict> conflicts) {
        java.util.LinkedHashMap<String, EngineConflict> unique = new java.util.LinkedHashMap<>();
        for (EngineConflict conflict : conflicts) {
            unique.putIfAbsent(conflict.type() + '|' + conflict.severity() + '|' + conflict.message(), conflict);
        }
        return List.copyOf(unique.values());
    }

    private ScheduleMetrics metricsFor(
            List<TripView> trips,
            VehicleSchedule blocks,
            BusAssigner.Result assignment,
            List<DutyPlan> duties,
            List<HandoverPlan> handovers,
            List<EngineConflict> conflicts,
            long elapsedMillis) {

        return new ScheduleMetrics(
                trips.size(),
                blocks.coveredTripCount(),
                blocks.uncovered().size(),
                blocks.busesRequired(),
                blocks.minFleetLowerBound(),
                (int) assignment.assignments().stream()
                        .map(plan -> plan.busId())
                        .distinct()
                        .count(),
                assignment.unassignedBlockNos().size(),
                duties.size(),
                handovers.size(),
                round(blocks.serviceKm()),
                round(blocks.deadKm()),
                blocks.deadKmRatio(),
                (int) conflicts.stream()
                        .filter(conflict -> conflict.severity() == Severity.HARD)
                        .count(),
                (int) conflicts.stream()
                        .filter(conflict -> conflict.severity() == Severity.SOFT)
                        .count(),
                elapsedMillis);
    }

    private static double round(double kilometres) {
        return Math.round(kilometres * 100.0) / 100.0;
    }
}
