package com.dtc.transit.scheduling.engine.vehicle;

/**
 * The conflict codes the engine emits.
 *
 * <p>Constants rather than an enum. The codes are stored as text and appear in API responses, so they
 * outlive any one Java version of the catalogue; an enum would make adding a code a schema-compatible
 * change in theory and a deployment-ordering problem in practice.
 */
public final class ConflictTypes {

    // ---- hard: these block publication -------------------------------------
    public static final String UNCOVERED_TRIP = "UNCOVERED_TRIP";
    public static final String BUS_DOUBLE_BOOKED = "BUS_DOUBLE_BOOKED";
    public static final String BUS_UNAVAILABLE = "BUS_UNAVAILABLE";
    public static final String EV_RANGE_EXCEEDED = "EV_RANGE_EXCEEDED";
    public static final String UNASSIGNED_BLOCK = "UNASSIGNED_BLOCK";
    public static final String NO_FEASIBLE_RELIEF = "NO_FEASIBLE_RELIEF";
    public static final String MAX_WORK_EXCEEDED = "MAX_WORK_EXCEEDED";
    public static final String CONTINUOUS_WORK_EXCEEDED = "CONTINUOUS_WORK_EXCEEDED";
    public static final String SPREAD_OVER_EXCEEDED = "SPREAD_OVER_EXCEEDED";
    public static final String HANDOVER_INFEASIBLE = "HANDOVER_INFEASIBLE";

    // ---- hard: crew assignment, from Phase 9 -------------------------------
    public static final String UNASSIGNED_DUTY = "UNASSIGNED_DUTY";
    public static final String CREW_DOUBLE_BOOKED = "CREW_DOUBLE_BOOKED";
    public static final String INSUFFICIENT_REST = "INSUFFICIENT_REST";
    public static final String WEEKLY_HOURS_EXCEEDED = "WEEKLY_HOURS_EXCEEDED";
    public static final String WEEKLY_REST_MISSING = "WEEKLY_REST_MISSING";
    public static final String LICENCE_INVALID = "LICENCE_INVALID";
    public static final String CREW_ON_LEAVE = "CREW_ON_LEAVE";
    public static final String QUALIFICATION_MISSING = "QUALIFICATION_MISSING";

    // ---- soft: warnings only -----------------------------------------------
    public static final String ESTIMATED_DEADHEAD = "ESTIMATED_DEADHEAD";
    public static final String SHORT_DUTY = "SHORT_DUTY";
    public static final String TOO_MANY_CHANGEOVERS = "TOO_MANY_CHANGEOVERS";
    public static final String FLEET_ABOVE_LOWER_BOUND = "FLEET_ABOVE_LOWER_BOUND";
    public static final String FAIRNESS_IMBALANCE = "FAIRNESS_IMBALANCE";

    private ConflictTypes() {
        // constants only
    }
}
