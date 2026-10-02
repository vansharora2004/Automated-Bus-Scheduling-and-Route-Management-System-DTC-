package com.dtc.transit.masterdata.bus;

/** Fleet availability. Only ACTIVE buses can be assigned to a block. */
public enum BusStatus {
    ACTIVE,
    UNDER_MAINTENANCE,
    BREAKDOWN,
    RETIRED;

    public boolean isAssignable() {
        return this == ACTIVE;
    }
}
