package com.dtc.transit.scheduling;

import static com.dtc.transit.support.SchedulingFixtures.STOP_A;
import static com.dtc.transit.support.SchedulingFixtures.STOP_B;
import static com.dtc.transit.support.SchedulingFixtures.depot;
import static com.dtc.transit.support.SchedulingFixtures.standardFleet;
import static com.dtc.transit.support.SchedulingFixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dtc.transit.scheduling.engine.assignment.BusAssigner;
import com.dtc.transit.scheduling.engine.assignment.EvenKilometreBusAssigner;
import com.dtc.transit.scheduling.engine.model.BusAssignmentPlan;
import com.dtc.transit.scheduling.engine.model.BusView;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;
import com.dtc.transit.support.SchedulingFixtures;

/** Putting physical buses on blocks: availability, electric range and an even spread of wear. */
class BusAssignmentTest {

    private static final int SIX_AM = 6 * 3600;

    private final GreedyBestFitBlockBuilder builder = new GreedyBestFitBlockBuilder();
    private final EvenKilometreBusAssigner assigner = new EvenKilometreBusAssigner();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("each block gets its own bus when the blocks overlap")
    void overlappingBlocksGetDifferentBuses() {
        DepotContext context = depot(standardFleet(3));
        VehicleSchedule schedule = builder.build(threeSimultaneousTrips(), context, rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        assertThat(result.assignments()).hasSize(3);
        assertThat(result.assignments().stream().map(BusAssignmentPlan::busId).distinct())
                .hasSize(3);
        assertThat(result.unassignedBlockNos()).isEmpty();
    }

    @Test
    @DisplayName("a bus in the workshop is not given a block that overlaps the repair")
    void workshopWindowsAreRespected() {
        // Bus 1 is unavailable all morning; bus 2 is free. Only one block exists, so bus 2 must take it.
        DepotContext context = depot(List.of(
                SchedulingFixtures.busUnavailable(1, 0, 12 * 3600), SchedulingFixtures.bus(2, "STANDARD")));
        VehicleSchedule schedule =
                builder.build(List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)), context, rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        assertThat(result.assignments()).singleElement().extracting(BusAssignmentPlan::busId).isEqualTo(2L);
    }

