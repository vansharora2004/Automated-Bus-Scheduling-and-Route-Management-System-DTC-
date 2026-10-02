package com.dtc.transit.scheduling.engine.assignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TimeWindow;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * Assigns buses to blocks, spreading kilometres as evenly as the constraints allow.
 *
 * <p>Even distribution is a maintenance concern rather than an aesthetic one. Letting the first bus in the
 * list take every long block wears it out years before the rest of the fleet, and the depot ends up with a
 * vehicle in the workshop and a yard full of under-used ones.
 *
 * <p>Blocks are assigned longest first. A long block is the hardest to place — it needs a bus free for most
 * of the day and, if electric, enough range — so placing it while choice remains avoids the failure where
 * every remaining bus is busy exactly when the longest block needs one.
 *
 * <p>Electric range is enforced here rather than during block building, because only here is it known which
 * physical bus a block will get. A block too long for any available electric bus goes to a diesel or CNG one;
 * a block too long for every bus of any kind is left unassigned with a hard conflict, which is honest.
 */
public class EvenKilometreBusAssigner implements BusAssigner {

    @Override
    public String name() {
        return "EVEN_KILOMETRE";
    }

    @Override
    public Result assign(VehicleSchedule schedule, DepotContext context, RuleSet rules) {
        List<Block> hardestFirst = schedule.blocks().stream()
                .sorted(Comparator.comparingDouble(Block::totalKm)
                        .reversed()
                        .thenComparingInt(Block::blockNo))
                .toList();

        Map<Long, Double> kilometresSoFar = new HashMap<>();
        Map<Long, List<TimeWindow>> booked = new HashMap<>();
        context.buses().forEach(bus -> {
            kilometresSoFar.put(bus.id(), 0.0);
            booked.put(bus.id(), new ArrayList<>());
        });

        List<BusAssignmentPlan> assignments = new ArrayList<>();
        List<Integer> unassigned = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        for (Block block : hardestFirst) {
            TimeWindow window = new TimeWindow(block.pullOutSec(), block.pullInSec());

            BusView chosen = context.buses().stream()
                    .filter(bus -> isFree(bus, window, booked.get(bus.id())))
                    .filter(bus -> satisfiesClass(bus, block))
                    .filter(bus -> withinRange(bus, block, rules))
                    // Fewest kilometres first, then the lowest id. The id tiebreak is what makes two runs over
                    // the same input assign the same buses.
                    .min(Comparator.comparingDouble((BusView bus) -> kilometresSoFar.get(bus.id()))
                            .thenComparingLong(BusView::id))
                    .orElse(null);

            if (chosen == null) {
                unassigned.add(block.blockNo());
                conflicts.add(unassignableConflict(block, context, rules, window));
                continue;
            }

            assignments.add(new BusAssignmentPlan(block.blockNo(), chosen.id(), window.fromSec(), window.toSec()));
            kilometresSoFar.merge(chosen.id(), block.totalKm(), Double::sum);
            booked.get(chosen.id()).add(window);
        }

        List<BusAssignmentPlan> inBlockOrder = assignments.stream()
                .sorted(Comparator.comparingInt(BusAssignmentPlan::blockNo))
                .toList();
        return new Result(inBlockOrder, unassigned.stream().sorted().toList(), conflicts);
    }

    /**
     * Explains why no bus could take a block.
     *
     * <p>Three different causes with three different fixes: buy a bus, retime the block, or wait for the
     * workshop. Reporting them as one "unassigned" would send a scheduler guessing.
     */
    private EngineConflict unassignableConflict(
            Block block, DepotContext context, RuleSet rules, TimeWindow window) {
        boolean anyOfClass = context.buses().stream().anyMatch(bus -> satisfiesClass(bus, block));
        if (!anyOfClass) {
            return EngineConflict.hard(
                    ConflictTypes.UNASSIGNED_BLOCK,
                    "Block %d needs a %s bus and depot %d has none"
                            .formatted(block.blockNo(), block.vehicleClass(), context.depotId()),
                    List.of());
        }

        boolean anyInRange = context.buses().stream()
                .filter(bus -> satisfiesClass(bus, block))
                .anyMatch(bus -> withinRange(bus, block, rules));
        if (!anyInRange) {
            return EngineConflict.hard(
                    ConflictTypes.EV_RANGE_EXCEEDED,
                    "Block %d covers %.1f km, beyond the usable range of every bus that could run it"
                            .formatted(block.blockNo(), block.totalKm()),
                    List.of());
        }

        return EngineConflict.hard(
                ConflictTypes.BUS_UNAVAILABLE,
                "Block %d runs %s to %s and every suitable bus is already booked or in the workshop then"
                        .formatted(
                                block.blockNo(),
                                ServiceTime.format(window.fromSec()),
                                ServiceTime.format(window.toSec())),
                List.of());
    }

    /** Free means not in the workshop and not already on another block. */
    private static boolean isFree(BusView bus, TimeWindow window, List<TimeWindow> alreadyBooked) {
        return bus.isAvailableFor(window) && alreadyBooked.stream().noneMatch(window::overlaps);
    }

    private static boolean satisfiesClass(BusView bus, Block block) {
        return block.vehicleClass() == null || block.vehicleClass().equals(bus.busType());
    }

    /**
     * Whether a bus can cover a block's distance.
     *
     * <p>Only electric buses are limited. A CNG or diesel bus refuels in minutes at any point in the day, so
     * modelling its range would add a constraint that never binds and would have to be tuned anyway.
     *
     * <p>A block with a charging event is credited with one charge, which is what the depot park was inserted
     * for. Crediting a full battery would assume the bus always reaches a free bay.
     */
    private static boolean withinRange(BusView bus, Block block, RuleSet rules) {
        if (!bus.isElectric() || bus.evRangeKm() == null) {
            return true;
        }
        double usable = rules.usableRangeMetres(bus.evRangeKm());
        boolean charges = block.events().stream()
                .anyMatch(event -> event.type() == BlockEventType.CHARGING);
        double allowance = charges ? usable * 2 : usable;
        return block.totalKm() * 1000.0 <= allowance;
    }
}
