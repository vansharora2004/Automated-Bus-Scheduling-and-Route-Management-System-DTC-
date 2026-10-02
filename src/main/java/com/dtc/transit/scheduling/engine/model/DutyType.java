package com.dtc.transit.scheduling.engine.model;

/**
 * What kind of shift a duty is, from when it runs.
 *
 * <p>Classified rather than chosen. The type drives allowances and fairness reporting, so deriving it from
 * sign-on and sign-off keeps it consistent with the duty it describes instead of depending on whoever typed
 * it in.
 */
public enum DutyType {

    /** Signs on before the morning peak. */
    EARLY,

    /** Starts and finishes inside the day. */
    MIDDLE,

    /** Signs off after the evening peak. */
    LATE,

    /** Works through the small hours, which attracts a night allowance. */
    NIGHT,

    /** Two separated stretches with a long unpaid gap, usually around mid-day depot parking. */
    SPLIT
}
