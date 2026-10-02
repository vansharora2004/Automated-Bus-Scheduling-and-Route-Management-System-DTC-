package com.dtc.transit.masterdata.bus;

/** Why a bus is out of service for a period. */
public enum UnavailabilityReason {
    MAINTENANCE,
    BREAKDOWN,
    CHARGING,
    OTHER
}
