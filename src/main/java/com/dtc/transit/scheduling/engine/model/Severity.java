package com.dtc.transit.scheduling.engine.model;

/** Whether a problem blocks publication or merely warns about it. */
public enum Severity {

    /** Blocks publication. A hard conflict means the schedule cannot legally or physically be run. */
    HARD,

    /** A warning. The schedule is runnable but worse than it could be. */
    SOFT
}
