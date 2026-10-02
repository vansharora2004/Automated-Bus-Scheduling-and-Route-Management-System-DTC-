package com.dtc.transit.scheduling.engine.vehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.UncoveredTrip;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/**
 * The default block builder: take each trip in time order and give it to the block that fits it best.
 *
 * <p>Best fit means the smallest slack, which keeps idle time small and so tends to produce fewer, busier
 * blocks. First fit would be marginally faster and noticeably worse, because it scatters trips across blocks
 * that then each carry a long idle gap.
 *
 * <p>Complexity is O(T × B): for every trip, every open block is tested. At roughly 1,500 trips and 150
 * blocks per depot-day that is a few hundred thousand integer comparisons, which is well under a second.
 *
 * <p><strong>Electric range.</strong> Buses are assigned after blocks are built, so the builder cannot know
 * which block will get an electric bus. It therefore caps a block's distance only when every bus in the
 * depot is electric, in which case the cap is the largest usable range available. A mixed depot has no cap
 * here, and {@link com.dtc.transit.scheduling.engine.assignment.BusAssigner} enforces the per-bus range when
 * it hands a block to an actual vehicle. Capping every block at the smallest EV range instead would shorten
 * blocks that a diesel bus could have run perfectly well.
 */
public class GreedyBestFitBlockBuilder implements BlockBuilder {

    @Override
    public String name() {
        return "GREEDY_BEST_FIT";
    }

    @Override
    public VehicleSchedule build(List<TripView> trips, DepotContext context, RuleSet rules) {
        // Sorted by start time then id. The id tiebreak is what makes two runs over the same input produce
        // the same blocks; without it the result would depend on the order the database happened to return.
        List<TripView> ordered = trips.stream()
                .sorted(Comparator.comparingInt(TripView::startSec).thenComparingLong(TripView::id))
                .toList();

        List<String> fieldable = List.copyOf(context.availableVehicleClasses());
        double usableMetres = depotRangeCapMetres(context, rules);

        List<BlockDraft> open = new ArrayList<>();
        List<UncoveredTrip> uncovered = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        for (TripView trip : ordered) {
            if (trip.requiredVehicleClass() != null && !fieldable.contains(trip.requiredVehicleClass())) {
                // No amount of rescheduling fixes a class the depot does not own, so this is reported once
                // against the trip rather than attempted and failed per block.
                uncovered.add(new UncoveredTrip(trip.id(), UncoveredTrip.VEHICLE_CLASS_UNSATISFIABLE));
                conflicts.add(EngineConflict.hard(
                        ConflictTypes.UNCOVERED_TRIP,
                        "Trip %d needs a %s bus and depot %d has none"
                                .formatted(trip.id(), trip.requiredVehicleClass(), context.depotId()),
                        List.of(new EntityRef("TRIP", trip.id()))));
                continue;
            }

            BlockDraft best = null;
            BlockDraft.Fit bestFit = null;
            boolean rejectedForRange = false;
            boolean rejectedForDuration = false;

            for (BlockDraft draft : open) {
                var fit = draft.fitFor(trip);
                if (fit.isEmpty()) {
                    // Separated so the uncovered reason can say which limit bound, rather than the
                    // uninformative "no block was feasible".
                    if (draft.wouldExceedDuration(trip, rules)) {
                        rejectedForDuration = true;
                    }
                    continue;
                }
                if (!draft.withinMetres(trip, usableMetres)) {
                    rejectedForRange = true;
                    continue;
                }
                // Strictly cheaper, so the first of several equally good blocks wins. The open list is built in
                // a stable order, which is what makes two runs over the same input produce the same blocks.
                if (bestFit == null || BlockDraft.Fit.CHEAPEST.compare(fit.get(), bestFit) < 0) {
                    bestFit = fit.get();
                    best = draft;
                }
            }

            if (best != null) {
                best.append(trip, allElectric(context));
                continue;
            }

            // No open block fits, so this trip needs a bus of its own. There is always a bus in principle;
            // whether the depot owns enough of them is the assigner's finding, not the builder's.
            if (open.size() < maxBlocks(context)) {
                open.add(new BlockDraft(trip, context, rules));
            } else {
                String reason = rejectedForDuration
                        ? UncoveredTrip.BLOCK_DURATION_EXCEEDED
                        : (rejectedForRange ? "Every candidate block would exceed the usable electric range"
                                : UncoveredTrip.NO_VEHICLE_AVAILABLE);
                uncovered.add(new UncoveredTrip(trip.id(), reason));
                conflicts.add(EngineConflict.hard(
                        ConflictTypes.UNCOVERED_TRIP,
                        "Trip %d could not be covered: %s".formatted(trip.id(), reason),
                        List.of(new EntityRef("TRIP", trip.id()))));
            }
        }

        // Blocks numbered by pull-out time, so block 1 is the first bus out of the gate. Numbering by
        // creation order would be almost the same and occasionally confusing.
        List<BlockDraft> sealedOrder = open.stream()
                .sorted(Comparator.comparingInt(BlockDraft::pullOutSec).thenComparingInt(BlockDraft::endSec))
                .toList();
        List<Block> blocks = new ArrayList<>(sealedOrder.size());
        for (int i = 0; i < sealedOrder.size(); i++) {
            blocks.add(sealedOrder.get(i).seal(i + 1));
        }

        return VehicleSchedule.of(ordered, blocks, uncovered, conflicts, null);
    }

    /**
     * The number of blocks the builder will open.
     *
     * <p>Bounded by the depot's fleet. Opening a 400th block for a depot with 120 buses would produce a
     * schedule that cannot be staffed with vehicles, and the uncovered list with a reason is more useful than
     * a plan nobody can run.
     */
    private static int maxBlocks(DepotContext context) {
        // The snapshot loader only includes buses the depot can actually field, so the fleet size is the
        // bound. A bus unavailable for part of the day still counts: it can take a block that fits around
        // the gap, and the assigner is what decides whether it does.
        return context.buses().size();
    }

    private static boolean allElectric(DepotContext context) {
        return !context.buses().isEmpty() && context.buses().stream().allMatch(BusView::isElectric);
    }

    /**
     * The distance cap to apply while building, in metres.
     *
     * <p>Unbounded unless the whole depot is electric. See the class comment for why a mixed depot is not
     * capped here.
     */
    private static double depotRangeCapMetres(DepotContext context, RuleSet rules) {
        if (!allElectric(context)) {
            return Double.MAX_VALUE;
        }
        return context.buses().stream()
                .filter(bus -> bus.evRangeKm() != null)
                .mapToDouble(bus -> rules.usableRangeMetres(bus.evRangeKm()))
                .max()
                .orElse(Double.MAX_VALUE);
    }
}
