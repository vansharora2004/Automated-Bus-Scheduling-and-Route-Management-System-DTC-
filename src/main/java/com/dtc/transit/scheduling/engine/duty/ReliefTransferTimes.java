package com.dtc.transit.scheduling.engine.duty;

import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * How long a crew takes to get from one relief point to another.
 *
 * <p>Not the same as a bus deadhead. A crew changing buses travels as a passenger, which means waiting for
 * something going their way, so the time is the bus movement time plus a factor for waiting. Using the raw
 * deadhead would make every transfer look faster than it is, and the schedule would ask people to be in two
 * places at once.
 *
 * <p>Sign-on and sign-off travel are the depot cases: a crew signing on at the depot and taking over a bus at a
 * terminal has to get there first.
 */
public class ReliefTransferTimes {

    /**
     * Multiple of the bus movement time that a crew transfer takes.
     *
     * <p>1.5, because a crew transferring is a passenger: they wait for a service, and the service does not run
     * to their convenience. Too low here produces handovers nobody can make.
     */
    public static final double TRANSFER_FACTOR = 1.5;

    private final DepotContext depot;
    private final RuleSet rules;

    public ReliefTransferTimes(DepotContext depot, RuleSet rules) {
        this.depot = depot;
        this.rules = rules;
    }

    /**
     * Seconds for a crew to move between two relief points.
     *
     * @param fromStopId null means the depot
     * @param toStopId null means the depot
     */
    public int transferSec(Long fromStopId, Long toStopId, int atSec) {
        if (java.util.Objects.equals(fromStopId, toStopId)) {
            // Same place, including depot to depot. No travel, and the handover buffer alone applies.
            return 0;
        }
        if (fromStopId == null) {
            return scaled(depot.travelTimes().fromDepot(toStopId));
        }
        if (toStopId == null) {
            return scaled(depot.travelTimes().toDepot(fromStopId));
        }
        return scaled(depot.travelTimes().betweenStops(fromStopId, toStopId, atSec));
    }

    /**
     * The whole gap a handover needs: the travel plus the buffer.
     *
     * <p>One method rather than two, so no caller can remember the travel and forget the buffer. The buffer is
     * what absorbs a late-running bus, and a handover planned without it is one that fails on the first bad day.
     */
    public int requiredGapSec(Long fromStopId, Long toStopId, int atSec) {
        return transferSec(fromStopId, toStopId, atSec) + rules.handoverBufferSec();
    }

    private static int scaled(int busSeconds) {
        return (int) Math.round(busSeconds * TRANSFER_FACTOR);
    }
}
