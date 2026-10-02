package com.dtc.transit.masterdata.crew;

/** Employment state. Only ACTIVE crew are eligible for assignment. */
public enum CrewStatus {
    ACTIVE,
    SUSPENDED,
    TERMINATED;

    public boolean isAssignable() {
        return this == ACTIVE;
    }
}
