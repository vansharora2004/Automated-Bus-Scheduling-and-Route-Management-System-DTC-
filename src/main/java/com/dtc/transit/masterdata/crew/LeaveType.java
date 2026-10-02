package com.dtc.transit.masterdata.crew;

/**
 * Why a crew member is away.
 *
 * <p>ABSENT is recorded after the fact for a no-show, so history reflects what happened rather than
 * what was planned.
 */
public enum LeaveType {
    CASUAL,
    SICK,
    EARNED,
    UNPAID,
    ABSENT
}
