package com.dtc.transit.scheduling.engine.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import com.dtc.transit.scheduling.engine.constraint.DutyEvaluator;
import com.dtc.transit.scheduling.engine.constraint.ValidationContext;
import com.dtc.transit.scheduling.engine.duty.DutyCandidate;
import com.dtc.transit.scheduling.engine.duty.DutyMetricsCalculator;
import com.dtc.transit.scheduling.engine.duty.ReliefTransferTimes;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Improves a legal set of duties without ever making it illegal.
 *
 * <p>Two moves, both of which only ever reduce the cost: move a piece of work from one duty to another, and merge
 * two short duties into one. A move is applied only when every hard constraint still holds afterwards and the
 * cost strictly decreases, so the search cannot trade legality for efficiency — which is the failure mode that
 * would be invisible in the output and expensive in a depot.
 *
 * <p>Seeded and time-bounded. The seed comes from the run, so the same input and seed produce the same duties
 * across any number of runs; the budget keeps a 45-depot build predictable.
 *
 * <p>Only {@code MovePiece} and {@code MergeDuties} are implemented. {@code SwapPieces}, {@code SplitDuty} and
 * {@code ReCut} are in the plan and are not here: the build order says to add one move at a time and verify each
 * before the next, and two verified moves are worth more than five unverified ones.
 */
public class LocalSearchImprover {

    /** Cost weight per duty, which is what makes merging two short duties worthwhile. */
    public static final long DUTY_WEIGHT = 8L * 3600;

    /** Cost weight per second of paid time. */
    public static final long PAID_WEIGHT = 1;

    /** Cost weight per second of paid time that is not platform time, which is the waste a scheduler pays for. */
    public static final long IDLE_WEIGHT = 2;

    /** Cost weight per second of overtime. */
    public static final long OVERTIME_WEIGHT = 4;

    /** Cost weight per bus changeover beyond the first piece of a duty. */
    public static final long CHANGEOVER_WEIGHT = 20L * 60;

    private final DutyEvaluator evaluator;
    private final ReliefTransferTimes transferTimes;

    public LocalSearchImprover(DutyEvaluator evaluator, ReliefTransferTimes transferTimes) {
        this.evaluator = evaluator;
        this.transferTimes = transferTimes;
    }

    /**
     * Runs until nothing improves, the iteration cap is reached, or the budget runs out.
     *
     * @param seed taken from the run, so the search is reproducible
     * @param budgetMillis wall-clock ceiling; the search returns the best legal solution it has at that point
     * @return the improved duties, each still a list of pieces in time order
     */
    public Result improve(
            List<List<DutyCandidate.WorkSegment>> duties,
            ValidationContext context,
            RuleSet rules,
            long seed,
            long budgetMillis,
            int maxIterations) {

        List<List<DutyCandidate.WorkSegment>> current = new ArrayList<>();
        duties.forEach(duty -> current.add(new ArrayList<>(duty)));

        long startedAt = System.nanoTime();
        var random = new SplittableRandom(seed);
        long costBefore = totalCost(current, rules);
        long cost = costBefore;
        int applied = 0;
        int iterations = 0;

        while (iterations < maxIterations
                && (System.nanoTime() - startedAt) / 1_000_000 < budgetMillis) {
            iterations++;

            boolean improved = tryMovePiece(current, context, rules, random)
                    || tryMergeDuties(current, context, rules, random);
            if (!improved) {
                break;
            }
            long after = totalCost(current, rules);
            // A move that did not reduce the cost is a bug in the move, not a reason to continue.
            if (after >= cost) {
                break;
            }
            cost = after;
            applied++;
        }

        current.removeIf(List::isEmpty);
        // Re-sorted so the output order depends on the duties, not on the order moves happened to be applied.
        current.sort(Comparator.comparingInt((List<DutyCandidate.WorkSegment> duty) -> duty.get(0).startSec())
                .thenComparingInt(duty -> duty.get(0).blockNo()));

        return new Result(current, costBefore, cost, applied, iterations);
    }

    /**
     * Moves one piece from the duty it is in to another duty that can take it.
     *
     * <p>Tried in a seeded order rather than exhaustively: the first improving move is taken, which keeps one
     * iteration cheap and lets the budget decide how much searching happens.
     */
    private boolean tryMovePiece(
            List<List<DutyCandidate.WorkSegment>> duties,
            ValidationContext context,
            RuleSet rules,
            SplittableRandom random) {

        if (duties.size() < 2) {
            return false;
        }
        int from = random.nextInt(duties.size());

        for (int offset = 0; offset < duties.size(); offset++) {
            int to = (from + 1 + offset) % duties.size();
            if (to == from || duties.get(from).isEmpty()) {
                continue;
            }
            for (int p = 0; p < duties.get(from).size(); p++) {
                var piece = duties.get(from).get(p);

                List<DutyCandidate.WorkSegment> sourceAfter = new ArrayList<>(duties.get(from));
                sourceAfter.remove(p);
                List<DutyCandidate.WorkSegment> targetAfter = insertInOrder(duties.get(to), piece);

                if (!isLegal(targetAfter, context, rules) || !isLegal(sourceAfter, context, rules)) {
                    continue;
                }
                if (!reachable(targetAfter)) {
                    continue;
                }

                long before = dutyCost(duties.get(from), rules) + dutyCost(duties.get(to), rules);
                long after = dutyCost(sourceAfter, rules) + dutyCost(targetAfter, rules);
                if (after >= before) {
                    continue;
                }

                duties.set(from, sourceAfter);
                duties.set(to, targetAfter);
                return true;
            }
        }
        return false;
    }

