package com.dtc.transit.scheduling.engine.model;

/**
 * Which physical bus runs which block.
 *
 * <p>Separate from the block itself. A block is work that needs doing; an assignment is the vehicle doing
 * it. Keeping them apart is what lets a bus break down and its block be reassigned without rebuilding the
 * block.
 */
public record BusAssignmentPlan(int blockNo, long busId, int fromSec, int toSec) {}
