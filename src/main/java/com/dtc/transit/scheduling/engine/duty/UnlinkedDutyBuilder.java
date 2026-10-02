package com.dtc.transit.scheduling.engine.duty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dtc.transit.scheduling.engine.constraint.Constraint;
import com.dtc.transit.scheduling.engine.constraint.DutyConstraints;
import com.dtc.transit.scheduling.engine.constraint.DutyEvaluator;
import com.dtc.transit.scheduling.engine.constraint.HandoverConstraint;
import com.dtc.transit.scheduling.engine.constraint.ValidationContext;
import com.dtc.transit.scheduling.engine.constraint.Violation;
import com.dtc.transit.scheduling.engine.model.Block;
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
 * Builds duties from pieces of work that may come from different buses.
 *
 * <p>This is where the efficiency over linked scheduling comes from. A block that leaves a two-hour tail has
 * nowhere to put it in linked mode; in unlinked mode that tail combines with a tail from another bus into one
 * full duty.
 *
 * <p>Greedy construction, exactly as specified: take the earliest unassigned piece, then repeatedly add the
 * cheapest piece that is both reachable and legal. Correctness before optimisation — a legal greedy schedule is
 * usable, and a cheaper one that violates a rest rule is not, so the local search runs afterwards and only ever
 * accepts moves that keep every hard constraint satisfied.
 *
 * <p>Every piece ends up in exactly one duty. The pool is consumed as pieces are taken, which makes that true by
 * construction rather than by a check afterwards.
 */
public class UnlinkedDutyBuilder {

    private final PieceCutter pieceCutter;

    public UnlinkedDutyBuilder() {
        this(new PieceCutter());
    }

    public UnlinkedDutyBuilder(PieceCutter pieceCutter) {
        this.pieceCutter = pieceCutter;
    }

    /** Greedy construction only, with no local search. Kept for tests that isolate the construction. */
    public Result build(VehicleSchedule schedule, DepotContext depot, RuleSet rules) {
        return build(schedule, depot, rules, 0L, 0L);
    }

    /**
     * Cuts, builds greedily, then improves within the budget.
     *
     * @param seed from the run, so the search is reproducible
     * @param searchBudgetMillis zero to skip the search entirely
     */
    public Result build(
            VehicleSchedule schedule, DepotContext depot, RuleSet rules, long seed, long searchBudgetMillis) {
        var transferTimes = new ReliefTransferTimes(depot, rules);
        var evaluator = evaluatorFor(transferTimes);
        var context = new ValidationContext(rules, depot);

        // Cut every block first, so the pool is the whole depot-day rather than one bus at a time.
        List<DutyCandidate.WorkSegment> pool = new ArrayList<>();
        for (Block block : schedule.blocks()) {
            pool.addAll(pieceCutter.cut(block, rules));
        }
        // Sorted by start, then block, then event sequence. The tiebreaks are what make two runs over the same
        // input produce the same duties; without them the result would depend on block iteration order.
        pool.sort(Comparator.comparingInt(DutyCandidate.WorkSegment::startSec)
                .thenComparingInt(DutyCandidate.WorkSegment::blockNo)
                .thenComparingInt(DutyCandidate.WorkSegment::fromEventSeq));

        List<List<DutyCandidate.WorkSegment>> duties = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();
        List<DutyCandidate.WorkSegment> unassigned = new ArrayList<>(pool);

        while (!unassigned.isEmpty()) {
            List<DutyCandidate.WorkSegment> duty = new ArrayList<>();
            duty.add(unassigned.remove(0));

            while (true) {
                DutyCandidate.WorkSegment bestPiece = null;
                long bestCost = Long.MAX_VALUE;

                for (DutyCandidate.WorkSegment piece : unassigned) {
                    var last = duty.get(duty.size() - 1);
                    if (piece.startSec() < last.endSec()) {
                        continue;
                    }
                    int gap = piece.startSec() - last.endSec();
                    int required = transferTimes.requiredGapSec(
                            last.endReliefStopId(), piece.startReliefStopId(), last.endSec());
                    if (gap < required) {
                        continue;
                    }

                    List<DutyCandidate.WorkSegment> extended = new ArrayList<>(duty);
                    extended.add(piece);
                    if (!evaluator.isLegal(candidate(extended, rules), context)) {
                        continue;
                    }

                    long cost = candidateCost(last, piece, gap, required, duty, rules);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestPiece = piece;
                    }
                }

                if (bestPiece == null) {
                    break;
                }
                duty.add(bestPiece);
                unassigned.remove(bestPiece);
            }

            // A single piece that is itself illegal cannot be fixed by adding more work, so it is emitted with a
            // conflict rather than dropped: the bus still runs, and somebody has to be told it cannot be staffed
            // legally.
            var built = candidate(duty, rules);
            var hard = evaluator.hardViolations(built, context);
            if (!hard.isEmpty()) {
                conflicts.add(EngineConflict.hard(
                        hard.get(0).code().equals(ConflictTypes.HANDOVER_INFEASIBLE)
                                ? ConflictTypes.HANDOVER_INFEASIBLE
                                : ConflictTypes.NO_FEASIBLE_RELIEF,
                        "A duty built from block %s could not be made legal: %s"
                                .formatted(built.blockNos(), hard.get(0).message()),
                        List.of()));
            }
            duties.add(duty);
        }

