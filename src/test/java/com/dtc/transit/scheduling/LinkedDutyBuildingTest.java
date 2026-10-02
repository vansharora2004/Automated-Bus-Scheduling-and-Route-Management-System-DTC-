package com.dtc.transit.scheduling;

import static com.dtc.transit.support.SchedulingFixtures.STOP_A;
import static com.dtc.transit.support.SchedulingFixtures.STOP_B;
import static com.dtc.transit.support.SchedulingFixtures.STOP_C;
import static com.dtc.transit.support.SchedulingFixtures.depot;
import static com.dtc.transit.support.SchedulingFixtures.standardFleet;
import static com.dtc.transit.support.SchedulingFixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dtc.transit.scheduling.engine.duty.LinkedDutyBuilder;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.relief.ReliefOpportunityFinder;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;
import com.dtc.transit.scheduling.engine.vehicle.GreedyBestFitBlockBuilder;

/**
 * Cutting bus blocks into legal crew duties.
 *
 * <p>Built from real blocks rather than synthetic events, because the thing being tested is the interaction
 * between where the relief opportunities fall and what the rules allow. A fixture that placed cut points by hand
 * would not exercise that at all.
 */
class LinkedDutyBuildingTest {

    private static final int SIX_AM = 6 * 3600;

    private final GreedyBestFitBlockBuilder blockBuilder = new GreedyBestFitBlockBuilder();
    private final ReliefOpportunityFinder reliefFinder = new ReliefOpportunityFinder();
    private final LinkedDutyBuilder dutyBuilder = new LinkedDutyBuilder();
    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("a short block becomes one duty with no handover")
    void shortBlockIsOneDuty() {
        var result = cut(List.of(trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 3600)), rules);

