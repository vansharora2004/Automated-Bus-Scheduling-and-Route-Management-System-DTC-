package com.dtc.transit.scheduling.engine.vehicle;

import java.util.ArrayList;
import java.util.List;

import com.dtc.transit.scheduling.engine.model.Block;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TravelTimes;
import com.dtc.transit.scheduling.engine.model.TripView;

/**
 * A block under construction.
 *
 * <p>Mutable, and deliberately package-private in spirit: the builder extends it trip by trip and seals it
 * into an immutable {@link Block} at the end. Doing this with immutable copies would allocate a new event
 * list per trip, which at fifty thousand trips is measurable for no benefit.
 *
 * <p>The draft tracks running kilometres and the electric range as it goes, so the feasibility test is a
 * comparison against fields rather than a walk back through the events.
 */
final class BlockDraft {

    private final List<BlockEvent> events = new ArrayList<>();
    private final RuleSet rules;
    private final TravelTimes travel;

    /** The strictest vehicle class any trip in the block has demanded, or null while none has. */
    private String vehicleClass;

    private int pullOutSec;
    private int pullInSec;
    private double serviceMetres;
    private double deadMetres;

    private TripView lastTrip;
    private long endStopId;
    private int endSec;

    /** True once a depot park has been inserted, which is what makes a split duty possible later. */
    private boolean parkedMidDay;

    BlockDraft(TripView first, DepotContext context, RuleSet rules) {
        this.rules = rules;
        this.travel = context.travelTimes();

        int pullOutTravel = travel.fromDepot(first.startStopId());
        this.pullOutSec = first.startSec() - pullOutTravel;
        if (pullOutSec < 0) {
            // A trip so early that the pull-out would precede the service day is still covered; the block
            // simply starts at zero. Refusing it would leave a real trip uncovered over an arithmetic edge.
            pullOutSec = 0;
        }
        double pullOutMetres = travel.distanceFromDepot(first.startStopId());

        add(new BlockEvent(
                0,
                BlockEventType.PULL_OUT,
                null,
                null,
                first.startStopId(),
                pullOutSec,
                first.startSec(),
                pullOutMetres,
                true));
        deadMetres += pullOutMetres;

        appendTrip(first);
    }

