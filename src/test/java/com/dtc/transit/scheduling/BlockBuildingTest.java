package com.dtc.transit.scheduling;

import static com.dtc.transit.support.SchedulingFixtures.DEPOT_SEC;
import static com.dtc.transit.support.SchedulingFixtures.STOP_A;
import static com.dtc.transit.support.SchedulingFixtures.STOP_B;
import static com.dtc.transit.support.SchedulingFixtures.STOP_TO_STOP_SEC;
import static com.dtc.transit.support.SchedulingFixtures.depot;
import static com.dtc.transit.support.SchedulingFixtures.standardFleet;
import static com.dtc.transit.support.SchedulingFixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.UncoveredTrip;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;
import com.dtc.transit.support.SchedulingFixtures;

/**
 * The greedy best-fit block builder, against hand-computed expectations.
 *
 * <p>No Spring and no database, which is the whole point of keeping the engine framework-free: each of these
 * runs in well under a millisecond, so the algorithm can be pinned down case by case rather than tested once
 * through an integration test that takes ten seconds and says only "it produced something".
 */
class BlockBuildingTest {

    private static final int SIX_AM = 6 * 3600;

    private final GreedyBestFitBlockBuilder builder = new GreedyBestFitBlockBuilder();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("trips that can chain go on one bus")
    void chainableTripsShareABlock() {
        // Ends at 07:00 at B, next starts 08:00 at B: an hour of slack covers any layover rule.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600), trip(2, STOP_B, STOP_A, SIX_AM + 7200, SIX_AM + 10800));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).tripIds()).containsExactly(1L, 2L);
        assertThat(result.uncovered()).isEmpty();
    }

