package com.dtc.transit.masterdata.crew;

/** Operating role. A bus needs a driver, plus a conductor where the route or vehicle requires one. */
public enum CrewRole {
    DRIVER,
    CONDUCTOR;

    /** Only a driver's licence is scheduling-critical, so only a driver record demands one. */
    public boolean requiresLicence() {
        return this == DRIVER;
    }
}
