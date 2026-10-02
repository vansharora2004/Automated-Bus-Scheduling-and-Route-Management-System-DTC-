package com.dtc.transit.scheduling;

import static com.dtc.transit.support.SchedulingFixtures.STOP_A;
import static com.dtc.transit.support.SchedulingFixtures.STOP_B;
import static com.dtc.transit.support.SchedulingFixtures.depot;
import static com.dtc.transit.support.SchedulingFixtures.standardFleet;
import static com.dtc.transit.support.SchedulingFixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;
import com.dtc.transit.scheduling.engine.vehicle.MinFleetMatchingBlockBuilder;

/**
 * The matching builder, used mainly as the lower bound on fleet size.
 *
 * <p>Known answers first. A matching implementation that is subtly wrong still returns a plausible number, so
 * the only way to trust it is on cases where the correct answer can be counted by hand.
 */
class MinFleetLowerBoundTest {

    private static final int SIX_AM = 6 * 3600;

    private final MinFleetMatchingBlockBuilder matching = new MinFleetMatchingBlockBuilder();
    private final GreedyBestFitBlockBuilder greedy = new GreedyBestFitBlockBuilder();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("a chain of trips that follow each other needs one bus")
    void sequentialTripsNeedOneBus() {
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_B, STOP_A, SIX_AM + 5400, SIX_AM + 9000),
                trip(3, STOP_A, STOP_B, SIX_AM + 10_800, SIX_AM + 14_400));

        assertThat(matching.minimumFleet(trips, depot(standardFleet(5)), rules)).isEqualTo(1);
    }

    @Test
    @DisplayName("trips that all run at once need one bus each")
    void simultaneousTripsNeedOneBusEach() {
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(3, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));

        assertThat(matching.minimumFleet(trips, depot(standardFleet(5)), rules)).isEqualTo(3);
    }

    @Test
    @DisplayName("the lower bound is the peak concurrency, not the trip count")
    void boundReflectsPeakConcurrency() {
        // Two overlapping pairs: trips 1 and 2 run together, then 3 and 4 run together later. Two buses can
        // cover all four, and no fewer can cover the peak of two.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(3, STOP_B, STOP_A, SIX_AM + 7200, SIX_AM + 10_800),
                trip(4, STOP_B, STOP_A, SIX_AM + 7200, SIX_AM + 10_800));

        assertThat(matching.minimumFleet(trips, depot(standardFleet(9)), rules)).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty day needs no buses")
    void emptyDayNeedsNothing() {
        assertThat(matching.minimumFleet(List.of(), depot(standardFleet(5)), rules)).isZero();
    }

    @Test
    @DisplayName("the greedy builder never uses fewer buses than the optimum")
    void greedyIsNeverBetterThanOptimal() {
        List<TripView> trips = interleavedDay();

        int bound = matching.minimumFleet(trips, depot(standardFleet(60)), rules);
        int greedyBlocks = greedy.build(trips, depot(standardFleet(60)), rules).blocks().size();

        // If greedy ever came out below the bound, one of the two would be wrong, and this is the assertion
        // that would catch it. The bound being a bound is the only guarantee either side gives.
        assertThat(greedyBlocks).isGreaterThanOrEqualTo(bound);
    }

    @Test
    @DisplayName("the matching builder covers every trip it has buses for")
    void matchingBuilderCoversItsTrips() {
        List<TripView> trips = interleavedDay();

        var result = matching.build(trips, depot(standardFleet(60)), rules);

        // VehicleSchedule.of refuses to be built if a trip went missing, so reaching this assertion already
        // proves accounting; the assertion states the stronger fact that nothing was dropped at all.
        assertThat(result.uncovered()).isEmpty();
        assertThat(result.coveredTripCount()).isEqualTo(trips.size());
    }

    @Test
    @DisplayName("blocks are left uncovered rather than invented when the fleet runs out")
    void fleetLimitIsRespected() {
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(3, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600));

        var result = matching.build(trips, depot(standardFleet(2)), rules);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.uncovered()).hasSize(1);
        assertThat(result.conflicts()).anyMatch(conflict -> conflict.isBlocking());
    }

    /** A day of 40 trips at a 15-minute headway in each direction, which interleaves enough to be non-trivial. */
    private static List<TripView> interleavedDay() {
        List<TripView> trips = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 20; i++) {
            int start = SIX_AM + i * 900;
            trips.add(trip(id++, STOP_A, STOP_B, start, start + 2700));
            trips.add(trip(id++, STOP_B, STOP_A, start + 300, start + 3000));
        }
        return trips;
    }
}
