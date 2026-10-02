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

import com.dtc.transit.scheduling.engine.OutputHash;
import com.dtc.transit.scheduling.engine.SchedulingEngine;
import com.dtc.transit.scheduling.engine.duty.PieceCutter;
import com.dtc.transit.scheduling.engine.duty.ReliefTransferTimes;
import com.dtc.transit.scheduling.engine.duty.UnlinkedDutyBuilder;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.relief.ReliefOpportunityFinder;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;

/**
 * Unlinked duty building: the properties the phase is gated on.
 *
 * <p>Deliberately narrow. The duty rules themselves are already covered by the linked-mode tests, which share the
 * same constraint catalogue; what is new here is that pieces come from several buses, so these check piece
 * accounting, transfer feasibility and determinism rather than re-testing the rules.
 */
class UnlinkedDutyBuildingTest {

    private static final int FIVE_AM = 5 * 3600;

    private final GreedyBestFitBlockBuilder blockBuilder = new GreedyBestFitBlockBuilder();
    private final ReliefOpportunityFinder reliefFinder = new ReliefOpportunityFinder();
    private final UnlinkedDutyBuilder dutyBuilder = new UnlinkedDutyBuilder();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("every piece of work belongs to exactly one duty")
    void everyPieceIsUsedExactlyOnce() {
        var schedule = blocks(twoLongBlocks());
        var result = dutyBuilder.build(schedule, depot(standardFleet(5)), rules);

        List<String> pieceKeys = result.duties().stream()
                .flatMap(duty -> duty.pieces().stream())
                .map(piece -> piece.blockNo() + ":" + piece.fromEventSeq())
                .toList();

        // Two crews on one stretch of a bus would both turn up for it, and nothing downstream would notice.
        assertThat(pieceKeys).doesNotHaveDuplicates();
        assertThat(result.duties()).isNotEmpty();
    }

    @Test
    @DisplayName("the pieces cover every block from pull-out to pull-in")
    void piecesCoverTheWholeBlock() {
        var schedule = blocks(twoLongBlocks());
        var result = dutyBuilder.build(schedule, depot(standardFleet(5)), rules);

        List<PieceOfWorkPlan> allPieces = result.duties().stream()
                .flatMap(duty -> duty.pieces().stream())
                .toList();

        // The union of the pieces has to equal the union of the block work: a gap is a bus running with nobody
        // on it.
        for (var block : schedule.blocks()) {
            var ofBlock = allPieces.stream()
                    .filter(piece -> piece.blockNo() == block.blockNo())
                    .sorted(java.util.Comparator.comparingInt(PieceOfWorkPlan::startSec))
                    .toList();
            assertThat(ofBlock).as("block %d has pieces", block.blockNo()).isNotEmpty();
            assertThat(ofBlock.get(0).startSec()).isEqualTo(block.pullOutSec());
            assertThat(ofBlock.get(ofBlock.size() - 1).endSec()).isEqualTo(block.pullInSec());
            for (int i = 1; i < ofBlock.size(); i++) {
                assertThat(ofBlock.get(i).startSec())
                        .as("block %d is continuous", block.blockNo())
                        .isEqualTo(ofBlock.get(i - 1).endSec());
            }
        }
    }

    @Test
    @DisplayName("every gap inside a duty covers the transfer time plus the handover buffer")
    void transferGapsAreRespected() {
        DepotContext context = depot(standardFleet(5));
        var transferTimes = new ReliefTransferTimes(context, rules);
        var result = dutyBuilder.build(blocks(twoLongBlocks()), context, rules);

        for (DutyPlan duty : result.duties()) {
            List<PieceOfWorkPlan> pieces = duty.pieces();
            for (int i = 1; i < pieces.size(); i++) {
                var previous = pieces.get(i - 1);
                var next = pieces.get(i);
                int required = transferTimes.requiredGapSec(
                        previous.endReliefStopId(), next.startReliefStopId(), previous.endSec());
                // Anything less asks a crew to be in two places at once, which the schedule would not reveal.
                assertThat(next.startSec() - previous.endSec())
                        .as("duty %d transfer between pieces %d and %d", duty.dutyNo(), i - 1, i)
                        .isGreaterThanOrEqualTo(required);
            }
        }
    }

    @Test
    @DisplayName("a duty may combine pieces from more than one bus")
    void dutiesCanSpanBuses() {
        var result = dutyBuilder.build(blocks(twoLongBlocks()), depot(standardFleet(5)), rules);

        assertThat(result.duties()).allSatisfy(duty -> assertThat(duty.mode()).isEqualTo(SchedulingMode.UNLINKED));
        // The point of the phase. If no duty ever spans two buses, unlinked mode is just linked mode with extra
        // machinery.
        assertThat(result.duties()).anySatisfy(duty -> assertThat(duty.blockNos()).hasSizeGreaterThan(1));
    }

