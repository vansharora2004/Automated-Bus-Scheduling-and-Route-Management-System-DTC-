package com.dtc.transit.route.route;

import java.util.EnumSet;
import java.util.Set;

/**
 * The route proposal lifecycle.
 *
 * <p>Each constant knows which states it may move to, so the rule lives next to the states rather than
 * scattered across service methods where one path can quietly diverge.
 *
 * <pre>
 * PROPOSED -> UNDER_REVIEW -> APPROVED -> ACTIVE -> RETIRED
 *                          -> REJECTED
 *          &lt;- returned for changes
 * </pre>
 */
public enum RouteStatus {

    /** Drafted by a planner, still editable. */
    PROPOSED,

    /** Submitted with analysis attached. A manager decides from here. */
    UNDER_REVIEW,

    /** Accepted but not yet carrying passengers. */
    APPROVED,

    /** Turned down, with a reason recorded. */
    REJECTED,

    /** In service, and therefore included in overlap analysis of new proposals. */
    ACTIVE,

    /** Withdrawn. Its route number becomes reusable. */
    RETIRED;

    public Set<RouteStatus> allowedNext() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(UNDER_REVIEW);
            // Returned for changes is a real path, so review can go back to PROPOSED.
            case UNDER_REVIEW -> EnumSet.of(PROPOSED, APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            // Terminal.
            case REJECTED, RETIRED -> EnumSet.noneOf(RouteStatus.class);
        };
    }

    public boolean canTransitionTo(RouteStatus target) {
        return allowedNext().contains(target);
    }

    /**
     * Whether geometry may still be edited.
     *
     * <p>A draft is editable, and so is a route in service: a diversion or a road closure really does
     * change the line, and refusing that would force a new route number for a temporary change.
     *
     * <p>UNDER_REVIEW and APPROVED are frozen deliberately. A reviewer is deciding on specific geometry,
     * and letting it shift underneath them would make the decision meaningless. REJECTED and RETIRED are
     * terminal.
     */
    public boolean isEditable() {
        return this == PROPOSED || this == ACTIVE;
    }
}
