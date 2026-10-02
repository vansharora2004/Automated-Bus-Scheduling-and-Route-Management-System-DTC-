package com.dtc.transit.scheduling.engine.model;

/** What a block is doing during one leg of its day. */
public enum BlockEventType {

    /** Depot to the first trip's start stop. */
    PULL_OUT,

    /** Revenue service: a timetabled trip. */
    TRIP,

    /** Non-revenue movement between two stops. */
    DEADHEAD,

    /** Standing at a stop between trips. */
    LAYOVER,

    /** Parked at the depot mid-day, which is what makes a split duty possible. */
    DEPOT_PARK,

    /** Plugged in at the depot. Only electric buses have these. */
    CHARGING,

    /** Last trip's end stop back to the depot. */
    PULL_IN;

    /** Whether the bus is carrying passengers, which is what separates service km from dead km. */
    public boolean isRevenue() {
        return this == TRIP;
    }

    /** Whether the crew is idle rather than driving, which is what a break rule can count. */
    public boolean isIdle() {
        return this == LAYOVER || this == DEPOT_PARK || this == CHARGING;
    }
}