        if (searchBudgetMillis > 0 && duties.size() > 1) {
            // Correctness first: the greedy result above is already legal and complete, and the search only ever
            // accepts a move that keeps it that way and reduces the cost.
            var improved = new com.dtc.transit.scheduling.engine.search.LocalSearchImprover(
                            evaluator, transferTimes)
                    .improve(duties, context, rules, seed, searchBudgetMillis, MAX_SEARCH_ITERATIONS);
            duties = improved.duties();
        }

        return finalise(duties, schedule, evaluator, context, rules);
    }

    /** Iteration cap, so a generous time budget cannot spin for minutes on a solution nothing improves. */
    public static final int MAX_SEARCH_ITERATIONS = 20_000;

    /**
     * How undesirable it is to add a piece to a duty.
     *
     * <p>Prefers a small idle gap, staying at the same relief point, and — when a break is still owed — a gap
     * long enough to serve as one. The break term matters: without it the builder fills every gap tightly and
     * then discovers the duty has no legal break left anywhere.
     */
    private long candidateCost(
            DutyCandidate.WorkSegment last,
            DutyCandidate.WorkSegment piece,
            int gap,
            int required,
            List<DutyCandidate.WorkSegment> duty,
            RuleSet rules) {

        long cost = gap - required;
        if (!java.util.Objects.equals(last.endReliefStopId(), piece.startReliefStopId())) {
            // A changeover costs the crew a journey and the schedule a point of failure.
            cost += 30L * 60;
        }
        if (breakIsOwed(duty, rules) && gap >= rules.minBreakSec()) {
            // This gap can serve as the break the duty still needs, which is worth more than a tight fit.
            cost -= 45L * 60;
        }
        return cost;
    }

    /** Whether the duty so far has worked long enough to need a break before it can continue. */
    private boolean breakIsOwed(List<DutyCandidate.WorkSegment> duty, RuleSet rules) {
        return DutyMetricsCalculator.compute(duty, rules).longestStretchSec()
                > rules.maxContinuousWorkSec() / 2;
    }

    /**
     * Numbers the duties, derives the handovers, and collects the soft findings.
     *
     * <p>Handovers come from the pieces rather than from the duties: two pieces of the same block that end up in
     * different duties are a crew change on that bus, at the moment one piece ends and the next begins.
     */
    private Result finalise(
            List<List<DutyCandidate.WorkSegment>> duties,
            VehicleSchedule schedule,
            DutyEvaluator evaluator,
            ValidationContext context,
            RuleSet rules) {

        List<DutyPlan> plans = new ArrayList<>(duties.size());
        Map<PieceKey, Integer> dutyNoByPiece = new LinkedHashMap<>();

        for (int i = 0; i < duties.size(); i++) {
            List<DutyCandidate.WorkSegment> segments = duties.get(i);
            int dutyNo = i + 1;
            segments.forEach(segment -> dutyNoByPiece.put(
                    new PieceKey(segment.blockNo(), segment.fromEventSeq()), dutyNo));

            var built = candidate(segments, rules);
            var metrics = built.metrics();
            List<EngineConflict> dutyConflicts = evaluator.evaluate(built, context).stream()
                    .map(Violation::toConflict)
                    .toList();

            plans.add(new DutyPlan(
                    dutyNo,
                    SchedulingMode.UNLINKED,
                    metrics.dutyType(),
                    metrics.signOnSec(),
                    metrics.signOffSec(),
                    metrics.platformSec(),
                    metrics.paidSec(),
                    metrics.breakSec(),
                    metrics.spreadSec(),
                    metrics.overtimeSec(),
                    segments.stream()
                            .map(segment -> new PieceOfWorkPlan(
                                    segment.blockNo(),
                                    segment.fromEventSeq(),
                                    segment.toEventSeq(),
                                    segment.startSec(),
                                    segment.endSec(),
                                    segment.startReliefStopId(),
                                    segment.endReliefStopId()))
                            .toList(),
                    dutyConflicts));
        }

        List<HandoverPlan> handovers = new ArrayList<>();
        for (Block block : schedule.blocks()) {
            List<DutyCandidate.WorkSegment> ofBlock = duties.stream()
                    .flatMap(List::stream)
                    .filter(segment -> segment.blockNo() == block.blockNo())
                    .sorted(Comparator.comparingInt(DutyCandidate.WorkSegment::startSec))
                    .toList();

            for (int i = 1; i < ofBlock.size(); i++) {
                Integer outgoing = dutyNoByPiece.get(
                        new PieceKey(ofBlock.get(i - 1).blockNo(), ofBlock.get(i - 1).fromEventSeq()));
                Integer incoming = dutyNoByPiece.get(
                        new PieceKey(ofBlock.get(i).blockNo(), ofBlock.get(i).fromEventSeq()));
                if (outgoing == null || incoming == null || outgoing.equals(incoming)) {
                    // The same crew continuing on the same bus is not a handover, even in unlinked mode.
                    continue;
                }
                handovers.add(new HandoverPlan(
                        block.blockNo(),
                        ofBlock.get(i - 1).endReliefStopId(),
                        ofBlock.get(i - 1).endSec(),
                        outgoing,
                        incoming));
            }
        }

        return new Result(plans, handovers, List.of());
    }

    private static DutyCandidate candidate(List<DutyCandidate.WorkSegment> segments, RuleSet rules) {
        return new DutyCandidate(segments, DutyMetricsCalculator.compute(segments, rules));
    }

    /**
     * The duty catalogue plus the handover rule.
     *
     * <p>Handover feasibility is only meaningful once a duty can span buses, so it joins the catalogue here
     * rather than being part of it everywhere. The rest of the rules are shared with linked mode unchanged.
     */
    static DutyEvaluator evaluatorFor(ReliefTransferTimes transferTimes) {
        List<Constraint<DutyCandidate>> constraints = new ArrayList<>(DutyConstraints.all());
        constraints.add(new HandoverConstraint(transferTimes));
        return new DutyEvaluator(constraints);
    }

    /** Identifies a piece without relying on object identity, which the local search would break. */
    private record PieceKey(int blockNo, int fromEventSeq) {}

    public record Result(
            List<DutyPlan> duties, List<HandoverPlan> handovers, List<EngineConflict> conflicts) {

        public Result {
            duties = List.copyOf(duties);
            handovers = List.copyOf(handovers);
            conflicts = List.copyOf(conflicts);
        }
    }
}
