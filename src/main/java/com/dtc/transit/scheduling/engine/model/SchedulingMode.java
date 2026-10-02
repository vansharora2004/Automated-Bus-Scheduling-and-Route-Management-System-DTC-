package com.dtc.transit.scheduling.engine.model;

/** Whether crew stay with one bus for their whole duty. */
public enum SchedulingMode {

    /** The crew stays on one bus. Simpler to operate, less efficient. */
    LINKED,

    /** The crew may change buses at relief points, which fills short pieces of work. */
    UNLINKED
}