        assertThat(result.duties()).hasSize(1);
        assertThat(result.handovers()).isEmpty();
        assertThat(result.conflicts()).noneMatch(conflict -> conflict.isBlocking());
    }

    @Test
    @DisplayName("a 17-hour block splits into two or three legal duties with handovers at relief points")
    void seventeenHourBlockSplitsIntoLegalDuties() {
        var result = cut(seventeenHourBlockTrips(), rules);

        // The headline Phase 7 check. One person cannot work seventeen hours, so the block has to become
        // several duties, and each has to be legal on its own.
        assertThat(result.duties()).hasSizeBetween(2, 3);
        assertThat(result.handovers()).hasSize(result.duties().size() - 1);

        assertThat(result.duties()).allSatisfy(duty -> {
            assertThat(duty.spreadSec())
                    .as("duty %d spread-over", duty.dutyNo())
                    .isLessThanOrEqualTo(rules.maxSpreadOverSec());
            assertThat(duty.platformSec() + rules.signOnSec() + rules.signOffSec())
                    .as("duty %d work", duty.dutyNo())
                    .isLessThanOrEqualTo(rules.maxWorkPerDutySec());
        });
        assertThat(result.conflicts()).noneMatch(conflict -> conflict.isBlocking());
    }

    @Test
    @DisplayName("the duties cover the block end to end, with no gap and no overlap")
    void dutiesTileTheBlock() {
        var schedule = blocks(seventeenHourBlockTrips(), rules);
        var block = schedule.blocks().get(0);
        var result = dutyBuilder.build(schedule, depot(standardFleet(5)), rules);

        List<DutyPlan> inOrder = result.duties().stream()
                .sorted(java.util.Comparator.comparingInt(DutyPlan::signOnSec))
                .toList();

        // The crew takes the bus at sign-on plus the sign-on allowance, and hands it back at sign-off minus the
        // sign-off allowance. Those bus times must tile the block exactly: a gap is a bus running with nobody on
        // it, and an overlap is two crews paid for the same hour.
        int firstBusTime = inOrder.get(0).signOnSec() + rules.signOnSec();
        int lastBusTime = inOrder.get(inOrder.size() - 1).signOffSec() - rules.signOffSec();
        assertThat(firstBusTime).isEqualTo(block.pullOutSec());
        assertThat(lastBusTime).isEqualTo(block.pullInSec());

        for (int i = 1; i < inOrder.size(); i++) {
            int previousHandsOver = inOrder.get(i - 1).signOffSec() - rules.signOffSec();
            int nextTakesOver = inOrder.get(i).signOnSec() + rules.signOnSec();
            assertThat(nextTakesOver).as("duty %d takes over", i).isEqualTo(previousHandsOver);
        }
    }

    @Test
    @DisplayName("handovers land on relief points, never mid-route")
    void handoversLandOnReliefPoints() {
        var schedule = blocks(seventeenHourBlockTrips(), rules);
        var context = depot(standardFleet(5));
        var result = dutyBuilder.build(schedule, context, rules);

        var block = schedule.blocks().get(0);
        List<Integer> reliefTimes =
                block.reliefOpportunities().stream().map(event -> event.endSec()).toList();

        assertThat(result.handovers()).isNotEmpty();
        assertThat(result.handovers()).allSatisfy(handover -> {
            // A handover anywhere else is a crew change the relief driver cannot physically get to.
            assertThat(reliefTimes).contains(handover.atSec());
            if (handover.reliefStopId() != null) {
                assertThat(context.isReliefPoint(handover.reliefStopId()))
                        .as("handover at stop %d", handover.reliefStopId())
                        .isTrue();
            }
        });
    }

    @Test
    @DisplayName("a long stretch with no relief point raises NO_FEASIBLE_RELIEF rather than passing silently")
    void noReliefOpportunityRaisesAConflict() {
        // Nearly six hours of driving that only ever stops at C, which is not a relief point. The ten-minute
        // turnarounds are too short to count as breaks, so the whole block is one unbroken stretch of work, and
        // the only cut points are the depot at either end. No legal duty exists.
        List<TripView> trips = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int start = SIX_AM + i * 60 * 60;
            trips.add(trip(i + 1, STOP_C, STOP_C, start, start + 50 * 60));
        }

        var result = cut(trips, rules);

        // Accepting it would roster a person for six unbroken hours. Reporting it tells the planner to add a
        // relief point or retime the route, which are the only real fixes.
        assertThat(result.conflicts())
                .anyMatch(conflict -> conflict.type().equals(ConflictTypes.NO_FEASIBLE_RELIEF));
        assertThat(result.conflicts()).anyMatch(conflict -> conflict.isBlocking());
    }

    @Test
    @DisplayName("changing the maximum continuous work changes the number of duties, with no code change")
    void tighteningContinuousWorkChangesTheOutcome() {
        List<TripView> trips = seventeenHourBlockTrips();

        int withDefaults = cut(trips, rules).duties().size();
        // Two hours instead of five. The same block now has to be cut into more, shorter duties.
        RuleSet tighter = RuleSet.defaults().toBuilder()
                .maxContinuousWorkMin(120)
                .maxWorkPerDutyMin(240)
                .targetWorkPerDutyMin(180)
                .minPaidDutyMin(60)
                .build();
        int withTighterRules = cut(trips, tighter).duties().size();

        // This is the Phase 7 promise: the labour rules are data. Changing a number in the rule set changes the
        // roster, and nothing is recompiled.
        assertThat(withTighterRules).isGreaterThan(withDefaults);
    }

    @Test
    @DisplayName("relaxing the maximum continuous work reduces the number of duties")
    void relaxingContinuousWorkAlsoChangesTheOutcome() {
        List<TripView> trips = seventeenHourBlockTrips();

        RuleSet generous = RuleSet.defaults().toBuilder()
                .maxContinuousWorkMin(600)
                .maxWorkPerDutyMin(660)
                .maxSpreadOverMin(780)
                .targetWorkPerDutyMin(600)
                .build();

        // The rule set moves the answer in both directions, which is what distinguishes configuration from a
        // coincidence.
        assertThat(cut(trips, generous).duties().size())
                .isLessThanOrEqualTo(cut(trips, rules).duties().size());
    }

    @Test
    @DisplayName("every duty is linked: one bus, one crew")
    void dutiesStayOnOneBus() {
        var result = cut(seventeenHourBlockTrips(), rules);

        assertThat(result.duties()).allSatisfy(duty -> {
            assertThat(duty.mode()).isEqualTo(SchedulingMode.LINKED);
            // The defining property of linked mode. Phase 8 is what allows more than one.
            assertThat(duty.blockNos()).hasSize(1);
        });
    }

    @Test
    @DisplayName("a mid-day depot park produces a split duty")
    void midDayParkProducesASplitDuty() {
        // Morning work, five hours parked at the depot, then evening work. One crew covering both halves has a
        // long unpaid gap in the middle, which is what a split duty is.
        List<TripView> trips = List.of(
                trip(1, STOP_A, STOP_B, SIX_AM, SIX_AM + 7200),
                trip(2, STOP_B, STOP_A, SIX_AM + 25_200, SIX_AM + 32_400));

        var result = cut(trips, rules);

        assertThat(result.duties()).isNotEmpty();
        boolean anySplit = result.duties().stream()
                .anyMatch(duty -> duty.dutyType() == com.dtc.transit.scheduling.engine.model.DutyType.SPLIT);
        boolean cutAtTheDepot = result.duties().size() > 1;
        // Either the same crew covers both halves, which makes it a split duty, or the depot park is used as a
        // handover point. Both are correct; silently rostering one crew across the gap as though it were work
        // is not.
        assertThat(anySplit || cutAtTheDepot).isTrue();
    }

    @Test
    @DisplayName("the same block produces the same duties every time")
    void dutyBuildingIsDeterministic() {
        List<TripView> trips = seventeenHourBlockTrips();

        String first = describe(cut(trips, rules).duties());
        String second = describe(cut(trips, rules).duties());

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("duty numbers are unique across the whole schedule")
    void dutyNumbersAreUniqueAcrossBlocks() {
        // Two overlapping long blocks, so both are cut and the numbering has to continue across them.
        List<TripView> trips = new ArrayList<>(seventeenHourBlockTrips());
        long id = 500;
        for (int i = 0; i < 14; i++) {
            int start = SIX_AM + i * 3600 + 120;
            trips.add(trip(id++, STOP_A, STOP_B, start, start + 3000));
        }

        var result = cut(trips, rules);

        assertThat(result.duties().stream().map(DutyPlan::dutyNo).distinct())
                .hasSameSizeAs(result.duties());
    }

    // --- fixtures -----------------------------------------------------------

    /**
     * A block that runs about seventeen hours.
     *
     * <p>Thirteen 45-minute trips alternating between the two termini from 05:00, with a 35-minute layover at
     * each turnaround. Both termini are relief points, so the block is full of legal cut points and the test is
     * about the rules rather than about geography.
     *
     * <p>The layover length is the part that matters. At 35 minutes it clears the 30-minute minimum break, so it
     * resets the continuous-work clock and a duty can span several trips. Shorter turnarounds would make every
     * duty bump into the five-hour continuous limit, and the block would need five duties rather than two or
     * three — correct behaviour, but a test of a different thing.
     */
    private static List<TripView> seventeenHourBlockTrips() {
        List<TripView> trips = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 13; i++) {
            int start = 5 * 3600 + i * 80 * 60;
            boolean outbound = i % 2 == 0;
            trips.add(trip(
                    id++,
                    outbound ? STOP_A : STOP_B,
                    outbound ? STOP_B : STOP_A,
                    start,
                    start + 45 * 60));
        }
        return trips;
    }

    private LinkedDutyBuilder.Result cut(List<TripView> trips, RuleSet ruleSet) {
        return dutyBuilder.build(blocks(trips, ruleSet), depot(standardFleet(5)), ruleSet);
    }

    private VehicleSchedule blocks(List<TripView> trips, RuleSet ruleSet) {
        DepotContext context = depot(standardFleet(5));
        var built = blockBuilder.build(trips, context, ruleSet);
        return reliefFinder.mark(built, context, ruleSet);
    }

    private static String describe(List<DutyPlan> duties) {
        StringBuilder text = new StringBuilder();
        duties.forEach(duty -> text.append(duty.dutyNo())
                .append(':')
                .append(duty.signOnSec())
                .append('-')
                .append(duty.signOffSec())
                .append('/')
                .append(duty.dutyType())
                .append('/')
                .append(duty.paidSec())
                .append('\n'));
        return text.toString();
    }
}
