package com.dtc.transit.scheduling.engine.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The output of stage 1: every trip either in a block or on the uncovered list.
 *
 * @param minFleetLowerBound the optimal peak vehicle requirement under the model, when it was computed;
 *     null when the lower bound was not asked for. Reported next to the block count so the greedy
 *     builder's cost is visible rather than assumed acceptable.
 */
public record VehicleSchedule(
        List<Block> blocks,
        List<UncoveredTrip> uncovered,
        List<EngineConflict> conflicts,
        Integer minFleetLowerBound) {

    public VehicleSchedule {
        blocks = List.copyOf(blocks);
        uncovered = List.copyOf(uncovered);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * Builds a schedule and checks it accounts for every trip exactly once.
     *
     * <p>This is the Phase 6 coverage invariant, enforced where the result is made rather than only in a
     * test. A trip missing from both lists is a bus that never turns up and nobody notices; a trip in two
     * blocks is two buses dispatched for one journey. Both are bugs in the builder, so they fail loudly
     * here instead of reaching a depot.
     *
     * @throws IllegalStateException when a trip is lost or duplicated
     */
    public static VehicleSchedule of(
            List<TripView> inputTrips,
            List<Block> blocks,
            List<UncoveredTrip> uncovered,
            List<EngineConflict> conflicts,
            Integer minFleetLowerBound) {

        Set<Long> seen = new HashSet<>();
        List<Long> duplicated = new ArrayList<>();
        for (Block block : blocks) {
            for (Long tripId : block.tripIds()) {
                if (!seen.add(tripId)) {
                    duplicated.add(tripId);
                }
            }
        }
        for (UncoveredTrip trip : uncovered) {
            if (!seen.add(trip.tripId())) {
                duplicated.add(trip.tripId());
            }
        }
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("trips accounted for more than once: " + duplicated.stream()
                    .sorted()
                    .distinct()
                    .toList());
        }

        List<Long> lost = inputTrips.stream()
                .map(TripView::id)
                .filter(id -> !seen.contains(id))
                .sorted()
                .toList();
        if (!lost.isEmpty()) {
            throw new IllegalStateException("trips in neither a block nor the uncovered list: " + lost);
        }

        return new VehicleSchedule(blocks, uncovered, conflicts, minFleetLowerBound);
    }

    public int busesRequired() {
        return blocks.size();
    }

    public int coveredTripCount() {
        return blocks.stream().mapToInt(block -> block.tripIds().size()).sum();
    }

    public double serviceKm() {
        return blocks.stream().mapToDouble(Block::serviceKm).sum();
    }

    public double deadKm() {
        return blocks.stream().mapToDouble(Block::deadKm).sum();
    }

    /**
     * Dead running as a share of all running.
     *
     * <p>The headline efficiency number for stage 1. Zero when nothing runs, rather than a division by
     * zero, because an empty depot-day is a legitimate result on a date with no service.
     */
    public double deadKmRatio() {
        double total = serviceKm() + deadKm();
        return total == 0 ? 0 : deadKm() / total;
    }

    /** A copy carrying marked relief opportunities, produced by the relief finder. */
    public VehicleSchedule withBlocks(List<Block> replacement) {
        return new VehicleSchedule(replacement, uncovered, conflicts, minFleetLowerBound);
    }

    public VehicleSchedule withConflicts(List<EngineConflict> replacement) {
        return new VehicleSchedule(blocks, uncovered, replacement, minFleetLowerBound);
    }

    public VehicleSchedule withMinFleetLowerBound(Integer bound) {
        return new VehicleSchedule(blocks, uncovered, conflicts, bound);
    }
}
