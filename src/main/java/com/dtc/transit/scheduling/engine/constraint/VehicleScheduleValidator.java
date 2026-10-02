package com.dtc.transit.scheduling.engine.constraint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TimeWindow;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * Re-checks a finished vehicle schedule from scratch.
 *
 * <p>Deliberately independent of the builder. The builder's own feasibility test and this validator could
 * both be wrong, but they are unlikely to be wrong in the same way, and a schedule that only the code that
 * produced it believes in is not validated at all. So this walks the finished events and re-derives every
 * invariant: coverage, layovers, chronology, bus availability, double-booking and range.
 *
 * <p>It reports rather than throws. A scheduler needs the full list of what is wrong, not the first thing the
 * validator happened to notice.
 */
public class VehicleScheduleValidator {

    public List<EngineConflict> validate(
            VehicleSchedule schedule,
            List<BusAssignmentPlan> assignments,
            List<TripView> inputTrips,
            DepotContext context,
            RuleSet rules) {

        List<EngineConflict> found = new ArrayList<>();
        Map<Long, TripView> tripsById = new HashMap<>();
        inputTrips.forEach(trip -> tripsById.put(trip.id(), trip));

        checkCoverage(schedule, inputTrips, found);
        schedule.blocks().forEach(block -> checkBlockInternals(block, tripsById, context, rules, found));
        checkAssignments(schedule, assignments, context, rules, found);
        checkEstimatedDeadheads(schedule, context, found);
        return found;
    }

    /** Every input trip in exactly one block, or on the uncovered list with a reason. */
    private void checkCoverage(VehicleSchedule schedule, List<TripView> inputTrips, List<EngineConflict> found) {
        Set<Long> covered = new HashSet<>();
        List<Long> duplicated = new ArrayList<>();
        for (Block block : schedule.blocks()) {
            for (Long tripId : block.tripIds()) {
                if (!covered.add(tripId)) {
                    duplicated.add(tripId);
                }
            }
        }
        duplicated.stream()
                .distinct()
                .sorted()
                .forEach(tripId -> found.add(EngineConflict.hard(
                        ConflictTypes.UNCOVERED_TRIP,
                        "Trip %d appears in more than one block, so two buses would be dispatched for it"
                                .formatted(tripId),
                        List.of(new EntityRef("TRIP", tripId)))));

        Set<Long> accountedFor = new HashSet<>(covered);
        schedule.uncovered().forEach(trip -> accountedFor.add(trip.tripId()));
        inputTrips.stream()
                .map(TripView::id)
                .filter(id -> !accountedFor.contains(id))
                .sorted()
                .forEach(id -> found.add(EngineConflict.hard(
                        ConflictTypes.UNCOVERED_TRIP,
                        "Trip %d is in no block and not reported as uncovered".formatted(id),
                        List.of(new EntityRef("TRIP", id)))));
    }

    /**
     * Chronology, layovers and continuity inside one block.
     *
     * <p>The continuity check is the one that catches the subtle bugs: a block whose events are each
     * individually fine but whose stops do not join up describes a bus that teleports.
     */
    private void checkBlockInternals(
            Block block,
            Map<Long, TripView> tripsById,
            DepotContext context,
            RuleSet rules,
            List<EngineConflict> found) {

        List<BlockEvent> events = block.events();
        BlockEvent previousTrip = null;

        for (int i = 0; i < events.size(); i++) {
            BlockEvent event = events.get(i);

            if (i > 0 && event.startSec() < events.get(i - 1).endSec()) {
                found.add(EngineConflict.hard(
                        ConflictTypes.BUS_DOUBLE_BOOKED,
                        "Block %d has overlapping events at %s: the bus would be in two places at once"
                                .formatted(block.blockNo(), ServiceTime.format(event.startSec())),
                        List.of()));
            }

            if (event.type() != BlockEventType.TRIP) {
                continue;
            }
            TripView trip = tripsById.get(event.tripId());
            if (trip == null) {
                // A trip id in the schedule that was never in the input means the snapshot and the result
                // disagree, which no amount of rescheduling fixes.
                found.add(EngineConflict.hard(
                        ConflictTypes.UNCOVERED_TRIP,
                        "Block %d contains trip %d, which was not in the run's input"
                                .formatted(block.blockNo(), event.tripId()),
                        List.of(new EntityRef("TRIP", event.tripId()))));
                continue;
            }

            if (previousTrip != null) {
                int required = rules.minLayoverSecAfter(
                        tripsById.get(previousTrip.tripId()).durationSec());
                int deadhead = previousTrip.toStopId().equals(event.fromStopId())
                        ? 0
                        : context.travelTimes()
                                .betweenStops(previousTrip.toStopId(), event.fromStopId(), previousTrip.endSec());
                int available = event.startSec() - previousTrip.endSec();
                if (available < required + deadhead) {
                    found.add(EngineConflict.hard(
                            ConflictTypes.BUS_DOUBLE_BOOKED,
                            ("Block %d allows %d s between trips %d and %d, but needs %d s of layover plus "
                                            + "%d s of dead running")
                                    .formatted(
                                            block.blockNo(),
                                            available,
                                            previousTrip.tripId(),
                                            event.tripId(),
                                            required,
                                            deadhead),
                            List.of(new EntityRef("TRIP", event.tripId()))));
                }
            }
            previousTrip = event;
        }

        if (block.durationSec() > rules.maxBlockDurationSec()) {
            found.add(EngineConflict.hard(
                    ConflictTypes.BUS_DOUBLE_BOOKED,
                    "Block %d runs %d min, beyond the %d min maximum"
                            .formatted(block.blockNo(), block.durationSec() / 60, rules.maxBlockDurationMin()),
                    List.of()));
        }
    }