    /**
     * Whether this block can take a trip, and what it would cost.
     *
     * <p>Feasibility and cost come from the same method so the two cannot drift apart, which is the usual way a
     * scheduler ends up choosing a block it then rejects.
     *
     * @return the cost of the continuation, or empty when the trip cannot follow
     */
    java.util.Optional<Fit> fitFor(TripView trip) {
        if (!classSatisfies(trip.requiredVehicleClass())) {
            return java.util.Optional.empty();
        }

        int layover = rules.minLayoverSecAfter(lastTrip.durationSec());
        boolean sameStop = endStopId == trip.startStopId();
        int deadheadSec = sameStop ? 0 : travel.betweenStops(endStopId, trip.startStopId(), endSec);
        double deadheadMetres = sameStop ? 0 : travel.distanceBetweenStops(endStopId, trip.startStopId());

        int earliestStart = endSec + layover + deadheadSec;
        if (earliestStart > trip.startSec()) {
            return java.util.Optional.empty();
        }

        if (trip.endSec() - pullOutSec > rules.maxBlockDurationSec()) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new Fit(trip.startSec() - earliestStart, deadheadMetres));
    }

    /**
     * What it costs to add a trip to this block.
     *
     * <p>Two numbers, compared in this order, and the order matters more than it looks. Minimising slack alone
     * actively <em>prefers</em> continuations that need dead running, because the empty movement eats the gap and
     * so makes the slack smaller. Ranking dead kilometres first and only then tightness is what makes "best fit"
     * mean what the objective says: cover the work with the fewest buses and the least dead running.
     *
     * @param slackSec idle time the continuation would leave before the trip departs
     * @param deadheadMetres empty running needed to reach the trip's start stop
     */
    record Fit(int slackSec, double deadheadMetres) {

        /** Lower is better. Dead running first, then the tightest fit. */
        static final java.util.Comparator<Fit> CHEAPEST =
                java.util.Comparator.comparingDouble(Fit::deadheadMetres).thenComparingInt(Fit::slackSec);
    }

    /**
     * Whether adding a trip would push the block past the maximum duration.
     *
     * <p>Asked separately from {@link #slackFor} so the uncovered reason can name the limit that bound,
     * rather than reporting the uninformative "no block was feasible".
     */
    boolean wouldExceedDuration(TripView trip, RuleSet ruleSet) {
        return trip.endSec() - pullOutSec > ruleSet.maxBlockDurationSec();
    }

    /**
     * Whether an electric bus of the given range could still finish the block after taking a trip.
     *
     * <p>Checked against the reserve rather than the nameplate range. Planning to arrive on an empty battery
     * leaves no margin for traffic, a diversion or a cold morning.
     */
    boolean withinMetres(TripView trip, double usableMetres) {
        double deadhead =
                endStopId == trip.startStopId() ? 0 : travel.distanceBetweenStops(endStopId, trip.startStopId());
        double afterTrip = serviceMetres + deadMetres + deadhead + trip.distanceM();
        double pullIn = travel.distanceToDepot(trip.endStopId());
        return afterTrip + pullIn <= usableMetres;
    }

    /**
     * Extends the block with a trip, inserting whatever has to happen before it.
     *
     * <p>A long idle gap becomes a depot return rather than a layover standing at a terminal: the bus is out
     * of the way, it can charge, and the crew can be relieved there.
     */
    void append(TripView trip, boolean electric) {
        boolean sameStop = endStopId == trip.startStopId();
        int deadhead = sameStop ? 0 : travel.betweenStops(endStopId, trip.startStopId(), endSec);
        int idle = trip.startSec() - endSec - deadhead;

        // The depot return only happens when the round trip actually fits inside the gap. A depot further
        // away than the gap is long would otherwise produce a pull-out that starts before the pull-in ends.
        boolean depotRoundTripFits =
                trip.startSec() - travel.fromDepot(trip.startStopId()) >= endSec + travel.toDepot(endStopId);

        if (idle >= rules.midDayDepotReturnGapSec() && depotRoundTripFits) {
            appendDepotReturn(trip, electric);
        } else {
            if (!sameStop) {
                double metres = travel.distanceBetweenStops(endStopId, trip.startStopId());
                add(new BlockEvent(
                        events.size(),
                        BlockEventType.DEADHEAD,
                        null,
                        endStopId,
                        trip.startStopId(),
                        endSec,
                        endSec + deadhead,
                        metres,
                        false));
                deadMetres += metres;
            }
            int layoverStart = endSec + deadhead;
            if (trip.startSec() > layoverStart) {
                add(new BlockEvent(
                        events.size(),
                        BlockEventType.LAYOVER,
                        null,
                        trip.startStopId(),
                        trip.startStopId(),
                        layoverStart,
                        trip.startSec(),
                        0,
                        false));
            }
        }

        appendTrip(trip);
    }

    private void appendDepotReturn(TripView trip, boolean electric) {
        int pullInTravel = travel.toDepot(endStopId);
        double pullInMetres = travel.distanceToDepot(endStopId);
        int arriveDepot = endSec + pullInTravel;

        add(new BlockEvent(
                events.size(),
                BlockEventType.PULL_IN,
                null,
                endStopId,
                null,
                endSec,
                arriveDepot,
                pullInMetres,
                true));
        deadMetres += pullInMetres;

        int outTravel = travel.fromDepot(trip.startStopId());
        double outMetres = travel.distanceFromDepot(trip.startStopId());
        int leaveDepot = trip.startSec() - outTravel;

        if (electric) {
            // Charging occupies the front of the layover, not the whole of it, so the plan does not assume a
            // bay is free for hours. A bay count is enforced from Phase 10; this records the intent.
            int chargeEnd = Math.min(leaveDepot, arriveDepot + rules.evChargingMin() * 60);
            if (chargeEnd > arriveDepot) {
                add(new BlockEvent(
                        events.size(),
                        BlockEventType.CHARGING,
                        null,
                        null,
                        null,
                        arriveDepot,
                        chargeEnd,
                        0,
                        false));
            }
            if (leaveDepot > chargeEnd) {
                add(parkEvent(chargeEnd, leaveDepot));
            }
        } else if (leaveDepot > arriveDepot) {
            add(parkEvent(arriveDepot, leaveDepot));
        }

        add(new BlockEvent(
                events.size(),
                BlockEventType.PULL_OUT,
                null,
                null,
                trip.startStopId(),
                leaveDepot,
                trip.startSec(),
                outMetres,
                true));
        deadMetres += outMetres;
        parkedMidDay = true;
    }

    private BlockEvent parkEvent(int fromSec, int toSec) {
        return new BlockEvent(events.size(), BlockEventType.DEPOT_PARK, null, null, null, fromSec, toSec, 0, true);
    }

    private void appendTrip(TripView trip) {
        add(new BlockEvent(
                events.size(),
                BlockEventType.TRIP,
                trip.id(),
                trip.startStopId(),
                trip.endStopId(),
                trip.startSec(),
                trip.endSec(),
                trip.distanceM(),
                false));
        serviceMetres += trip.distanceM();
        lastTrip = trip;
        endStopId = trip.endStopId();
        endSec = trip.endSec();
        if (trip.requiredVehicleClass() != null) {
            vehicleClass = trip.requiredVehicleClass();
        }
    }

    /** Seals the draft, adding the final pull-in. */
    Block seal(int blockNo) {
        int pullInTravel = travel.toDepot(endStopId);
        double pullInMetres = travel.distanceToDepot(endStopId);
        pullInSec = endSec + pullInTravel;
        add(new BlockEvent(
                events.size(),
                BlockEventType.PULL_IN,
                null,
                endStopId,
                null,
                endSec,
                pullInSec,
                pullInMetres,
                true));
        deadMetres += pullInMetres;

        return new Block(
                blockNo,
                vehicleClass,
                pullOutSec,
                pullInSec,
                serviceMetres / 1000.0,
                deadMetres / 1000.0,
                List.copyOf(events));
    }

    private void add(BlockEvent event) {
        events.add(event);
    }

    private boolean classSatisfies(String required) {
        if (required == null) {
            return true;
        }
        return vehicleClass == null || vehicleClass.equals(required);
    }

    String vehicleClass() {
        return vehicleClass;
    }

    int endSec() {
        return endSec;
    }

    int pullOutSec() {
        return pullOutSec;
    }

    boolean parkedMidDay() {
        return parkedMidDay;
    }

    double runningMetres() {
        return serviceMetres + deadMetres;
    }
}
