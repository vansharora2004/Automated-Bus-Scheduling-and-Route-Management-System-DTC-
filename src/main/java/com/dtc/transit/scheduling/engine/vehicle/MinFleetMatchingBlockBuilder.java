package com.dtc.transit.scheduling.engine.vehicle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.UncoveredTrip;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/**
 * The optimal-fleet block builder, and the lower bound the greedy result is judged against.
 *
 * <p>The problem is a minimum path cover of a directed acyclic graph: an edge from trip i to trip j means a
 * bus finishing i can reach and start j legally. Every chain of trips is one bus, so the fewest buses is the
 * trip count minus the largest set of edges that can be used without reusing a trip as anyone's predecessor
 * or successor — a maximum bipartite matching. Hopcroft-Karp finds it in O(E √V).
 *
 * <p>This is optimal <em>under the model</em>, and the model is the point. It ignores which physical bus goes
 * where, electric range and depot parking, all of which the greedy builder handles. So this is the lower
 * bound rather than the default: a number to measure against, and an alternative builder for a depot where
 * fleet size dominates everything else.
 *
 * <p>Edges are pruned to a time window. Without pruning a 1,500-trip day has over a million candidate edges,
 * most of them joining trips twelve hours apart that no sane schedule would chain.
 */
public class MinFleetMatchingBlockBuilder implements BlockBuilder {

    /**
     * How far ahead an edge may reach, in seconds.
     *
     * <p>Four hours. Long enough to include every chain a real block would use, short enough to keep the
     * graph sparse. A larger window changes the answer only by allowing a bus to idle for half a day, which
     * the maximum block duration would reject anyway.
     */
    public static final int EDGE_WINDOW_SEC = 4 * 3600;

    @Override
    public String name() {
        return "MIN_FLEET_MATCHING";
    }

    @Override
    public VehicleSchedule build(List<TripView> trips, DepotContext context, RuleSet rules) {
        List<TripView> ordered = trips.stream()
                .sorted(Comparator.comparingInt(TripView::startSec).thenComparingLong(TripView::id))
                .toList();
        if (ordered.isEmpty()) {
            return VehicleSchedule.of(ordered, List.of(), List.of(), List.of(), 0);
        }

        int[] successor = matchSuccessors(ordered, context, rules);
        int lowerBound = (int) Arrays.stream(successor).filter(s -> s < 0).count();

        List<Block> blocks = assembleChains(ordered, successor, context, rules);
        List<UncoveredTrip> uncovered = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        // A chain may still be rejected by the fleet size, which the matching does not model.
        if (blocks.size() > context.buses().size()) {
            List<Block> fitted = blocks.subList(0, context.buses().size());
            for (Block dropped : blocks.subList(context.buses().size(), blocks.size())) {
                for (Long tripId : dropped.tripIds()) {
                    uncovered.add(new UncoveredTrip(tripId, UncoveredTrip.NO_VEHICLE_AVAILABLE));
                    conflicts.add(EngineConflict.hard(
                            ConflictTypes.UNCOVERED_TRIP,
                            "Trip %d needs a %d'th bus and depot %d has %d"
                                    .formatted(tripId, blocks.size(), context.depotId(), context.buses().size()),
                            List.of(new EntityRef("TRIP", tripId))));
                }
            }
            blocks = renumber(fitted);
        }

        return VehicleSchedule.of(ordered, blocks, uncovered, conflicts, lowerBound);
    }

    /**
     * The lower bound on its own, without building blocks.
     *
     * <p>Used to report how far above optimal the greedy answer is. Computing it separately means a run can
     * have the number without paying for a second full build.
     */
    public int minimumFleet(List<TripView> trips, DepotContext context, RuleSet rules) {
        List<TripView> ordered = trips.stream()
                .sorted(Comparator.comparingInt(TripView::startSec).thenComparingLong(TripView::id))
                .toList();
        if (ordered.isEmpty()) {
            return 0;
        }
        int[] successor = matchSuccessors(ordered, context, rules);
        return (int) Arrays.stream(successor).filter(s -> s < 0).count();
    }

