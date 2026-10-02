package com.dtc.transit.user;

/**
 * The four roles the system recognises.
 *
 * <p>Spring Security authorities are these names prefixed with {@code ROLE_}. A user may hold more
 * than one role, and permissions are the union of them.
 */
public enum Role {

    /** Manages users, roles, depots and rule sets. */
    ADMIN,

    /** Approves route proposals, publishes schedules, reads every report. */
    MANAGER,

    /** Designs routes, stops and timetables; runs overlap and coverage analysis. */
    PLANNER,

    /** Generates and fixes schedules for their own depot. */
    SCHEDULER;

    public String authority() {
        return "ROLE_" + name();
    }
}
