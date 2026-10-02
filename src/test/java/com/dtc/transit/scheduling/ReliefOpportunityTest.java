package com.dtc.transit.scheduling;

import static com.dtc.transit.support.SchedulingFixtures.STOP_A;
import static com.dtc.transit.support.SchedulingFixtures.STOP_B;
import static com.dtc.transit.support.SchedulingFixtures.STOP_C;
import static com.dtc.transit.support.SchedulingFixtures.depot;
import static com.dtc.transit.support.SchedulingFixtures.standardFleet;
import static com.dtc.transit.support.SchedulingFixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.relief.ReliefOpportunityFinder;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;

/**
 * Where a crew can be changed.
 *
 * <p>Three conditions have to hold together — a reachable place, a stopped bus and enough time — and the
 * fixture deliberately has one stop that is not a relief point, so a finder that simply marks everything
 * cannot pass.
 */
class ReliefOpportunityTest {

    private static final int SIX_AM = 6 * 3600;

    private final GreedyBestFitBlockBuilder builder = new GreedyBestFitBlockBuilder();
    private final ReliefOpportunityFinder finder = new ReliefOpportunityFinder();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("pull-out and pull-in are always relief opportunities")
    void depotMovementsAreAlwaysOpportunities() {
        Block block = markedBlock(List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)));

        // The crew signs on and off at the depot, so a relief crew is already there and nothing needs checking.
        assertThat(eventsOfType(block, BlockEventType.PULL_OUT)).allMatch(BlockEvent::reliefOpportunity);
        assertThat(eventsOfType(block, BlockEventType.PULL_IN)).allMatch(BlockEvent::reliefOpportunity);
    }

    @Test
    @DisplayName("a trip ending at a relief point with time to spare is an opportunity")
    void tripEndingAtReliefPointIsAnOpportunity() {
        // Arrives B at 07:00, next departure 07:30: half an hour clears the five-minute handover buffer.
        Block block = markedBlock(List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_B, STOP_A, SIX_AM + 5400, SIX_AM + 9000)));

        BlockEvent firstTrip = eventsOfType(block, BlockEventType.TRIP).get(0);
        assertThat(firstTrip.reliefOpportunity()).isTrue();
    }

    @Test
    @DisplayName("a trip ending at a stop that is not a relief point is not an opportunity")
    void tripEndingAtOrdinaryStopIsNotAnOpportunity() {
        // C is a mid-route stop with no facilities: a relief crew cannot realistically be there.
        Block block = markedBlock(List.of(
                trip(1, STOP_A, STOP_C, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_C, STOP_A, SIX_AM + 5400, SIX_AM + 9000)));

        BlockEvent firstTrip = eventsOfType(block, BlockEventType.TRIP).get(0);
        assertThat(firstTrip.reliefOpportunity()).isFalse();
    }

    @Test
    @DisplayName("a relief point the bus leaves again immediately is not an opportunity")
    void tooLittleTimeIsNotAnOpportunity() {
        // Arrives B at 07:00 and departs again at 07:04. Four minutes is under the five-minute buffer, so a
        // relief here would make the bus late.
        RuleSet tightLayovers = RuleSet.defaults().toBuilder().minLayoverMin(1).minLayoverPct(0).build();
        Block block = markedBlock(
                List.of(
                        trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                        trip(2, STOP_B, STOP_A, SIX_AM + 3840, SIX_AM + 7200)),
                tightLayovers);

        BlockEvent firstTrip = eventsOfType(block, BlockEventType.TRIP).get(0);
        assertThat(firstTrip.reliefOpportunity()).isFalse();
    }

    @Test
    @DisplayName("exactly the handover buffer is enough")
    void exactlyTheBufferQualifies() {
        // Arrives B at 07:00, departs 07:05: exactly five minutes. The boundary passes, because a rule written
        // as "at least five minutes" has to accept five.
        RuleSet tightLayovers = RuleSet.defaults().toBuilder().minLayoverMin(1).minLayoverPct(0).build();
        Block block = markedBlock(
                List.of(
                        trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                        trip(2, STOP_B, STOP_A, SIX_AM + 3900, SIX_AM + 7200)),
                tightLayovers);

        assertThat(eventsOfType(block, BlockEventType.TRIP).get(0).reliefOpportunity()).isTrue();
    }

    @Test
    @DisplayName("mid-day depot parking is an opportunity, which is what makes a split duty possible")
    void depotParkIsAnOpportunity() {
        Block block = markedBlock(List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_B, STOP_A, SIX_AM + 18_000, SIX_AM + 21_600)));

        assertThat(eventsOfType(block, BlockEventType.DEPOT_PARK)).isNotEmpty();
        assertThat(eventsOfType(block, BlockEventType.DEPOT_PARK)).allMatch(BlockEvent::reliefOpportunity);
    }

    @Test
    @DisplayName("the last trip of a block is an opportunity when it ends at a relief point")
    void lastTripAtAReliefPointQualifies() {
        Block block = markedBlock(List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)));

        // There is no following departure to be late for, so only the place has to be right.
        assertThat(eventsOfType(block, BlockEventType.TRIP).get(0).reliefOpportunity()).isTrue();
    }

    @Test
    @DisplayName("dead running is never an opportunity")
    void deadheadIsNeverAnOpportunity() {
        Block block = markedBlock(List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600),
                trip(2, STOP_A, STOP_B, SIX_AM + 7200, SIX_AM + 10_800)));

        // The bus is moving and empty. Stopping it to change crew would add dead time for no reason.
        assertThat(eventsOfType(block, BlockEventType.DEADHEAD)).isNotEmpty();
        assertThat(eventsOfType(block, BlockEventType.DEADHEAD)).noneMatch(BlockEvent::reliefOpportunity);
    }

    private Block markedBlock(List<TripView> trips) {
        return markedBlock(trips, rules);
    }

    private Block markedBlock(List<TripView> trips, RuleSet ruleSet) {
        var context = depot(standardFleet(3));
        var built = builder.build(trips, context, ruleSet);
        return finder.mark(built, context, ruleSet).blocks().get(0);
    }

    private static List<BlockEvent> eventsOfType(Block block, BlockEventType type) {
        return block.events().stream().filter(event -> event.type() == type).toList();
    }
}
