package com.dtc.transit.scheduling.engine.duty;

import java.util.ArrayList;
import java.util.List;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.constraint.DutyEvaluator;
import com.dtc.transit.scheduling.engine.constraint.ValidationContext;
import com.dtc.transit.scheduling.engine.constraint.Violation;
import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.HandoverPlan;
import com.dtc.transit.scheduling.engine.model.PieceOfWorkPlan;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * Cuts each bus block into legal crew duties, with the crew staying on one bus.
 *
 * <p>The cuts can only fall on relief opportunities, so the block's marked events define the candidate points.
 * From a cursor the builder considers <em>every</em> later relief point, not just the next one, and picks the
 * legal candidate whose work is closest to the target.
 *
 * <p><strong>Why every candidate.</strong> Hard constraints are not monotone in segment length. A longer segment
 * can be legal where a shorter one was not, because the break that satisfies the continuous-work rule may lie
 * beyond the shorter segment's end. Stopping at the first infeasible point would therefore reject duties that are
 * perfectly legal, and would do it while producing output that looks entirely reasonable — which is the worst
 * kind of wrong.
 *
 * <p>Complexity is O(R²) per block in the number of relief points, with each candidate costing a metrics
 * computation over its events. A seventeen-hour block has on the order of fifty relief points, so this is
 * thousands of evaluations per block rather than millions.
 */
public class LinkedDutyBuilder {

    /**
     * Penalty applied when a cut would leave a final duty below the minimum paid guarantee.
     *
     * <p>Expressed in the same units as the distance from the target, so the two can be added. Without it the
     * builder happily leaves a twenty-minute tail at the end of a block, which the depot then pays four hours
     * for.
     */
    public static final int SHORT_TAIL_PENALTY_SEC = 2 * 3600;

    private final DutyEvaluator evaluator;

    public LinkedDutyBuilder() {
        this(new DutyEvaluator());
    }

