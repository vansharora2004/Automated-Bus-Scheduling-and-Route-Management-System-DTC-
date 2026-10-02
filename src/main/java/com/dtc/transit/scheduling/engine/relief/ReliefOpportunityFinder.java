package com.dtc.transit.scheduling.engine.relief;

import java.util.ArrayList;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/**
 * Marks the points in a block where one crew could hand over to another.
 *
 * <p>A handover needs three things at once: a place where a relief driver can reach the bus, enough time for
 * the change, and a bus that is actually stopped. Marking only on geography would put a relief in the middle
 * of a 40-second turnaround; marking only on time would relieve a crew at a roadside stop with no way to get
 * there.
 *
 * <p>Pull-out and pull-in are always opportunities: the bus is at the depot, which is where crews sign on and
 * off, so there is nothing to check.
 */
public class ReliefOpportunityFinder {

    /**
     * Returns the schedule with relief opportunities marked.
     *
     * <p>A copy rather than a mutation. The blocks have already been costed and validated, and silently
     * rewriting them would make it impossible to tell whether a later stage saw the marked version.
     */
    public VehicleSchedule mark(VehicleSchedule schedule, DepotContext context, RuleSet rules) {
        List<Block> marked = new ArrayList<>(schedule.blocks().size());
        for (Block block : schedule.blocks()) {
            marked.add(block.withEvents(markEvents(block, context, rules)));
        }
        return schedule.withBlocks(marked);
    }

    private List<BlockEvent> markEvents(Block block, DepotContext context, RuleSet rules) {
        List<BlockEvent> events = block.events();
        List<BlockEvent> result = new ArrayList<>(events.size());

        for (int i = 0; i < events.size(); i++) {
            BlockEvent event = events.get(i);
            result.add(isReliefOpportunity(event, nextDeparture(events, i), context, rules)
                    ? event.asReliefOpportunity()
                    : clearMark(event));
        }
        return result;
    }

    /**
     * When the bus next has to move, which is the deadline a crew change has to beat.
     *
     * <p>Null at the end of the block: once the bus is in for the night there is no following departure to be
     * late for.
     */
    private Integer nextDeparture(List<BlockEvent> events, int index) {
        for (int i = index + 1; i < events.size(); i++) {
            BlockEvent next = events.get(i);
            if (next.type() == BlockEventType.TRIP || next.type() == BlockEventType.PULL_OUT) {
                return next.startSec();
            }
        }
        return null;
    }

    private boolean isReliefOpportunity(
            BlockEvent event, Integer nextDepartureSec, DepotContext context, RuleSet rules) {
        // At the depot, by definition. Sign-on and sign-off happen here and a relief crew is already present.
        if (event.type() == BlockEventType.PULL_OUT
                || event.type() == BlockEventType.PULL_IN
                || event.type() == BlockEventType.DEPOT_PARK
                || event.type() == BlockEventType.CHARGING) {
            return true;
        }

        // Anywhere else the bus must be standing still at a stop a relief crew can reach.
        if (event.type() != BlockEventType.TRIP && event.type() != BlockEventType.LAYOVER) {
            return false;
        }
        Long stopId = event.toStopId();
        if (stopId == null || !context.isReliefPoint(stopId)) {
            return false;
        }

        // And the change has to fit before the bus leaves again. A relief at a stop the bus departs from
        // thirty seconds later is a relief that makes the bus late.
        if (nextDepartureSec == null) {
            return true;
        }
        return nextDepartureSec - event.endSec() >= rules.handoverBufferSec();
    }

    private static BlockEvent clearMark(BlockEvent event) {
        if (!event.reliefOpportunity()) {
            return event;
        }
        return new BlockEvent(
                event.seq(),
                event.type(),
                event.tripId(),
                event.fromStopId(),
                event.toStopId(),
                event.startSec(),
                event.endSec(),
                event.distanceM(),
                false);
    }
}