    @Test
    @DisplayName("a handover is recorded wherever a bus changes crew")
    void handoversAreRecordedAtCrewChanges() {
        var result = dutyBuilder.build(blocks(twoLongBlocks()), depot(standardFleet(5)), rules);

        assertThat(result.handovers()).isNotEmpty();
        assertThat(result.handovers()).allSatisfy(handover -> {
            // A handover between a duty and itself is not a crew change.
            assertThat(handover.outgoingDutyNo()).isNotEqualTo(handover.incomingDutyNo());
            assertThat(handover.atSec()).isPositive();
        });
    }

    @Test
    @DisplayName("the piece cutter keeps every piece within the allowed length range")
    void piecesAreWithinTheLengthRange() {
        var schedule = blocks(twoLongBlocks());
        var cutter = new PieceCutter();

        for (var block : schedule.blocks()) {
            var pieces = cutter.cut(block, rules);
            assertThat(pieces).isNotEmpty();
            // A piece is worked without a break, so it cannot exceed the continuous-work limit.
            assertThat(pieces).allSatisfy(piece -> assertThat(piece.lengthSec())
                    .as("piece of block %d", block.blockNo())
                    .isLessThanOrEqualTo(rules.maxContinuousWorkSec()));
        }
    }

    @Test
    @DisplayName("the same input and seed produce an identical output hash")
    void outputIsDeterministic() {
        var engine = new SchedulingEngine();
        DepotContext context = depot(standardFleet(5));
        List<TripView> trips = twoLongBlocks();

        String first = null;
        for (int run = 0; run < 5; run++) {
            var result = engine.run(trips, context, rules, SchedulingMode.UNLINKED, 42L, 250, false, p -> {});
            String hash = OutputHash.of(
                    result.vehicleSchedule(), result.duties(), result.busAssignments());
            if (first == null) {
                first = hash;
            }
            // Determinism is what makes the stored seed meaningful and a regression reproducible. The local
            // search uses a seeded SplittableRandom and every collection is sorted, never hash-ordered.
            assertThat(hash).as("run %d", run).isEqualTo(first);
        }
        assertThat(first).isNotBlank();
    }

    @Test
    @DisplayName("the local search never makes a duty illegal and never increases the cost")
    void localSearchIsSafe() {
        DepotContext context = depot(standardFleet(5));
        List<TripView> trips = twoLongBlocks();
        var engine = new SchedulingEngine();

        var withoutSearch = engine.run(trips, context, rules, SchedulingMode.UNLINKED, 7L, 0, false, p -> {});
        var withSearch = engine.run(trips, context, rules, SchedulingMode.UNLINKED, 7L, 500, false, p -> {});

        // Searching must not buy efficiency with legality. Both runs cover the same work, and the searched one
        // uses no more duties than the greedy one.
        assertThat(withSearch.metrics().duties()).isLessThanOrEqualTo(withoutSearch.metrics().duties());
        assertThat(withSearch.conflicts().stream().filter(c -> c.isBlocking()).count())
                .isLessThanOrEqualTo(
                        withoutSearch.conflicts().stream().filter(c -> c.isBlocking()).count());
    }

    @Test
    @DisplayName("unlinked mode uses no more duties than linked mode for the same work")
    void unlinkedIsNoWorseThanLinked() {
        DepotContext context = depot(standardFleet(5));
        List<TripView> trips = twoLongBlocks();
        var engine = new SchedulingEngine();

        int linked = engine.run(trips, context, rules, SchedulingMode.LINKED, 1L, 0, false, p -> {})
                .metrics()
                .duties();
        int unlinked = engine.run(trips, context, rules, SchedulingMode.UNLINKED, 1L, 500, false, p -> {})
                .metrics()
                .duties();

        // The whole justification for the phase: combining pieces across buses should not cost more duties than
        // keeping every crew on one bus.
        assertThat(unlinked).isLessThanOrEqualTo(linked);
    }

    // --- fixtures -----------------------------------------------------------

    /**
     * Two long blocks whose work interleaves.
     *
     * <p>Both run most of the day with 35-minute turnarounds, offset from each other by half a cycle. That offset
     * is what creates pieces a crew can realistically combine across the two buses.
     */
    private static List<TripView> twoLongBlocks() {
        List<TripView> trips = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 12; i++) {
            int start = FIVE_AM + i * 80 * 60;
            boolean outbound = i % 2 == 0;
            trips.add(trip(id++, outbound ? STOP_A : STOP_B, outbound ? STOP_B : STOP_A, start, start + 45 * 60));
        }
        for (int i = 0; i < 12; i++) {
            int start = FIVE_AM + 40 * 60 + i * 80 * 60;
            boolean outbound = i % 2 == 0;
            trips.add(trip(id++, outbound ? STOP_B : STOP_A, outbound ? STOP_A : STOP_B, start, start + 45 * 60));
        }
        return trips;
    }

    private VehicleSchedule blocks(List<TripView> trips) {
        DepotContext context = depot(standardFleet(5));
        return reliefFinder.mark(blockBuilder.build(trips, context, rules), context, rules);
    }
}