    public LinkedDutyBuilder(DutyEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Builds duties for every block in a vehicle schedule.
     *
     * @return the duties, the handovers between them, and any conflicts raised while cutting
     */
    public Result build(VehicleSchedule schedule, DepotContext depot, RuleSet rules) {
        ValidationContext context = new ValidationContext(rules, depot);

        List<DutyPlan> duties = new ArrayList<>();
        List<HandoverPlan> handovers = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        for (Block block : schedule.blocks()) {
            BlockCut cut = cutBlock(block, context, rules);
            int firstDutyNo = duties.size() + 1;

            for (int i = 0; i < cut.duties().size(); i++) {
                duties.add(cut.duties().get(i).withDutyNo(firstDutyNo + i));
            }
            for (HandoverPlan handover : cut.handovers()) {
                handovers.add(new HandoverPlan(
                        handover.blockNo(),
                        handover.reliefStopId(),
                        handover.atSec(),
                        firstDutyNo + handover.outgoingDutyNo(),
                        firstDutyNo + handover.incomingDutyNo()));
            }
            conflicts.addAll(cut.conflicts());
        }

        return new Result(duties, handovers, conflicts);
    }

    /**
     * Cuts one block.
     *
     * <p>Duty numbers here are local to the block, starting at zero, and are renumbered by the caller. Doing it
     * the other way round would make this method need to know how many duties other blocks produced.
     */
    private BlockCut cutBlock(Block block, ValidationContext context, RuleSet rules) {
        List<Integer> cutPoints = cutPointsOf(block);
        List<BlockEvent> events = block.events();

        List<DutyPlan> duties = new ArrayList<>();
        List<HandoverPlan> handovers = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        int cursor = 0;
        int lastCut = cutPoints.size() - 1;

        while (cursor < lastCut) {
            Candidate chosen = null;
            Violation firstReason = null;

            // Every later cut point, because the rules are not monotone in segment length.
            for (int end = cursor + 1; end <= lastCut; end++) {
                DutyCandidate candidate = candidateFor(block, events, cutPoints, cursor, end, rules);
                if (candidate == null) {
                    continue;
                }
                if (!evaluator.isLegal(candidate, context)) {
                    if (firstReason == null) {
                        var hard = evaluator.hardViolations(candidate, context);
                        firstReason = hard.isEmpty() ? null : hard.get(0);
                    }
                    continue;
                }
                int cost = costOf(candidate, cutPoints, end, lastCut, rules);
                if (chosen == null || cost < chosen.cost()) {
                    chosen = new Candidate(end, candidate, cost);
                }
            }

            if (chosen == null) {
                // Nothing legal from here. The work still has to be represented, so the shortest possible duty is
                // emitted carrying a hard conflict: a silently dropped stretch of a block would be a bus running
                // with nobody on it, which is worse than an obviously broken schedule.
                DutyCandidate fallback =
                        candidateFor(block, events, cutPoints, cursor, cursor + 1, rules);
                conflicts.add(EngineConflict.hard(
                        ConflictTypes.NO_FEASIBLE_RELIEF,
                        "Block %d has no legal duty starting at %s: %s"
                                .formatted(
                                        block.blockNo(),
                                        ServiceTime.format(cutPoints.get(cursor)),
                                        firstReason == null
                                                ? "no relief opportunity is reachable within the rules"
                                                : firstReason.message()),
                        List.of()));
                if (fallback != null) {
                    duties.add(toPlan(fallback, duties.size(), context));
                }
                cursor = cursor + 1;
                continue;
            }

            duties.add(toPlan(chosen.candidate(), duties.size(), context));
            if (chosen.endCut() < lastCut) {
                BlockEvent reliefEvent = eventEndingAt(events, cutPoints.get(chosen.endCut()));
                handovers.add(new HandoverPlan(
                        block.blockNo(),
                        reliefEvent == null ? null : reliefEvent.toStopId(),
                        cutPoints.get(chosen.endCut()),
                        duties.size() - 1,
                        duties.size()));
            }
            cursor = chosen.endCut();
        }

        return new BlockCut(duties, handovers, conflicts);
    }

    /**
     * The times at which a crew change may happen in this block.
     *
     * <p>The first is the moment the bus leaves the depot; the rest are the ends of marked events. The last is
     * necessarily the end of the block, because the pull-in is always a relief opportunity.
     */
    private List<Integer> cutPointsOf(Block block) {
        List<Integer> cuts = new ArrayList<>();
        cuts.add(block.pullOutSec());
        for (BlockEvent event : block.events()) {
            if (event.reliefOpportunity() && event.endSec() > cuts.get(cuts.size() - 1)) {
                cuts.add(event.endSec());
            }
        }
        if (cuts.get(cuts.size() - 1) < block.pullInSec()) {
            // Defensive: a block whose pull-in was somehow not marked would otherwise lose its last stretch.
            cuts.add(block.pullInSec());
        }
        return cuts;
    }

    /** Builds the candidate duty covering the block between two cut points, or null when it would be empty. */
    private DutyCandidate candidateFor(
            Block block, List<BlockEvent> events, List<Integer> cutPoints, int from, int to, RuleSet rules) {

        int startSec = cutPoints.get(from);
        int endSec = cutPoints.get(to);
        if (endSec <= startSec) {
            return null;
        }

        List<BlockEvent> covered = events.stream()
                .filter(event -> event.startSec() >= startSec && event.endSec() <= endSec)
                .toList();
        if (covered.isEmpty()) {
            return null;
        }

        var segment = new DutyCandidate.WorkSegment(
                block.blockNo(),
                covered.get(0).seq(),
                covered.get(covered.size() - 1).seq(),
                startSec,
                endSec,
                reliefStopAt(events, startSec, true),
                reliefStopAt(events, endSec, false),
                covered);

        return new DutyCandidate(List.of(segment), DutyMetricsCalculator.compute(List.of(segment), rules));
    }

    /**
     * How undesirable a legal candidate is. Lower is better.
     *
     * <p>Distance from the target working day, plus a penalty for leaving a tail too short to be worth paying
     * for. The target rather than the maximum, because a schedule of duties at the legal limit has no slack for
     * the day a bus breaks down.
     */
    private int costOf(
            DutyCandidate candidate, List<Integer> cutPoints, int end, int lastCut, RuleSet rules) {

        int distanceFromTarget = Math.abs(candidate.metrics().workSec() - rules.targetWorkPerDutySec());
        if (end >= lastCut) {
            return distanceFromTarget;
        }

        int remainder = cutPoints.get(lastCut) - cutPoints.get(end);
        return distanceFromTarget + (remainder < rules.minPaidDutySec() ? SHORT_TAIL_PENALTY_SEC : 0);
    }

    private DutyPlan toPlan(DutyCandidate candidate, int localDutyNo, ValidationContext context) {
        List<PieceOfWorkPlan> pieces = candidate.segments().stream()
                .map(segment -> new PieceOfWorkPlan(
                        segment.blockNo(),
                        segment.fromEventSeq(),
                        segment.toEventSeq(),
                        segment.startSec(),
                        segment.endSec(),
                        segment.startReliefStopId(),
                        segment.endReliefStopId()))
                .toList();

        // Soft findings are attached to the duty rather than dropped. A short duty is legal and still worth a
        // planner's attention, because the depot pays the guarantee for it.
        List<EngineConflict> dutyConflicts = evaluator.evaluate(candidate, context).stream()
                .map(Violation::toConflict)
                .toList();

        var metrics = candidate.metrics();
        return new DutyPlan(
                localDutyNo,
                SchedulingMode.LINKED,
                metrics.dutyType(),
                metrics.signOnSec(),
                metrics.signOffSec(),
                metrics.platformSec(),
                metrics.paidSec(),
                metrics.breakSec(),
                metrics.spreadSec(),
                metrics.overtimeSec(),
                pieces,
                dutyConflicts);
    }

    /**
     * Where a cut happens, or null when it is at the depot.
     *
     * @param atStart whether the time is the start of a stretch, in which case the stop is where the bus will be
     *     when the incoming crew takes it
     */
    private Long reliefStopAt(List<BlockEvent> events, int atSec, boolean atStart) {
        if (atStart) {
            // The event is found first and only then mapped. Mapping inside the stream would hand a null
            // stop id to findFirst, which refuses it: a pull-out legitimately starts nowhere but the depot.
            return events.stream()
                    .filter(event -> event.startSec() == atSec)
                    .findFirst()
                    .map(BlockEvent::fromStopId)
                    .orElse(null);
        }
        BlockEvent ending = eventEndingAt(events, atSec);
        return ending == null ? null : ending.toStopId();
    }

    private static BlockEvent eventEndingAt(List<BlockEvent> events, int atSec) {
        return events.stream()
                .filter(event -> event.endSec() == atSec)
                .filter(event -> event.type() != BlockEventType.PULL_OUT)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** A legal candidate and what it costs, so the search can compare without recomputing. */
    private record Candidate(int endCut, DutyCandidate candidate, int cost) {}

    private record BlockCut(
            List<DutyPlan> duties, List<HandoverPlan> handovers, List<EngineConflict> conflicts) {}

    /**
     * @param conflicts raised while cutting, which is where {@code NO_FEASIBLE_RELIEF} comes from
     */
    public record Result(
            List<DutyPlan> duties, List<HandoverPlan> handovers, List<EngineConflict> conflicts) {

        public Result {
            duties = List.copyOf(duties);
            handovers = List.copyOf(handovers);
            conflicts = List.copyOf(conflicts);
        }
    }
}
