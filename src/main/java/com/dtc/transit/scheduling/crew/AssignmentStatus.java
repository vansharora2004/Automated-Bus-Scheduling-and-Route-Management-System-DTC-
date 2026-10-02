package com.dtc.transit.scheduling.crew;

/** What has happened to one crew booking. */
public enum AssignmentStatus {

    /** As the assigner produced it. */
    ASSIGNED,

    /** Replaced by a human, with a reason on the record. */
    OVERRIDDEN,

    /** Withdrawn. Excluded from the double-booking guard, because cancelled work is not work. */
    CANCELLED
}