    /**
     * Maximum matching between trips as predecessors and trips as successors.
     *
     * @return for each trip index, the index of the trip that follows it on the same bus, or -1 when the bus
     *     finishes its day there. The count of -1 entries is the number of buses.
     */
    private int[] matchSuccessors(List<TripView> trips, DepotContext context, RuleSet rules) {
        int n = trips.size();
        List<List<Integer>> edges = buildEdges(trips, context, rules);

        // Only the predecessor side is tracked during the search. Maintaining both directions while paths are
        // being rewritten is where this algorithm is usually got wrong; the successor view is derived once at
        // the end, when the matching is final and cannot disagree with itself.
        int[] predecessorOf = new int[n];
        Arrays.fill(predecessorOf, -1);

        boolean[] visited = new boolean[n];
        for (int left = 0; left < n; left++) {
            Arrays.fill(visited, false);
            augment(left, edges, predecessorOf, visited);
        }

        int[] successorOf = new int[n];
        Arrays.fill(successorOf, -1);
        for (int right = 0; right < n; right++) {
            if (predecessorOf[right] >= 0) {
                successorOf[predecessorOf[right]] = right;
            }
        }
        return successorOf;
    }

    /**
     * Kuhn's augmenting-path search for one left vertex.
     *
     * <p>Hopcroft-Karp's phase structure is the textbook route to O(E √V). On a graph this sparse the simpler
     * augmenting search reaches the same maximum matching, and being obviously correct matters more here than
     * a constant factor on a number that is only ever used as a reference bound.
     */
    private boolean augment(int left, List<List<Integer>> edges, int[] predecessorOf, boolean[] visited) {
        for (int right : edges.get(left)) {
            if (visited[right]) {
                continue;
            }
            visited[right] = true;
            if (predecessorOf[right] < 0 || augment(predecessorOf[right], edges, predecessorOf, visited)) {
                predecessorOf[right] = left;
                return true;
            }
        }
        return false;
    }

    /** Adjacency: which trips can directly follow each trip, within the pruning window. */
    private List<List<Integer>> buildEdges(List<TripView> trips, DepotContext context, RuleSet rules) {
        int n = trips.size();
        List<List<Integer>> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            edges.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            TripView from = trips.get(i);
            int layover = rules.minLayoverSecAfter(from.durationSec());
            for (int j = i + 1; j < n; j++) {
                TripView to = trips.get(j);
                if (to.startSec() - from.endSec() > EDGE_WINDOW_SEC) {
                    // Trips are start-ordered, so once one is out of the window every later one is too.
                    break;
                }
                int deadhead = from.endStopId() == to.startStopId()
                        ? 0
                        : context.travelTimes().betweenStops(from.endStopId(), to.startStopId(), from.endSec());
                if (from.endSec() + layover + deadhead <= to.startSec() && classesCompatible(from, to)) {
                    edges.get(i).add(j);
                }
            }
        }
        return edges;
    }

    private static boolean classesCompatible(TripView from, TripView to) {
        return from.requiredVehicleClass() == null
                || to.requiredVehicleClass() == null
                || from.requiredVehicleClass().equals(to.requiredVehicleClass());
    }

    /** Walks the matching into chains and turns each chain into a block. */
    private List<Block> assembleChains(
            List<TripView> trips, int[] successor, DepotContext context, RuleSet rules) {
        int n = trips.size();
        boolean[] isSuccessor = new boolean[n];
        for (int s : successor) {
            if (s >= 0) {
                isSuccessor[s] = true;
            }
        }

        boolean allElectric = !context.buses().isEmpty()
                && context.buses().stream().allMatch(com.dtc.transit.scheduling.engine.model.BusView::isElectric);

        List<BlockDraft> drafts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isSuccessor[i]) {
                continue;
            }
            BlockDraft draft = new BlockDraft(trips.get(i), context, rules);
            for (int next = successor[i]; next >= 0; next = successor[next]) {
                draft.append(trips.get(next), allElectric);
            }
            drafts.add(draft);
        }

        List<BlockDraft> inOrder = drafts.stream()
                .sorted(Comparator.comparingInt(BlockDraft::pullOutSec).thenComparingInt(BlockDraft::endSec))
                .toList();
        List<Block> blocks = new ArrayList<>(inOrder.size());
        for (int i = 0; i < inOrder.size(); i++) {
            blocks.add(inOrder.get(i).seal(i + 1));
        }
        return blocks;
    }

    private static List<Block> renumber(List<Block> blocks) {
        List<Block> renumbered = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            renumbered.add(blocks.get(i).withBlockNo(i + 1));
        }
        return renumbered;
    }
}
