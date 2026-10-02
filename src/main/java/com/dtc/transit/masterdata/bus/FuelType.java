package com.dtc.transit.masterdata.bus;

/**
 * Propulsion.
 *
 * <p>Electric matters to scheduling in a way the others do not: a block must stay within usable range,
 * and charging competes for a limited number of depot bays.
 */
public enum FuelType {
    CNG,
    ELECTRIC,
    DIESEL;

    public boolean requiresRange() {
        return this == ELECTRIC;
    }
}
