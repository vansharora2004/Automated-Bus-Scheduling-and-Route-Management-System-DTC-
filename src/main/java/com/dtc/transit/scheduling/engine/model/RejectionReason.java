package com.dtc.transit.scheduling.engine.model;

/**
 * Why a crew member cannot take a duty.
 *
 * <p>Codes rather than messages, because the useful output is a histogram: "18 drivers considered, 9 without
 * enough rest, 6 on leave, 3 with an expired licence" tells a scheduler what to do. Eighteen sentences do not.
 */
public enum RejectionReason {
    WRONG_DEPOT,
    WRONG_ROLE,
    INACTIVE,
    ON_LEAVE,
    WEEKLY_OFF,
    LICENCE_INVALID,
    QUALIFICATION_MISSING,
    INSUFFICIENT_REST,
    WEEKLY_HOURS_EXCEEDED,
    WEEKLY_REST_MISSING,
    ALREADY_BOOKED
}
