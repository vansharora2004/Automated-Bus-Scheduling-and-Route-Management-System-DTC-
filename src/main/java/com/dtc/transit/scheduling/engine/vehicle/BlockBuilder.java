package com.dtc.transit.scheduling.engine.vehicle;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TripView;
import com.dtc.transit.scheduling.engine.model.VehicleSchedule;

/**
 * Chains a depot-day's trips onto buses.
 *
 * <p>An interface with two implementations, chosen by configuration. The greedy builder is the default
 * because it is fast and always produces an answer; the matching builder is optimal on fleet size and
 * doubles as the lower bound the greedy result is judged against.
 */
public interface BlockBuilder {

    /** A short name, carried into the run's metrics so a result can be traced to the builder. */
    String name();

    VehicleSchedule build(List<TripView> trips, DepotContext context, RuleSet rules);
}