    /**
     * Merges two duties into one when the result is legal and cheaper.
     *
     * <p>The move that pays for itself most often: two duties of two hours each cost two minimum-paid guarantees,
     * where one duty of four hours costs one.
     */
    private boolean tryMergeDuties(
            List<List<DutyCandidate.WorkSegment>> duties,
            ValidationContext context,
            RuleSet rules,
            SplittableRandom random) {

        if (duties.size() < 2) {
            return false;
        }
        int first = random.nextInt(duties.size());

        for (int offset = 0; offset < duties.size(); offset++) {
            int second = (first + 1 + offset) % duties.size();
            if (second == first || duties.get(first).isEmpty() || duties.get(second).isEmpty()) {
                continue;
            }

            List<DutyCandidate.WorkSegment> merged = new ArrayList<>(duties.get(first));
            duties.get(second).forEach(piece -> merged.add(piece));
            merged.sort(Comparator.comparingInt(DutyCandidate.WorkSegment::startSec)
                    .thenComparingInt(DutyCandidate.WorkSegment::blockNo));

            if (overlaps(merged) || !reachable(merged) || !isLegal(merged, context, rules)) {
                continue;
            }

            long before = dutyCost(duties.get(first), rules) + dutyCost(duties.get(second), rules);
            long after = dutyCost(merged, rules);
            if (after >= before) {
                continue;
            }

            duties.set(first, merged);
            duties.set(second, new ArrayList<>());
            return true;
        }
        return false;
    }

    /** Whether every consecutive pair leaves enough time for the crew to make the transfer. */
    private boolean reachable(List<DutyCandidate.WorkSegment> duty) {
        for (int i = 1; i < duty.size(); i++) {
            var previous = duty.get(i - 1);
            var next = duty.get(i);
            int required = transferTimes.requiredGapSec(
                    previous.endReliefStopId(), next.startReliefStopId(), previous.endSec());
            if (next.startSec() - previous.endSec() < required) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(List<DutyCandidate.WorkSegment> duty) {
        for (int i = 1; i < duty.size(); i++) {
            if (duty.get(i).startSec() < duty.get(i - 1).endSec()) {
                return true;
            }
        }
        return false;
    }

    private boolean isLegal(List<DutyCandidate.WorkSegment> duty, ValidationContext context, RuleSet rules) {
        if (duty.isEmpty()) {
            // An emptied duty is legal: it simply ceases to exist.
            return true;
        }
        return evaluator.isLegal(
                new DutyCandidate(duty, DutyMetricsCalculator.compute(duty, rules)), context);
    }

    private static List<DutyCandidate.WorkSegment> insertInOrder(
            List<DutyCandidate.WorkSegment> duty, DutyCandidate.WorkSegment piece) {
        List<DutyCandidate.WorkSegment> result = new ArrayList<>(duty);
        result.add(piece);
        result.sort(Comparator.comparingInt(DutyCandidate.WorkSegment::startSec)
                .thenComparingInt(DutyCandidate.WorkSegment::blockNo));
        return overlaps(result) ? List.of() : result;
    }

    /**
     * The cost of one duty.
     *
     * <p>Weights from the architecture's cost function: duty count, paid time, idle paid time, overtime and
     * changeovers. An empty duty costs nothing, which is what makes merging show up as an improvement.
     */
    long dutyCost(List<DutyCandidate.WorkSegment> duty, RuleSet rules) {
        if (duty.isEmpty()) {
            return 0;
        }
        var metrics = DutyMetricsCalculator.compute(duty, rules);
        long idle = Math.max(0, metrics.paidSec() - metrics.platformSec());
        return DUTY_WEIGHT
                + PAID_WEIGHT * metrics.paidSec()
                + IDLE_WEIGHT * idle
                + OVERTIME_WEIGHT * metrics.overtimeSec()
                + CHANGEOVER_WEIGHT * Math.max(0, duty.size() - 1);
    }

    long totalCost(List<List<DutyCandidate.WorkSegment>> duties, RuleSet rules) {
        return duties.stream().mapToLong(duty -> dutyCost(duty, rules)).sum();
    }

    /**
     * @param costBefore and costAfter are reported so a run can show what the search bought, and so a regression
     *     that makes it do nothing is visible rather than silent
     */
    public record Result(
            List<List<DutyCandidate.WorkSegment>> duties,
            long costBefore,
            long costAfter,
            int movesApplied,
            int iterations) {}
}
