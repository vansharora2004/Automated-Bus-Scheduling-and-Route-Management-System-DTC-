package com.dtc.transit.scheduling.engine.constraint;

/** What a constraint is about, which decides when the engine runs it. */
public enum Scope {

    /** One bus block: chronology, layovers, range. */
    BLOCK,

    /** One duty: work, continuous work, breaks, spread-over. */
    DUTY,

    /** One crew or bus assignment: availability, licence, rest between duties. */
    ASSIGNMENT,

    /** The whole schedule: coverage, double-booking across rows. */
    SCHEDULE
}