    /** Availability, double-booking and electric range, checked against the assignments actually made. */
    private void checkAssignments(
            VehicleSchedule schedule,
            List<BusAssignmentPlan> assignments,
            DepotContext context,
            RuleSet rules,
            List<EngineConflict> found) {

        Map<Integer, Block> blocksByNo = new HashMap<>();
        schedule.blocks().forEach(block -> blocksByNo.put(block.blockNo(), block));
        Map<Long, BusView> busesById = new HashMap<>();
        context.buses().forEach(bus -> busesById.put(bus.id(), bus));

        Map<Long, List<BusAssignmentPlan>> byBus = new HashMap<>();
        assignments.forEach(plan -> byBus.computeIfAbsent(plan.busId(), key -> new ArrayList<>())
                .add(plan));

        byBus.keySet().stream().sorted().forEach(busId -> {
            List<BusAssignmentPlan> forBus = byBus.get(busId).stream()
                    .sorted(Comparator.comparingInt(BusAssignmentPlan::fromSec))
                    .toList();
            BusView bus = busesById.get(busId);

            for (int i = 0; i < forBus.size(); i++) {
                BusAssignmentPlan plan = forBus.get(i);
                TimeWindow window = new TimeWindow(plan.fromSec(), plan.toSec());

                if (i > 0 && forBus.get(i - 1).toSec() > plan.fromSec()) {
                    found.add(EngineConflict.hard(
                            ConflictTypes.BUS_DOUBLE_BOOKED,
                            "Bus %d is assigned to blocks %d and %d, which overlap"
                                    .formatted(busId, forBus.get(i - 1).blockNo(), plan.blockNo()),
                            List.of(new EntityRef("BUS", busId))));
                }

                if (bus == null) {
                    found.add(EngineConflict.hard(
                            ConflictTypes.BUS_UNAVAILABLE,
                            "Block %d is assigned to bus %d, which is not in the depot's fleet"
                                    .formatted(plan.blockNo(), busId),
                            List.of(new EntityRef("BUS", busId))));
                    continue;
                }

                if (!bus.isAvailableFor(window)) {
                    found.add(EngineConflict.hard(
                            ConflictTypes.BUS_UNAVAILABLE,
                            "Bus %s is in the workshop during block %d (%s to %s)"
                                    .formatted(
                                            bus.fleetNo(),
                                            plan.blockNo(),
                                            ServiceTime.format(plan.fromSec()),
                                            ServiceTime.format(plan.toSec())),
                            List.of(new EntityRef("BUS", busId))));
                }

                Block block = blocksByNo.get(plan.blockNo());
                if (block != null && bus.isElectric() && bus.evRangeKm() != null) {
                    boolean charges = block.events().stream()
                            .anyMatch(event -> event.type() == BlockEventType.CHARGING);
                    double allowance = rules.usableRangeMetres(bus.evRangeKm()) * (charges ? 2 : 1);
                    if (block.totalKm() * 1000.0 > allowance) {
                        found.add(EngineConflict.hard(
                                ConflictTypes.EV_RANGE_EXCEEDED,
                                "Block %d covers %.1f km on electric bus %s, whose usable range is %.1f km"
                                        .formatted(
                                                plan.blockNo(),
                                                block.totalKm(),
                                                bus.fleetNo(),
                                                allowance / 1000.0),
                                List.of(new EntityRef("BUS", busId))));
                    }
                }
            }
        });

        Set<Integer> assignedBlocks = new HashSet<>();
        assignments.forEach(plan -> assignedBlocks.add(plan.blockNo()));
        schedule.blocks().stream()
                .map(Block::blockNo)
                .filter(blockNo -> !assignedBlocks.contains(blockNo))
                .sorted()
                .forEach(blockNo -> found.add(EngineConflict.hard(
                        ConflictTypes.UNASSIGNED_BLOCK,
                        "Block %d has no bus".formatted(blockNo),
                        List.of())));
    }

    /**
     * Warns when a block's dead running rests on estimated rather than surveyed travel times.
     *
     * <p>Soft, because an estimate is not an error. But a block whose feasibility depends on one may not be
     * drivable in practice, and the depot deserves to know which ones to watch.
     */
    private void checkEstimatedDeadheads(
            VehicleSchedule schedule, DepotContext context, List<EngineConflict> found) {
        for (Block block : schedule.blocks()) {
            boolean anyEstimated = block.events().stream()
                    .filter(event -> event.type() == BlockEventType.DEADHEAD)
                    .filter(event -> event.fromStopId() != null && event.toStopId() != null)
                    .anyMatch(event ->
                            context.travelTimes().isEstimated(event.fromStopId(), event.toStopId()));
            if (anyEstimated) {
                found.add(EngineConflict.soft(
                        ConflictTypes.ESTIMATED_DEADHEAD,
                        "Block %d relies on an estimated deadhead time, so its layovers may be tighter "
                                        .formatted(block.blockNo())
                                + "in practice than they look here",
                        List.of()));
            }
        }
    }
}
