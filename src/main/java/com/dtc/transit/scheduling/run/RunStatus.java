package com.dtc.transit.scheduling.run;

/** Where a scheduling job is in its life. */
public enum RunStatus {

    /** Waiting for a worker. A partial unique index allows only one of these per depot-day. */
    QUEUED,

    /** Claimed by a worker, which must keep its heartbeat current or the reaper will take it. */
    RUNNING,

    COMPLETED,

    FAILED,

    CANCELLED;

    /** Whether the run still holds the one-per-depot-day slot. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
