package com.dtc.transit.scheduling.engine.model;

/**
 * One crew taking a bus over from another.
 *
 * <p>Recorded as its own row rather than inferred from two adjacent duties. A handover is an operational event
 * with a place and a time that someone has to be at, and the report a depot uses in the morning is a list of
 * these.
 *
 * @param reliefStopId where the change happens, null when it is at the depot
 */
public record HandoverPlan(int blockNo, Long reliefStopId, int atSec, int outgoingDutyNo, int incomingDutyNo) {}
