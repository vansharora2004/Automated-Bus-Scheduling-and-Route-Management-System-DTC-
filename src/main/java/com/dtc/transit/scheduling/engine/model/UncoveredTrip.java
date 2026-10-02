package com.dtc.transit.scheduling.engine.model;

/**
 * A trip no block could take, with the reason.
 *
 * <p>The reason is the point. "Not covered" sends a scheduler hunting; "no bus of class ARTICULATED was
 * free" tells them whether to buy, borrow or retime.
 */
public record UncoveredTrip(long tripId, String reason) {

    /** Reason codes, kept as constants so the same wording reaches every report. */
    public static final String NO_VEHICLE_AVAILABLE = "No vehicle of the required class was available";

    public static final String VEHICLE_CLASS_UNSATISFIABLE =
            "No bus in the depot satisfies the trip's required vehicle class";

    public static final String BLOCK_DURATION_EXCEEDED =
            "Adding this trip would push every candidate block past the maximum block duration";
}
