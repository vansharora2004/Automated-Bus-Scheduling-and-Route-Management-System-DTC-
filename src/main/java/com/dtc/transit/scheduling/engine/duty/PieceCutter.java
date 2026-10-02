package com.dtc.transit.scheduling.engine.duty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Cuts a block into pieces of work that a crew could take.
 *
 * <p>Dynamic programming over the block's relief opportunities. Each piece has to fall within an allowed length
 * range, and among the ways of satisfying that the cutter prefers fewer pieces and cuts at the depot. Greedy
 * cutting would work most of the time and then produce an unusable two-minute tail at the end of a block,
 * because a greedy choice early on cannot see what it leaves behind.
 *
 * <p>Complexity is O(R²) in the number of relief points, which on a long block is a few thousand evaluations.
 *
 * <p><strong>Where the length range comes from.</strong> The rule set has no explicit piece length, so the range
 * is derived from rules that already exist: a piece is worked without a break, so it cannot be longer than the
 * continuous-work limit, and a piece shorter than the minimum break is not worth the changeover it costs. Adding
 * two more rule-set fields would have been the alternative, and would have made every stored rule set and every
 * fixture need migrating for values that are already implied.
 */
public class PieceCutter {

    /**
     * Penalty for a cut that is not at the depot, in seconds of equivalent cost.
     *
     * <p>A depot cut is free for the crew: they are already there to sign on or off. Anywhere else costs them a
     * journey, so the cutter prefers the depot when the lengths work out either way.
     */
    public static final int NON_DEPOT_CUT_PENALTY_SEC = 20 * 60;

    /** Penalty per piece, which is what makes the cutter prefer fewer of them. */
    public static final int PIECE_PENALTY_SEC = 60 * 60;

    /**
     * Cuts one block.
     *
     * @return the pieces in time order; a single piece covering the whole block when it is short enough
     */
    public List<DutyCandidate.WorkSegment> cut(Block block, RuleSet rules) {
        List<Integer> cuts = cutPointsOf(block);
        int n = cuts.size();
        if (n < 2) {
            return List.of();
        }

        int minPieceSec = rules.minBreakSec();
        int maxPieceSec = rules.maxContinuousWorkSec();

        // best[i] is the lowest cost of covering the block from its start up to cut point i.
        long[] best = new long[n];
        int[] previous = new int[n];
        java.util.Arrays.fill(best, Long.MAX_VALUE);
        java.util.Arrays.fill(previous, -1);
        best[0] = 0;

        for (int end = 1; end < n; end++) {
            for (int start = 0; start < end; start++) {
                if (best[start] == Long.MAX_VALUE) {
                    continue;
                }
                int length = cuts.get(end) - cuts.get(start);
                if (length > maxPieceSec) {
                    // Cut points are ordered, so every earlier start is even longer.
                    continue;
                }
                // The final piece is allowed to be short: the end of a block has to go somewhere, and refusing
                // it would leave the block uncut rather than awkwardly cut.
                if (length < minPieceSec && end != n - 1) {
                    continue;
                }
                long cost = best[start] + pieceCost(block, cuts.get(end), length, rules);
                if (cost < best[end]) {
                    best[end] = cost;
                    previous[end] = start;
                }
            }
        }

        if (best[n - 1] == Long.MAX_VALUE) {
            // No combination of cut points satisfies the length range. The block is returned as one piece, which
            // the duty rules will then reject with NO_FEASIBLE_RELIEF rather than the work silently vanishing.
            return List.of(segmentFor(block, cuts.get(0), cuts.get(n - 1)));
        }

        List<DutyCandidate.WorkSegment> pieces = new ArrayList<>();
        for (int at = n - 1; at > 0; at = previous[at]) {
            pieces.add(segmentFor(block, cuts.get(previous[at]), cuts.get(at)));
        }
        Collections.reverse(pieces);
        return pieces;
    }

    /**
     * What one piece costs.
     *
     * <p>Three terms: a flat charge per piece so fewer is better, the distance from an ideal piece length, and a
     * penalty for cutting away from the depot.
     */
    private long pieceCost(Block block, int endSec, int lengthSec, RuleSet rules) {
        int ideal = rules.maxContinuousWorkSec() * 3 / 4;
        long cost = PIECE_PENALTY_SEC + Math.abs(lengthSec - ideal);
        if (!isAtDepot(block, endSec)) {
            cost += NON_DEPOT_CUT_PENALTY_SEC;
        }
        return cost;
    }

    /** Whether a cut time falls at the depot, which is where a crew signs on and off anyway. */
    private boolean isAtDepot(Block block, int atSec) {
        return block.events().stream()
                .filter(event -> event.endSec() == atSec)
                .anyMatch(event -> event.type() == BlockEventType.PULL_IN
                        || event.type() == BlockEventType.DEPOT_PARK
                        || event.type() == BlockEventType.CHARGING);
    }

    private DutyCandidate.WorkSegment segmentFor(Block block, int startSec, int endSec) {
        List<BlockEvent> covered = block.events().stream()
                .filter(event -> event.startSec() >= startSec && event.endSec() <= endSec)
                .toList();
        if (covered.isEmpty()) {
            // Cannot happen for cut points taken from the block's own events, but a zero-event piece would
            // produce a duty with no work in it, so it is refused loudly rather than stored.
            throw new IllegalStateException(
                    "block " + block.blockNo() + " has no events between " + startSec + " and " + endSec);
        }
        return new DutyCandidate.WorkSegment(
                block.blockNo(),
                covered.get(0).seq(),
                covered.get(covered.size() - 1).seq(),
                startSec,
                endSec,
                stopAtStart(block, startSec),
                stopAtEnd(block, endSec),
                covered);
    }

    /**
     * The times at which this block can change crew.
     *
     * <p>Identical to the linked builder's notion of a cut point, and deliberately so: both stages have to agree
     * about where a handover is physically possible, or an unlinked schedule would place one where a linked one
     * would not.
     */
    public List<Integer> cutPointsOf(Block block) {
        List<Integer> cuts = new ArrayList<>();
        cuts.add(block.pullOutSec());
        for (BlockEvent event : block.events()) {
            if (event.reliefOpportunity() && event.endSec() > cuts.get(cuts.size() - 1)) {
                cuts.add(event.endSec());
            }
        }
        if (cuts.get(cuts.size() - 1) < block.pullInSec()) {
            cuts.add(block.pullInSec());
        }
        return cuts;
    }

    private static Long stopAtStart(Block block, int atSec) {
        return block.events().stream()
                .filter(event -> event.startSec() == atSec)
                .findFirst()
                .map(BlockEvent::fromStopId)
                .orElse(null);
    }

    private static Long stopAtEnd(Block block, int atSec) {
        return block.events().stream()
                .filter(event -> event.endSec() == atSec)
                .filter(event -> event.type() != BlockEventType.PULL_OUT)
                .reduce((first, second) -> second)
                .map(BlockEvent::toStopId)
                .orElse(null);
    }
}