    @Test
    @DisplayName("trips that overlap in time need separate buses")
    void overlappingTripsNeedSeparateBlocks() {
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600), trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.coveredTripCount()).isEqualTo(2);
    }

    /**
     * The layover boundary, which is the rule most likely to be off by one.
     *
     * <p>The second trip departs from the same stop the first arrived at, so no dead running is needed and the
     * whole gap is available as layover. A 30-minute first trip gives a minimum layover of
     * max(5 min, 10% of 30 min) = 5 min, so 300 seconds of gap must pass and 299 must not.
     */
    @ParameterizedTest(name = "a gap of {0}s after a 30-minute trip")
    @ValueSource(ints = {300, 301, 600})
    @DisplayName("a gap at or above the minimum layover chains")
    void layoverAtOrAboveMinimumChains(int gapSec) {
        List<TripView> trips = sameStopPair(1800, gapSec);

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        assertThat(result.blocks()).hasSize(1);
    }

    @ParameterizedTest(name = "a gap of {0}s after a 30-minute trip")
    @ValueSource(ints = {0, 299})
    @DisplayName("a gap below the minimum layover does not chain")
    void layoverBelowMinimumDoesNotChain(int gapSec) {
        List<TripView> trips = sameStopPair(1800, gapSec);

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        // Two blocks, not a rejected trip: the work is still covered, it just costs another bus.
        assertThat(result.blocks()).hasSize(2);
    }

    @Test
    @DisplayName("the percentage term of the layover rule binds on a long trip")
    void percentageLayoverBindsOnLongTrips() {
        // A two-hour trip needs 10% of it, 12 minutes, which is more than the 5-minute floor. A 10-minute gap
        // is therefore not enough, although it would have been after a short trip.
        List<TripView> trips = sameStopPair(7200, 600);

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        assertThat(result.blocks()).hasSize(2);
        assertThat(builder.build(sameStopPair(7200, 720), depot(standardFleet(5)), rules).blocks())
                .hasSize(1);
    }

    @Test
    @DisplayName("dead running is inserted when the next trip starts somewhere else")
    void deadheadInsertedBetweenDifferentStops() {
        // Arrives B at 07:00, next departs A at 08:00. The bus has to get from B to A, which takes 10 minutes.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600), trip(2, STOP_A, STOP_B, SIX_AM + 7200, SIX_AM + 9000));

        Block block = builder
                .build(trips, depot(standardFleet(5)), rules)
                .blocks()
                .get(0);

        var deadheads = block.events().stream()
                .filter(event -> event.type() == BlockEventType.DEADHEAD)
                .toList();
        assertThat(deadheads).hasSize(1);
        assertThat(deadheads.get(0).fromStopId()).isEqualTo(STOP_B);
        assertThat(deadheads.get(0).toStopId()).isEqualTo(STOP_A);
        assertThat(deadheads.get(0).durationSec()).isEqualTo(STOP_TO_STOP_SEC);
    }

    @Test
    @DisplayName("a block starts with a pull-out and ends with a pull-in")
    void blockIsBookendedByDepotMovements() {
        List<TripView> trips = List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));

        Block block = builder
                .build(trips, depot(standardFleet(1)), rules)
                .blocks()
                .get(0);

        assertThat(block.events().get(0).type()).isEqualTo(BlockEventType.PULL_OUT);
        assertThat(block.events().get(block.events().size() - 1).type()).isEqualTo(BlockEventType.PULL_IN);
        // The pull-out is timed backwards from the first departure, so the bus arrives exactly on time rather
        // than leaving the depot at an arbitrary moment.
        assertThat(block.pullOutSec()).isEqualTo(SIX_AM - DEPOT_SEC);
        assertThat(block.pullInSec()).isEqualTo(SIX_AM + 3600 + DEPOT_SEC);
    }

    @Test
    @DisplayName("a long mid-day gap sends the bus back to the depot")
    void longGapBecomesADepotReturn() {
        // Four hours of idle, well past the 90-minute threshold.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_B, STOP_A, SIX_AM + 18_000, SIX_AM + 21_600));

        Block block = builder
                .build(trips, depot(standardFleet(1)), rules)
                .blocks()
                .get(0);

        // Standing at a terminal for four hours blocks a stand and gives the crew nowhere to go; the depot has
        // parking, facilities and a gate a relief crew can reach.
        assertThat(block.events().stream().map(event -> event.type()))
                .containsSubsequence(
                        BlockEventType.TRIP,
                        BlockEventType.PULL_IN,
                        BlockEventType.DEPOT_PARK,
                        BlockEventType.PULL_OUT,
                        BlockEventType.TRIP);
    }

    @Test
    @DisplayName("an all-electric depot plans a charge during the mid-day return")
    void electricDepotChargesMidDay() {
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_B, STOP_A, SIX_AM + 18_000, SIX_AM + 21_600));

        Block block = builder
                .build(trips, depot(List.of(SchedulingFixtures.electricBus(1, 250))), rules)
                .blocks()
                .get(0);

        assertThat(block.events().stream().map(event -> event.type())).contains(BlockEventType.CHARGING);
    }

    @Test
    @DisplayName("a trip needing a class the depot cannot field is reported as uncovered, with the reason")
    void unsatisfiableVehicleClassIsUncovered() {
        List<TripView> trips = List.of(
                SchedulingFixtures.tripNeeding(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600, "ARTICULATED"));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        assertThat(result.blocks()).isEmpty();
        assertThat(result.uncovered())
                .singleElement()
                .extracting(UncoveredTrip::reason)
                .isEqualTo(UncoveredTrip.VEHICLE_CLASS_UNSATISFIABLE);
        // Reported as a hard conflict too, because an uncovered trip means a bus that never turns up.
        assertThat(result.conflicts()).anyMatch(conflict -> conflict.isBlocking());
    }

    @Test
    @DisplayName("trips are never lost: more work than buses leaves the surplus on the uncovered list")
    void everyTripIsAccountedFor() {
        // Five simultaneous trips, two buses. Three cannot run and must say so.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(3, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(4, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(5, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(2)), rules);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.uncovered()).hasSize(3);
        assertThat(result.uncovered())
                .allSatisfy(uncovered ->
                        assertThat(uncovered.reason()).isEqualTo(UncoveredTrip.NO_VEHICLE_AVAILABLE));
    }

    @Test
    @DisplayName("the maximum block duration stops a block running all day and night")
    void blockDurationIsCapped() {
        RuleSet shortBlocks = RuleSet.defaults().toBuilder().maxBlockDurationMin(240).build();
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                // Would make the block run from 05:45 to past 13:00, beyond a four-hour cap.
                trip(2, STOP_B, STOP_A, SIX_AM + 21_600, SIX_AM + 25_200));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), shortBlocks);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.uncovered()).isEmpty();
    }

    @Test
    @DisplayName("best fit picks the block with the least idle time, not the first that fits")
    void bestFitPrefersTheTightestBlock() {
        // Two blocks are open and both could take trip 3. Block A frees up at 07:00, block B at 08:00.
        // Trip 3 departs 08:30 from the same stop, so B leaves 30 minutes idle and A leaves 90.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 7200),
                trip(3, STOP_B, STOP_A, SIX_AM + 9000, SIX_AM + 12_600));

        VehicleSchedule result = builder.build(trips, depot(standardFleet(5)), rules);

        Block withThree = result.blocks().stream()
                .filter(block -> block.tripIds().contains(3L))
                .findFirst()
                .orElseThrow();
        // Trip 2 is the later-finishing one, so the tight fit is to follow it.
        assertThat(withThree.tripIds()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("the same input produces the same blocks every time")
    void buildIsDeterministic() {
        List<TripView> trips = List.of(
                trip(3, STOP_B, STOP_A, SIX_AM + 9000, SIX_AM + 12_600),
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 7200));

        VehicleSchedule first = builder.build(trips, depot(standardFleet(5)), rules);
        VehicleSchedule second = builder.build(trips, depot(standardFleet(5)), rules);

        // Determinism is what makes a stored seed meaningful and a regression reproducible. It holds because
        // the builder sorts rather than relying on the order the input happened to arrive in.
        assertThat(describe(second)).isEqualTo(describe(first));
    }

    @Test
    @DisplayName("service and dead kilometres are tracked separately")
    void kilometresAreSplitByRevenue() {
        List<TripView> trips = List.of(
                SchedulingFixtures.tripOfLength(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600, 10_000));

        Block block = builder
                .build(trips, depot(standardFleet(1)), rules)
                .blocks()
                .get(0);

        // 10 km in service; 6 km out and 6 km back as dead running. Mixing them would make fleet utilisation
        // look better than it is.
        assertThat(block.serviceKm()).isEqualTo(10.0);
        assertThat(block.deadKm()).isEqualTo(12.0);
    }

    private static List<TripView> sameStopPair(int firstTripLengthSec, int gapSec) {
        int firstEnd = SIX_AM + firstTripLengthSec;
        return List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, firstEnd),
                trip(2, STOP_B, STOP_A, firstEnd + gapSec, firstEnd + gapSec + 1800));
    }

    /** A stable text form of a schedule, so two builds can be compared in one assertion. */
    private static String describe(VehicleSchedule schedule) {
        StringBuilder text = new StringBuilder();
        for (Block block : schedule.blocks()) {
            text.append(block.blockNo()).append('@').append(block.pullOutSec()).append(':');
            block.events()
                    .forEach(event -> text.append(event.type())
                            .append('/')
                            .append(event.startSec())
                            .append('-')
                            .append(event.endSec())
                            .append(','));
            text.append('\n');
        }
        schedule.uncovered().forEach(uncovered -> text.append("uncovered ")
                .append(uncovered.tripId())
                .append('\n'));
        return text.toString();
    }
}