    @Test
    @DisplayName("a block nobody is free for is left unassigned, saying why")
    void noFreeBusIsReportedNotHidden() {
        DepotContext context = depot(List.of(SchedulingFixtures.busUnavailable(1, 0, 12 * 3600)));
        VehicleSchedule schedule =
                builder.build(List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)), context, rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        // Assigning it anyway would produce a schedule the depot cannot run and would discover at 05:45.
        assertThat(result.assignments()).isEmpty();
        assertThat(result.unassignedBlockNos()).containsExactly(1);
        assertThat(result.conflicts())
                .singleElement()
                .satisfies(conflict -> assertThat(conflict.type()).isEqualTo(ConflictTypes.BUS_UNAVAILABLE));
    }

    @Test
    @DisplayName("kilometres are spread across the fleet rather than piled on the first bus")
    void kilometresAreSpread() {
        // Three separate single-trip blocks at different times of day. One bus could physically run all three;
        // spreading them is a maintenance decision, not a feasibility one.
        DepotContext context = depot(standardFleet(3));
        VehicleSchedule schedule = builder.build(
                List.of(
                        trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                        trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                        trip(3, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)),
                context,
                rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        Map<Long, Long> blocksPerBus = result.assignments().stream()
                .collect(Collectors.groupingBy(BusAssignmentPlan::busId, Collectors.counting()));
        // Letting one bus take every long block wears it out years before the rest of the fleet.
        assertThat(blocksPerBus.values()).allMatch(count -> count == 1L);
    }

    @Test
    @DisplayName("an electric bus is not given a block beyond its usable range")
    void electricRangeIsEnforced() {
        // The block covers 40 km of service plus 12 km of dead running. A 40 km battery with a 15% reserve has
        // 34 km usable, so this electric bus cannot run it, but the CNG bus can.
        DepotContext context =
                depot(List.of(SchedulingFixtures.electricBus(1, 40), SchedulingFixtures.bus(2, "STANDARD")));
        VehicleSchedule schedule = builder.build(
                List.of(SchedulingFixtures.tripOfLength(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600, 40_000)),
                context,
                rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        assertThat(result.assignments()).singleElement().extracting(BusAssignmentPlan::busId).isEqualTo(2L);
    }

    @Test
    @DisplayName("a block beyond every electric bus's range reports the range, not a vague failure")
    void unreachableRangeIsNamed() {
        DepotContext context = depot(List.of(SchedulingFixtures.electricBus(1, 40)));
        VehicleSchedule schedule = builder.build(
                List.of(SchedulingFixtures.tripOfLength(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600, 200_000)),
                context,
                rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        assertThat(result.conflicts())
                .singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.type()).isEqualTo(ConflictTypes.EV_RANGE_EXCEEDED);
                    // "Buy a longer-range bus" and "wait for the workshop" are different actions, so the
                    // message has to distinguish them.
                    assertThat(conflict.message()).contains("usable range");
                });
    }

    @Test
    @DisplayName("one bus takes two blocks when they do not overlap")
    void busIsReusedAfterItsBlockEnds() {
        // Blocks are built with a two-bus depot and then assigned against a one-bus depot. The assigner is the
        // unit under test here, and the builder will not hand it two disjoint blocks otherwise: it bounds the
        // number of blocks by the fleet, and merges a long idle gap into a single block with depot parking.
        // A short maximum block duration is what forces the split instead.
        RuleSet shortBlocks = RuleSet.defaults().toBuilder().maxBlockDurationMin(120).build();
        VehicleSchedule schedule = builder.build(
                List.of(
                        trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                        trip(2, STOP_A, STOP_B, SIX_AM + 28_800, SIX_AM + 32_400)),
                depot(standardFleet(2)),
                shortBlocks);
        assertThat(schedule.blocks()).hasSize(2);

        BusAssigner.Result result = assigner.assign(schedule, depot(standardFleet(1)), shortBlocks);

        // A bus finishing at 07:15 is free again long before 13:45, so holding a second vehicle back for the
        // afternoon block would waste one for the whole day.
        assertThat(result.assignments()).hasSize(2);
        assertThat(result.assignments().stream().map(BusAssignmentPlan::busId).distinct())
                .containsExactly(1L);
        assertThat(result.unassignedBlockNos()).isEmpty();
    }

    @Test
    @DisplayName("the same input assigns the same buses every time")
    void assignmentIsDeterministic() {
        DepotContext context = depot(standardFleet(5));
        VehicleSchedule schedule = builder.build(threeSimultaneousTrips(), context, rules);

        String first = describe(assigner.assign(schedule, context, rules));
        String second = describe(assigner.assign(schedule, context, rules));

        assertThat(second).isEqualTo(first);
    }

    private static List<TripView> threeSimultaneousTrips() {
        return List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(3, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));
    }

    private static String describe(BusAssigner.Result result) {
        return result.assignments().stream()
                .map(plan -> plan.blockNo() + "->" + plan.busId())
                .collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("a bus of the wrong class is never given a block that needs a different one")
    void vehicleClassIsRespected() {
        DepotContext context = depot(List.of(
                SchedulingFixtures.bus(1, "STANDARD"), new BusView(2, "A-2", "ARTICULATED", "CNG", null, 65, List.of())));
        VehicleSchedule schedule = builder.build(
                List.of(SchedulingFixtures.tripNeeding(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600, "ARTICULATED")),
                context,
                rules);

        BusAssigner.Result result = assigner.assign(schedule, context, rules);

        assertThat(result.assignments()).singleElement().extracting(BusAssignmentPlan::busId).isEqualTo(2L);
    }
}
